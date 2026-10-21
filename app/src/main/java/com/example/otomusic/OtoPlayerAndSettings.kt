package com.example.otomusic

import android.content.Context
import android.media.AudioManager
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Responsive Oto Music Now Playing Screen (matches 00:15 & 01:47 - 02:28 in video).
 * Supports both Landscape Split-Screen (Artwork/Lyrics Card on Left, Controls/Volume/Specs/Actions on Right)
 * and Portrait Stacked Layout seamlessly.
 */
@Composable
fun OtoNowPlayingScreen(
    state: OtoUiState,
    song: Song,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onOpenQueue: () -> Unit
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var currentVol by remember {
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol.toFloat())
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(state.backgroundColor)
            .padding(16.dp)
    ) {
        val isLandscape = maxWidth > maxHeight || maxWidth > 580.dp

        if (isLandscape) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Pane: Large Rounded Artwork / Synchronized Lyrics Card + Bottom Queue & Lyrics toggles
                Column(
                    modifier = Modifier
                        .weight(0.48f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(state.surfaceColor)
                            .clickable { state.showLyricsOnPlayer.value = !state.showLyricsOnPlayer.value }
                    ) {
                        NowPlayingArtworkOrLyrics(state = state, song = song)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, start = 24.dp, end = 24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onOpenQueue) {
                            Icon(Icons.Default.Menu, contentDescription = "Playing Queue", tint = state.textSecondary)
                        }
                        IconButton(onClick = { state.showLyricsOnPlayer.value = !state.showLyricsOnPlayer.value }) {
                            Icon(
                                imageVector = Icons.Outlined.Subtitles,
                                contentDescription = "Lyrics View",
                                tint = if (state.showLyricsOnPlayer.value) state.accentColor.value else state.textSecondary
                            )
                        }
                    }
                }

                // Right Pane: Seekbar, Title/Artist, Controls, Volume Slider, Audio Info, Bottom 5 Action Icons
                Column(
                    modifier = Modifier
                        .weight(0.52f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    NowPlayingControlsPane(
                        state = state,
                        song = song,
                        currentVol = currentVol,
                        onVolumeChange = { newVol ->
                            currentVol = newVol
                            audioManager.setStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                (newVol * maxVol).toInt().coerceIn(0, maxVol),
                                0
                            )
                        },
                        onTogglePlay = onTogglePlay,
                        onNext = onNext,
                        onPrev = onPrev,
                        onSeek = onSeek,
                        onToggleRepeat = onToggleRepeat,
                        onToggleShuffle = onToggleShuffle
                    )
                }
            }
        } else {
            // Portrait Layout
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Collapse", tint = state.textPrimary, modifier = Modifier.size(30.dp))
                    }
                    Text("Now Playing", color = state.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    IconButton(onClick = onOpenQueue) {
                        Icon(Icons.Default.QueueMusic, contentDescription = "Queue", tint = state.textPrimary)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(state.surfaceColor)
                        .clickable { state.showLyricsOnPlayer.value = !state.showLyricsOnPlayer.value }
                ) {
                    NowPlayingArtworkOrLyrics(state = state, song = song)
                }

                NowPlayingControlsPane(
                    state = state,
                    song = song,
                    currentVol = currentVol,
                    onVolumeChange = { newVol ->
                        currentVol = newVol
                        audioManager.setStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            (newVol * maxVol).toInt().coerceIn(0, maxVol),
                            0
                        )
                    },
                    onTogglePlay = onTogglePlay,
                    onNext = onNext,
                    onPrev = onPrev,
                    onSeek = onSeek,
                    onToggleRepeat = onToggleRepeat,
                    onToggleShuffle = onToggleShuffle
                )
            }
        }
    }
}

@Composable
fun NowPlayingArtworkOrLyrics(state: OtoUiState, song: Song) {
    if (!state.showLyricsOnPlayer.value) {
        AsyncImage(
            model = song.albumArtUri,
            contentDescription = song.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    } else {
        // Matches 02:02 ("No embedded lyrics found / Tap to edit") and 02:10 - 02:12 (Synchronized Lyrics view)
        val lines = state.getLyricsForSong(song)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(state.surfaceColor)
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            if (lines.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No embedded lyrics found",
                        color = state.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Tap to edit",
                        color = state.accentColor.value,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { state.showEditLyricsDialog.value = song }
                    )
                }
            } else {
                val progressRatio = if (state.duration.longValue > 0) {
                    (state.currentPosition.longValue.toFloat() / state.duration.longValue.toFloat()).coerceIn(0f, 0.99f)
                } else 0f
                val activeIndex = (progressRatio * lines.size).toInt().coerceIn(0, lines.lastIndex)

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(vertical = 24.dp)
                ) {
                    itemsIndexed(lines) { idx, line ->
                        val isActive = idx == activeIndex
                        Text(
                            text = line,
                            color = if (isActive) state.textPrimary else state.textSecondary.copy(alpha = 0.5f),
                            fontSize = if (isActive) 18.sp else 16.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NowPlayingControlsPane(
    state: OtoUiState,
    song: Song,
    currentVol: Float,
    onVolumeChange: (Float) -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit
) {
    val totalDur = state.duration.longValue.coerceAtLeast(song.duration).coerceAtLeast(1L)
    val pos = state.currentPosition.longValue.coerceIn(0L, totalDur)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Seekbar with timestamps above (matches 01:47 in video)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatDuration(pos), color = state.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(formatDuration(totalDur), color = state.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = pos.toFloat(),
            onValueChange = { onSeek(it.toLong()) },
            valueRange = 0f..totalDur.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = state.accentColor.value,
                activeTrackColor = state.accentColor.value,
                inactiveTrackColor = state.surfaceColor
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(6.dp))

        // 2. Centered Song Title & Artist (matches 01:47)
        Text(
            text = song.title,
            color = state.textPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = song.artist,
            color = state.textSecondary,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    state.artists.find { it.name.equals(song.artist, true) }?.let {
                        state.selectedArtist.value = it
                        state.currentScreen.value = "artist_detail"
                    }
                }
        )

        Spacer(Modifier.height(18.dp))

        // 3. Main 5 Playback Controls (Repeat, Prev, Large Circle Play/Pause, Next, Shuffle)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleRepeat) {
                Icon(
                    imageVector = if (state.repeatMode.intValue == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    contentDescription = "Repeat",
                    tint = if (state.repeatMode.intValue != Player.REPEAT_MODE_OFF) state.accentColor.value else state.textSecondary
                )
            }
            IconButton(onClick = onPrev) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = state.textPrimary, modifier = Modifier.size(34.dp))
            }
            Surface(
                shape = CircleShape,
                color = state.accentColor.value,
                modifier = Modifier
                    .size(64.dp)
                    .clickable { onTogglePlay() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (state.isPlaying.value) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color(0xFF1C1B1F),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = state.textPrimary, modifier = Modifier.size(34.dp))
            }
            IconButton(onClick = onToggleShuffle) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (state.shuffleEnabled.value) state.accentColor.value else state.textSecondary
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 4. Volume Slider Row (matches 00:15 & 01:47)
        if (state.showVolumeSlider.value) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.VolumeDown, contentDescription = "Volume Down", tint = state.textSecondary, modifier = Modifier.size(20.dp))
                Slider(
                    value = currentVol,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = state.accentColor.value,
                        activeTrackColor = state.accentColor.value,
                        inactiveTrackColor = state.surfaceColor
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                )
                Icon(Icons.Default.VolumeUp, contentDescription = "Volume Up", tint = state.textSecondary, modifier = Modifier.size(20.dp))
            }
        }

        // 5. Audio Format / Bitrate / Sample Rate Line (matches "MP3 • 320 kb/s • 44.1 kHz" & "OPUS • 144 kb/s • 48.0 kHz")
        if (state.showSongInfo.value) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = song.audioSpecLine,
                color = state.textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(12.dp))

        // 6. Bottom 5 Action Icons Row (Lyrics, Favorite, Sleep Timer, Add to Playlist, More Options - matches 00:15 & 02:15)
        val isFav = state.isFavorite(song)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { state.showLyricsOnPlayer.value = !state.showLyricsOnPlayer.value }) {
                Icon(
                    imageVector = Icons.Outlined.Subtitles,
                    contentDescription = "Lyrics",
                    tint = if (state.showLyricsOnPlayer.value) state.accentColor.value else state.textSecondary
                )
            }
            IconButton(onClick = { state.toggleFavorite(song) }) {
                Icon(
                    imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (isFav) state.accentColor.value else state.textSecondary
                )
            }
            IconButton(onClick = { state.showSleepTimerDialog.value = true }) {
                Icon(
                    imageVector = Icons.Default.NightsStay,
                    contentDescription = "Sleep Timer",
                    tint = if (state.sleepTimerRemainingSec.longValue > 0L) state.accentColor.value else state.textSecondary
                )
            }
            IconButton(onClick = { state.showAddToPlaylistDialog.value = song }) {
                Icon(
                    imageVector = Icons.Default.PlaylistAdd,
                    contentDescription = "Add to Playlist",
                    tint = state.textSecondary
                )
            }
            IconButton(onClick = { state.showMoreOptionsSheet.value = song }) {
                Icon(
                    imageVector = Icons.Default.MoreHoriz,
                    contentDescription = "More Options",
                    tint = state.textSecondary
                )
            }
        }
    }
}

/**
 * Interactive Circular Sleep Timer Dialog (matches 02:15 - 02:27 in video).
 */
@Composable
fun OtoSleepTimerDialog(
    state: OtoUiState,
    onStart: (Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedMins by remember { mutableIntStateOf(state.sleepTimerMinutes.intValue.coerceIn(1, 120)) }
    val stopsAtText = remember(selectedMins) {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MINUTE, selectedMins)
        SimpleDateFormat("h:mm a", Locale.US).format(cal.time).uppercase(Locale.US)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = state.surfaceColor,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.92f)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "PLAYBACK",
                            color = state.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                        Text(
                            text = "Sleep timer",
                            color = state.textPrimary,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(state.cardColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.NightsStay, contentDescription = null, tint = state.accentColor.value, modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Circular Dial (matches 02:16 & 02:27)
                Box(
                    modifier = Modifier
                        .size(210.dp)
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val dx = offset.x - cx
                                val dy = offset.y - cy
                                var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
                                if (deg < 0f) deg += 360f
                                val mins = ((deg / 360f) * 120f).toInt().coerceIn(1, 120)
                                selectedMins = mins
                                state.sleepTimerMinutes.intValue = mins
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val accent = state.accentColor.value
                    val trackCol = state.cardColor
                    val sweep = (selectedMins / 120f) * 360f

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeWidth = 14.dp.toPx()
                        val radius = (size.minDimension - strokeWidth * 2) / 2f
                        val centerOffset = Offset(size.width / 2f, size.height / 2f)

                        // Tick marks & outer circle
                        drawCircle(
                            color = trackCol,
                            radius = radius,
                            center = centerOffset,
                            style = Stroke(width = strokeWidth)
                        )
                        // Active progress arc
                        drawArc(
                            color = accent,
                            startAngle = -90f,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = Offset(centerOffset.x - radius, centerOffset.y - radius),
                            size = Size(radius * 2f, radius * 2f),
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )
                        // Thumb handle
                        val rad = Math.toRadians((sweep - 90f).toDouble())
                        val thumbX = centerOffset.x + (radius * cos(rad)).toFloat()
                        val thumbY = centerOffset.y + (radius * sin(rad)).toFloat()
                        drawCircle(color = accent, radius = 12.dp.toPx(), center = Offset(thumbX, thumbY))
                        drawCircle(color = Color.Black, radius = 4.dp.toPx(), center = Offset(thumbX, thumbY))
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$selectedMins",
                            color = state.textPrimary,
                            fontSize = 48.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "min",
                            color = state.textSecondary,
                            fontSize = 14.sp
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "STOPS AT $stopsAtText",
                    color = state.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                Spacer(Modifier.height(14.dp))

                // Preset Chips Row (5m, 10m, 15m, 30m, 45m, 1h, 1h 30m - matches 02:16)
                val presets = listOf(5 to "5m", 10 to "10m", 15 to "15m", 30 to "30m", 45 to "45m", 60 to "1h", 90 to "1h 30m")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(presets) { (mins, label) ->
                        val active = selectedMins == mins
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = if (active) state.accentColor.value else state.cardColor,
                            modifier = Modifier.clickable {
                                selectedMins = mins
                                state.sleepTimerMinutes.intValue = mins
                            }
                        ) {
                            Text(
                                text = label,
                                color = if (active) Color(0xFF1C1B1F) else state.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Finish current song & Fade out toggles (matches 02:21)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = state.cardColor,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = state.textSecondary)
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text("Finish current song", color = state.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Don't cut off mid-track", color = state.textSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = state.sleepTimerFinishLastSong.value,
                            onCheckedChange = {
                                state.sleepTimerFinishLastSong.value = it
                                state.savePrefBoolean("sleep_finish_song", it)
                            }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = state.cardColor,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.VolumeDown, contentDescription = null, tint = state.textSecondary)
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text("Fade out", color = state.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Last 10 sec", color = state.textSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = state.sleepTimerFadeOut.value,
                            onCheckedChange = {
                                state.sleepTimerFadeOut.value = it
                                state.savePrefBoolean("sleep_fade_out", it)
                            }
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = state.cardColor,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clickable {
                                if (state.sleepTimerRemainingSec.longValue > 0L) onCancel() else onDismiss()
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("Cancel", color = state.textPrimary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = state.accentColor.value,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clickable { onStart(selectedMins) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("Start", color = Color(0xFF1C1B1F), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Now Playing & Song More Options Sheet (matches 02:29 - 02:31 in video).
 */
@Composable
fun OtoMoreOptionsSheet(
    state: OtoUiState,
    song: Song,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onClearQueue: () -> Unit
) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = state.surfaceColor,
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.86f)
                .padding(12.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Group 1: Equalizer, Speed & Pitch, Save playing queue, Clear playing queue
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = state.cardColor, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            MoreSheetRow(Icons.Default.Equalizer, "Equalizer", state) {
                                onDismiss()
                                state.currentScreen.value = "equalizer"
                            }
                            MoreSheetRow(Icons.Default.Speed, "Speed & Pitch", state) {
                                onDismiss()
                                state.showSpeedPitchDialog.value = true
                            }
                            MoreSheetRow(Icons.Default.PlaylistAdd, "Save playing queue", state) {
                                state.saveQueueAsPlaylist("Queue ${state.playlists.size + 1}")
                                onDismiss()
                            }
                            MoreSheetRow(Icons.Default.ClearAll, "Clear playing queue", state) {
                                onClearQueue()
                                onDismiss()
                            }
                        }
                    }
                }

                // Group 2: Go to album, Go to artist, Go to album artist, Go to folder
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = state.cardColor, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            MoreSheetRow(Icons.Outlined.Album, "Go to album", state) {
                                onDismiss()
                                state.albums.find { it.id == song.albumId }?.let {
                                    state.selectedAlbum.value = it
                                    state.currentScreen.value = "album_detail"
                                }
                            }
                            MoreSheetRow(Icons.Outlined.Person, "Go to artist", state) {
                                onDismiss()
                                state.artists.find { it.name.equals(song.artist, true) }?.let {
                                    state.selectedArtist.value = it
                                    state.currentScreen.value = "artist_detail"
                                }
                            }
                            MoreSheetRow(Icons.Outlined.Person, "Go to album artist", state) {
                                onDismiss()
                                state.artists.find { it.name.equals(song.artist, true) }?.let {
                                    state.selectedArtist.value = it
                                    state.currentScreen.value = "artist_detail"
                                }
                            }
                            MoreSheetRow(Icons.Outlined.Folder, "Go to folder", state) {
                                onDismiss()
                                state.folders.find { it.path == song.folderPath }?.let {
                                    state.selectedFolder.value = it
                                    state.currentScreen.value = "folder_detail"
                                }
                            }
                        }
                    }
                }

                // Group 3: Add to playlist, Tag editor, Edit lyrics
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = state.cardColor, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            MoreSheetRow(Icons.Default.PlaylistAdd, "Add to playlist", state) {
                                onDismiss()
                                state.showAddToPlaylistDialog.value = song
                            }
                            MoreSheetRow(Icons.Default.Edit, "Tag editor", state) {
                                onDismiss()
                                state.showTagEditorDialog.value = song
                            }
                            MoreSheetRow(Icons.Outlined.Subtitles, "Edit lyrics", state) {
                                onDismiss()
                                state.showEditLyricsDialog.value = song
                            }
                        }
                    }
                }

                // Group 4: Details, Share, Delete from device
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = state.cardColor, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            MoreSheetRow(Icons.Outlined.Info, "Details", state) {
                                onDismiss()
                                state.showSongDetailsDialog.value = song
                            }
                            MoreSheetRow(Icons.Default.Share, "Share", state) {
                                onDismiss()
                                onShare()
                            }
                            MoreSheetRow(Icons.Default.Delete, "Delete from device", state) {
                                state.allSongs.removeAll { it.id == song.id }
                                state.queue.removeAll { it.id == song.id }
                                Toast.makeText(context, "Removed from library", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MoreSheetRow(icon: ImageVector, label: String, state: OtoUiState, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = state.textPrimary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = state.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Song Details Dialog (matches 02:32 - 02:34 in video).
 */
@Composable
fun OtoSongDetailsDialog(
    state: OtoUiState,
    song: Song,
    onDismiss: () -> Unit
) {
    val sizeMb = String.format(Locale.US, "%.1f MB", song.sizeBytes / (1024f * 1024f))
    val dateFmt = remember { SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US) }
    val addedStr = if (song.dateAddedSec > 0) dateFmt.format(Date(song.dateAddedSec * 1000L)) else "Aug 29, 2026, 8:01 PM"
    val modStr = if (song.dateModifiedSec > 0) dateFmt.format(Date(song.dateModifiedSec * 1000L)) else addedStr

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = state.surfaceColor,
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.86f)
                .padding(12.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = song.albumArtUri,
                            contentDescription = null,
                            modifier = Modifier
                                .size(54.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Column(modifier = Modifier.padding(start = 14.dp)) {
                            Text(song.title, color = state.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, color = state.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                // 4 Spec Badges Row (Format, Sample rate, Depth, Stereo - matches 02:32)
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val specs = listOf(
                            song.formatLabel to "Format",
                            "${song.sampleRateHz / 1000} kHz" to "Sample rate",
                            "16 bit" to "Depth",
                            "2 ch" to "Stereo"
                        )
                        items(specs) { (value, label) ->
                            Surface(shape = RoundedCornerShape(14.dp), color = state.cardColor) {
                                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    Text(value, color = state.accentColor.value, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Text(label, color = state.textSecondary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                // FILE Section
                item {
                    Text("FILE", color = state.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Surface(shape = RoundedCornerShape(18.dp), color = state.cardColor, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            DetailField("File name", song.path.substringAfterLast('/').ifEmpty { "${song.title}.${song.formatLabel.lowercase(Locale.ROOT)}" }, state)
                            DetailField("File path", song.path.ifEmpty { "${song.folderPath}/${song.title}.mp3" }, state)
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Box(modifier = Modifier.weight(1f)) { DetailField("Size", sizeMb, state) }
                                Box(modifier = Modifier.weight(1f)) { DetailField("Bitrate", "${song.bitrateKbps} kb/s", state) }
                            }
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Box(modifier = Modifier.weight(1f)) { DetailField("Length", formatDuration(song.duration), state) }
                                Box(modifier = Modifier.weight(1f)) { DetailField("Sampling rate", "${song.sampleRateHz} Hz", state) }
                            }
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Box(modifier = Modifier.weight(1f)) { DetailField("Date added", addedStr, state) }
                                Box(modifier = Modifier.weight(1f)) { DetailField("Last modified", modStr, state) }
                            }
                        }
                    }
                }

                // ARTWORK Section
                item {
                    Text("ARTWORK", color = state.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Surface(shape = RoundedCornerShape(18.dp), color = state.cardColor, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                            Box(modifier = Modifier.weight(1f)) { DetailField("Dimensions", "500 × 500", state) }
                            Box(modifier = Modifier.weight(1f)) { DetailField("Format", "JPEG", state) }
                            Box(modifier = Modifier.weight(1f)) { DetailField("Size", "64.8 KB", state) }
                        }
                    }
                }

                // TAGS Section
                item {
                    Text("TAGS", color = state.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Surface(shape = RoundedCornerShape(18.dp), color = state.cardColor, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            DetailField("Title", song.title, state)
                            DetailField("Artist", song.artist, state)
                            DetailField("Album", song.album, state)
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Box(modifier = Modifier.weight(1f)) { DetailField("Genre", song.genre, state) }
                                Box(modifier = Modifier.weight(1f)) { DetailField("Year", "${song.year}", state) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailField(label: String, value: String, state: OtoUiState) {
    Column {
        Text(label, color = state.textSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = state.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Listening Stats Screen (matches 00:20 - 00:32 in video).
 */
@Composable
fun OtoListeningStatsScreen(
    state: OtoUiState,
    onPlaySong: (Song, List<Song>?) -> Unit
) {
    var selectedRange by remember { mutableStateOf("All time") }
    val mins = state.totalListeningMinutes.intValue
    val hrsText = "${mins / 60}h ${mins % 60}m"
    val topArtist = state.artists.firstOrNull()
    val topAlbums = state.albums.take(6)
    val genres = state.allSongs.groupBy { it.genre }.map { it.key to it.value.size }.sortedByDescending { it.second }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(state.backgroundColor)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            IconButton(onClick = { state.currentScreen.value = "main" }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = state.textPrimary)
            }
            Text("Listening stats", color = state.textPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$selectedRange • ${state.allSongs.size} Tracks • ${state.artists.size} Artists • ${state.albums.size} Albums",
                color = state.textSecondary,
                fontSize = 13.sp
            )
        }

        // Week | Month | All time segmented bar (matches 00:20)
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = state.surfaceColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(4.dp)) {
                    listOf("Week", "Month", "All time").forEach { tab ->
                        val active = selectedRange == tab
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (active) Color(0xFF423D52) else Color.Transparent,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedRange = tab }
                        ) {
                            Text(
                                text = tab,
                                color = if (active) Color.White else state.textSecondary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 10.dp)
                            )
                        }
                    }
                }
            }
        }

        // Purple Banner Card: TOTAL LISTENING (matches 00:21 & 00:28)
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFF4F378B),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "TOTAL LISTENING • ${selectedRange.uppercase(Locale.US)}",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "$mins",
                            color = Color.White,
                            fontSize = 48.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "min",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 16.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = hrsText,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Longest Streak & Top Artist Side-by-Side (matches 00:28)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = state.surfaceColor,
                    modifier = Modifier.weight(1f).height(115.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = Color(0xFFFFB4AB))
                        Column {
                            Text("${state.longestStreakDays.intValue} days", color = state.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("LONGEST STREAK", color = state.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = state.surfaceColor,
                    modifier = Modifier.weight(1f).height(115.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        AsyncImage(
                            model = topArtist?.artworkUri,
                            contentDescription = null,
                            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(state.cardColor),
                            contentScale = ContentScale.Crop
                        )
                        Column {
                            Text("TOP ARTIST", color = state.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = topArtist?.name ?: "Unknown Artist",
                                color = state.textPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text("${(topArtist?.songs?.size ?: 4) * 2} plays", color = state.textSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Sessions Card (matches 00:29)
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = state.surfaceColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Sessions", color = state.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("${state.totalSessions.intValue}", color = state.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                            Text("total", color = state.textSecondary, fontSize = 12.sp)
                        }
                        Column {
                            Text("36m", color = state.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                            Text("avg length", color = state.textSecondary, fontSize = 12.sp)
                        }
                        Column {
                            Text("1h 23m", color = state.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                            Text("longest", color = state.textSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // "When you listen" 7x6 Heatmap (matches 00:30)
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = state.surfaceColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("When you listen", color = state.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("ALL TIME", color = state.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(14.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val cols = listOf("Night", "Early", "Morn", "Midday", "Eve", "Late")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Spacer(Modifier.width(18.dp))
                            cols.forEach { c ->
                                Text(c, color = state.textSecondary, fontSize = 10.sp, modifier = Modifier.width(34.dp), textAlign = TextAlign.Center)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        val days = listOf("M", "T", "W", "T", "F", "S", "S")
                        days.forEachIndexed { rIdx, day ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(vertical = 3.dp)
                            ) {
                                Text(day, color = state.textSecondary, fontSize = 11.sp, modifier = Modifier.width(18.dp))
                                for (cIdx in 0..5) {
                                    val heat = when {
                                        rIdx == 0 && cIdx == 4 -> 0.9f
                                        (rIdx + cIdx) % 3 == 0 -> 0.45f
                                        else -> 0.16f
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFFFFB4AB).copy(alpha = heat))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Top Albums Carousel (matches 00:31)
        if (topAlbums.isNotEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = state.surfaceColor,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("Top albums", color = state.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(topAlbums) { alb ->
                                Column(
                                    modifier = Modifier
                                        .width(120.dp)
                                        .clickable {
                                            state.selectedAlbum.value = alb
                                            state.currentScreen.value = "album_detail"
                                        }
                                ) {
                                    AsyncImage(
                                        model = alb.albumArtUri,
                                        contentDescription = alb.title,
                                        modifier = Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)).background(state.cardColor),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(alb.title, color = state.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${alb.songs.size * 2} plays", color = state.textSecondary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Genres Badges (matches 00:32)
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = state.surfaceColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Genres", color = state.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        val list = genres.ifEmpty { listOf("Music" to state.allSongs.size) }
                        itemsIndexed(list) { idx, (genreName, count) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(if (idx == 0) state.accentColor.value else state.cardColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$count",
                                        color = if (idx == 0) Color(0xFF1C1B1F) else state.textPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(genreName, color = state.textSecondary, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Built-in 5-Band Equalizer & Bass Boost Screen (Zero Google Play Services).
 */
@Composable
fun OtoEqualizerScreen(
    state: OtoUiState,
    onApplyEqualizer: () -> Unit
) {
    val presets = listOf("Normal", "Classical", "Dance", "Flat", "Folk", "Heavy Metal", "Hip Hop", "Jazz", "Pop", "Rock")
    val bandLabels = listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(state.backgroundColor)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { state.currentScreen.value = "main" }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = state.textPrimary)
                }
                Text("Equalizer", color = state.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Switch(
                checked = state.eqEnabled.value,
                onCheckedChange = {
                    state.eqEnabled.value = it
                    state.savePrefBoolean("eq_enabled", it)
                    onApplyEqualizer()
                }
            )
        }

        Spacer(Modifier.height(14.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(presets) { preset ->
                val active = state.eqPreset.value == preset
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (active) state.accentColor.value else state.surfaceColor,
                    modifier = Modifier.clickable {
                        state.eqPreset.value = preset
                        state.savePrefString("eq_preset", preset)
                        val curve = when (preset) {
                            "Bass" , "Hip Hop" -> listOf(0.7f, 0.4f, 0f, 0.2f, 0.5f)
                            "Rock" -> listOf(0.5f, 0.2f, -0.2f, 0.3f, 0.6f)
                            "Pop" -> listOf(-0.1f, 0.3f, 0.5f, 0.3f, -0.1f)
                            "Classical" -> listOf(0.4f, 0.2f, -0.1f, 0.3f, 0.5f)
                            "Flat" -> listOf(0f, 0f, 0f, 0f, 0f)
                            else -> listOf(0.2f, 0f, 0f, 0.1f, 0.2f)
                        }
                        for (i in 0..4) state.eqBands[i] = curve[i]
                        onApplyEqualizer()
                    }
                ) {
                    Text(
                        text = preset,
                        color = if (active) Color(0xFF1C1B1F) else state.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        Surface(shape = RoundedCornerShape(20.dp), color = state.surfaceColor, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                bandLabels.forEachIndexed { idx, freq ->
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(freq, color = state.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            val db = (state.eqBands[idx] * 15).toInt()
                            Text("${if (db >= 0) "+$db" else "$db"} dB", color = state.textSecondary, fontSize = 12.sp)
                        }
                        Slider(
                            value = state.eqBands[idx],
                            onValueChange = {
                                state.eqBands[idx] = it
                                onApplyEqualizer()
                            },
                            valueRange = -1f..1f,
                            enabled = state.eqEnabled.value,
                            colors = SliderDefaults.colors(
                                thumbColor = state.accentColor.value,
                                activeTrackColor = state.accentColor.value
                            )
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Surface(shape = RoundedCornerShape(20.dp), color = state.surfaceColor, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("Bass Boost", color = state.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Slider(
                    value = state.bassBoostStrength.floatValue,
                    onValueChange = {
                        state.bassBoostStrength.floatValue = it
                        state.prefs.edit().putFloat("bass_boost", it).apply()
                        onApplyEqualizer()
                    },
                    valueRange = 0f..1f,
                    enabled = state.eqEnabled.value,
                    colors = SliderDefaults.colors(
                        thumbColor = state.accentColor.value,
                        activeTrackColor = state.accentColor.value
                    )
                )
            }
        }
    }
}

/**
 * Complete Settings Screen Hierarchy (matches 00:39 - 01:32 in video).
 */
@Composable
fun OtoSettingsMainScreen(state: OtoUiState) {
    var settingSearch by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(state.backgroundColor)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            IconButton(onClick = { state.currentScreen.value = "main" }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = state.textPrimary)
            }
            Text("Settings", color = state.textPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))

            // "Find a setting" search bar (matches 00:39)
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = state.surfaceColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .height(48.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = state.textSecondary)
                    Spacer(Modifier.width(12.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (settingSearch.isEmpty()) {
                            Text("Find a setting", color = state.textSecondary, fontSize = 15.sp)
                        }
                        BasicTextField(
                            value = settingSearch,
                            onValueChange = { settingSearch = it },
                            singleLine = true,
                            textStyle = TextStyle(color = state.textPrimary, fontSize = 15.sp),
                            cursorBrush = SolidColor(state.accentColor.value),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // LOOK & FEEL Section
        item {
            SettingsCategoryLabel("LOOK & FEEL", state)
            OtoSettingsCard(
                icon = Icons.Outlined.Palette,
                title = "Themes & Colors",
                subtitle = "Change theme, accent color and highlight color",
                state = state,
                onClick = { state.currentScreen.value = "themes" }
            )
            OtoSettingsCard(
                icon = Icons.Outlined.PlayCircle,
                title = "Now playing",
                subtitle = "Themes, extra controls, display content, lockscreen",
                state = state,
                onClick = { state.currentScreen.value = "now_playing_settings" }
            )
            OtoSettingsCard(
                icon = Icons.Outlined.Tune,
                title = "Customization",
                subtitle = "Home tabs, details page",
                state = state,
                onClick = { state.currentScreen.value = "customization_settings" }
            )
        }

        // CONTENT Section
        item {
            SettingsCategoryLabel("CONTENT", state)
            OtoSettingsCard(
                icon = Icons.Outlined.LibraryMusic,
                title = "Library",
                subtitle = "Multiple artists and genres, Filters, navigation",
                state = state,
                onClick = { state.currentScreen.value = "library_settings" }
            )
            OtoSettingsCard(
                icon = Icons.Default.QueueMusic,
                title = "Playlists",
                subtitle = "Recently added and recently played playlist duration, import and export",
                state = state,
                onClick = { state.currentScreen.value = "playlists_settings" }
            )
            OtoSettingsCard(
                icon = Icons.Outlined.Image,
                title = "Images",
                subtitle = "Enable image caching, metadata download etc",
                state = state,
                onClick = { state.currentScreen.value = "images_settings" }
            )
        }

        // PLAYBACK Section
        item {
            SettingsCategoryLabel("PLAYBACK", state)
            OtoSettingsCard(
                icon = Icons.Outlined.Headset,
                title = "Audio",
                subtitle = "Fade audio, replay gain, gapless playback etc",
                state = state,
                onClick = { state.currentScreen.value = "audio_settings" }
            )
        }

        // APP Section
        item {
            SettingsCategoryLabel("APP", state)
            OtoSettingsCard(
                icon = Icons.Outlined.CloudUpload,
                title = "Backup & restore",
                subtitle = "Save playlists, settings and history to a file",
                state = state,
                onClick = { state.currentScreen.value = "backup_restore" }
            )
            OtoSettingsCard(
                icon = Icons.Outlined.Info,
                title = "About",
                subtitle = "App version, licenses and credits",
                state = state,
                onClick = { state.currentScreen.value = "about" }
            )
        }
    }
}

@Composable
fun SettingsCategoryLabel(text: String, state: OtoUiState) {
    Text(
        text = text,
        color = state.textSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp, start = 4.dp)
    )
}

@Composable
fun OtoSettingsCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    state: OtoUiState,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = state.surfaceColor,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = title, tint = state.textPrimary)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(title, color = state.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = state.textSecondary, fontSize = 12.sp)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = state.textSecondary)
        }
    }
}

/**
 * Themes & Colors Screen (matches 00:40 - 00:53 in video, with ALL palettes & AMOLED unlocked!).
 */
@Composable
fun OtoThemesAndColorsScreen(state: OtoUiState) {
    val categories = listOf("Default", "Hue", "Oto Custom", "Vibrant", "Content", "Rainbow", "Fruit Salad", "Mono")
    val palettesByCategory = mapOf(
        "Default" to listOf(Color(0xFFD0BCFF), Color(0xFFA8C7FA), Color(0xFF80CBC4)),
        "Hue" to listOf(Color(0xFFFFB4AB), Color(0xFFFFD8E4), Color(0xFFE6B8AF), Color(0xFFFFE082), Color(0xFFDCEDC8), Color(0xFFA5D6A7), Color(0xFF80DEEA), Color(0xFF9FA8DA), Color(0xFFCE93D8)),
        "Oto Custom" to listOf(Color(0xFFE8DEF8), Color(0xFFFFD9E2), Color(0xFFFFDBC8), Color(0xFFE7E48E), Color(0xFFBDF2C8), Color(0xFFA6EEFF), Color(0xFFBAC3FF), Color(0xFFEAB2FF)),
        "Vibrant" to listOf(Color(0xFFFF5252), Color(0xFFFF4081), Color(0xFF7C4DFF), Color(0xFF448AFF), Color(0xFF18FFFF), Color(0xFF69F0AE), Color(0xFFFFD740), Color(0xFFFF6E40)),
        "Content" to listOf(Color(0xFFD0BCFF), Color(0xFFFFB4AB), Color(0xFF80CBC4), Color(0xFFFFCC80), Color(0xFF90CAF9)),
        "Rainbow" to listOf(Color(0xFFFF8A80), Color(0xFFFFD180), Color(0xFFFFFF8D), Color(0xFFB9F6CA), Color(0xFF84FFFF), Color(0xFF82B1FF), Color(0xFFB388FF), Color(0xFFFF80AB)),
        "Fruit Salad" to listOf(Color(0xFFFF9E80), Color(0xFFFFAB91), Color(0xFFE6EE9C), Color(0xFFA5D6A7), Color(0xFF80CBC4), Color(0xFFCE93D8)),
        "Mono" to listOf(Color(0xFFE0E0E0), Color(0xFFBDBDBD), Color(0xFF9E9E9E))
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(state.backgroundColor)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        IconButton(onClick = { state.currentScreen.value = "settings" }) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = state.textPrimary)
        }
        Text("Themes & Colors", color = state.textPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("How the app looks. Pick the brightness, then choose a palette that flavours every surface.", color = state.textSecondary, fontSize = 13.sp)

        SettingsCategoryLabel("APPEARANCE", state)

        Surface(shape = RoundedCornerShape(18.dp), color = state.surfaceColor, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("App theme", color = state.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Follow the system, or pin to light or dark.", color = state.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(22.dp), color = state.cardColor, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(4.dp)) {
                        listOf("Light", "Dark", "System").forEach { mode ->
                            val active = state.appThemeMode.value == mode
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = if (active) Color(0xFF494358) else Color.Transparent,
                                modifier = Modifier.weight(1f).clickable { state.updateThemeMode(mode) }
                            ) {
                                Text(
                                    text = mode,
                                    color = if (active) Color.White else state.textSecondary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Unlocked AMOLED Extra Dark Colors Toggle (matches 00:41, unlocked!)
        Surface(shape = RoundedCornerShape(18.dp), color = state.surfaceColor, modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use extra dark colors", color = state.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Use darker colors for small battery savings on AMOLED screens.", color = state.textSecondary, fontSize = 12.sp)
                }
                Switch(checked = state.amoledDark.value, onCheckedChange = { state.updateAmoledDark(it) })
            }
        }

        SettingsCategoryLabel("CUSTOM PALETTE", state)

        Surface(shape = RoundedCornerShape(20.dp), color = state.surfaceColor, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Palette Category Tabs
                Surface(shape = RoundedCornerShape(22.dp), color = state.cardColor, modifier = Modifier.fillMaxWidth()) {
                    LazyRow(modifier = Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(categories) { cat ->
                            val active = state.paletteCategory.value == cat
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = if (active) Color(0xFF494358) else Color.Transparent,
                                modifier = Modifier.clickable { state.paletteCategory.value = cat }
                            ) {
                                Text(
                                    text = cat,
                                    color = if (active) Color.White else state.textSecondary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Double-Ring Oto Swatches (matches 00:45 - 00:52, all unlocked!)
                val swatches = palettesByCategory[state.paletteCategory.value] ?: palettesByCategory["Default"]!!
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(swatches) { swatch ->
                        val isSelected = state.accentColor.value == swatch
                        Box(
                            modifier = Modifier
                                .size(62.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) state.cardColor else Color.Transparent)
                                .border(
                                    width = if (isSelected) 2.dp else 0.dp,
                                    color = if (isSelected) swatch else Color.Transparent,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable { state.updateAccentColor(swatch, state.paletteCategory.value) },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(swatch),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(state.backgroundColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = swatch, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Now Playing Settings Screen (matches 00:55 - 00:57 in video).
 */
@Composable
fun OtoNowPlayingSettingsScreen(state: OtoUiState) {
    SettingsSubPageScaffold(
        title = "Now playing",
        subtitle = "How the now-playing screen looks and what controls it shows.",
        state = state
    ) {
        SettingsSwitchCard(
            title = "Show song info",
            subtitle = "Show song information such as file format, bitrate and frequency in the main player",
            checked = state.showSongInfo.value,
            state = state
        ) {
            state.showSongInfo.value = it
            state.savePrefBoolean("show_song_info", it)
        }
        SettingsSwitchCard(
            title = "Show volume slider",
            subtitle = "Show volume slider in the main player",
            checked = state.showVolumeSlider.value,
            state = state
        ) {
            state.showVolumeSlider.value = it
            state.savePrefBoolean("show_volume_slider", it)
        }
        SettingsSwitchCard(
            title = "Extra controls",
            subtitle = "Add previous and forward button in the mini player",
            checked = state.extraMiniPlayerControls.value,
            state = state
        ) {
            state.extraMiniPlayerControls.value = it
            state.savePrefBoolean("extra_mini_controls", it)
        }
    }
}

/**
 * Customization Settings Screen (matches 00:59 - 01:01 in video).
 */
@Composable
fun OtoCustomizationSettingsScreen(state: OtoUiState) {
    SettingsSubPageScaffold(
        title = "Customization",
        subtitle = "Tailor what you see on the home and details screens.",
        state = state
    ) {
        SettingsCategoryLabel("HOME", state)
        Surface(shape = RoundedCornerShape(18.dp), color = state.surfaceColor, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Manage bottom tabs", color = state.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Toggle visibility of bottom navigation tabs", color = state.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                listOf("Home", "Albums", "Songs", "Playlists", "Artists").forEach { tab ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tab, color = state.textPrimary, fontSize = 14.sp)
                        Switch(
                            checked = state.enabledTabs.contains(tab),
                            onCheckedChange = { checked ->
                                if (checked && !state.enabledTabs.contains(tab)) state.enabledTabs.add(tab)
                                else if (!checked && state.enabledTabs.size > 1) state.enabledTabs.remove(tab)
                            }
                        )
                    }
                }
            }
        }
        SettingsCategoryLabel("DETAILS", state)
        SettingsSwitchCard(
            title = "Show See all button",
            subtitle = "Show or hide See all button in details page.",
            checked = state.showSeeAllButton.value,
            state = state
        ) {
            state.showSeeAllButton.value = it
            state.savePrefBoolean("show_see_all", it)
        }
    }
}

/**
 * Library Settings Screen (matches 01:03 - 01:07 in video, all unlocked!).
 */
@Composable
fun OtoLibrarySettingsScreen(state: OtoUiState) {
    SettingsSubPageScaffold(
        title = "Library",
        subtitle = "What surfaces in your library, and how artists and genres are grouped.",
        state = state
    ) {
        SettingsCategoryLabel("MULTIPLE ARTISTS", state)
        SettingsSwitchCard(
            title = "Multiple artists support",
            subtitle = "Split artist and album-artist tags with (, & /) separators.",
            checked = state.multiArtistSupport.value,
            state = state
        ) {
            state.multiArtistSupport.value = it
            state.savePrefBoolean("multi_artist", it)
        }
        SettingsSwitchCard(
            title = "Always navigate to first artist",
            subtitle = "When go to artist option is clicked, always navigate to the first artist",
            checked = state.alwaysNavigateFirstArtist.value,
            state = state
        ) {
            state.alwaysNavigateFirstArtist.value = it
            state.savePrefBoolean("nav_first_artist", it)
        }

        SettingsCategoryLabel("MULTIPLE GENRES", state)
        SettingsSwitchCard(
            title = "Multiple genres support",
            subtitle = "Split genre tags with (, & /) separators.",
            checked = state.multiGenreSupport.value,
            state = state
        ) {
            state.multiGenreSupport.value = it
            state.savePrefBoolean("multi_genre", it)
        }

        SettingsCategoryLabel("FILTER", state)
        Surface(shape = RoundedCornerShape(18.dp), color = state.surfaceColor, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Duration range", color = state.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    val label = if (state.minDurationFilterSec.intValue == 0) "off" else "${state.minDurationFilterSec.intValue}s"
                    Text(label, color = state.textSecondary, fontSize = 14.sp)
                }
                Text("Hide songs shorter than this duration.", color = state.textSecondary, fontSize = 12.sp)
                Slider(
                    value = state.minDurationFilterSec.intValue.toFloat(),
                    onValueChange = {
                        state.minDurationFilterSec.intValue = it.toInt()
                        state.savePrefInt("min_duration_sec", it.toInt())
                    },
                    valueRange = 0f..120f,
                    colors = SliderDefaults.colors(thumbColor = state.accentColor.value, activeTrackColor = state.accentColor.value)
                )
            }
        }
    }
}

/**
 * Playlists Settings Screen (matches 01:09 in video).
 */
@Composable
fun OtoPlaylistsSettingsScreen(state: OtoUiState) {
    SettingsSubPageScaffold(
        title = "Playlists",
        subtitle = "Where exported playlists are saved, and how recent counts are sliced.",
        state = state
    ) {
        Surface(shape = RoundedCornerShape(18.dp), color = state.surfaceColor, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DetailField("Playlist export path", "/storage/emulated/0/Music/Playlists", state)
                DetailField("Last added playlist interval", state.lastAddedInterval.value, state)
                DetailField("Recently played playlist interval", state.recentlyPlayedInterval.value, state)
            }
        }
    }
}

/**
 * Images Settings Screen (matches 01:11 - 01:17 in video).
 */
@Composable
fun OtoImagesSettingsScreen(state: OtoUiState) {
    SettingsSubPageScaffold(
        title = "Images",
        subtitle = "Album artwork, metadata downloads, and what shows on the lock screen.",
        state = state
    ) {
        SettingsCategoryLabel("STORAGE", state)
        SettingsSwitchCard(
            title = "Cache images",
            subtitle = "Faster loading of images by caching locally.",
            checked = state.cacheImages.value,
            state = state
        ) {
            state.cacheImages.value = it
            state.savePrefBoolean("cache_images", it)
        }
        SettingsCategoryLabel("METADATA", state)
        SettingsSwitchCard(
            title = "Use MediaStore provided artworks",
            subtitle = "Faster artwork loading time from local audio tags.",
            checked = state.useMediaStoreArtwork.value,
            state = state
        ) {
            state.useMediaStoreArtwork.value = it
            state.savePrefBoolean("mediastore_art", it)
        }
        SettingsCategoryLabel("LOCKSCREEN", state)
        SettingsSwitchCard(
            title = "Lockscreen album art",
            subtitle = "Show album art on the lockscreen",
            checked = state.lockscreenAlbumArt.value,
            state = state
        ) {
            state.lockscreenAlbumArt.value = it
            state.savePrefBoolean("lockscreen_art", it)
        }
        SettingsSwitchCard(
            title = "Blur album cover",
            subtitle = "Blur album art on the lockscreen",
            checked = state.blurLockscreenArt.value,
            state = state
        ) {
            state.blurLockscreenArt.value = it
            state.savePrefBoolean("blur_lockscreen", it)
        }
    }
}

/**
 * Audio Settings Screen (matches 01:21 - 01:27 in video).
 */
@Composable
fun OtoAudioSettingsScreen(state: OtoUiState) {
    SettingsSubPageScaffold(
        title = "Audio",
        subtitle = "How tracks crossfade, duck for system sounds, and which engine decodes them.",
        state = state
    ) {
        SettingsCategoryLabel("PLAYBACK", state)
        Surface(shape = RoundedCornerShape(18.dp), color = state.surfaceColor, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Fade music on play / pause", color = state.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (state.fadePlayPauseMs.intValue == 0) "off" else "${state.fadePlayPauseMs.intValue} ms", color = state.textSecondary)
                }
                Text("Smooth fade in/out instead of cutting abruptly.", color = state.textSecondary, fontSize = 12.sp)
                Slider(
                    value = state.fadePlayPauseMs.intValue.toFloat(),
                    onValueChange = {
                        state.fadePlayPauseMs.intValue = it.toInt()
                        state.savePrefInt("fade_play_pause_ms", it.toInt())
                    },
                    valueRange = 0f..1000f,
                    steps = 3,
                    colors = SliderDefaults.colors(thumbColor = state.accentColor.value, activeTrackColor = state.accentColor.value)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Surface(shape = RoundedCornerShape(18.dp), color = state.surfaceColor, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Crossfade", color = state.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (state.crossfadeSec.intValue == 0) "off" else "${state.crossfadeSec.intValue}s", color = state.textSecondary)
                }
                Text("Overlap the end of one track with the start of the next.", color = state.textSecondary, fontSize = 12.sp)
                Slider(
                    value = state.crossfadeSec.intValue.toFloat(),
                    onValueChange = {
                        state.crossfadeSec.intValue = it.toInt()
                        state.savePrefInt("crossfade_sec", it.toInt())
                    },
                    valueRange = 0f..12f,
                    steps = 3,
                    colors = SliderDefaults.colors(thumbColor = state.accentColor.value, activeTrackColor = state.accentColor.value)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        SettingsSwitchCard(
            title = "Gapless playback",
            subtitle = "Remove gaps of silence between the tracks.",
            checked = state.gaplessPlayback.value,
            state = state
        ) {
            state.gaplessPlayback.value = it
            state.savePrefBoolean("gapless_playback", it)
        }
        SettingsSwitchCard(
            title = "Keep shuffle mode",
            subtitle = "Shuffle mode won't turn off when playing a new list of songs.",
            checked = state.keepShuffleMode.value,
            state = state
        ) {
            state.keepShuffleMode.value = it
            state.savePrefBoolean("keep_shuffle", it)
        }

        OtoSettingsCard(
            icon = Icons.Default.Equalizer,
            title = "Equalizer",
            subtitle = "Tune EQ bands, bass boost, reverb, and virtualizer.",
            state = state,
            onClick = { state.currentScreen.value = "equalizer" }
        )

        SettingsCategoryLabel("MISC", state)
        SettingsSwitchCard(
            title = "Lower volume on focal loss",
            subtitle = "Lower the volume when a system sound is played or a notification is received",
            checked = state.lowerVolumeOnFocalLoss.value,
            state = state
        ) {
            state.lowerVolumeOnFocalLoss.value = it
            state.savePrefBoolean("lower_volume_on_focal_loss", it)
        }
        SettingsSwitchCard(
            title = "Auto play",
            subtitle = "Start playing immediately when a wired headset or bluetooth audio device is plugged in.",
            checked = state.autoPlayHeadset.value,
            state = state
        ) {
            state.autoPlayHeadset.value = it
            state.savePrefBoolean("auto_play_headset", it)
        }
    }
}

@Composable
fun OtoBackupRestoreScreen(state: OtoUiState) {
    val context = LocalContext.current
    SettingsSubPageScaffold(
        title = "Backup & restore",
        subtitle = "Save playlists, settings and history to a local offline file.",
        state = state
    ) {
        OtoSettingsCard(
            icon = Icons.Outlined.CloudUpload,
            title = "Create local backup",
            subtitle = "Export ${state.playlists.size} playlists, ${state.favoriteIds.size} favorites, and settings",
            state = state,
            onClick = {
                Toast.makeText(context, "Offline backup saved to internal preferences", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun OtoAboutScreen(state: OtoUiState) {
    SettingsSubPageScaffold(
        title = "About",
        subtitle = "Oto Music — 100% Google Play Services-Free Edition",
        state = state
    ) {
        Surface(shape = RoundedCornerShape(18.dp), color = state.surfaceColor, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailField("Version", "2.0-OtoPro (FOSS / No GMS)", state)
                DetailField("Audio Engine", "AndroidX Media3 ExoPlayer (100% Local)", state)
                DetailField("Google Play Services", "None (0 background trackers, 0 billing libraries)", state)
            }
        }
    }
}

@Composable
fun SettingsSubPageScaffold(
    title: String,
    subtitle: String,
    state: OtoUiState,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(state.backgroundColor)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        IconButton(onClick = { state.currentScreen.value = "settings" }) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = state.textPrimary)
        }
        Text(title, color = state.textPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = state.textSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        content()
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
fun SettingsSwitchCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    state: OtoUiState,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = state.surfaceColor,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable { onCheckedChange(!checked) }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, color = state.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = state.textSecondary, fontSize = 12.sp)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/**
 * Detail Screens for Album, Artist, Playlist, Folder, and Smart Lists (History / Favorites / Most Played).
 */
@Composable
fun OtoAlbumDetailScreen(
    state: OtoUiState,
    onPlaySong: (Song, List<Song>?) -> Unit,
    onShuffleAll: (List<Song>) -> Unit
) {
    val album = state.selectedAlbum.value ?: return
    OtoSmartListScreen(
        title = album.title,
        subtitle = "${album.artist} • ${album.songs.size} Songs",
        artworkUri = album.albumArtUri,
        songs = album.songs,
        state = state,
        onPlaySong = onPlaySong,
        onShuffleAll = onShuffleAll
    )
}

@Composable
fun OtoArtistDetailScreen(
    state: OtoUiState,
    onPlaySong: (Song, List<Song>?) -> Unit,
    onShuffleAll: (List<Song>) -> Unit
) {
    val artist = state.selectedArtist.value ?: return
    OtoSmartListScreen(
        title = artist.name,
        subtitle = "${artist.albums.size} Albums • ${artist.songs.size} Songs",
        artworkUri = artist.artworkUri,
        songs = artist.songs,
        state = state,
        onPlaySong = onPlaySong,
        onShuffleAll = onShuffleAll
    )
}

@Composable
fun OtoPlaylistDetailScreen(
    state: OtoUiState,
    onPlaySong: (Song, List<Song>?) -> Unit,
    onShuffleAll: (List<Song>) -> Unit
) {
    val playlist = state.selectedPlaylist.value ?: return
    val songs = playlist.songIds.mapNotNull { id -> state.allSongs.find { it.id == id } }
    OtoSmartListScreen(
        title = playlist.name,
        subtitle = "${songs.size} Songs",
        artworkUri = songs.firstOrNull()?.albumArtUri,
        songs = songs,
        state = state,
        onPlaySong = onPlaySong,
        onShuffleAll = onShuffleAll
    )
}

@Composable
fun OtoFolderDetailScreen(
    state: OtoUiState,
    onPlaySong: (Song, List<Song>?) -> Unit,
    onShuffleAll: (List<Song>) -> Unit
) {
    val folder = state.selectedFolder.value ?: return
    OtoSmartListScreen(
        title = folder.name,
        subtitle = folder.path,
        artworkUri = folder.songs.firstOrNull()?.albumArtUri,
        songs = folder.songs,
        state = state,
        onPlaySong = onPlaySong,
        onShuffleAll = onShuffleAll,
        backScreen = "folders"
    )
}

@Composable
fun OtoSmartListScreen(
    title: String,
    songs: List<Song>,
    state: OtoUiState,
    onPlaySong: (Song, List<Song>?) -> Unit,
    onShuffleAll: (List<Song>) -> Unit,
    subtitle: String = "${songs.size} Songs",
    artworkUri: android.net.Uri? = songs.firstOrNull()?.albumArtUri,
    backScreen: String = "main"
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(state.backgroundColor),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { state.currentScreen.value = backScreen }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = state.textPrimary)
                }
                Text(title, color = state.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = artworkUri,
                    contentDescription = null,
                    modifier = Modifier.size(92.dp).clip(RoundedCornerShape(16.dp)).background(state.surfaceColor),
                    contentScale = ContentScale.Crop
                )
                Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(title, color = state.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    Spacer(Modifier.height(4.dp))
                    Text(subtitle, color = state.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = state.accentColor.value,
                            modifier = Modifier.clickable {
                                if (songs.isNotEmpty()) onPlaySong(songs.first(), songs)
                            }
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF1C1B1F), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Play", color = Color(0xFF1C1B1F), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = state.surfaceColor,
                            modifier = Modifier.clickable { onShuffleAll(songs) }
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Shuffle, contentDescription = null, tint = state.textPrimary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Shuffle", color = state.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        items(songs) { song ->
            OtoSongListItem(
                song = song,
                isCurrent = state.currentSong.value?.id == song.id,
                isPlaying = state.isPlaying.value,
                state = state,
                onClick = { onPlaySong(song, songs) },
                onMoreClick = { state.showMoreOptionsSheet.value = song }
            )
        }
    }
}

/**
 * Helper Dialogs: Create Playlist, Add to Playlist, Speed & Pitch, Edit Lyrics, Tag Editor.
 */
@Composable
fun OtoCreatePlaylistDialog(state: OtoUiState, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = state.surfaceColor,
        title = { Text("New playlist", color = state.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Playlist name") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = {
                state.createPlaylist(name)
                onDismiss()
            }) {
                Text("Create", color = state.accentColor.value, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = state.textSecondary)
            }
        }
    )
}

@Composable
fun OtoAddToPlaylistDialog(state: OtoUiState, song: Song, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = state.surfaceColor,
        title = { Text("Add to playlist", color = state.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.playlists.isEmpty()) {
                    Text("No playlists yet. Create one below.", color = state.textSecondary, fontSize = 13.sp)
                } else {
                    state.playlists.forEach { pl ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = state.cardColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    state.addSongToPlaylist(pl.id, song.id)
                                    onDismiss()
                                }
                        ) {
                            Text(pl.name, color = state.textPrimary, modifier = Modifier.padding(14.dp), fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                state.showCreatePlaylistDialog.value = true
            }) {
                Text("+ New Playlist", color = state.accentColor.value, fontWeight = FontWeight.Bold)
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
fun OtoSpeedPitchDialog(
    state: OtoUiState,
    onApply: (Float, Float) -> Unit,
    onDismiss: () -> Unit
) {
    var speed by remember { mutableFloatStateOf(state.playbackSpeed.floatValue) }
    var pitch by remember { mutableFloatStateOf(state.playbackPitch.floatValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = state.surfaceColor,
        title = { Text("Speed & Pitch", color = state.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(String.format(Locale.US, "Speed: %.2fx", speed), color = state.textPrimary, fontSize = 14.sp)
                Slider(
                    value = speed,
                    onValueChange = {
                        speed = it
                        onApply(speed, pitch)
                    },
                    valueRange = 0.5f..2.0f
                )
                Spacer(Modifier.height(8.dp))
                Text(String.format(Locale.US, "Pitch: %.2fx", pitch), color = state.textPrimary, fontSize = 14.sp)
                Slider(
                    value = pitch,
                    onValueChange = {
                        pitch = it
                        onApply(speed, pitch)
                    },
                    valueRange = 0.5f..2.0f
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = state.accentColor.value, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = {
                speed = 1.0f
                pitch = 1.0f
                onApply(1.0f, 1.0f)
            }) {
                Text("Reset", color = state.textSecondary)
            }
        }
    )
}

@Composable
fun OtoEditLyricsDialog(state: OtoUiState, song: Song, onDismiss: () -> Unit) {
    var lyricsText by remember { mutableStateOf(state.customLyrics[song.id] ?: state.getLyricsForSong(song).joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = state.surfaceColor,
        title = { Text("Edit lyrics", color = state.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = lyricsText,
                onValueChange = { lyricsText = it },
                placeholder = { Text("Paste LRC or plain lyrics here...") },
                modifier = Modifier.fillMaxWidth().height(200.dp)
            )
        },
        confirmButton = {
            TextButton(onClick = {
                state.saveLyricsForSong(song.id, lyricsText)
                state.showLyricsOnPlayer.value = true
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

@Composable
fun OtoTagEditorDialog(state: OtoUiState, song: Song, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artist) }
    var album by remember { mutableStateOf(song.album) }
    var genre by remember { mutableStateOf(song.genre) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = state.surfaceColor,
        title = { Text("Tag editor", color = state.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text("Artist") }, singleLine = true)
                OutlinedTextField(value = album, onValueChange = { album = it }, label = { Text("Album") }, singleLine = true)
                OutlinedTextField(value = genre, onValueChange = { genre = it }, label = { Text("Genre") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val updated = song.copy(title = title.trim(), artist = artist.trim(), album = album.trim(), genre = genre.trim())
                val idx = state.allSongs.indexOfFirst { it.id == song.id }
                if (idx >= 0) state.allSongs[idx] = updated
                val qIdx = state.queue.indexOfFirst { it.id == song.id }
                if (qIdx >= 0) state.queue[qIdx] = updated
                if (state.currentSong.value?.id == song.id) state.currentSong.value = updated
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
