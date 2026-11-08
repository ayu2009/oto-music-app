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
    private var cachedMediaItems: List<MediaItem> = emptyList()

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
                    .crossfade(false)
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
            .setBufferDurationsMs(1500, 4000, 350, 700)
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

        val resilientPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun play() {
                ensureMediaItemsLoaded()
                if (playbackState == Player.STATE_IDLE) prepare()
                super.play()
            }

            override fun setPlayWhenReady(playWhenReady: Boolean) {
                if (playWhenReady) {
                    ensureMediaItemsLoaded()
                    if (playbackState == Player.STATE_IDLE) prepare()
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

                val orderedButtons = ImmutableList.of(
                    buildFavoriteButton(isFav),
                    CommandButton.Builder()
                        .setPlayerCommand(origPrev?.playerCommand ?: Player.COMMAND_SEEK_TO_PREVIOUS)
                        .setIconResId(R.drawable.ic_notif_prev)
                        .setDisplayName(origPrev?.displayName ?: "Previous")
                        .setExtras(origPrev?.extras ?: Bundle.EMPTY)
                        .setEnabled(true)
                        .build(),
                    CommandButton.Builder()
                        .setPlayerCommand(origPlayPause?.playerCommand ?: Player.COMMAND_PLAY_PAUSE)
                        .setIconResId(if (isPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play)
                        .setDisplayName(origPlayPause?.displayName ?: if (isPlaying) "Pause" else "Play")
                        .setExtras(origPlayPause?.extras ?: Bundle.EMPTY)
                        .setEnabled(true)
                        .build(),
                    CommandButton.Builder()
                        .setPlayerCommand(origNext?.playerCommand ?: Player.COMMAND_SEEK_TO_NEXT)
                        .setIconResId(R.drawable.ic_notif_next)
                        .setDisplayName(origNext?.displayName ?: "Next")
                        .setExtras(origNext?.extras ?: Bundle.EMPTY)
                        .setEnabled(true)
                        .build(),
                    buildCloseButton()
                )

                super.addNotificationActions(mediaSession, orderedButtons, builder, actionFactory)

                val artUri = p.currentMediaItem?.mediaMetadata?.artworkUri
                val squareArt = loadSquareArtworkFast(artUri)
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
                    val items = getOrLoadMediaItems()
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
        val items = getOrLoadMediaItems()
        if (items.isEmpty()) return
        val lastId = prefs.getString("last_played_song_id", null)
        val startIndex = if (lastId != null) {
            items.indexOfFirst { it.mediaId == lastId }.coerceAtLeast(0)
        } else 0
        val startPos = prefs.getLong("last_played_pos_ms", 0L).coerceAtLeast(0L)
        exoPlayer.setMediaItems(items, startIndex, startPos)
        exoPlayer.prepare()
    }

    private fun getOrLoadMediaItems(): List<MediaItem> {
        if (cachedMediaItems.isNotEmpty()) return cachedMediaItems
        val list = ArrayList<MediaItem>(256)
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
                    if (cursor.getLong(durCol) <= 0L) continue
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
        cachedMediaItems = list
        return list
    }

    private fun buildFavoriteButton(isFavorite: Boolean): CommandButton =
        CommandButton.Builder()
            .setDisplayName("Favorite")
            .setSessionCommand(SessionCommand(ACTION_FAVORITE, Bundle.EMPTY))
            .setIconResId(if (isFavorite) R.drawable.ic_notif_heart_filled else R.drawable.ic_notif_heart_outline)
            .build()

    private fun buildCloseButton(): CommandButton =
        CommandButton.Builder()
            .setDisplayName("Close")
            .setSessionCommand(SessionCommand(ACTION_CLOSE, Bundle.EMPTY))
            .setIconResId(R.drawable.ic_notif_close)
            .build()

    private fun updateNotificationCustomLayout() {
        val session = mediaSession ?: return
        val currentId = exoPlayer.currentMediaItem?.mediaId
        val isFav = currentId != null &&
            (prefs.getStringSet("favorites", emptySet())?.contains(currentId) == true)
        session.setCustomLayout(listOf(buildFavoriteButton(isFav), buildCloseButton()))
    }

    /**
     * O(1) fast center-crop to 144x144 RGB_565 (~40 KB RAM, zero pixel loops, zero .recycle() crashes).
     */
    private fun loadSquareArtworkFast(uri: Uri?): Bitmap? {
        if (uri == null) return null
        val existing = cachedSquareBitmap
        if (uri == cachedArtUri && existing != null && !existing.isRecycled) return existing
        return try {
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = 2
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val raw = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null

            val w = raw.width
            val h = raw.height
            val side = minOf(w, h).coerceAtLeast(1)
            val square = Bitmap.createBitmap(raw, (w - side) / 2, (h - side) / 2, side, side)
            val scaled = Bitmap.createScaledBitmap(square, 144, 144, true)
            cachedArtUri = uri
            cachedSquareBitmap = scaled
            scaled
        } catch (_: Throwable) {
            null
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
