@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.example.otomusic

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
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
            Coil.imageLoader(applicationContext).memoryCache?.clear()
            System.gc()
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            Coil.imageLoader(applicationContext).memoryCache?.clear()
        }
    }

    override fun onCreate() {
        super.onCreate()

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

        player = ExoPlayer.Builder(this, audioOnlyRenderersFactory)
            .setLoadControl(lowRamLoadControl)
            .setAudioAttributes(audioAttributes, handleAudioFocus)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.repeatMode = Player.REPEAT_MODE_OFF

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateNotificationCustomLayout()
            }
        })

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
                if (squareArt != null) {
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

        mediaSession = MediaSession.Builder(this, player)
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

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        ACTION_CLOSE -> {
                            clearArtworkCache()
                            player.stop()
                            player.clearMediaItems()
                            stopSelf()
                        }
                        ACTION_FAVORITE -> {
                            val currentId = player.currentMediaItem?.mediaId
                            if (currentId != null) {
                                val favs = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
                                if (favs.contains(currentId)) favs.remove(currentId) else favs.add(currentId)
                                prefs.edit().putStringSet("favorites", favs).apply()
                                updateNotificationCustomLayout()
                            }
                        }
                        ACTION_GET_AUDIO_SESSION_ID -> {
                            val resultBundle = Bundle().apply {
                                putInt(KEY_AUDIO_SESSION_ID, player.audioSessionId)
                            }
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, resultBundle))
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
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
        val currentId = player.currentMediaItem?.mediaId
        val isFav = currentId != null &&
            (prefs.getStringSet("favorites", emptySet())?.contains(currentId) == true)
        session.setCustomLayout(listOf(buildFavoriteButton(isFav), buildCloseButton()))
    }

    private fun loadSquareArtworkLowRam(uri: Uri?): Bitmap? {
        if (uri == null) return null
        if (uri == cachedArtUri && cachedSquareBitmap != null && cachedSquareBitmap?.isRecycled == false) {
            return cachedSquareBitmap
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
            if (raw != cropped) raw.recycle()

            cachedSquareBitmap?.let { old -> if (old != cropped && !old.isRecycled) old.recycle() }
            cachedArtUri = uri
            cachedSquareBitmap = cropped
            cropped
        } catch (_: Exception) {
            null
        }
    }

    private fun cropBlackBarsAndSquare(src: Bitmap, targetSize: Int): Bitmap {
        val w = src.width
        val h = src.height
        val midY = h / 2
        val midX = w / 2

        fun isNearBlack(pixel: Int): Boolean {
            val r = AndroidColor.red(pixel)
            val g = AndroidColor.green(pixel)
            val b = AndroidColor.blue(pixel)
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
        val side = minOf(contentW, contentH)
        val cropX = left + (contentW - side) / 2
        val cropY = top + (contentH - side) / 2

        val square = Bitmap.createBitmap(src, cropX, cropY, side, side)
        val scaled = Bitmap.createScaledBitmap(square, targetSize, targetSize, true)
        if (square != src && square != scaled) square.recycle()
        return scaled
    }

    private fun clearArtworkCache() {
        cachedArtUri = null
        cachedSquareBitmap?.let { if (!it.isRecycled) it.recycle() }
        cachedSquareBitmap = null
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || !player.isPlaying) {
            if (!player.isPlaying) clearArtworkCache()
            Coil.imageLoader(applicationContext).memoryCache?.clear()
            System.gc()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (!player.isPlaying) clearArtworkCache()
        Coil.imageLoader(applicationContext).memoryCache?.clear()
        System.gc()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val currentPlayer = mediaSession?.player
        if (currentPlayer == null || !currentPlayer.playWhenReady || currentPlayer.mediaItemCount == 0) {
            clearArtworkCache()
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        application.unregisterActivityLifecycleCallbacks(activityRamCleaner)
        clearArtworkCache()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}

// ============================================================================
// 100% GMS-Free Gemini AI Smart DJ & 1-Tap Auto-Find Lyrics UI & HTTPS Client
// ============================================================================

@Composable
fun OtoAutoFindLyricsEmptyState(state: OtoUiState, song: Song) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (state.isAiBusy.value) "Finding lyrics with Gemini AI..." else "No embedded lyrics found",
            color = state.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = !state.isAiBusy.value,
            onClick = {
                if (state.geminiApiKey.value.isBlank()) {
                    state.showGeminiKeyDialog.value = true
                } else {
                    state.isAiBusy.value = true
                    scope.launch {
                        val res = GeminiAiHelper.fetchLyricsForSong(state.geminiApiKey.value, song)
                        state.isAiBusy.value = false
                        res.onSuccess { fetched ->
                            state.saveLyricsForSong(song.id, fetched)
                            Toast.makeText(ctx, "Lyrics saved for offline use!", Toast.LENGTH_SHORT).show()
                        }.onFailure { err ->
                            Toast.makeText(ctx, "AI Lyrics error: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = state.accentColor.value,
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (state.isAiBusy.value) "Searching..." else "1-Tap Auto-Find Lyrics",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Tap to edit manually",
            color = state.textSecondary,
            fontSize = 13.sp,
            modifier = Modifier.clickable { state.showEditLyricsDialog.value = song }
        )
    }
}

@Composable
fun OtoAutoFindLyricsEditButton(
    state: OtoUiState,
    song: Song,
    onLyricsFound: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    OutlinedButton(
        enabled = !state.isAiBusy.value,
        onClick = {
            if (state.geminiApiKey.value.isBlank()) {
                state.showGeminiKeyDialog.value = true
            } else {
                state.isAiBusy.value = true
                scope.launch {
                    val res = GeminiAiHelper.fetchLyricsForSong(state.geminiApiKey.value, song)
                    state.isAiBusy.value = false
                    res.onSuccess { fetched ->
                        onLyricsFound(fetched)
                        state.saveLyricsForSong(song.id, fetched)
                        Toast.makeText(ctx, "Lyrics found & saved!", Toast.LENGTH_SHORT).show()
                    }.onFailure { err ->
                        Toast.makeText(ctx, "Error: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = state.accentColor.value, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (state.isAiBusy.value) "Finding lyrics..." else "1-Tap Auto-Find Lyrics with AI",
            color = state.accentColor.value,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun OtoAiDjHomeCard(state: OtoUiState) {
    Surface(
        color = state.surfaceColor,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { state.showAiDjDialog.value = true }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(state.accentColor.value.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "AI Smart DJ",
                    tint = state.accentColor.value,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
            ) {
                Text(
                    text = "AI Smart DJ",
                    color = state.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    text = if (state.aiDjIntro.value.isNotBlank()) state.aiDjIntro.value
                    else "Tap to let your AI DJ mix songs from your offline library",
                    color = state.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = state.textSecondary
            )
        }
    }
}

@Composable
fun OtoAiSmartDjDialog(
    state: OtoUiState,
    onPlaySong: (Song, List<Song>?) -> Unit,
    onDismiss: () -> Unit
) {
    var moodPrompt by remember { mutableStateOf("") }
    var djMixResult by remember { mutableStateOf<List<Song>>(emptyList()) }
    var djMessage by remember { mutableStateOf(state.aiDjIntro.value) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    val presetMoods = listOf(
        "Late Night Chill",
        "Gym & Workout Hype",
        "Feel-Good Upbeat",
        "Deep Focus & Study",
        "Rainy Day Acoustic",
        "Throwback Classics"
    )

    fun triggerDjMix(promptToRun: String) {
        if (state.geminiApiKey.value.isBlank()) {
            state.showGeminiKeyDialog.value = true
            return
        }
        if (state.allSongs.isEmpty()) {
            Toast.makeText(ctx, "No offline songs found in library yet!", Toast.LENGTH_SHORT).show()
            return
        }
        state.isAiBusy.value = true
        scope.launch {
            val res = GeminiAiHelper.buildSmartDjQueue(
                apiKey = state.geminiApiKey.value,
                moodPrompt = promptToRun,
                library = state.allSongs.toList()
            )
            state.isAiBusy.value = false
            res.onSuccess { (intro, matchedSongs) ->
                djMessage = intro
                djMixResult = matchedSongs
                state.aiDjIntro.value = intro
                state.prefs.edit().putString("ai_dj_last_intro", intro).apply()
                if (matchedSongs.isNotEmpty()) {
                    onPlaySong(matchedSongs.first(), matchedSongs)
                    Toast.makeText(ctx, "AI DJ started playing ${matchedSongs.size} tracks!", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { err ->
                Toast.makeText(ctx, "AI DJ error: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = state.surfaceColor,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = state.accentColor.value)
                    Spacer(Modifier.width(8.dp))
                    Text("AI Smart DJ", color = state.textPrimary, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                }
                TextButton(onClick = { state.showGeminiKeyDialog.value = true }) {
                    Text("API Key", color = state.accentColor.value, fontSize = 12.sp)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Pick a quick vibe or type what you want to hear from your offline library:",
                    color = state.textSecondary,
                    fontSize = 13.sp
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(presetMoods) { mood ->
                        FilterChip(
                            selected = moodPrompt == mood,
                            onClick = {
                                moodPrompt = mood
                                triggerDjMix(mood)
                            },
                            label = { Text(mood, fontSize = 12.sp) }
                        )
                    }
                }
                OutlinedTextField(
                    value = moodPrompt,
                    onValueChange = { moodPrompt = it },
                    placeholder = { Text("e.g. Energetic night drive, relaxing melodies...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.isAiBusy.value) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(vertical = 6.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = state.accentColor.value,
                            strokeWidth = 2.dp
                        )
                        Text("AI DJ is mixing your offline tracks...", color = state.textPrimary, fontSize = 13.sp)
                    }
                } else if (djMessage.isNotBlank()) {
                    Surface(
                        color = state.cardColor,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "DJ Note: $djMessage",
                                color = state.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            if (djMixResult.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Up next: " + djMixResult.take(4).joinToString(", ") { it.title },
                                    color = state.textSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !state.isAiBusy.value,
                onClick = {
                    val q = moodPrompt.ifBlank { "Best mix of my favorite and top songs" }
                    triggerDjMix(q)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = state.accentColor.value,
                    contentColor = Color.Black
                )
            ) {
                Text("Mix & Play", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = state.textSecondary)
            }
        }
    )
}

@Composable
fun OtoGeminiApiKeyDialog(
    state: OtoUiState,
    onDismiss: () -> Unit
) {
    var keyInput by remember { mutableStateOf(state.geminiApiKey.value) }
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = state.surfaceColor,
        title = { Text("Gemini AI API Key", color = state.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Paste your free Gemini API key from Google AI Studio (aistudio.google.com/app/apikey). Uses direct HTTPS with zero Google Play Services and zero background RAM.",
                    color = state.textSecondary,
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    placeholder = { Text("AIzaSy...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                state.saveGeminiApiKey(keyInput)
                Toast.makeText(ctx, "Gemini API Key saved!", Toast.LENGTH_SHORT).show()
                onDismiss()
            }) {
                Text("Save", color = state.accentColor.value, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = state.textSecondary)
            }
        }
    )
}

object GeminiAiHelper {
    private val MODELS = listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash")

    suspend fun callGeminiPrompt(apiKey: String, prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("API key is empty"))
        }

        var lastError: Exception? = null
        for (model in MODELS) {
            try {
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$cleanKey"
                val conn = (URL(endpoint).openConnection() as HttpsURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12000
                    readTimeout = 20000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }

                val payload = JSONObject().apply {
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put(
                                "parts",
                                JSONArray().put(JSONObject().put("text", prompt))
                            )
                        )
                    )
                }

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val code = conn.responseCode
                if (code in 200..299) {
                    val responseStr = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                    val json = JSONObject(responseStr)
                    val candidates = json.optJSONArray("candidates")
                    val first = candidates?.optJSONObject(0)
                    val content = first?.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    val text = parts?.optJSONObject(0)?.optString("text")?.trim()
                    if (!text.isNullOrEmpty()) {
                        return@withContext Result.success(text)
                    }
                } else {
                    val errStr = try {
                        BufferedReader(InputStreamReader(conn.errorStream, Charsets.UTF_8)).use { it.readText() }
                    } catch (_: Exception) {
                        "HTTP $code"
                    }
                    lastError = IllegalStateException("HTTP $code: ${errStr.take(120)}")
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        Result.failure(lastError ?: IllegalStateException("Failed to reach Gemini API"))
    }

    suspend fun fetchLyricsForSong(apiKey: String, song: Song): Result<String> {
        val cleanArtist = if (song.artist.equals("<unknown>", ignoreCase = true)) "" else " by \"${song.artist}\""
        val prompt = """
            Find the complete, accurate lyrics for the song "${song.title}"$cleanArtist.
            Rules:
            - Output ONLY the plain song lyrics line by line.
            - Do NOT include markdown code blocks, titles, or introductory commentary.
            - If you are not sure of exact word-for-word lyrics, provide the known verses/chorus cleanly.
        """.trimIndent()
        return callGeminiPrompt(apiKey, prompt)
    }

    suspend fun buildSmartDjQueue(
        apiKey: String,
        moodPrompt: String,
        library: List<Song>
    ): Result<Pair<String, List<Song>>> {
        val sample = library.take(150)
        val catalogLines = sample.joinToString("\n") { s ->
            "${s.id}|${s.title.replace("|", " ")}|${s.artist.replace("|", " ")}|${s.genre.replace("|", " ")}"
        }
        val prompt = """
            You are an expert AI Music DJ inside an offline music player.
            User request / mood: "$moodPrompt"
            
            Here is the user's local song catalog (format: ID|Title|Artist|Genre):
            $catalogLines
            
            Select between 8 and 20 songs from this catalog that best fit the requested mood and flow well together.
            Reply in EXACTLY two lines:
            Line 1: A short, energetic 1-sentence DJ intro (max 18 words) describing the mix.
            Line 2: ONLY the comma-separated numerical IDs of the selected songs in playback order (for example: 102,45,88,19).
        """.trimIndent()

        return callGeminiPrompt(apiKey, prompt).mapCatching { reply ->
            val nonBlank = reply.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val intro = nonBlank.firstOrNull()?.removePrefix("Line 1:")?.trim()
                ?: "Spinning your custom AI DJ mix!"
            val idsText = nonBlank.drop(1).joinToString(" ")
            val idMap = library.associateBy { it.id }
            val picked = Regex("\\d+").findAll(idsText)
                .mapNotNull { it.value.toLongOrNull() }
                .mapNotNull { idMap[it] }
                .distinctBy { it.id }
                .toList()

            val finalQueue = if (picked.isNotEmpty()) picked else library.shuffled().take(15)
            intro to finalQueue
        }
    }
}
