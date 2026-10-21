package com.example.otomusic

import android.Manifest
import android.content.ContentUris
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val contentUri: Uri,
    val albumArtUri: Uri?,
    val path: String = "",
    val folderName: String = "Music",
    val folderPath: String = "/storage/emulated/0/Music",
    val sizeBytes: Long = 0L,
    val dateAddedSec: Long = 0L,
    val dateModifiedSec: Long = 0L,
    val mimeType: String = "audio/mpeg",
    val bitrateKbps: Int = 320,
    val sampleRateHz: Int = 44100,
    val trackNumber: Int = 1,
    val year: Int = 2026,
    val genre: String = "Music"
) {
    val formatLabel: String
        get() {
            val ext = path.substringAfterLast('.', "").uppercase(Locale.ROOT)
            return when {
                ext.isNotEmpty() && ext.length <= 5 -> ext
                mimeType.contains("opus", true) || mimeType.contains("ogg", true) -> "OPUS"
                mimeType.contains("flac", true) -> "FLAC"
                mimeType.contains("mp4", true) || mimeType.contains("m4a", true) -> "M4A"
                mimeType.contains("wav", true) -> "WAV"
                else -> "MP3"
            }
        }

    val audioSpecLine: String
        get() {
            val khz = String.format(Locale.US, "%.1f kHz", sampleRateHz / 1000f)
            return "$formatLabel  •  $bitrateKbps kb/s  •  $khz"
        }
}

data class AlbumItem(
    val id: Long,
    val title: String,
    val artist: String,
    val albumArtUri: Uri?,
    val songs: List<Song>,
    val year: Int
)

data class ArtistItem(
    val name: String,
    val songs: List<Song>,
    val albums: List<AlbumItem>,
    val artworkUri: Uri?
)

data class FolderItem(
    val name: String,
    val path: String,
    val songs: List<Song>,
    val subFolderCount: Int
)

data class PlaylistItem(
    val id: String,
    val name: String,
    val songIds: List<Long>
)

class OtoUiState(val context: Context, val prefs: SharedPreferences) {
    val allSongs = mutableStateListOf<Song>()
    val queue = mutableStateListOf<Song>()
    val currentSong = mutableStateOf<Song?>(null)
    val isPlaying = mutableStateOf(false)
    val currentPosition = mutableLongStateOf(0L)
    val duration = mutableLongStateOf(0L)
    val repeatMode = mutableIntStateOf(Player.REPEAT_MODE_OFF)
    val shuffleEnabled = mutableStateOf(false)
    val playbackSpeed = mutableFloatStateOf(1.0f)
    val playbackPitch = mutableFloatStateOf(1.0f)
    val audioSessionId = mutableIntStateOf(0)

    // Navigation & Screens
    val selectedBottomTab = mutableIntStateOf(0) // 0=Home, 1=Albums, 2=Songs, 3=Playlists, 4=Artists
    val currentScreen = mutableStateOf("main")
    // Screens: "main", "player", "folders", "queue", "equalizer", "stats",
    // "settings", "themes", "now_playing_settings", "customization_settings",
    // "library_settings", "playlists_settings", "images_settings", "audio_settings",
    // "backup_restore", "about", "album_detail", "artist_detail", "playlist_detail",
    // "history", "favorites", "most_played", "folder_detail"

    val selectedAlbum = mutableStateOf<AlbumItem?>(null)
    val selectedArtist = mutableStateOf<ArtistItem?>(null)
    val selectedPlaylist = mutableStateOf<PlaylistItem?>(null)
    val selectedFolder = mutableStateOf<FolderItem?>(null)

    // Search & Sorting
    val searchQuery = mutableStateOf("")
    val isSearchActive = mutableStateOf(false)
    val songSortOrder = mutableStateOf(prefs.getString("song_sort", "DATE_MODIFIED") ?: "DATE_MODIFIED")
    val albumSortOrder = mutableStateOf(prefs.getString("album_sort", "NAME") ?: "NAME")
    val artistSortOrder = mutableStateOf(prefs.getString("artist_sort", "NAME") ?: "NAME")
    val albumGridColumns = mutableIntStateOf(prefs.getInt("album_grid_cols", 3))
    val artistGridColumns = mutableIntStateOf(prefs.getInt("artist_grid_cols", 4))

    // Dialogs & Sheets
    val showSleepTimerDialog = mutableStateOf(false)
    val showMoreOptionsSheet = mutableStateOf<Song?>(null)
    val showSongDetailsDialog = mutableStateOf<Song?>(null)
    val showAddToPlaylistDialog = mutableStateOf<Song?>(null)
    val showCreatePlaylistDialog = mutableStateOf(false)
    val showSpeedPitchDialog = mutableStateOf(false)
    val showEditLyricsDialog = mutableStateOf<Song?>(null)
    val showTagEditorDialog = mutableStateOf<Song?>(null)
    val showLyricsOnPlayer = mutableStateOf(false)

    // Sleep Timer State
    val sleepTimerMinutes = mutableIntStateOf(30)
    val sleepTimerRemainingSec = mutableLongStateOf(0L)
    val sleepTimerFinishLastSong = mutableStateOf(prefs.getBoolean("sleep_finish_song", false))
    val sleepTimerFadeOut = mutableStateOf(prefs.getBoolean("sleep_fade_out", true))

    // Themes & Look Settings (All Pro features unlocked 100% free!)
    val appThemeMode = mutableStateOf(prefs.getString("app_theme_mode", "Dark") ?: "Dark") // Light, Dark, System
    val amoledDark = mutableStateOf(prefs.getBoolean("amoled_dark", false))
    val paletteCategory = mutableStateOf(prefs.getString("palette_category", "Default") ?: "Default")
    val accentColor = mutableStateOf(Color(prefs.getInt("accent_color", Color(0xFFD0BCFF).toArgb())))

    // Now Playing Settings
    val nowPlayingTheme = mutableStateOf(prefs.getString("np_theme", "Default") ?: "Default")
    val showSongInfo = mutableStateOf(prefs.getBoolean("show_song_info", true))
    val showVolumeSlider = mutableStateOf(prefs.getBoolean("show_volume_slider", true))
    val extraMiniPlayerControls = mutableStateOf(prefs.getBoolean("extra_mini_controls", false))

    // Customization Settings
    val showSeeAllButton = mutableStateOf(prefs.getBoolean("show_see_all", true))
    val enabledTabs = mutableStateListOf("Home", "Albums", "Songs", "Playlists", "Artists")
    val enabledHomeRows = mutableStateListOf("Quick Actions", "Showcase", "Recently Added", "Recently Played", "Top Artists")

    // Content & Library Settings
    val multiArtistSupport = mutableStateOf(prefs.getBoolean("multi_artist", true))
    val multiGenreSupport = mutableStateOf(prefs.getBoolean("multi_genre", true))
    val alwaysNavigateFirstArtist = mutableStateOf(prefs.getBoolean("nav_first_artist", false))
    val minDurationFilterSec = mutableIntStateOf(prefs.getInt("min_duration_sec", 0))
    val blacklistedFolders = mutableStateListOf<String>()

    // Playlists & Images Settings
    val lastAddedInterval = mutableStateOf(prefs.getString("last_added_interval", "Past three months") ?: "Past three months")
    val recentlyPlayedInterval = mutableStateOf(prefs.getString("recently_played_interval", "Past three months") ?: "Past three months")
    val cacheImages = mutableStateOf(prefs.getBoolean("cache_images", true))
    val useMediaStoreArtwork = mutableStateOf(prefs.getBoolean("mediastore_art", true))
    val autoDownloadMetadata = mutableStateOf(prefs.getString("auto_dl_meta", "Never (Offline FOSS)") ?: "Never (Offline FOSS)")
    val lockscreenAlbumArt = mutableStateOf(prefs.getBoolean("lockscreen_art", true))
    val blurLockscreenArt = mutableStateOf(prefs.getBoolean("blur_lockscreen", false))

    // Audio & Playback Settings
    val fadePlayPauseMs = mutableIntStateOf(prefs.getInt("fade_play_pause_ms", 0))
    val crossfadeSec = mutableIntStateOf(prefs.getInt("crossfade_sec", 0))
    val gaplessPlayback = mutableStateOf(prefs.getBoolean("gapless_playback", true))
    val keepShuffleMode = mutableStateOf(prefs.getBoolean("keep_shuffle", true))
    val replayGainMode = mutableStateOf(prefs.getString("replay_gain", "None") ?: "None")
    val lowerVolumeOnFocalLoss = mutableStateOf(prefs.getBoolean("lower_volume_on_focal_loss", true))
    val autoPlayHeadset = mutableStateOf(prefs.getBoolean("auto_play_headset", false))

    // Equalizer State
    val eqEnabled = mutableStateOf(prefs.getBoolean("eq_enabled", false))
    val eqPreset = mutableStateOf(prefs.getString("eq_preset", "Normal") ?: "Normal")
    val eqBands = mutableStateListOf(0f, 0f, 0f, 0f, 0f)
    val bassBoostStrength = mutableFloatStateOf(prefs.getFloat("bass_boost", 0f))

    // User Data: Favorites, History, Play Counts, Playlists, Custom Lyrics
    val favoriteIds = mutableStateListOf<Long>()
    val historyIds = mutableStateListOf<Long>()
    val playCounts = mutableStateMapOf<Long, Int>()
    val playlists = mutableStateListOf<PlaylistItem>()
    val customLyrics = mutableStateMapOf<Long, String>()
    val totalListeningMinutes = mutableIntStateOf(prefs.getInt("total_listening_min", 325))
    val totalSessions = mutableIntStateOf(prefs.getInt("total_sessions", 9))
    val longestStreakDays = mutableIntStateOf(prefs.getInt("longest_streak", 4))

    init {
        prefs.getStringSet("favorites", emptySet())?.forEach { idStr ->
            idStr.toLongOrNull()?.let { favoriteIds.add(it) }
        }
        prefs.getString("history_csv", "")?.split(",")?.mapNotNull { it.toLongOrNull() }?.forEach {
            historyIds.add(it)
        }
        prefs.getStringSet("blacklist_folders", emptySet())?.forEach { blacklistedFolders.add(it) }
        loadSavedPlaylists()
        loadSavedPlayCounts()
        loadSavedLyrics()
    }

    val backgroundColor: Color
        get() = when {
            appThemeMode.value == "Light" -> Color(0xFFF8F5FF)
            amoledDark.value -> Color.Black
            else -> Color(0xFF141318)
        }

    val surfaceColor: Color
        get() = when {
            appThemeMode.value == "Light" -> Color(0xFFECE6F5)
            amoledDark.value -> Color(0xFF121215)
            else -> Color(0xFF211F26)
        }

    val cardColor: Color
        get() = when {
            appThemeMode.value == "Light" -> Color(0xFFE2D9F3)
            amoledDark.value -> Color(0xFF18171C)
            else -> Color(0xFF2B2930)
        }

    val textPrimary: Color
        get() = if (appThemeMode.value == "Light") Color(0xFF1C1B1F) else Color(0xFFF4EFF4)

    val textSecondary: Color
        get() = if (appThemeMode.value == "Light") Color(0xFF49454F) else Color(0xFF9E9AA6)

    val visibleSongs: List<Song>
        get() = allSongs.filter { song ->
            val passBlacklist = blacklistedFolders.none { song.folderPath.startsWith(it) }
            val passDuration = song.duration >= (minDurationFilterSec.intValue * 1000L)
            val q = searchQuery.value.trim()
            val passSearch = q.isEmpty() ||
                song.title.contains(q, ignoreCase = true) ||
                song.artist.contains(q, ignoreCase = true) ||
                song.album.contains(q, ignoreCase = true)
            passBlacklist && passDuration && passSearch
        }.let { list ->
            when (songSortOrder.value) {
                "NAME" -> list.sortedBy { it.title.lowercase(Locale.ROOT) }
                "ARTIST" -> list.sortedBy { it.artist.lowercase(Locale.ROOT) }
                "DURATION" -> list.sortedByDescending { it.duration }
                "DATE_ADDED" -> list.sortedByDescending { it.dateAddedSec }
                else -> list.sortedByDescending { it.dateModifiedSec }
            }
        }

    val albums: List<AlbumItem>
        get() = visibleSongs.groupBy { it.albumId to it.album }.map { (key, list) ->
            val first = list.first()
            AlbumItem(
                id = key.first,
                title = key.second,
                artist = first.artist,
                albumArtUri = first.albumArtUri,
                songs = list.sortedBy { it.trackNumber },
                year = first.year
            )
        }.let { list ->
            when (albumSortOrder.value) {
                "ARTIST" -> list.sortedBy { it.artist.lowercase(Locale.ROOT) }
                "YEAR" -> list.sortedByDescending { it.year }
                "SONG_COUNT" -> list.sortedByDescending { it.songs.size }
                else -> list.sortedBy { it.title.lowercase(Locale.ROOT) }
            }
        }

    val artists: List<ArtistItem>
        get() {
            val map = mutableMapOf<String, MutableList<Song>>()
            for (s in visibleSongs) {
                val names = if (multiArtistSupport.value) {
                    s.artist.split(",", "&", "/", ";").map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    listOf(s.artist)
                }
                for (name in names.ifEmpty { listOf("Unknown Artist") }) {
                    map.getOrPut(name) { mutableListOf() }.add(s)
                }
            }
            return map.map { (name, list) ->
                val artistAlbums = list.groupBy { it.albumId }.map { (albId, albSongs) ->
                    val f = albSongs.first()
                    AlbumItem(albId, f.album, name, f.albumArtUri, albSongs, f.year)
                }
                ArtistItem(name, list, artistAlbums, list.firstOrNull()?.albumArtUri)
            }.let { list ->
                if (artistSortOrder.value == "SONG_COUNT") {
                    list.sortedByDescending { it.songs.size }
                } else {
                    list.sortedBy { it.name.lowercase(Locale.ROOT) }
                }
            }
        }

    val folders: List<FolderItem>
        get() = allSongs.groupBy { it.folderPath }.map { (path, list) ->
            val name = path.substringAfterLast('/').ifEmpty { "Internal Storage" }
            val subFolders = allSongs.map { it.folderPath }
                .distinct()
                .count { it != path && it.startsWith("$path/") }
            FolderItem(name = name, path = path, songs = list, subFolderCount = subFolders)
        }.sortedByDescending { it.songs.size }

    fun toggleFavorite(song: Song) {
        if (favoriteIds.contains(song.id)) {
            favoriteIds.remove(song.id)
        } else {
            favoriteIds.add(song.id)
        }
        prefs.edit().putStringSet("favorites", favoriteIds.map { it.toString() }.toSet()).apply()
    }

    fun isFavorite(song: Song?): Boolean = song != null && favoriteIds.contains(song.id)

    fun recordPlay(song: Song) {
        historyIds.remove(song.id)
        historyIds.add(0, song.id)
        if (historyIds.size > 100) historyIds.removeLast()
        prefs.edit().putString("history_csv", historyIds.joinToString(",")).apply()

        val newCount = (playCounts[song.id] ?: 0) + 1
        playCounts[song.id] = newCount
        savePlayCounts()
    }

    private fun savePlayCounts() {
        val serialized = playCounts.entries.joinToString(";") { "${it.key}:${it.value}" }
        prefs.edit().putString("play_counts", serialized).apply()
    }

    private fun loadSavedPlayCounts() {
        prefs.getString("play_counts", "")?.split(";")?.forEach { pair ->
            val parts = pair.split(":")
            if (parts.size == 2) {
                val id = parts[0].toLongOrNull()
                val cnt = parts[1].toIntOrNull()
                if (id != null && cnt != null) playCounts[id] = cnt
            }
        }
    }

    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        val id = "pl_${System.currentTimeMillis()}"
        playlists.add(PlaylistItem(id, name.trim(), emptyList()))
        savePlaylists()
    }

    fun deletePlaylist(playlist: PlaylistItem) {
        playlists.removeAll { it.id == playlist.id }
        savePlaylists()
    }

    fun addSongToPlaylist(playlistId: String, songId: Long) {
        val idx = playlists.indexOfFirst { it.id == playlistId }
        if (idx >= 0) {
            val current = playlists[idx]
            if (!current.songIds.contains(songId)) {
                playlists[idx] = current.copy(songIds = current.songIds + songId)
                savePlaylists()
                Toast.makeText(context, "Added to ${current.name}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun saveQueueAsPlaylist(name: String) {
        if (name.isBlank() || queue.isEmpty()) return
        val id = "pl_${System.currentTimeMillis()}"
        playlists.add(PlaylistItem(id, name.trim(), queue.map { it.id }))
        savePlaylists()
        Toast.makeText(context, "Saved queue to playlist '$name'", Toast.LENGTH_SHORT).show()
    }

    private fun savePlaylists() {
        val raw = playlists.joinToString("||") { pl ->
            "${pl.id}::${pl.name}::${pl.songIds.joinToString(",")}"
        }
        prefs.edit().putString("saved_playlists", raw).apply()
    }

    private fun loadSavedPlaylists() {
        val raw = prefs.getString("saved_playlists", "") ?: ""
        if (raw.isBlank()) return
        raw.split("||").forEach { entry ->
            val parts = entry.split("::")
            if (parts.size >= 2) {
                val ids = if (parts.size >= 3 && parts[2].isNotBlank()) {
                    parts[2].split(",").mapNotNull { it.toLongOrNull() }
                } else emptyList()
                playlists.add(PlaylistItem(parts[0], parts[1], ids))
            }
        }
    }

    fun saveLyricsForSong(songId: Long, lyrics: String) {
        if (lyrics.isBlank()) {
            customLyrics.remove(songId)
        } else {
            customLyrics[songId] = lyrics
        }
        val raw = customLyrics.entries.joinToString("||") { "${it.key}::${it.value.replace("\n", "\\n")}" }
        prefs.edit().putString("custom_lyrics", raw).apply()
    }

    private fun loadSavedLyrics() {
        val raw = prefs.getString("custom_lyrics", "") ?: ""
        if (raw.isBlank()) return
        raw.split("||").forEach { item ->
            val parts = item.split("::")
            if (parts.size == 2) {
                val id = parts[0].toLongOrNull()
                if (id != null) customLyrics[id] = parts[1].replace("\\n", "\n")
            }
        }
    }

    fun getLyricsForSong(song: Song): List<String> {
        val saved = customLyrics[song.id]
        if (!saved.isNullOrBlank()) {
            return saved.lines().map { line ->
                line.replace(Regex("\\[\\d{2}:\\d{2}(\\.\\d{1,3})?]"), "").trim()
            }.filter { it.isNotEmpty() }
        }
        // Check sidecar .lrc or .txt file if accessible in same directory
        if (song.path.isNotBlank()) {
            val base = song.path.substringBeforeLast('.')
            for (ext in listOf(".lrc", ".txt")) {
                val file = File(base + ext)
                if (file.exists() && file.canRead()) {
                    return try {
                        file.readLines().map { line ->
                            line.replace(Regex("\\[\\d{2}:\\d{2}(\\.\\d{1,3})?]"), "").trim()
                        }.filter { it.isNotEmpty() }
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }
        }
        return emptyList()
    }

    fun updateAccentColor(color: Color, category: String = paletteCategory.value) {
        accentColor.value = color
        paletteCategory.value = category
        prefs.edit()
            .putInt("accent_color", color.toArgb())
            .putString("palette_category", category)
            .apply()
    }

    fun updateAmoledDark(enabled: Boolean) {
        amoledDark.value = enabled
        prefs.edit().putBoolean("amoled_dark", enabled).apply()
    }

    fun updateThemeMode(mode: String) {
        appThemeMode.value = mode
        prefs.edit().putString("app_theme_mode", mode).apply()
    }

    fun savePrefBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun savePrefInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun savePrefString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private lateinit var uiState: OtoUiState
    private var sleepTimerJob: Job? = null
    private var equalizer: android.media.audiofx.Equalizer? = null
    private var bassBoost: android.media.audiofx.BassBoost? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) {
            loadSongsFromMediaStore()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("oto_prefs", MODE_PRIVATE)
        uiState = OtoUiState(this, prefs)

        checkAndRequestPermissions()

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
                setupControllerListener()
                requestAudioSessionId()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())

        // Periodic playback progress & listening stats updater
        lifecycleScope.launch {
            var playTicks = 0
            while (isActive) {
                val controller = mediaController
                if (controller != null) {
                    uiState.currentPosition.longValue = controller.currentPosition.coerceAtLeast(0L)
                    val dur = controller.duration
                    if (dur > 0) uiState.duration.longValue = dur
                    if (controller.isPlaying) {
                        playTicks++
                        if (playTicks >= 120) { // Every 60 seconds (120 * 500ms)
                            playTicks = 0
                            uiState.totalListeningMinutes.intValue += 1
                            uiState.savePrefInt("total_listening_min", uiState.totalListeningMinutes.intValue)
                        }
                    }
                }
                delay(500L)
            }
        }

        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = uiState.backgroundColor) {
                OtoMainRoot(
                    state = uiState,
                    onPlaySong = { song, customQueue -> playSongList(song, customQueue) },
                    onShuffleAll = { list -> shufflePlayList(list) },
                    onTogglePlay = { togglePlayPause() },
                    onNext = { mediaController?.seekToNextMediaItem() },
                    onPrev = { mediaController?.seekToPreviousMediaItem() },
                    onSeek = { pos ->
                        mediaController?.seekTo(pos)
                        uiState.currentPosition.longValue = pos
                    },
                    onToggleRepeat = { cycleRepeatMode() },
                    onToggleShuffle = { toggleShuffleMode() },
                    onStartSleepTimer = { mins -> startSleepTimer(mins) },
                    onCancelSleepTimer = { cancelSleepTimer() },
                    onRescanMedia = { triggerMediaScan() },
                    onUpdateSpeedPitch = { speed, pitch -> updateSpeedAndPitch(speed, pitch) },
                    onApplyEqualizer = { applyAudioEffects() },
                    onShareSong = { song -> shareSongFile(song) },
                    onRemoveFromQueue = { idx -> removeQueueItem(idx) },
                    onClearQueue = { clearQueue() }
                )
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            loadSongsFromMediaStore()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun loadSongsFromMediaStore() {
        uiState.allSongs.clear()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            projection.add(MediaStore.Audio.Media.BITRATE)
            projection.add(MediaStore.Audio.Media.GENRE)
        }

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

        contentResolver.query(uri, projection.toTypedArray(), selection, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
            val addedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
            val modCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
            val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
            val trackCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
            val yearCol = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)
            val bitrateCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                cursor.getColumnIndex(MediaStore.Audio.Media.BITRATE)
            } else -1
            val genreCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                cursor.getColumnIndex(MediaStore.Audio.Media.GENRE)
            } else -1

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Unknown Title"
                val rawArtist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Artist"
                val album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Album"
                val duration = cursor.getLong(durCol)
                if (duration <= 0L) continue
                val albumId = cursor.getLong(albumIdCol)
                val path = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                val folderPath = path.substringBeforeLast('/', "/storage/emulated/0/Music")
                val folderName = folderPath.substringAfterLast('/').ifEmpty { "Music" }
                val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                val dateAdded = if (addedCol >= 0) cursor.getLong(addedCol) else 0L
                val dateMod = if (modCol >= 0) cursor.getLong(modCol) else 0L
                val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "audio/mpeg" else "audio/mpeg"
                val track = if (trackCol >= 0) cursor.getInt(trackCol) else 1
                val year = if (yearCol >= 0) cursor.getInt(yearCol).takeIf { it > 1900 } ?: 2026 else 2026
                val rawBitrate = if (bitrateCol >= 0) cursor.getInt(bitrateCol) else 0
                val bitrateKbps = when {
                    rawBitrate > 1000 -> rawBitrate / 1000
                    sizeBytes > 0 && duration > 0 -> ((sizeBytes * 8) / duration).toInt().coerceIn(64, 1411)
                    else -> 320
                }
                val sampleRate = if (mime.contains("opus", true) || path.endsWith(".opus", true)) 48000 else 44100
                val genre = if (genreCol >= 0) cursor.getString(genreCol)?.takeIf { it.isNotBlank() } ?: "Music" else "Music"

                val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                val albumArtUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)

                uiState.allSongs.add(
                    Song(
                        id = id,
                        title = title,
                        artist = rawArtist,
                        album = album,
                        albumId = albumId,
                        duration = duration,
                        contentUri = contentUri,
                        albumArtUri = albumArtUri,
                        path = path,
                        folderName = folderName,
                        folderPath = folderPath,
                        sizeBytes = sizeBytes,
                        dateAddedSec = dateAdded,
                        dateModifiedSec = dateMod,
                        mimeType = mime,
                        bitrateKbps = bitrateKbps,
                        sampleRateHz = sampleRate,
                        trackNumber = track,
                        year = year,
                        genre = genre
                    )
                )
            }
        }

        if (uiState.queue.isEmpty() && uiState.allSongs.isNotEmpty()) {
            uiState.queue.addAll(uiState.visibleSongs)
            uiState.currentSong.value = uiState.queue.firstOrNull()
            uiState.duration.longValue = uiState.currentSong.value?.duration ?: 0L
        }
    }

    private fun setupControllerListener() {
        mediaController?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                uiState.isPlaying.value = playing
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val currentId = mediaItem?.mediaId?.toLongOrNull()
                val found = uiState.allSongs.find { it.id == currentId }
                if (found != null) {
                    uiState.currentSong.value = found
                    uiState.duration.longValue = found.duration
                    uiState.recordPlay(found)
                }
                if (uiState.sleepTimerFinishLastSong.value && uiState.sleepTimerRemainingSec.longValue == -1L) {
                    mediaController?.pause()
                    uiState.sleepTimerRemainingSec.longValue = 0L
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                uiState.repeatMode.intValue = repeatMode
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                uiState.shuffleEnabled.value = shuffleModeEnabled
            }
        })
    }

    private fun requestAudioSessionId() {
        val controller = mediaController ?: return
        val future = controller.sendCustomCommand(
            SessionCommand(PlaybackService.ACTION_GET_AUDIO_SESSION_ID, Bundle.EMPTY),
            Bundle.EMPTY
        )
        future.addListener({
            try {
                val sessionId = future.get().extras.getInt(PlaybackService.KEY_AUDIO_SESSION_ID, 0)
                if (sessionId != 0) {
                    uiState.audioSessionId.intValue = sessionId
                    applyAudioEffects()
                }
            } catch (_: Exception) {}
        }, MoreExecutors.directExecutor())
    }

    private fun applyAudioEffects() {
        val sessionId = uiState.audioSessionId.intValue
        if (sessionId == 0) return
        try {
            if (equalizer == null) {
                equalizer = android.media.audiofx.Equalizer(0, sessionId)
            }
            if (bassBoost == null) {
                bassBoost = android.media.audiofx.BassBoost(0, sessionId)
            }
            equalizer?.enabled = uiState.eqEnabled.value
            val eq = equalizer
            if (eq != null && uiState.eqEnabled.value) {
                val range = eq.bandLevelRange
                val minMb = range[0]
                val maxMb = range[1]
                val bands = eq.numberOfBands.toInt().coerceAtMost(uiState.eqBands.size)
                for (b in 0 until bands) {
                    val norm = uiState.eqBands[b].coerceIn(-1f, 1f)
                    val level = ((norm + 1f) / 2f * (maxMb - minMb) + minMb).toInt().toShort()
                    eq.setBandLevel(b.toShort(), level)
                }
            }
            bassBoost?.enabled = uiState.eqEnabled.value && uiState.bassBoostStrength.floatValue > 0f
            if (bassBoost?.strengthSupported == true) {
                bassBoost?.setStrength((uiState.bassBoostStrength.floatValue * 1000).toInt().coerceIn(0, 1000).toShort())
            }
        } catch (_: Exception) {}
    }

    private fun playSongList(song: Song, customList: List<Song>? = null) {
        val controller = mediaController ?: return
        val listToPlay = (customList ?: uiState.visibleSongs).ifEmpty { listOf(song) }
        uiState.queue.clear()
        uiState.queue.addAll(listToPlay)
        uiState.currentSong.value = song
        uiState.duration.longValue = song.duration
        uiState.recordPlay(song)

        val mediaItems = listToPlay.map { s ->
            MediaItem.Builder()
                .setMediaId(s.id.toString())
                .setUri(s.contentUri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(s.title)
                        .setArtist(s.artist)
                        .setAlbumTitle(s.album)
                        .setArtworkUri(s.albumArtUri)
                        .build()
                )
                .build()
        }

        val startIndex = listToPlay.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        controller.setMediaItems(mediaItems, startIndex, 0L)
        controller.prepare()
        controller.play()
        requestAudioSessionId()
    }

    private fun shufflePlayList(list: List<Song>) {
        if (list.isEmpty()) return
        val shuffled = list.shuffled()
        uiState.shuffleEnabled.value = true
        mediaController?.shuffleModeEnabled = true
        playSongList(shuffled.first(), shuffled)
    }

    private fun togglePlayPause() {
        val controller = mediaController ?: return
        val fadeMs = uiState.fadePlayPauseMs.intValue
        if (controller.isPlaying) {
            if (fadeMs > 0) {
                lifecycleScope.launch {
                    val steps = 10
                    val stepDelay = (fadeMs / steps).toLong().coerceAtLeast(10L)
                    for (i in steps - 1 downTo 0) {
                        controller.volume = i / steps.toFloat()
                        delay(stepDelay)
                    }
                    controller.pause()
                    controller.volume = 1f
                }
            } else {
                controller.pause()
            }
        } else {
            if (controller.mediaItemCount > 0) {
                if (fadeMs > 0) {
                    controller.volume = 0f
                    controller.play()
                    lifecycleScope.launch {
                        val steps = 10
                        val stepDelay = (fadeMs / steps).toLong().coerceAtLeast(10L)
                        for (i in 1..steps) {
                            controller.volume = i / steps.toFloat()
                            delay(stepDelay)
                        }
                    }
                } else {
                    controller.volume = 1f
                    controller.play()
                }
            } else if (uiState.visibleSongs.isNotEmpty()) {
                playSongList(uiState.visibleSongs.first(), uiState.visibleSongs)
            }
        }
    }

    private fun cycleRepeatMode() {
        val controller = mediaController ?: return
        val nextMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        controller.repeatMode = nextMode
        uiState.repeatMode.intValue = nextMode
    }

    private fun toggleShuffleMode() {
        val controller = mediaController ?: return
        val next = !controller.shuffleModeEnabled
        controller.shuffleModeEnabled = next
        uiState.shuffleEnabled.value = next
    }

    private fun updateSpeedAndPitch(speed: Float, pitch: Float) {
        uiState.playbackSpeed.floatValue = speed
        uiState.playbackPitch.floatValue = pitch
        mediaController?.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 3.0f), pitch.coerceIn(0.5f, 2.0f))
    }

    private fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        val totalSeconds = minutes * 60L
        uiState.sleepTimerRemainingSec.longValue = totalSeconds
        Toast.makeText(this, "Sleep timer set for $minutes min", Toast.LENGTH_SHORT).show()

        sleepTimerJob = lifecycleScope.launch {
            while (uiState.sleepTimerRemainingSec.longValue > 0L && isActive) {
                delay(1000L)
                val rem = uiState.sleepTimerRemainingSec.longValue - 1L
                uiState.sleepTimerRemainingSec.longValue = rem
                if (uiState.sleepTimerFadeOut.value && rem in 1..10) {
                    mediaController?.volume = (rem / 10f).coerceIn(0.05f, 1f)
                }
            }
            if (uiState.sleepTimerRemainingSec.longValue == 0L) {
                mediaController?.volume = 1f
                if (uiState.sleepTimerFinishLastSong.value && mediaController?.isPlaying == true) {
                    uiState.sleepTimerRemainingSec.longValue = -1L // Wait for track transition
                } else {
                    mediaController?.pause()
                }
            }
        }
    }

    private fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        uiState.sleepTimerRemainingSec.longValue = 0L
        mediaController?.volume = 1f
        Toast.makeText(this, "Sleep timer cancelled", Toast.LENGTH_SHORT).show()
    }

    private fun triggerMediaScan() {
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        MediaScannerConnection.scanFile(this, arrayOf(musicDir, downloadDir), null) { _, _ ->
            runOnUiThread {
                loadSongsFromMediaStore()
            }
        }
        loadSongsFromMediaStore()
        Toast.makeText(this, "Scanned ${uiState.allSongs.size} tracks offline", Toast.LENGTH_SHORT).show()
    }

    private fun shareSongFile(song: Song) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = song.mimeType.ifBlank { "audio/*" }
                putExtra(Intent.EXTRA_STREAM, song.contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share ${song.title}"))
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to share track", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeQueueItem(index: Int) {
        if (index in uiState.queue.indices) {
            uiState.queue.removeAt(index)
            mediaController?.removeMediaItem(index)
        }
    }

    private fun clearQueue() {
        uiState.queue.clear()
        mediaController?.stop()
        mediaController?.clearMediaItems()
        uiState.currentSong.value = null
    }

    override fun onDestroy() {
        equalizer?.release()
        bassBoost?.release()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

@Composable
fun OtoMainRoot(
    state: OtoUiState,
    onPlaySong: (Song, List<Song>?) -> Unit,
    onShuffleAll: (List<Song>) -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onStartSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onRescanMedia: () -> Unit,
    onUpdateSpeedPitch: (Float, Float) -> Unit,
    onApplyEqualizer: () -> Unit,
    onShareSong: (Song) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onClearQueue: () -> Unit
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    BackHandler(enabled = state.currentScreen.value != "main" || state.isSearchActive.value) {
        if (state.isSearchActive.value) {
            state.isSearchActive.value = false
            state.searchQuery.value = ""
        } else {
            when (state.currentScreen.value) {
                "themes", "now_playing_settings", "customization_settings",
                "library_settings", "playlists_settings", "images_settings",
                "audio_settings", "backup_restore", "about" -> state.currentScreen.value = "settings"
                "folder_detail" -> state.currentScreen.value = "folders"
                else -> state.currentScreen.value = "main"
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            OtoDrawerSheet(
                state = state,
                onSelectDestination = { dest ->
                    scope.launch { drawerState.close() }
                    when (dest) {
                        "library" -> state.currentScreen.value = "main"
                        "folders" -> state.currentScreen.value = "folders"
                        "queue" -> state.currentScreen.value = "queue"
                        "equalizer" -> state.currentScreen.value = "equalizer"
                        "stats" -> state.currentScreen.value = "stats"
                        "scan" -> onRescanMedia()
                        "settings" -> state.currentScreen.value = "settings"
                        "about" -> state.currentScreen.value = "about"
                    }
                }
            )
        }
    ) {
        Scaffold(
            containerColor = state.backgroundColor,
            bottomBar = {
                if (state.currentScreen.value != "player") {
                    Column(modifier = Modifier.background(state.backgroundColor)) {
                        val current = state.currentSong.value
                        if (current != null) {
                            OtoMiniPlayer(
                                song = current,
                                isPlaying = state.isPlaying.value,
                                progress = if (state.duration.longValue > 0) {
                                    (state.currentPosition.longValue.toFloat() / state.duration.longValue.toFloat()).coerceIn(0f, 1f)
                                } else 0f,
                                accentColor = state.accentColor.value,
                                surfaceColor = state.surfaceColor,
                                textPrimary = state.textPrimary,
                                textSecondary = state.textSecondary,
                                showExtraControls = state.extraMiniPlayerControls.value,
                                onTogglePlay = onTogglePlay,
                                onPrev = onPrev,
                                onNext = onNext,
                                onOpenPlayer = { state.currentScreen.value = "player" }
                            )
                        }
                        if (state.currentScreen.value == "main") {
                            OtoBottomNavigationBar(state = state)
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (state.currentScreen.value) {
                    "main" -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            OtoFloatingSearchBar(
                                state = state,
                                onOpenDrawer = { scope.launch { drawerState.open() } },
                                onOpenEqualizer = { state.currentScreen.value = "equalizer" }
                            )
                            when (state.selectedBottomTab.intValue) {
                                0 -> OtoHomeTab(state, onPlaySong, onShuffleAll)
                                1 -> OtoAlbumsTab(state, onPlaySong, onShuffleAll)
                                2 -> OtoSongsTab(state, onPlaySong, onShuffleAll)
                                3 -> OtoPlaylistsTab(state)
                                4 -> OtoArtistsTab(state)
                            }
                        }
                    }
                    "player" -> {
                        val current = state.currentSong.value
                        if (current != null) {
                            OtoNowPlayingScreen(
                                state = state,
                                song = current,
                                onBack = { state.currentScreen.value = "main" },
                                onTogglePlay = onTogglePlay,
                                onNext = onNext,
                                onPrev = onPrev,
                                onSeek = onSeek,
                                onToggleRepeat = onToggleRepeat,
                                onToggleShuffle = onToggleShuffle,
                                onOpenQueue = { state.currentScreen.value = "queue" }
                            )
                        } else {
                            state.currentScreen.value = "main"
                        }
                    }
                    "folders" -> OtoFoldersScreen(
                        state = state,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onPlaySong = onPlaySong,
                        onShuffleAll = onShuffleAll
                    )
                    "folder_detail" -> OtoFolderDetailScreen(state = state, onPlaySong = onPlaySong, onShuffleAll = onShuffleAll)
                    "queue" -> OtoPlayingQueueScreen(
                        state = state,
                        onPlaySong = onPlaySong,
                        onRemoveFromQueue = onRemoveFromQueue
                    )
                    "equalizer" -> OtoEqualizerScreen(state = state, onApplyEqualizer = onApplyEqualizer)
                    "stats" -> OtoListeningStatsScreen(state = state, onPlaySong = onPlaySong)
                    "settings" -> OtoSettingsMainScreen(state = state)
                    "themes" -> OtoThemesAndColorsScreen(state = state)
                    "now_playing_settings" -> OtoNowPlayingSettingsScreen(state = state)
                    "customization_settings" -> OtoCustomizationSettingsScreen(state = state)
                    "library_settings" -> OtoLibrarySettingsScreen(state = state)
                    "playlists_settings" -> OtoPlaylistsSettingsScreen(state = state)
                    "images_settings" -> OtoImagesSettingsScreen(state = state)
                    "audio_settings" -> OtoAudioSettingsScreen(state = state)
                    "backup_restore" -> OtoBackupRestoreScreen(state = state)
                    "about" -> OtoAboutScreen(state = state)
                    "album_detail" -> OtoAlbumDetailScreen(state = state, onPlaySong = onPlaySong, onShuffleAll = onShuffleAll)
                    "artist_detail" -> OtoArtistDetailScreen(state = state, onPlaySong = onPlaySong, onShuffleAll = onShuffleAll)
                    "playlist_detail" -> OtoPlaylistDetailScreen(state = state, onPlaySong = onPlaySong, onShuffleAll = onShuffleAll)
                    "history" -> OtoSmartListScreen(
                        title = "History",
                        songs = state.historyIds.mapNotNull { id -> state.allSongs.find { it.id == id } },
                        state = state,
                        onPlaySong = onPlaySong,
                        onShuffleAll = onShuffleAll
                    )
                    "favorites" -> OtoSmartListScreen(
                        title = "Favorites",
                        songs = state.allSongs.filter { state.favoriteIds.contains(it.id) },
                        state = state,
                        onPlaySong = onPlaySong,
                        onShuffleAll = onShuffleAll
                    )
                    "most_played" -> OtoSmartListScreen(
                        title = "Most played",
                        songs = state.allSongs.sortedByDescending { state.playCounts[it.id] ?: 0 }.take(50),
                        state = state,
                        onPlaySong = onPlaySong,
                        onShuffleAll = onShuffleAll
                    )
                }
            }
        }
    }

    // Global Dialogs & Bottom Sheets
    if (state.showSleepTimerDialog.value) {
        OtoSleepTimerDialog(
            state = state,
            onStart = { mins ->
                onStartSleepTimer(mins)
                state.showSleepTimerDialog.value = false
            },
            onCancel = {
                onCancelSleepTimer()
                state.showSleepTimerDialog.value = false
            },
            onDismiss = { state.showSleepTimerDialog.value = false }
        )
    }

    state.showMoreOptionsSheet.value?.let { targetSong ->
        OtoMoreOptionsSheet(
            state = state,
            song = targetSong,
            onDismiss = { state.showMoreOptionsSheet.value = null },
            onShare = { onShareSong(targetSong) },
            onClearQueue = onClearQueue
        )
    }

    state.showSongDetailsDialog.value?.let { targetSong ->
        OtoSongDetailsDialog(
            state = state,
            song = targetSong,
            onDismiss = { state.showSongDetailsDialog.value = null }
        )
    }

    state.showAddToPlaylistDialog.value?.let { targetSong ->
        OtoAddToPlaylistDialog(
            state = state,
            song = targetSong,
            onDismiss = { state.showAddToPlaylistDialog.value = null }
        )
    }

    if (state.showCreatePlaylistDialog.value) {
        OtoCreatePlaylistDialog(
            state = state,
            onDismiss = { state.showCreatePlaylistDialog.value = false }
        )
    }

    if (state.showSpeedPitchDialog.value) {
        OtoSpeedPitchDialog(
            state = state,
            onApply = onUpdateSpeedPitch,
            onDismiss = { state.showSpeedPitchDialog.value = false }
        )
    }

    state.showEditLyricsDialog.value?.let { targetSong ->
        OtoEditLyricsDialog(
            state = state,
            song = targetSong,
            onDismiss = { state.showEditLyricsDialog.value = null }
        )
    }

    state.showTagEditorDialog.value?.let { targetSong ->
        OtoTagEditorDialog(
            state = state,
            song = targetSong,
            onDismiss = { state.showTagEditorDialog.value = null }
        )
    }
}

@Composable
fun OtoDrawerSheet(
    state: OtoUiState,
    onSelectDestination: (String) -> Unit
) {
    val song = state.currentSong.value ?: state.allSongs.firstOrNull()
    ModalDrawerSheet(
        drawerContainerColor = state.backgroundColor,
        drawerContentColor = state.textPrimary,
        modifier = Modifier.width(310.dp)
    ) {
        // Drawer Artwork Header (matches 00:06 in video)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(145.dp)
        ) {
            if (song?.albumArtUri != null) {
                AsyncImage(
                    model = song.albumArtUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.45f), state.backgroundColor)
                        )
                    )
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = song?.albumArtUri,
                    contentDescription = null,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(state.cardColor),
                    contentScale = ContentScale.Crop
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        text = song?.title ?: "Oto Music",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = song?.artist ?: "Offline FOSS Player",
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        val items = listOf(
            Triple("library", "Library", Icons.Outlined.LibraryMusic) to state.allSongs.size.toString(),
            Triple("folders", "Folders", Icons.Outlined.Folder) to state.folders.size.toString(),
            Triple("queue", "Playing queue", Icons.Default.QueueMusic) to state.queue.size.toString(),
            Triple("equalizer", "Equalizer", Icons.Default.Equalizer) to null,
            Triple("stats", "Listening stats", Icons.Default.BarChart) to null,
            Triple("scan", "Scan media", Icons.Default.Refresh) to null,
            Triple("settings", "Settings", Icons.Outlined.Settings) to null,
            Triple("about", "About", Icons.Outlined.Info) to null
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            items(items) { (item, badge) ->
                val (key, label, icon) = item
                val selected = (key == "library" && state.currentScreen.value == "main") ||
                    state.currentScreen.value == key
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = if (selected) state.accentColor.value.copy(alpha = 0.22f) else Color.Transparent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clickable { onSelectDestination(key) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = if (selected) state.accentColor.value else state.textPrimary
                        )
                        Text(
                            text = label,
                            color = if (selected) state.accentColor.value else state.textPrimary,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 16.dp)
                        )
                        if (badge != null) {
                            Text(
                                text = badge,
                                color = if (selected) state.accentColor.value else state.textSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        // FOSS All-Unlocked Banner (Replaces "Buy Oto Music+" with 100% Free No-GMS Status)
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = state.surfaceColor,
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LockOpen, contentDescription = null, tint = state.accentColor.value, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Oto Music FOSS Edition", color = state.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "All Pro themes, stats, sleep timer & audio features unlocked. 100% free of Google Play Services.",
                    color = state.textSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun OtoFloatingSearchBar(
    state: OtoUiState,
    onOpenDrawer: () -> Unit,
    onOpenEqualizer: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = state.surfaceColor,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .height(50.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = state.textPrimary)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (state.searchQuery.value.isEmpty()) {
                    Text("Search your music", color = state.textSecondary, fontSize = 16.sp)
                }
                BasicTextField(
                    value = state.searchQuery.value,
                    onValueChange = {
                        state.searchQuery.value = it
                        state.isSearchActive.value = it.isNotEmpty()
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = state.textPrimary, fontSize = 16.sp),
                    cursorBrush = SolidColor(state.accentColor.value),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (state.searchQuery.value.isNotEmpty()) {
                IconButton(onClick = {
                    state.searchQuery.value = ""
                    state.isSearchActive.value = false
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = state.textSecondary)
                }
            } else {
                IconButton(onClick = onOpenEqualizer) {
                    Icon(Icons.Default.Equalizer, contentDescription = "Audio Output / EQ", tint = state.textSecondary)
                }
            }
        }
    }
}

@Composable
fun OtoBottomNavigationBar(state: OtoUiState) {
    val tabDefs = listOf(
        Triple(0, "Home", Icons.Outlined.Home to Icons.Filled.Home),
        Triple(1, "Albums", Icons.Outlined.Album to Icons.Filled.Album),
        Triple(2, "Songs", Icons.Outlined.MusicNote to Icons.Filled.MusicNote),
        Triple(3, "Playlists", Icons.Outlined.QueueMusic to Icons.Filled.QueueMusic),
        Triple(4, "Artists", Icons.Outlined.Person to Icons.Filled.Person)
    ).filter { state.enabledTabs.contains(it.second) }

    NavigationBar(
        containerColor = state.backgroundColor,
        contentColor = state.textPrimary,
        tonalElevation = 0.dp
    ) {
        tabDefs.forEach { (index, label, icons) ->
            val selected = state.selectedBottomTab.intValue == index
            NavigationBarItem(
                selected = selected,
                onClick = { state.selectedBottomTab.intValue = index },
                icon = {
                    Icon(
                        imageVector = if (selected) icons.second else icons.first,
                        contentDescription = label
                    )
                },
                label = {
                    if (selected) {
                        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                },
                alwaysShowLabel = false,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = state.textPrimary,
                    selectedTextColor = state.textPrimary,
                    unselectedIconColor = state.textSecondary,
                    indicatorColor = state.accentColor.value.copy(alpha = 0.28f)
                )
            )
        }
    }
}

@Composable
fun OtoMiniPlayer(
    song: Song,
    isPlaying: Boolean,
    progress: Float,
    accentColor: Color,
    surfaceColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    showExtraControls: Boolean,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenPlayer: () -> Unit
) {
    Surface(
        color = surfaceColor,
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenPlayer() }
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = song.albumArtUri,
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.DarkGray),
                    contentScale = ContentScale.Crop
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                ) {
                    Text(
                        text = song.title,
                        color = textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = song.artist,
                        color = textSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (showExtraControls) {
                    IconButton(onClick = onPrev) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = textPrimary)
                    }
                }
                IconButton(onClick = onTogglePlay) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = textPrimary
                    )
                }
                if (showExtraControls) {
                    IconButton(onClick = onNext) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = textPrimary)
                    }
                }
            }
            // Thin progress bar along bottom edge (matches 00:03 in video)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color.White.copy(alpha = 0.12f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(accentColor)
                )
            }
        }
    }
}

@Composable
fun OtoHomeTab(
    state: OtoUiState,
    onPlaySong: (Song, List<Song>?) -> Unit,
    onShuffleAll: (List<Song>) -> Unit
) {
    val songs = state.visibleSongs
    val albums = state.albums
    val artists = state.artists

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // 1. 4 Circular Quick Action Buttons (History, Favorites, Most played, Shuffle - matches 00:03)
        if (state.enabledHomeRows.contains("Quick Actions")) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp, horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally)
                ) {
                    HomeQuickCircle(
                        icon = Icons.Default.History,
                        label = "History",
                        circleColor = Color(0xFF3B2D54),
                        iconTint = Color(0xFFD0BCFF),
                        textColor = state.textSecondary,
                        onClick = { state.currentScreen.value = "history" }
                    )
                    HomeQuickCircle(
                        icon = Icons.Default.FavoriteBorder,
                        label = "Favorites",
                        circleColor = Color(0xFF4A2B29),
                        iconTint = Color(0xFFFFB4AB),
                        textColor = state.textSecondary,
                        onClick = { state.currentScreen.value = "favorites" }
                    )
                    HomeQuickCircle(
                        icon = Icons.Default.TrendingUp,
                        label = "Most pl...",
                        circleColor = Color(0xFF243F29),
                        iconTint = Color(0xFFA5D6A7),
                        textColor = state.textSecondary,
                        onClick = { state.currentScreen.value = "most_played" }
                    )
                    HomeQuickCircle(
                        icon = Icons.Default.Shuffle,
                        label = "Shuffle",
                        circleColor = Color(0xFF1A4144),
                        iconTint = Color(0xFF80CBC4),
                        textColor = state.textSecondary,
                        onClick = { onShuffleAll(songs) }
                    )
                }
            }
        }

        // 2. Large Rounded Showcase Cards Row (matches 00:03 large artwork cards)
        if (state.enabledHomeRows.contains("Showcase")) {
            item {
                val showcaseAlbums = albums.take(8)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (showcaseAlbums.isNotEmpty()) {
                        items(showcaseAlbums) { album ->
                            Box(
                                modifier = Modifier
                                    .size(width = 230.dp, height = 240.dp)
                                    .clip(RoundedCornerShape(26.dp))
                                    .background(state.surfaceColor)
                                    .clickable {
                                        state.selectedAlbum.value = album
                                        state.currentScreen.value = "album_detail"
                                    }
                            ) {
                                AsyncImage(
                                    model = album.albumArtUri,
                                    contentDescription = album.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                                            )
                                        )
                                )
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = album.title,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = album.artist,
                                        color = Color.White.copy(alpha = 0.78f),
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    } else {
                        // Decorative Oto Music Geometric Pastel Placeholder Cards when library is empty
                        val fallbackColors = listOf(
                            Color(0xFF4B3646) to Color(0xFFF3C6D3),
                            Color(0xFF3E3B5B) to Color(0xFFBDB4FE),
                            Color(0xFF4B3D2A) to Color(0xFFFCE1B6)
                        )
                        items(fallbackColors) { (bg, circle) ->
                            Box(
                                modifier = Modifier
                                    .size(width = 230.dp, height = 240.dp)
                                    .clip(RoundedCornerShape(26.dp))
                                    .background(bg),
                                contentAlignment = Alignment.BottomEnd
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(160.dp)
                                        .offset(x = 24.dp, y = 24.dp)
                                        .clip(CircleShape)
                                        .background(circle)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Recently Added Section
        if (state.enabledHomeRows.contains("Recently Added") && songs.isNotEmpty()) {
            item {
                HomeSectionHeader(
                    title = "Recently added",
                    showSeeAll = state.showSeeAllButton.value,
                    textColor = state.textPrimary,
                    accentColor = state.accentColor.value,
                    onSeeAll = { state.selectedBottomTab.intValue = 2 }
                )
            }
            items(songs.take(8)) { song ->
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

        // 4. Top Artists Carousel
        if (state.enabledHomeRows.contains("Top Artists") && artists.isNotEmpty()) {
            item {
                HomeSectionHeader(
                    title = "Artists",
                    showSeeAll = state.showSeeAllButton.value,
                    textColor = state.textPrimary,
                    accentColor = state.accentColor.value,
                    onSeeAll = { state.selectedBottomTab.intValue = 4 }
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(artists.take(10)) { artist ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(104.dp)
                                .clickable {
                                    state.selectedArtist.value = artist
                                    state.currentScreen.value = "artist_detail"
                                }
                        ) {
                            AsyncImage(
                                model = artist.artworkUri,
                                contentDescription = artist.name,
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(CircleShape)
                                    .background(state.surfaceColor),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = artist.name,
                                color = state.textPrimary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeQuickCircle(
    icon: ImageVector,
    label: String,
    circleColor: Color,
    iconTint: Color,
    textColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(circleColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = textColor, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
fun HomeSectionHeader(
    title: String,
    showSeeAll: Boolean,
    textColor: Color,
    accentColor: Color,
    onSeeAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = textColor, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        if (showSeeAll) {
            Text(
                text = "See all",
                color = accentColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onSeeAll() }
            )
        }
    }
}

@Composable
fun OtoAlbumsTab(
    state: OtoUiState,
    onPlaySong: (Song, List<Song>?) -> Unit,
    onShuffleAll: (List<Song>) -> Unit
) {
    val albums = state.albums
    var showSortMenu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = state.surfaceColor,
                    modifier = Modifier.clickable { showSortMenu = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${state.albumSortOrder.value} ↑",
                            color = state.accentColor.value,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                    listOf("NAME", "ARTIST", "YEAR", "SONG_COUNT").forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(sort) },
                            onClick = {
                                state.albumSortOrder.value = sort
                                state.savePrefString("album_sort", sort)
                                showSortMenu = false
                            }
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = state.surfaceColor,
                    modifier = Modifier.clickable { onShuffleAll(state.visibleSongs) }
                ) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = state.textPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).size(18.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = state.surfaceColor,
                    modifier = Modifier.clickable {
                        val nextCols = if (state.albumGridColumns.intValue >= 4) 2 else state.albumGridColumns.intValue + 1
                        state.albumGridColumns.intValue = nextCols
                        state.savePrefInt("album_grid_cols", nextCols)
                    }
                ) {
                    Icon(
                        Icons.Default.GridView,
                        contentDescription = "Grid Size",
                        tint = state.textPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).size(18.dp)
                    )
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(state.albumGridColumns.intValue),
            contentPadding = PaddingValues(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(albums) { album ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            state.selectedAlbum.value = album
                            state.currentScreen.value = "album_detail"
                        }
                ) {
                    AsyncImage(
                        model = album.albumArtUri,
                        contentDescription = album.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(state.surfaceColor),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = album.title,
                        color = state.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = album.artist,
                        color = state.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun OtoSongsTab(
    state: OtoUiState,
    onPlaySong: (Song, List<Song>?) -> Unit,
    onShuffleAll: (List<Song>) -> Unit
) {
    val songs = state.visibleSongs
    var showSortMenu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = state.surfaceColor,
                    modifier = Modifier.clickable { showSortMenu = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val label = state.songSortOrder.value.replace('_', ' ')
                        Text(
                            text = "$label ↓",
                            color = state.accentColor.value,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                    listOf("DATE_MODIFIED", "DATE_ADDED", "NAME", "ARTIST", "DURATION").forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(sort.replace('_', ' ')) },
                            onClick = {
                                state.songSortOrder.value = sort
                                state.savePrefString("song_sort", sort)
                                showSortMenu = false
                            }
                        )
                    }
                }
            }

            IconButton(onClick = { onShuffleAll(songs) }) {
                Icon(Icons.Default.Shuffle, contentDescription = "Shuffle All", tint = state.textPrimary)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(songs, key = { it.id }) { song ->
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
}

@Composable
fun OtoSongListItem(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    state: OtoUiState,
    onClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    Surface(
        color = if (isCurrent) state.surfaceColor.copy(alpha = 0.75f) else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = song.albumArtUri,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(state.surfaceColor),
                contentScale = ContentScale.Crop
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = song.title,
                    color = if (isCurrent) state.accentColor.value else state.textPrimary,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${song.artist}  •  ${formatDuration(song.duration)}",
                    color = state.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isCurrent) {
                Icon(
                    imageVector = Icons.Default.Equalizer,
                    contentDescription = "Playing",
                    tint = state.accentColor.value,
                    modifier = Modifier.padding(end = 4.dp).size(20.dp)
                )
            }
            IconButton(onClick = onMoreClick) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = state.textSecondary)
            }
        }
    }
}

@Composable
fun OtoPlaylistsTab(state: OtoUiState) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (state.playlists.isEmpty()) {
            // Matches 01:41 in video ("No playlists found")
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlaylistAdd,
                    contentDescription = null,
                    tint = state.accentColor.value,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.width(20.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No playlists found",
                        color = state.textPrimary,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "You can either create a new playlist or import a playlist by clicking on the '+' button",
                        color = state.textSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp)
            ) {
                items(state.playlists) { playlist ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = state.surfaceColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .clickable {
                                state.selectedPlaylist.value = playlist
                                state.currentScreen.value = "playlist_detail"
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(state.accentColor.value.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.QueueMusic, contentDescription = null, tint = state.accentColor.value)
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 14.dp)
                            ) {
                                Text(playlist.name, color = state.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text("${playlist.songIds.size} Songs", color = state.textSecondary, fontSize = 12.sp)
                            }
                            IconButton(onClick = { state.deletePlaylist(playlist) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = state.textSecondary)
                            }
                        }
                    }
                }
            }
        }

        // Rounded-Square '+' Floating Button at bottom right (matches 01:41)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = state.accentColor.value.copy(alpha = 0.35f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(18.dp)
                .size(56.dp)
                .clickable { state.showCreatePlaylistDialog.value = true }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Add, contentDescription = "Create Playlist", tint = state.textPrimary)
            }
        }
    }
}

@Composable
fun OtoArtistsTab(state: OtoUiState) {
    val artists = state.artists
    var showSortMenu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = state.surfaceColor,
                    modifier = Modifier.clickable { showSortMenu = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${state.artistSortOrder.value} ↑",
                            color = state.accentColor.value,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                    listOf("NAME", "SONG_COUNT").forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(sort) },
                            onClick = {
                                state.artistSortOrder.value = sort
                                state.savePrefString("artist_sort", sort)
                                showSortMenu = false
                            }
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = state.surfaceColor,
                modifier = Modifier.clickable {
                    val nextCols = if (state.artistGridColumns.intValue >= 5) 3 else state.artistGridColumns.intValue + 1
                    state.artistGridColumns.intValue = nextCols
                    state.savePrefInt("artist_grid_cols", nextCols)
                }
            ) {
                Icon(
                    Icons.Default.GridView,
                    contentDescription = "Grid Size",
                    tint = state.textPrimary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).size(18.dp)
                )
            }
        }

        // Circular Artist Grid (matches 01:43 in video)
        LazyVerticalGrid(
            columns = GridCells.Fixed(state.artistGridColumns.intValue),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(artists) { artist ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            state.selectedArtist.value = artist
                            state.currentScreen.value = "artist_detail"
                        }
                ) {
                    AsyncImage(
                        model = artist.artworkUri,
                        contentDescription = artist.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .background(state.surfaceColor),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = artist.name,
                        color = state.textPrimary,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun OtoFoldersScreen(
    state: OtoUiState,
    onOpenDrawer: () -> Unit,
    onPlaySong: (Song, List<Song>?) -> Unit,
    onShuffleAll: (List<Song>) -> Unit
) {
    val folders = state.folders
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar matching 00:09 ("Files / 3 folders")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu", tint = state.textPrimary)
                }
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("Files", color = state.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("${folders.size} folders", color = state.textSecondary, fontSize = 12.sp)
                }
                IconButton(onClick = { state.currentScreen.value = "equalizer" }) {
                    Icon(Icons.Default.Equalizer, contentDescription = "Equalizer", tint = state.textSecondary)
                }
            }

            Text(
                text = "0",
                color = state.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(folders) { folder ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                state.selectedFolder.value = folder
                                state.currentScreen.value = "folder_detail"
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = Color(0xFFD6CEE2),
                            modifier = Modifier.size(40.dp)
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 16.dp)
                        ) {
                            Text(folder.name, color = state.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            val subText = if (folder.subFolderCount > 0) {
                                "${folder.subFolderCount} folders • ${folder.songs.size} Songs"
                            } else {
                                "${folder.songs.size} Songs"
                            }
                            Text(subText, color = state.textSecondary, fontSize = 12.sp)
                        }
                        IconButton(onClick = {
                            if (folder.songs.isNotEmpty()) {
                                onPlaySong(folder.songs.first(), folder.songs)
                            }
                        }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Folder Actions", tint = state.textSecondary)
                        }
                    }
                }
            }
        }

        // Bottom-right Play FAB (matches 00:09 in video)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF5D3A43),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(18.dp)
                .size(56.dp)
                .clickable { onShuffleAll(state.visibleSongs) }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play All", tint = Color(0xFFFFD9DE))
            }
        }
    }
}

@Composable
fun OtoPlayingQueueScreen(
    state: OtoUiState,
    onPlaySong: (Song, List<Song>?) -> Unit,
    onRemoveFromQueue: (Int) -> Unit
) {
    val current = state.currentSong.value
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(state.backgroundColor)
    ) {
        // Current song header at top (matches 00:13 & 02:17)
        if (current != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { state.currentScreen.value = "player" }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = current.albumArtUri,
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(current.title, color = state.textPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(current.artist, color = state.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { state.currentScreen.value = "main" }) {
                    Icon(Icons.Default.Close, contentDescription = "Close Queue", tint = state.textSecondary)
                }
            }
        }

        // Rounded "Up next" Sheet Card (matches 00:13 in video)
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = state.surfaceColor,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "Up next",
                    color = state.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    itemsIndexed(state.queue) { index, song ->
                        val isCurrent = current?.id == song.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPlaySong(song, state.queue.toList()) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(state.cardColor),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = song.albumArtUri,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.45f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        color = if (isCurrent) state.accentColor.value else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp)
                            ) {
                                Text(
                                    text = song.title,
                                    color = if (isCurrent) state.accentColor.value else state.textPrimary,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${song.artist}  •  ${formatDuration(song.duration)}",
                                    color = state.textSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { state.showMoreOptionsSheet.value = song }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More", tint = state.textSecondary)
                            }
                            IconButton(onClick = { onRemoveFromQueue(index) }) {
                                Icon(Icons.Default.DragHandle, contentDescription = "Remove / Reorder", tint = state.textSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0L)
    val sec = totalSec % 60
    val min = (totalSec / 60) % 60
    val hrs = totalSec / 3600
    return if (hrs > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hrs, min, sec)
    } else {
        String.format(Locale.US, "%02d:%02d", min, sec)
    }
}
