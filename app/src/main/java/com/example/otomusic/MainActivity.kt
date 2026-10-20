package com.example.otomusic

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
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
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val albumId: Long,
    val path: String,
    val size: Long,
    val dateAdded: Long,
    val uri: Uri
) {
    val albumArtUri: Uri
        get() = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)

    val folderName: String get() = File(path).parentFile?.name ?: "Music"
    val extension: String get() = path.substringAfterLast('.', "MP3").uppercase()
    val bitrateKbps: Int get() = if (duration > 0) ((size * 8) / duration).toInt().coerceIn(64, 1411) else 320
    val formattedDuration: String get() = formatMillis(duration)
    val formattedSize: String get() = String.format(Locale.US, "%.1f MB", size / (1024f * 1024f))
    val formattedDate: String get() = SimpleDateFormat("MMM dd, yyyy, h:mm a", Locale.US).format(Date(dateAdded * 1000L))
}

fun formatMillis(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L)) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

enum class BottomTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Home),
    ALBUMS("Albums", Icons.Outlined.Album),
    SONGS("Songs", Icons.Outlined.MusicNote),
    PLAYLISTS("Playlists", Icons.Outlined.QueueMusic),
    ARTISTS("Artists", Icons.Outlined.Person)
}

enum class OverlayScreen { NONE, FOLDERS, LISTENING_STATS, SETTINGS, THEMES, NOW_PLAYING_SETTINGS, AUDIO_SETTINGS }

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

            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = bgColor, surface = surfaceColor, primary = accentColor, secondaryContainer = accentColor.copy(alpha = 0.25f)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    OtoCompleteApp(controller, { fetchSongs() }, accentColor, { accentColor = it }, amoledDark, { amoledDark = it })
                }
            }
        }
    }

    private fun fetchSongs(): List<Song> {
        val list = mutableListOf<Song>()
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED)
        val cursor = contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")
        cursor?.use {
            while (it.moveToNext()) {
                val duration = it.getLong(4)
                if (duration > 1000) {
                    list.add(Song(
                        it.getLong(0), it.getString(1) ?: "Unknown", it.getString(2)?.takeIf { a -> a != "<unknown>" } ?: "Unknown Artist",
                        it.getString(3) ?: "Unknown Album", duration, it.getLong(5), it.getString(6) ?: "", it.getLong(7), it.getLong(8),
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.getLong(0))
                    ))
                }
            }
        }
        return list
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtoCompleteApp(
    controller: MediaController?, loadSongs: () -> List<Song>, accentColor: Color, onAccentChange: (Color) -> Unit, amoledDark: Boolean, onAmoledChange: (Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var allSongs by remember { mutableStateOf(emptyList<Song>()) }
    var currentTab by remember { mutableStateOf(BottomTab.HOME) }
    var activeOverlay by remember { mutableStateOf(OverlayScreen.NONE) }
    var currentSong by remember { mutableStateOf<Song?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var showNowPlayingFull by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) allSongs = loadSongs()
    }

    LaunchedEffect(Unit) {
        permLauncher.launch(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    LaunchedEffect(controller, isPlaying) {
        while (isPlaying) {
            currentPosition = controller?.currentPosition ?: 0L
            delay(500L)
        }
    }

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

    fun playSongQueue(song: Song) {
        currentSong = song
        controller?.run {
            setMediaItems(allSongs.map { MediaItem.fromUri(it.uri) }, allSongs.indexOfFirst { it.id == song.id }.coerceAtLeast(0), 0L)
            prepare()
            play()
        }
    }

    BackHandler(enabled = showNowPlayingFull || activeOverlay != OverlayScreen.NONE) {
        if (showNowPlayingFull) showNowPlayingFull = false else activeOverlay = OverlayScreen.NONE
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.background, modifier = Modifier.width(300.dp)) {
                Spacer(Modifier.height(24.dp))
                NavigationDrawerItem(label = { Text("Library") }, selected = activeOverlay == OverlayScreen.NONE, icon = { Icon(Icons.Outlined.LibraryMusic, null) }, onClick = { activeOverlay = OverlayScreen.NONE; scope.launch { drawerState.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text("Settings") }, selected = activeOverlay == OverlayScreen.SETTINGS, icon = { Icon(Icons.Outlined.Settings, null) }, onClick = { activeOverlay = OverlayScreen.SETTINGS; scope.launch { drawerState.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    ) {
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
                                    IconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White)
                                    }
                                }
                                LinearProgressIndicator(progress = { if (song.duration > 0) (currentPosition.toFloat() / song.duration).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = accentColor, trackColor = Color.DarkGray)
                            }
                        }
                    }
                    if (activeOverlay == OverlayScreen.NONE) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                            BottomTab.entries.forEach { tab ->
                                NavigationBarItem(selected = currentTab == tab, onClick = { currentTab = tab }, icon = { Icon(tab.icon, contentDescription = tab.label) }, label = { Text(tab.label, fontSize = 11.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = Color.White, indicatorColor = MaterialTheme.colorScheme.secondaryContainer, unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray))
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                when (activeOverlay) {
                    OverlayScreen.SETTINGS -> SettingsMainScreen(onBack = { activeOverlay = OverlayScreen.NONE }, onOpenThemes = { activeOverlay = OverlayScreen.THEMES })
                    OverlayScreen.THEMES -> ThemesAndColorsScreen(accentColor, onAccentChange, amoledDark, onAmoledChange, onBack = { activeOverlay = OverlayScreen.SETTINGS })
                    OverlayScreen.NONE -> {
                        Column {
                            Surface(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, null, tint = Color.LightGray) }
                                    Text("Search your music", color = Color.Gray, modifier = Modifier.weight(1f))
                                }
                            }
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(allSongs) { song ->
                                    val isSelected = currentSong?.id == song.id
                                    Row(
                                        modifier = Modifier.fillMaxWidth().background(if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent).clickable { playSongQueue(song) }.padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        AsyncImage(model = song.albumArtUri, contentDescription = null, modifier = Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2B2836)), contentScale = ContentScale.Crop)
                                        Spacer(Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(song.title, color = if (isSelected) accentColor else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${song.artist} • ${song.formattedDuration}", color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

        AnimatedVisibility(visible = showNowPlayingFull && currentSong != null, enter = slideInVertically(initialOffsetY = { it }), exit = slideOutVertically(targetOffsetY = { it })) {
            currentSong?.let { song ->
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Row(modifier = Modifier.fillMaxSize().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(12.dp), contentAlignment = Alignment.Center) {
                            AsyncImage(model = song.albumArtUri, contentDescription = null, modifier = Modifier.aspectRatio(1f).fillMaxHeight(0.85f).clip(RoundedCornerShape(20.dp)).background(Color(0xFF25232E)), contentScale = ContentScale.Crop)
                        }
                        Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Slider(value = currentPosition.toFloat(), onValueChange = { controller?.seekTo(it.toLong()); currentPosition = it.toLong() }, valueRange = 0f..song.duration.coerceAtLeast(1L).toFloat(), colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatMillis(currentPosition), color = Color.LightGray, fontSize = 12.sp)
                                Text(song.formattedDuration, color = Color.LightGray, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(20.dp))
                            Text(song.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, color = Color.Gray, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(28.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { controller?.seekToPrevious() }) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
                                Surface(shape = CircleShape, color = accentColor, modifier = Modifier.size(64.dp).clickable { controller?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                                    Box(contentAlignment = Alignment.Center) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color(0xFF1B1429), modifier = Modifier.size(32.dp)) }
                                }
                                IconButton(onClick = { controller?.seekToNext() }) { Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
                            }
                            Spacer(Modifier.height(24.dp))
                            Text("${song.extension} • ${song.bitrateKbps} kb/s • 44.1 kHz", color = Color.LightGray, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsMainScreen(onBack: () -> Unit, onOpenThemes: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
        Text("Settings", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF282433), modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().clickable { onOpenThemes() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Palette, null, tint = Color.LightGray)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Themes & Colors", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("Change theme, accent color and highlight color", color = Color.Gray, fontSize = 12.sp)
                }
                Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
            }
        }
    }
}

@Composable
fun ThemesAndColorsScreen(accentColor: Color, onSelectAccent: (Color) -> Unit, amoledDark: Boolean, onToggleAmoled: (Boolean) -> Unit, onBack: () -> Unit) {
    val palette = listOf(Color(0xFFD0BCFF), Color(0xFFFFB4AB), Color(0xFFFFD8E4), Color(0xFFA8C7FA), Color(0xFF80CBC4), Color(0xFFA5D6A7), Color(0xFFFFE082), Color(0xFFFFAB91))
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
        Text("Themes & Colors", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use extra dark colors", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("Use darker colors for AMOLED screens.", color = Color.Gray, fontSize = 12.sp)
                }
                Switch(checked = amoledDark, onCheckedChange = onToggleAmoled)
            }
        }
        Spacer(Modifier.height(20.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(palette) { color ->
                    Box(modifier = Modifier.size(52.dp).clip(CircleShape).background(color).border(if (accentColor == color) 3.dp else 0.dp, Color.White, CircleShape).clickable { onSelectAccent(color) })
                }
            }
        }
    }
}
