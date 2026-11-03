@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.example.otomusic

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.ComponentCallbacks2
import android.content.ContentUris
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import coil.Coil
import coil.ImageLoader
import coil.memory.MemoryCache
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var prefs: SharedPreferences
    private var cachedArtUri: Uri? = null
    private var cachedSquareBitmap: Bitmap? = null

    companion object {
        const val ACTION_FAVORITE = "ACTION_FAVORITE"
        const val ACTION_CLOSE = "ACTION_CLOSE"
        const val ACTION_GET_AUDIO_SESSION_ID = "ACTION_GET_AUDIO_SESSION_ID"
        const val KEY_AUDIO_SESSION_ID = "KEY_AUDIO_SESSION_ID"
    }

    private val activityRamCleaner = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {
            try {
                Coil.imageLoader(applicationContext).memoryCache?.clear()
            } catch (_: Throwable) {}
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            try {
                Coil.imageLoader(applicationContext).memoryCache?.clear()
            } catch (_: Throwable) {}
        }
    }

    override fun onCreate() {
        super.onCreate()

        try {
            Coil.setImageLoader(
                ImageLoader.Builder(applicationContext)
                    .bitmapConfig(Bitmap.Config.RGB_565)
                    .allowHardware(false)
                    .memoryCache {
                        MemoryCache.Builder(applicationContext)
                            .maxSizeBytes(6 * 1024 * 1024)
                            .build()
                    }
                    .build()
            )
            application.registerActivityLifecycleCallbacks(activityRamCleaner)
        } catch (_: Throwable) {}

        prefs = getSharedPreferences("oto_prefs", MODE_PRIVATE)
        val handleAudioFocus = prefs.getBoolean("lower_volume_on_focal_loss", true)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val audioOnlyRenderersFactory = RenderersFactory {
                eventHandler: Handler,
                _: VideoRendererEventListener,
                audioRendererEventListener: AudioRendererEventListener,
                _: TextOutput,
                _: MetadataOutput ->
            arrayOf<Renderer>(
                MediaCodecAudioRenderer(
                    this@PlaybackService,
                    MediaCodecSelector.DEFAULT,
                    eventHandler,
                    audioRendererEventListener
                )
            )
        }

        val lowRamLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, 4000, 400, 800)
            .setTargetBufferBytes(384 * 1024)
            .setBackBuffer(0, false)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        exoPlayer = ExoPlayer.Builder(this, audioOnlyRenderersFactory)
            .setLoadControl(lowRamLoadControl)
            .setAudioAttributes(audioAttributes, handleAudioFocus)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF

        // Preload MediaStore songs into ExoPlayer immediately if service was restarted by notification tap
        ensureMediaItemsLoaded()

        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.mediaId?.let { id ->
                    prefs.edit()
                        .putString("last_played_song_id", id)
                        .putLong("last_played_pos_ms", 0L)
                        .apply()
                }
                updateNotificationCustomLayout()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    prefs.edit()
                        .putLong("last_played_pos_ms", exoPlayer.currentPosition.coerceAtLeast(0L))
                        .apply()
                }
            }
        })

        // Wrap ExoPlayer in a ForwardingPlayer that guarantees songs are loaded & prepared
        // whenever Play is pressed from a notification after hours in the background.
        val resilientPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun play() {
                ensureMediaItemsLoaded()
                if (playbackState == Player.STATE_IDLE) {
                    prepare()
                }
                super.play()
            }

            override fun setPlayWhenReady(playWhenReady: Boolean) {
                if (playWhenReady) {
                    ensureMediaItemsLoaded()
                    if (playbackState == Player.STATE_IDLE) {
                        prepare()
                    }
                }
                super.setPlayWhenReady(playWhenReady)
            }

            override fun seekToNext() {
                ensureMediaItemsLoaded()
                super.seekToNext()
            }

            override fun seekToPrevious() {
                ensureMediaItemsLoaded()
                super.seekToPrevious()
            }
        }

        val notificationProvider = object : DefaultMediaNotificationProvider(this) {
            override fun addNotificationActions(
                mediaSession: MediaSession,
                mediaButtons: ImmutableList<CommandButton>,
                builder: NotificationCompat.Builder,
                actionFactory: MediaNotification.ActionFactory
            ): IntArray {
                val p = mediaSession.player
                val currentId = p.currentMediaItem?.mediaId
                val isFav = currentId != null &&
                    (prefs.getStringSet("favorites", emptySet())?.contains(currentId) == true)
                val isPlaying = p.playWhenReady && p.playbackState != Player.STATE_ENDED

                val origPrev = mediaButtons.firstOrNull {
                    it.playerCommand == Player.COMMAND_SEEK_TO_PREVIOUS ||
                        it.playerCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                }
                val origPlayPause = mediaButtons.firstOrNull {
                    it.playerCommand == Player.COMMAND_PLAY_PAUSE
                }
                val origNext = mediaButtons.firstOrNull {
                    it.playerCommand == Player.COMMAND_SEEK_TO_NEXT ||
                        it.playerCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
                }

                val favBtn = buildFavoriteButton(isFav)
                val prevBtn = CommandButton.Builder()
                    .setPlayerCommand(origPrev?.playerCommand ?: Player.COMMAND_SEEK_TO_PREVIOUS)
                    .setIconResId(R.drawable.ic_notif_prev)
                    .setDisplayName(origPrev?.displayName ?: "Previous")
                    .setExtras(origPrev?.extras ?: Bundle.EMPTY)
                    .setEnabled(true)
                    .build()
                val playPauseBtn = CommandButton.Builder()
                    .setPlayerCommand(origPlayPause?.playerCommand ?: Player.COMMAND_PLAY_PAUSE)
                    .setIconResId(if (isPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play)
                    .setDisplayName(origPlayPause?.displayName ?: if (isPlaying) "Pause" else "Play")
                    .setExtras(origPlayPause?.extras ?: Bundle.EMPTY)
                    .setEnabled(true)
                    .build()
                val nextBtn = CommandButton.Builder()
                    .setPlayerCommand(origNext?.playerCommand ?: Player.COMMAND_SEEK_TO_NEXT)
                    .setIconResId(R.drawable.ic_notif_next)
                    .setDisplayName(origNext?.displayName ?: "Next")
                    .setExtras(origNext?.extras ?: Bundle.EMPTY)
                    .setEnabled(true)
                    .build()
                val closeBtn = buildCloseButton()

                val orderedButtons = ImmutableList.of(
                    favBtn,
                    prevBtn,
                    playPauseBtn,
                    nextBtn,
                    closeBtn
                )

                super.addNotificationActions(mediaSession, orderedButtons, builder, actionFactory)

                val artUri = p.currentMediaItem?.mediaMetadata?.artworkUri
                val squareArt = loadSquareArtworkLowRam(artUri)
                if (squareArt != null && !squareArt.isRecycled) {
                    builder.setLargeIcon(squareArt)
                }

                return intArrayOf(1, 2, 3)
            }
        }.apply {
            setSmallIcon(R.drawable.ic_notif_music_note)
        }

        setMediaNotificationProvider(notificationProvider)

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, resilientPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCustomLayout(listOf(buildFavoriteButton(false), buildCloseButton()))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val result = super.onConnect(session, controller)
                    val cmds = result.availableSessionCommands.buildUpon()
                        .add(SessionCommand(ACTION_FAVORITE, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_CLOSE, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_GET_AUDIO_SESSION_ID, Bundle.EMPTY))
                        .build()
                    return MediaSession.ConnectionResult.accept(cmds, result.availablePlayerCommands)
                }

                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val items = querySavedOrAllMediaItems()
                    val lastId = prefs.getString("last_played_song_id", null)
                    val startIndex = if (lastId != null) {
                        items.indexOfFirst { it.mediaId == lastId }.coerceAtLeast(0)
                    } else 0
                    val startPos = prefs.getLong("last_played_pos_ms", 0L).coerceAtLeast(0L)
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(items, startIndex, startPos)
                    )
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        ACTION_CLOSE -> {
                            prefs.edit()
                                .putLong("last_played_pos_ms", exoPlayer.currentPosition.coerceAtLeast(0L))
                                .apply()
                            exoPlayer.pause()
                            exoPlayer.stop()
                            stopSelf()
                        }
                        ACTION_FAVORITE -> {
                            val currentId = exoPlayer.currentMediaItem?.mediaId
                            if (currentId != null) {
                                val favs = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
                                if (favs.contains(currentId)) favs.remove(currentId) else favs.add(currentId)
                                prefs.edit().putStringSet("favorites", favs).apply()
                                updateNotificationCustomLayout()
                            }
                        }
                        ACTION_GET_AUDIO_SESSION_ID -> {
                            val resultBundle = Bundle().apply {
                                putInt(KEY_AUDIO_SESSION_ID, exoPlayer.audioSessionId)
                            }
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, resultBundle))
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
    }

    private fun ensureMediaItemsLoaded() {
        if (exoPlayer.mediaItemCount > 0) return
        val items = querySavedOrAllMediaItems()
        if (items.isEmpty()) return
        val lastId = prefs.getString("last_played_song_id", null)
        val startIndex = if (lastId != null) {
            items.indexOfFirst { it.mediaId == lastId }.coerceAtLeast(0)
        } else 0
        val startPos = prefs.getLong("last_played_pos_ms", 0L).coerceAtLeast(0L)
        exoPlayer.setMediaItems(items, startIndex, startPos)
        exoPlayer.prepare()
    }

    private fun querySavedOrAllMediaItems(): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        try {
            val deletedSet = prefs.getStringSet("deleted_song_ids", emptySet()) ?: emptySet()
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM_ID
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

            contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    if (deletedSet.contains(id.toString())) continue
                    val duration = cursor.getLong(durCol)
                    if (duration <= 0L) continue
                    val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Unknown Title"
                    val artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Artist"
                    val album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Album"
                    val albumId = cursor.getLong(albumIdCol)

                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val albumArtUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)

                    list.add(
                        MediaItem.Builder()
                            .setMediaId(id.toString())
                            .setUri(contentUri)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(title)
                                    .setArtist(artist)
                                    .setAlbumTitle(album)
                                    .setArtworkUri(albumArtUri)
                                    .build()
                            )
                            .build()
                    )
                }
            }
        } catch (_: Throwable) {}
        return list
    }

    private fun buildFavoriteButton(isFavorite: Boolean): CommandButton {
        return CommandButton.Builder()
            .setDisplayName("Favorite")
            .setSessionCommand(SessionCommand(ACTION_FAVORITE, Bundle.EMPTY))
            .setIconResId(if (isFavorite) R.drawable.ic_notif_heart_filled else R.drawable.ic_notif_heart_outline)
            .build()
    }

    private fun buildCloseButton(): CommandButton {
        return CommandButton.Builder()
            .setDisplayName("Close")
            .setSessionCommand(SessionCommand(ACTION_CLOSE, Bundle.EMPTY))
            .setIconResId(R.drawable.ic_notif_close)
            .build()
    }

    private fun updateNotificationCustomLayout() {
        val session = mediaSession ?: return
        val currentId = exoPlayer.currentMediaItem?.mediaId
        val isFav = currentId != null &&
            (prefs.getStringSet("favorites", emptySet())?.contains(currentId) == true)
        session.setCustomLayout(listOf(buildFavoriteButton(isFav), buildCloseButton()))
    }

    private fun loadSquareArtworkLowRam(uri: Uri?): Bitmap? {
        if (uri == null) return null
        val existing = cachedSquareBitmap
        if (uri == cachedArtUri && existing != null && !existing.isRecycled) {
            return existing
        }
        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

            val targetSize = 192
            var sampleSize = 1
            while (boundsOpts.outWidth / (sampleSize * 2) >= targetSize &&
                boundsOpts.outHeight / (sampleSize * 2) >= targetSize
            ) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val raw = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null

            val cropped = cropBlackBarsAndSquare(raw, targetSize)
            cachedArtUri = uri
            cachedSquareBitmap = cropped
            cropped
        } catch (_: Throwable) {
            null
        }
    }

    private fun cropBlackBarsAndSquare(src: Bitmap, targetSize: Int): Bitmap {
        return try {
            val w = src.width
            val h = src.height
            if (w <= 4 || h <= 4) return src
            val midY = h / 2
            val midX = w / 2

            fun isNearBlack(pixel: Int): Boolean {
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                return r < 18 && g < 18 && b < 18
            }

            var left = 0
            while (left < w / 3 && isNearBlack(src.getPixel(left, midY))) left++
            var right = w - 1
            while (right > (w * 2) / 3 && isNearBlack(src.getPixel(right, midY))) right--
            var top = 0
            while (top < h / 3 && isNearBlack(src.getPixel(midX, top))) top++
            var bottom = h - 1
            while (bottom > (h * 2) / 3 && isNearBlack(src.getPixel(midX, bottom))) bottom--

            val contentW = (right - left + 1).coerceAtLeast(1)
            val contentH = (bottom - top + 1).coerceAtLeast(1)
            val side = minOf(contentW, contentH).coerceAtLeast(1)
            val cropX = (left + (contentW - side) / 2).coerceIn(0, (w - side).coerceAtLeast(0))
            val cropY = (top + (contentH - side) / 2).coerceIn(0, (h - side).coerceAtLeast(0))

            val square = Bitmap.createBitmap(src, cropX, cropY, side, side)
            Bitmap.createScaledBitmap(square, targetSize, targetSize, true)
        } catch (_: Throwable) {
            src
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            try {
                Coil.imageLoader(applicationContext).memoryCache?.clear()
            } catch (_: Throwable) {}
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        prefs.edit()
            .putLong("last_played_pos_ms", exoPlayer.currentPosition.coerceAtLeast(0L))
            .apply()
        if (!exoPlayer.playWhenReady && exoPlayer.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        try {
            application.unregisterActivityLifecycleCallbacks(activityRamCleaner)
        } catch (_: Throwable) {}
        mediaSession?.run {
            exoPlayer.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
