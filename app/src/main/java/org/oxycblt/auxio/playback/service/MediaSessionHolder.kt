/*
 * Copyright (c) 2021 Auxio Project
 * MediaSessionHolder.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.playback.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.ContextThemeWrapper
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.car.app.mediaextensions.MetadataExtras
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import javax.inject.Inject
import org.oxycblt.auxio.BuildConfig
import org.oxycblt.auxio.ForegroundListener
import org.oxycblt.auxio.ForegroundServiceNotification
import org.oxycblt.auxio.IntegerTable
import org.oxycblt.auxio.R
import org.oxycblt.auxio.image.BitmapProvider
import org.oxycblt.auxio.image.ImageSettings
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.music.service.MediaSessionUID
import org.oxycblt.auxio.music.service.toMediaDescription
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.Progression
import org.oxycblt.auxio.playback.state.QueueChange
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.ui.UISettings
import org.oxycblt.auxio.util.getColorCompat
import org.oxycblt.auxio.util.getDrawableCompat
import org.oxycblt.auxio.util.isNight
import org.oxycblt.auxio.util.newBroadcastPendingIntent
import org.oxycblt.auxio.util.newMainPendingIntent
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * A component that mirrors the current playback state into the [MediaSessionCompat] and
 * [PlaybackNotification].
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
class MediaSessionHolder
private constructor(
    private val context: Context,
    private val foregroundListener: ForegroundListener,
    private val playbackManager: PlaybackStateManager,
    private val bitmapProvider: BitmapProvider,
    private val imageSettings: ImageSettings,
    private val uiSettings: UISettings,
    private val mediaSessionInterface: MediaSessionInterface,
) : PlaybackStateManager.Listener, ImageSettings.Listener {

    class Factory
    @Inject
    constructor(
        private val playbackManager: PlaybackStateManager,
        private val bitmapProvider: BitmapProvider,
        private val imageSettings: ImageSettings,
        private val uiSettings: UISettings,
        private val mediaSessionInterface: MediaSessionInterface,
    ) {
        fun create(context: Context, foregroundListener: ForegroundListener) =
            MediaSessionHolder(
                context,
                foregroundListener,
                playbackManager,
                bitmapProvider,
                imageSettings,
                uiSettings,
                mediaSessionInterface,
            )
    }

    private val mediaSession = MediaSessionCompat(context, context.packageName)
    val token: MediaSessionCompat.Token
        get() = mediaSession.sessionToken

    val notification: ForegroundServiceNotification
        field = PlaybackNotification(context, mediaSession.sessionToken)

    private var fallbackArtwork: Bitmap? = null
    private var fallbackKey: String? = null

    fun attach() {
        playbackManager.addListener(this)
        imageSettings.registerListener(this)
        mediaSession.apply {
            isActive = true
            setQueueTitle(context.getString(R.string.lbl_queue))
            setCallback(mediaSessionInterface)
        }
    }

    fun tryMediaButtonIntent(intent: Intent): Boolean =
        MediaButtonReceiver.handleIntent(mediaSession, intent) != null

    /**
     * Release this instance, closing the [MediaSessionCompat] and preventing any further updates to
     * the [PlaybackNotification].
     */
    fun release() {
        bitmapProvider.release()
        playbackManager.removeListener(this)
        imageSettings.unregisterListener(this)
        fallbackArtwork = null
        fallbackKey = null
        mediaSession.apply {
            isActive = false
            release()
        }
    }

    // --- PLAYBACKSTATEMANAGER OVERRIDES ---

    override fun onIndexMoved(index: Int) {
        updateMediaMetadata(playbackManager.currentSong, playbackManager.parent)
        invalidateSessionState()
    }

    override fun onQueueChanged(queue: List<Song>, index: Int, change: QueueChange) {
        updateQueue(queue)
        when (change.type) {
            // Nothing special to do with mapping changes.
            QueueChange.Type.MAPPING -> {}
            // Index changed, ensure playback state's index changes.
            QueueChange.Type.INDEX -> invalidateSessionState()
            // Song changed, ensure metadata changes.
            QueueChange.Type.SONG ->
                updateMediaMetadata(playbackManager.currentSong, playbackManager.parent)
        }
    }

    override fun onQueueReordered(queue: List<Song>, index: Int, isShuffled: Boolean) {
        updateQueue(queue)
        invalidateSessionState()
        mediaSession.setShuffleMode(
            if (isShuffled) {
                PlaybackStateCompat.SHUFFLE_MODE_ALL
            } else {
                PlaybackStateCompat.SHUFFLE_MODE_NONE
            }
        )
        invalidateNotificationActions()
    }

    override fun onNewPlayback(
        parent: MusicParent?,
        queue: List<Song>,
        index: Int,
        isShuffled: Boolean,
    ) {
        updateMediaMetadata(playbackManager.currentSong, parent)
        updateQueue(queue)
        invalidateSessionState()
    }

    override fun onProgressionChanged(progression: Progression) {
        invalidateSessionState()
        notification.updatePlaying(playbackManager.progression.isPlaying)
        if (!bitmapProvider.isBusy) {
            foregroundListener.updateForeground(ForegroundListener.Change.MEDIA_SESSION)
        }
    }

    override fun onRepeatModeChanged(repeatMode: RepeatMode) {
        mediaSession.setRepeatMode(
            when (repeatMode) {
                RepeatMode.NONE -> PlaybackStateCompat.REPEAT_MODE_NONE
                RepeatMode.TRACK -> PlaybackStateCompat.REPEAT_MODE_ONE
                RepeatMode.ALL -> PlaybackStateCompat.REPEAT_MODE_ALL
            }
        )

        invalidateNotificationActions()
    }

    // --- SETTINGS OVERRIDES ---

    override fun onImageSettingsChanged() {
        // Theme/colors may have changed, drop cached fallback so it is re-rendered.
        fallbackArtwork = null
        fallbackKey = null
        // Need to reload the metadata cover.
        updateMediaMetadata(playbackManager.currentSong, playbackManager.parent)
    }

    // --- MEDIASESSION OVERRIDES ---

    // --- INTERNAL ---

    /**
     * Upload a new [MediaMetadataCompat] based on the current playback state to the
     * [MediaSessionCompat] and [PlaybackNotification].
     *
     * @param song The current [Song] to create the [MediaMetadataCompat] from, or null if no [Song]
     *   is currently playing.
     * @param parent The current [MusicParent] to create the [MediaMetadataCompat] from, or null if
     *   playback is currently occuring from all songs.
     */
    private fun updateMediaMetadata(song: Song?, parent: MusicParent?) {
        L.d("Updating media metadata to $song with $parent")
        if (song == null) {
            // Nothing playing, reset the MediaSession and close the notification.
            L.d("Nothing playing, resetting media session")
            mediaSession.setMetadata(emptyMetadata)
            return
        }

        // Populate MediaMetadataCompat. For efficiency, cache some fields that are re-used
        // several times.
        val title = song.name.resolve(context)
        val artist = song.artists.resolveNames(context)
        val album = song.album.name.resolve(context)
        val builder =
            MediaMetadataCompat.Builder()
                .putText(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putText(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                // Note: We would leave the artist field null if it didn't exist and let downstream
                // consumers handle it, but that would break the notification display.
                .putText(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putText(
                    MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST,
                    song.album.artists.resolveNames(context),
                )
                .putText(MediaMetadataCompat.METADATA_KEY_AUTHOR, artist)
                .putText(MediaMetadataCompat.METADATA_KEY_COMPOSER, artist)
                .putText(MediaMetadataCompat.METADATA_KEY_WRITER, artist)
                .putText(MediaMetadataCompat.METADATA_KEY_GENRE, song.genres.resolveNames(context))
                .putText(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putText(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
                .putText(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.durationMs)
                .putText(
                    PlaybackNotification.KEY_PARENT,
                    parent?.name?.resolve(context) ?: context.getString(R.string.lbl_all_songs),
                )
                .putText(
                    MetadataExtras.KEY_SUBTITLE_LINK_MEDIA_ID,
                    MediaSessionUID.SingleItem(song.artists[0].uid).toString(),
                )
                .putText(
                    MetadataExtras.KEY_DESCRIPTION_LINK_MEDIA_ID,
                    MediaSessionUID.SingleItem(song.album.uid).toString(),
                )
        // These fields are nullable and so we must check first before adding them to the fields.
        song.track?.let {
            L.d("Adding track information")
            builder.putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, it.toLong())
        }
        song.disc?.let {
            L.d("Adding disc information")
            builder.putLong(MediaMetadataCompat.METADATA_KEY_DISC_NUMBER, it.number.toLong())
        }
        song.date?.let {
            L.d("Adding date information")
            builder.putString(MediaMetadataCompat.METADATA_KEY_DATE, it.toString())
            builder.putLong(MediaMetadataCompat.METADATA_KEY_YEAR, it.year.toLong())
        }

        // We are normally supposed to use URIs for album art, but that removes some of the
        // nice things we can do like square cropping (we crop at runtime since it lets us avoid
        // heavy image processing pipelines) or high quality covers (the default lower downsamples
        // much more than the runtime bitmap downsampler). Instead, we load a full-size bitmap into
        // the media session and take the performance hit.
        bitmapProvider.load(
            song,
            object : BitmapProvider.Target {
                override fun onConfigRequest(builder: ImageRequest.Builder): ImageRequest.Builder =
                    // Android 17 optimized cover downsampling which accidentally made it so that
                    // hardware bitmap covers would actually crash it. Fix this by manually
                    // downsampling to whatever the system imagines, as disabling hardware bitmaps
                    // trashes performance. If I remember correctly this is somehow still higher
                    // quality than the URI loading.
                    builder
                        .size(MediaSessionCompat.getBitmapDimensionLimit())
                        .memoryCachePolicy(CachePolicy.READ_ONLY)
                        .allowHardware(Build.VERSION.SDK_INT < 37)

                override fun onCompleted(bitmap: Bitmap?) {
                    L.d("Bitmap loaded, applying media session and posting notification")
                    // Always provide artwork so lock-screen / SystemUI media controls have
                    // something to show even when the song has no embedded cover.
                    val art = bitmap ?: getFallbackArtwork()
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art)
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                    val metadata = builder.build()
                    mediaSession.setMetadata(metadata)
                    notification.updateMetadata(metadata)
                    foregroundListener.updateForeground(ForegroundListener.Change.MEDIA_SESSION)
                }
            },
        )
    }

    /**
     * Render Auxio's default cover (same background + disk logo as the in-app [CoverView]
     * placeholder) as a full-size software [Bitmap] suitable for [MediaSessionCompat] and
     * notifications.
     *
     * Must stay large (>= [MediaSessionCompat.getBitmapDimensionLimit]) - tiny placeholders
     * crashed some SystemUIs in the past. Colors are resolved against the user's accent and
     * dark/light setting (mirroring MainActivity.setupTheme) so the lock-screen matches the
     * app. Result is cached until settings change.
     */
    private fun getFallbackArtwork(): Bitmap {
        val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val key =
            "${uiSettings.accent.index}:${uiSettings.theme}:${uiSettings.useBlackTheme}:$nightMask"
        fallbackArtwork?.takeIf { fallbackKey == key }?.let { return it }
        // Mirror MainActivity.setupTheme: service context has no AppCompat night override,
        // so compute the effective dark mode explicitly and force the night qualifier
        // to match, otherwise night colors would follow the system instead of the app.
        val dark =
            when (uiSettings.theme) {
                AppCompatDelegate.MODE_NIGHT_YES -> true
                AppCompatDelegate.MODE_NIGHT_NO -> false
                else -> context.isNight
            }
        val themeRes =
            if (dark && uiSettings.useBlackTheme) uiSettings.accent.blackTheme
            else uiSettings.accent.theme
        val config = Configuration(context.resources.configuration)
        config.uiMode =
            (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (dark) Configuration.UI_MODE_NIGHT_YES
                 else Configuration.UI_MODE_NIGHT_NO)
        val themed = ContextThemeWrapper(context.createConfigurationContext(config), themeRes)
        val size = MediaSessionCompat.getBitmapDimensionLimit().takeIf { it > 0 } ?: 512
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(themed.getColorCompat(R.color.sel_cover_bg).defaultColor)
        // Wrap before mutate so any XML tint is reliably overridden in service context.
        val icon =
            DrawableCompat.wrap(themed.getDrawableCompat(R.drawable.ic_album_24)).mutate()
        DrawableCompat.setTint(
            icon,
            themed.getColorCompat(R.color.sel_on_cover_bg).defaultColor,
        )
        // Same proportions as CoverView.StyledDrawable: icon fills the middle 50%.
        val inset = size / 4
        icon.setBounds(inset, inset, size - inset, size - inset)
        icon.draw(canvas)
        fallbackArtwork = result
        fallbackKey = key
        return result
    }

    /**
     * Upload a new queue to the [MediaSessionCompat].
     *
     * @param queue The current queue to upload.
     */
    private fun updateQueue(queue: List<Song>) {
        val queueItems = queue.mapIndexed { i, song ->
            val description =
                song.toMediaDescription(
                    context,
                    { putInt(MediaSessionInterface.KEY_QUEUE_POS, i) },
                )
            // Store the item index so we can then use the analogous index in the
            // playback state.
            MediaSessionCompat.QueueItem(description, i.toLong())
        }
        L.d("Uploading ${queueItems.size} songs to MediaSession queue")
        mediaSession.setQueue(queueItems)
    }

    /** Invalidate the current [MediaSessionCompat]'s [PlaybackStateCompat]. */
    private fun invalidateSessionState() {
        L.d("Updating media session playback state")

        val state =
            // InternalPlayer.State handles position/state information.
            playbackManager.progression
                .intoPlaybackState(PlaybackStateCompat.Builder())
                .setActions(MediaSessionInterface.ACTIONS)
                // Active queue ID corresponds to the indices we populated prior, use them here.
                .setActiveQueueItemId(playbackManager.index.toLong())

        // Android 13+ relies on custom actions in the notification.

        // Add repeat action
        val repeatAction =
            PlaybackStateCompat.CustomAction.Builder(
                    PlaybackActions.ACTION_INC_REPEAT_MODE,
                    context.getString(R.string.desc_change_repeat),
                    playbackManager.repeatMode.icon,
                )
                .build()
        state.addCustomAction(repeatAction)

        // Add shuffle action
        val shuffleAction =
            PlaybackStateCompat.CustomAction.Builder(
                    PlaybackActions.ACTION_INVERT_SHUFFLE,
                    context.getString(R.string.desc_shuffle),
                    if (playbackManager.isShuffled) {
                        R.drawable.ic_shuffle_on_24
                    } else {
                        R.drawable.ic_shuffle_off_24
                    },
                )
                .build()
        state.addCustomAction(shuffleAction)

        mediaSession.setPlaybackState(state.build())
    }

    /** Invalidate both repeat and shuffle notification actions. */
    private fun invalidateNotificationActions() {
        L.d("Invalidating notification actions")
        invalidateSessionState()

        notification.updateRepeatMode(playbackManager.repeatMode)
        notification.updateShuffled(playbackManager.isShuffled)

        if (!bitmapProvider.isBusy) {
            L.d("Not loading a bitmap, post the notification")
            foregroundListener.updateForeground(ForegroundListener.Change.MEDIA_SESSION)
        }
    }

    companion object {
        private val emptyMetadata = MediaMetadataCompat.Builder().build()
    }
}

/**
 * The playback notification component. Due to race conditions regarding notification updates, this
 * component is not self-sufficient. [MediaSessionHolder] should be used instead of manage it.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@SuppressLint("RestrictedApi")
private class PlaybackNotification(
    private val context: Context,
    sessionToken: MediaSessionCompat.Token,
) : ForegroundServiceNotification(context, CHANNEL_INFO) {
    init {
        setSmallIcon(R.drawable.ic_auxio_24)
        setCategory(NotificationCompat.CATEGORY_TRANSPORT)
        setShowWhen(false)
        setSilent(true)
        setContentIntent(context.newMainPendingIntent())
        setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        addAction(buildRepeatAction(context, RepeatMode.NONE))
        addAction(
            buildAction(context, PlaybackActions.ACTION_SKIP_PREV, R.drawable.ic_skip_prev_24)
        )
        addAction(buildPlayPauseAction(context, true))
        addAction(
            buildAction(context, PlaybackActions.ACTION_SKIP_NEXT, R.drawable.ic_skip_next_24)
        )
        addAction(buildShuffleAction(context, false))

        setStyle(
            MediaStyle(this).setMediaSession(sessionToken).setShowActionsInCompactView(1, 2, 3)
        )
    }

    override val code: Int
        get() = IntegerTable.PLAYBACK_NOTIFICATION_CODE

    // --- STATE FUNCTIONS ---

    /**
     * Update the currently shown metadata in this notification.
     *
     * @param metadata The [MediaMetadataCompat] to display in this notification.
     */
    fun updateMetadata(metadata: MediaMetadataCompat) {
        L.d("Updating shown metadata")
        val albumArt = metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
        if (albumArt != null) {
            setLargeIcon(albumArt)
        } else {
            // setLargeIcon(null) doesn't reliably clear the icon on all devices.
            // Use a transparent 1x1 bitmap instead.
            setLargeIcon(EMPTY_BITMAP)
        }
        setContentTitle(metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
        setContentText(metadata.getText(MediaMetadataCompat.METADATA_KEY_ARTIST))
        setSubText(metadata.getText(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION))
    }

    /**
     * Update the playing state shown in this notification.
     *
     * @param isPlaying Whether playback should be indicated as ongoing or paused.
     */
    fun updatePlaying(isPlaying: Boolean) {
        L.d("Updating playing state: $isPlaying")
        mActions[2] = buildPlayPauseAction(context, isPlaying)
    }

    /**
     * Update the secondary action in this notification to show the current [RepeatMode].
     *
     * @param repeatMode The current [RepeatMode].
     */
    fun updateRepeatMode(repeatMode: RepeatMode) {
        L.d("Applying repeat mode action: $repeatMode")
        mActions[0] = buildRepeatAction(context, repeatMode)
    }

    /**
     * Update the secondary action in this notification to show the current shuffle state.
     *
     * @param isShuffled Whether the queue is currently shuffled or not.
     */
    fun updateShuffled(isShuffled: Boolean) {
        L.d("Applying shuffle action: $isShuffled")
        mActions[4] = buildShuffleAction(context, isShuffled)
    }

    // --- NOTIFICATION ACTION BUILDERS ---

    private fun buildPlayPauseAction(
        context: Context,
        isPlaying: Boolean,
    ): NotificationCompat.Action {
        val drawableRes =
            if (isPlaying) {
                R.drawable.ic_pause_24
            } else {
                R.drawable.ic_play_24
            }
        return buildAction(context, PlaybackActions.ACTION_PLAY_PAUSE, drawableRes)
    }

    private fun buildRepeatAction(
        context: Context,
        repeatMode: RepeatMode,
    ): NotificationCompat.Action {
        return buildAction(context, PlaybackActions.ACTION_INC_REPEAT_MODE, repeatMode.icon)
    }

    private fun buildShuffleAction(
        context: Context,
        isShuffled: Boolean,
    ): NotificationCompat.Action {
        val drawableRes =
            if (isShuffled) {
                R.drawable.ic_shuffle_on_24
            } else {
                R.drawable.ic_shuffle_off_24
            }
        return buildAction(context, PlaybackActions.ACTION_INVERT_SHUFFLE, drawableRes)
    }

    private fun buildAction(context: Context, actionName: String, @DrawableRes iconRes: Int) =
        NotificationCompat.Action.Builder(
                iconRes,
                actionName,
                context.newBroadcastPendingIntent(actionName),
            )
            .build()

    companion object {
        const val KEY_PARENT = BuildConfig.APPLICATION_ID + ".metadata.PARENT"

        /** Notification channel used by solely the playback notification. */
        private val CHANNEL_INFO =
            ChannelInfo(
                id = BuildConfig.APPLICATION_ID + ".channel.PLAYBACK",
                nameRes = R.string.lbl_playback,
            )

        /** Cached 2x2 transparent bitmap. 2x2 to stop palette extraction blowing up the app */
        private val EMPTY_BITMAP: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    }
}
