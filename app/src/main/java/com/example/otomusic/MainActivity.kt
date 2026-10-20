package com.example.otomusic

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class Song(
    val id: Long, val title: String, val artist: String, val album: String, val duration: Long, val albumId: Long, val path: String, val size: Long, val dateAdded: Long, val uri: Uri
) {
    val albumArtUri: Uri get() = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
    val extension: String get() = path.substringAfterLast('.', "MP3").uppercase()
    val bitrateKbps: Int get() = if (duration > 0) ((size * 8) / duration).toInt().coerceIn(64, 1411) else 320
    val formattedDuration: String get() = formatMillis(duration)
}

fun formatMillis(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L)) / 1000
    return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}

enum class BottomTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Home), ALBUMS("Albums", Icons.Outlined.Album), SONGS("Songs", Icons.Outlined.MusicNote), PLAYLISTS("Playlists", Icons.Outlined.QueueMusic), ARTISTS("Artists", Icons.Outlined.Person)
}
enum class OverlayScreen { NONE, SETTINGS, THEMES }

@UnstableApi
class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controller = controllerFuture.get() }, MoreExecutors.directExecutor())

        setContent {
            var accentColor by remember { mutableStateOf(Color(0xFFD0BCFF)) }
            var amoledDark by remember { mutableStateOf(false) }
            val bgColor = if (amoledDark) Color(0xFF000000) else Color(0xFF131118)
            val surfaceColor = if (amoledDark) Color(0xFF121016) else Color(0xFF211E29)

            MaterialTheme(colorScheme = darkColorScheme(background = bgColor, surface = surfaceColor, primary = accentColor)) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    OtoCompleteApp(controller, { fetchSongs() }, accentColor, { accentColor = it }, amoledDark, { amoledDark = it })
                }
            }
        }
    }

    private fun fetchSongs(): List<Song> {
        val list = mutableListOf<Song>()
        val cursor = contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED), "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")
        cursor?.use {
            while (it.moveToNext()) {
                val duration = it.getLong(4)
                if (duration > 1000) list.add(Song(it.getLong(0), it.getString(1) ?: "Unknown", it.getString(2) ?: "Unknown Artist", it.getString(3) ?: "Unknown Album", duration, it.getLong(5), it.getString(6) ?: "", it.getLong(7), it.getLong(8), ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.getLong(0))))
            }
        }
        return list
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtoCompleteApp(controller: MediaController?, loadSongs: () -> List<Song>, accentColor: Color, onAccentChange: (Color) -> Unit, amoledDark: Boolean, onAmoledChange: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var allSongs by remember { mutableStateOf(emptyList<Song>()) }
    var currentTab by remember { mutableStateOf(BottomTab.SONGS) }
    var activeOverlay by remember { mutableStateOf(OverlayScreen.NONE) }
    var currentSong by remember { mutableStateOf<Song?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var showNowPlayingFull by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) allSongs = loadSongs() }
    LaunchedEffect(Unit) { permLauncher.launch(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE) }
    LaunchedEffect(controller, isPlaying) { while (isPlaying) { currentPosition = controller?.currentPosition ?: 0L; delay(500L) } }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                val idx = controller?.currentMediaItemIndex ?: return
                if (idx in allSongs.indices) currentSong = allSongs[idx]
            }
        }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }

    fun playSong(song: Song) {
        currentSong = song
        controller?.run {
            setMediaItems(allSongs.map { s -> MediaItem.Builder().setUri(s.uri).setMediaId(s.id.toString()).setMediaMetadata(MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album).setArtworkUri(s.albumArtUri).build()).build() }, allSongs.indexOfFirst { it.id == song.id }.coerceAtLeast(0), 0L)
            prepare(); play()
        }
    }

    BackHandler(enabled = showNowPlayingFull || activeOverlay != OverlayScreen.NONE) {
        if (showNowPlayingFull) showNowPlayingFull = false else activeOverlay = OverlayScreen.NONE
    }

    Scaffold(
        bottomBar = {
            Column {
                currentSong?.let { song ->
                    Surface(modifier = Modifier.fillMaxWidth().clickable { showNowPlayingFull = true }, color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
                        Column {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(model = song.albumArtUri, contentDescription = null, modifier = Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray), contentScale = ContentScale.Crop)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(song.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(song.artist, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White) }
                            }
                            LinearProgressIndicator(progress = { if (song.duration > 0) (currentPosition.toFloat() / song.duration).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = accentColor, trackColor = Color.DarkGray)
                        }
                    }
                }
                if (activeOverlay == OverlayScreen.NONE) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                        BottomTab.entries.forEach { tab -> NavigationBarItem(selected = currentTab == tab, onClick = { currentTab = tab }, icon = { Icon(tab.icon, null) }, label = { Text(tab.label, fontSize = 11.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = Color.White, unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray, indicatorColor = Color(0xFF282433))) }
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (activeOverlay) {
                OverlayScreen.SETTINGS -> SettingsScreen(onBack = { activeOverlay = OverlayScreen.NONE }, onOpenThemes = { activeOverlay = OverlayScreen.THEMES })
                OverlayScreen.THEMES -> ThemesScreen(accentColor, onAccentChange, amoledDark, onAmoledChange, onBack = { activeOverlay = OverlayScreen.SETTINGS })
                OverlayScreen.NONE -> {
                    Column {
                        // FUNCTIONAL SEARCH BAR
                        Surface(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { activeOverlay = OverlayScreen.SETTINGS }) { Icon(Icons.Default.Menu, null, tint = Color.LightGray) }
                                BasicTextField(value = searchQuery, onValueChange = { searchQuery = it }, textStyle = TextStyle(color = Color.White, fontSize = 16.sp), cursorBrush = SolidColor(accentColor), modifier = Modifier.weight(1f).padding(horizontal = 8.dp), decorationBox = { innerTextField -> if (searchQuery.isEmpty()) Text("Search your music", color = Color.Gray) else innerTextField() })
                                if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null, tint = Color.Gray) }
                            }
                        }

                        // BOTTOM TAB NAVIGATION LOGIC
                        val filteredSongs = allSongs.filter { it.title.contains(searchQuery, true) || it.artist.contains(searchQuery, true) }
                        when (currentTab) {
                            BottomTab.SONGS -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(filteredSongs) { song ->
                                    val isSelected = currentSong?.id == song.id
                                    Row(modifier = Modifier.fillMaxWidth().background(if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent).clickable { playSong(song) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        AsyncImage(model = song.albumArtUri, contentDescription = null, modifier = Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2B2836)), contentScale = ContentScale.Crop)
                                        Spacer(Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(song.title, color = if (isSelected) accentColor else Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${song.artist} •${song.formattedDuration}", color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                            BottomTab.ALBUMS -> {
                                val albums = filteredSongs.distinctBy { it.album }
                                LazyVerticalGrid(columns = GridCells.Fixed(2), contentPadding = PaddingValues(8.dp)) {
                                    items(albums) { album ->
                                        Column(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
                                            AsyncImage(model = album.albumArtUri, contentDescription = null, modifier = Modifier.aspectRatio(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF2B2836)), contentScale = ContentScale.Crop)
                                            Text(album.album, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                            Text(album.artist, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                            BottomTab.ARTISTS -> {
                                val artists = filteredSongs.distinctBy { it.artist }
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(artists) { artist ->
                                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Box(modifier = Modifier.size(50.dp).clip(CircleShape).background(Color(0xFF2B2836)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, null, tint = Color.Gray) }
                                            Text(artist.artist, color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(start = 16.dp))
                                        }
                                    }
                                }
                            }
                            else -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("${currentTab.label} under construction", color = Color.Gray) }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = showNowPlayingFull && currentSong != null, enter = slideInVertically(initialOffsetY = { it }), exit = slideOutVertically(targetOffsetY = { it })) {
            currentSong?.let { song ->
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { showNowPlayingFull = false }) { Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
                            Text("Now Playing", color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            IconButton(onClick = { /* Todo: Open Lyrics Overlay */ }) { Icon(Icons.Outlined.Lyrics, "Lyrics", tint = Color.White) }
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
                            AsyncImage(model = song.albumArtUri, contentDescription = null, modifier = Modifier.aspectRatio(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF25232E)), contentScale = ContentScale.Crop)
                        }
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp)) {
                            Text(song.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, color = accentColor, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(24.dp))
                            Slider(value = currentPosition.toFloat(), onValueChange = { controller?.seekTo(it.toLong()); currentPosition = it.toLong() }, valueRange = 0f..song.duration.coerceAtLeast(1L).toFloat(), colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatMillis(currentPosition), color = Color.LightGray, fontSize = 12.sp)
                                Text(song.formattedDuration, color = Color.LightGray, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(16.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { controller?.seekToPrevious() }) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(40.dp)) }
                                Surface(shape = CircleShape, color = accentColor, modifier = Modifier.size(72.dp).clickable { controller?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                                    Box(content
