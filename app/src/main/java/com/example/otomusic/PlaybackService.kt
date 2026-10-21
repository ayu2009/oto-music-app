@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.example.otomusic

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * 100% Google Play Services-Free Media3 Background Service.
 * Provides system media notifications, hardware headset controls, audio focus handling,
 * gapless playback, and custom notification actions without any GMS dependencies.
 */
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    companion object {
        const val ACTION_FAVORITE = "ACTION_FAVORITE"
        const val ACTION_CLOSE = "ACTION_CLOSE"
        const val ACTION_GET_AUDIO_SESSION_ID = "ACTION_GET_AUDIO_SESSION_ID"
        const val KEY_AUDIO_SESSION_ID = "KEY_AUDIO_SESSION_ID"
    }

    override fun onCreate() {
        super.onCreate()

        val prefs = getSharedPreferences("oto_prefs", MODE_PRIVATE)
        val handleAudioFocus = prefs.getBoolean("lower_volume_on_focal_loss", true)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, handleAudioFocus)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.repeatMode = Player.REPEAT_MODE_OFF

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val favButton = CommandButton.Builder()
            .setDisplayName("Favorite")
            .setSessionCommand(SessionCommand(ACTION_FAVORITE, Bundle.EMPTY))
            .setIconResId(android.R.drawable.btn_star_big_off)
            .build()

        val closeButton = CommandButton.Builder()
            .setDisplayName("Close")
            .setSessionCommand(SessionCommand(ACTION_CLOSE, Bundle.EMPTY))
            .setIconResId(android.R.drawable.ic_menu_close_clear_cancel)
            .build()

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCustomLayout(listOf(favButton, closeButton))
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        val currentPlayer = mediaSession?.player
        if (currentPlayer == null || !currentPlayer.playWhenReady || currentPlayer.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
