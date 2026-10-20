package com.example.otomusic

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.media.AudioManager
import android.media.MediaExtractor
import android.media.MediaFormat
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
import java.util.concurrent.TimeUnit

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
        get() = ContentUris.withAppendedId(
            Uri.parse("content://media/external/audio/albumart"),
            albumId
        )

    val folderName: String
        get() = File(path).parentFile?.name ?: "Music"

    val folderPath: String
        get() = File(path).parent ?: "/storage/emulated/0/Music"

    val extension: String
        get() = path.substringAfterLast('.', "MP3").uppercase()

    val bitrateKbps: Int
        get() = if (duration > 0) ((size * 8) / duration).toInt().coerceIn(64, 1411) else 320

    val formattedDuration: String
        get() = formatMillis(duration)

    val formattedSize: String
        get() = String.format(Locale.US, "%.1f MB", size / (1024f * 1024f))

    val formattedDate: String
        get() = SimpleDateFormat("MMM dd, yyyy, h:mm a", Locale.US).format(Date(dateAdded * 1000L))
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

enum class OverlayScreen {
    NONE, FOLDERS, LISTENING_STATS, SETTINGS, THEMES, NOW_PLAYING_SETTINGS, AUDIO_SETTINGS
}

@UnstableApi
class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            controller = controllerFuture.get()
        }, MoreExecutors.directExecutor())

        setContent {
            var accentColor by remember { mutableStateOf(Color(0xFFD0BCFF)) }
            var amoledDark by remember { mutableStateOf(false) }

            val bgColor = if (amoledDark) Color(0xFF000000) else Color(0xFF131118)
            val surfaceColor = if (amoledDark) Color(0xFF121016) else Color(0xFF211E29)

            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = bgColor,
                    surface = surfaceColor,
                    primary = accentColor,
                    secondaryContainer = accentColor.copy(alpha = 0.25f)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    OtoCompleteApp(
                        controller = controller,
                        loadSongs = { fetchSongs() },
                        accentColor = accentColor,
                        onAccentChange = { accentColor = it },
                        amoledDark = amoledDark,
                        onAmoledChange = { amoledDark = it }
                    )
                }
            }
        }
    }

    private fun fetchSongs(): List<Song> {
        val list = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val cursor = contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )
        cursor?.use {
            val idC = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleC = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistC = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumC = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durC = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albIdC = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val dataC = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val sizeC = it.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dateC = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

            while (it.moveToNext()) {
                val id = it.getLong(idC)
                val title = it.getString(titleC) ?: "Unknown"
                val artist = it.getString(artistC)?.takeIf { a -> a != "<unknown>" } ?: "Unknown Artist"
                val album = it.getString(albumC) ?: "Unknown Album"
                val duration = it.getLong(durC)
                val albumId = it.getLong(albIdC)
                val path = it.getString(dataC) ?: ""
                val size = it.getLong(sizeC)
                val dateAdded = it.getLong(dateC)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                if (duration > 1000) {
                    list.add(Song(id, title, artist, album, duration, albumId, path, size, dateAdded, uri))
                }
            }
        }
        return list
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtoCompleteApp(
    controller: MediaController?,
    loadSongs: () -> List<Song>,
    accentColor: Color,
    onAccentChange: (Color) -> Unit,
    amoledDark: Boolean,
    onAmoledChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    var allSongs by remember { mutableStateOf(emptyList<Song>()) }
    var favoriteIds by remember { mutableStateOf(setOf<Long>()) }
    var playCounts by remember { mutableStateOf(mapOf<Long, Int>()) }
    var historyIds by remember { mutableStateOf(listOf<Long>()) }
    var playlists by remember { mutableStateOf(mapOf<String, List<Long>>()) }

    var currentTab by remember { mutableStateOf(BottomTab.HOME) }
    var activeOverlay by remember { mutableStateOf(OverlayScreen.NONE) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    var currentSong by remember { mutableStateOf<Song?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var shuffleEnabled by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }

    var showNowPlayingFull by remember { mutableStateOf(false) }
    var showLyricsInPlayer by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showMoreOptionsSheet by remember { mutableStateOf<Song?>(null) }
    var showFileDetailsDialog by remember { mutableStateOf<Song?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    var showSongInfoBadge by remember { mutableStateOf(true) }
    var showVolumeSlider by remember { mutableStateOf(true) }
    var extraMiniControls by remember { mutableStateOf(false) }
    var sleepTimerMinutesLeft by remember { mutableIntStateOf(0) }

    // Custom Lyrics Map
    var customLyrics by remember { mutableStateOf(mapOf<Long, List<String>>()) }
    var showLyricsEditorDialog by remember { mutableStateOf<Song?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            allSongs = loadSongs()
            if (allSongs.isNotEmpty() && currentSong == null) {
                currentSong = allSongs.first()
            }
        }
    }

    LaunchedEffect(Unit) {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        permissionLauncher.launch(perm)
    }

    // Sync Playback Progress
    LaunchedEffect(controller, isPlaying) {
        while (isPlaying) {
            currentPosition = controller?.currentPosition ?: 0L
            delay(500L)
        }
    }

    // Sleep Timer Countdown
    LaunchedEffect(sleepTimerMinutesLeft) {
        if (sleepTimerMinutesLeft > 0) {
            delay(60_000L)
            sleepTimerMinutesLeft -= 1
            if (sleepTimerMinutesLeft == 0) {
                controller?.pause()
            }
        }
    }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val idx = controller?.currentMediaItemIndex ?: return
                if (idx in allSongs.indices) {
                    currentSong = allSongs[idx]
                }
            }
        }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }

    fun playSongQueue(song: Song, queue: List<Song> = allSongs) {
        currentSong = song
        playCounts = playCounts + (song.id to ((playCounts[song.id] ?: 0) + 1))
        historyIds = (listOf(song.id) + historyIds).distinct().take(50)
        controller?.run {
            val items = queue.map { MediaItem.fromUri(it.uri) }
            val startIndex = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
            setMediaItems(items, startIndex, 0L)
            prepare()
            play()
        }
    }

    BackHandler(enabled = showNowPlayingFull || activeOverlay != OverlayScreen.NONE || isSearching) {
        when {
            showNowPlayingFull -> showNowPlayingFull = false
            activeOverlay != OverlayScreen.NONE -> {
                activeOverlay = if (activeOverlay in listOf(OverlayScreen.THEMES, OverlayScreen.NOW_PLAYING_SETTINGS, OverlayScreen.AUDIO_SETTINGS))
                    OverlayScreen.SETTINGS else OverlayScreen.NONE
            }
            isSearching -> {
                isSearching = false
                searchQuery = ""
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.width(300.dp)
            ) {
                // Header with Current Song Banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    currentSong?.let { song ->
                        AsyncImage(
                            model = song.albumArtUri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            alpha = 0.35f
                        )
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = song.albumArtUri,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.DarkGray),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(song.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(song.artist, color = Color.LightGray, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                NavigationDrawerItem(
                    label = { Text("Library") },
                    badge = { Text("${allSongs.size}") },
                    selected = activeOverlay == OverlayScreen.NONE,
                    icon = { Icon(Icons.Outlined.LibraryMusic, null) },
                    onClick = {
                        activeOverlay = OverlayScreen.NONE
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Folders") },
                    badge = { Text("${allSongs.map { it.folderName }.distinct().size}") },
                    selected = activeOverlay == OverlayScreen.FOLDERS,
                    icon = { Icon(Icons.Outlined.Folder, null) },
                    onClick = {
                        activeOverlay = OverlayScreen.FOLDERS
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Playing queue") },
                    badge = { Text("${allSongs.size}") },
                    selected = false,
                    icon = { Icon(Icons.Outlined.QueueMusic, null) },
                    onClick = {
                        scope.launch { drawerState.close() }
                        showQueueSheet = true
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Equalizer") },
                    selected = activeOverlay == OverlayScreen.AUDIO_SETTINGS,
                    icon = { Icon(Icons.Outlined.GraphicEq, null) },
                    onClick = {
                        activeOverlay = OverlayScreen.AUDIO_SETTINGS
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Listening stats") },
                    selected = activeOverlay == OverlayScreen.LISTENING_STATS,
                    icon = { Icon(Icons.Outlined.Insights, null) },
                    onClick = {
                        activeOverlay = OverlayScreen.LISTENING_STATS
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Scan media") },
                    selected = false,
                    icon = { Icon(Icons.Outlined.Radar, null) },
                    onClick = {
                        allSongs = loadSongs()
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    selected = activeOverlay == OverlayScreen.SETTINGS,
                    icon = { Icon(Icons.Outlined.Settings, null) },
                    onClick = {
                        activeOverlay = OverlayScreen.SETTINGS
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                bottomBar = {
                    Column {
                        // Floating Mini Player Bar
                        currentSong?.let { song ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                     
