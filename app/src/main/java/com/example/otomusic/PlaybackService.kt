package com.example.otomusic

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()

        val favButton = CommandButton.Builder()
            .setDisplayName("Favorite")
            .setSessionCommand(SessionCommand("ACTION_FAVORITE", Bundle.EMPTY))
            .setIconResId(android.R.drawable.star_off)
            .build()

        val closeButton = CommandButton.Builder()
            .setDisplayName("Close")
            .setSessionCommand(SessionCommand("ACTION_CLOSE", Bundle.EMPTY))
            .setIconResId(android.R.drawable.ic_menu_close_clear_cancel)
            .build()

        mediaSession = MediaSession.Builder(this, player)
            .setCustomLayout(listOf(favButton, closeButton))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    val result = super.onConnect(session, controller)
                    val cmds = result.availableSessionCommands.buildUpon()
                        .add(SessionCommand("ACTION_FAVORITE", Bundle.EMPTY))
                        .add(SessionCommand("ACTION_CLOSE", Bundle.EMPTY))
                        .build()
                    return MediaSession.ConnectionResult.accept(cmds, result.availablePlayerCommands)
                }
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == "ACTION_CLOSE") {
                        player.stop()
                        player.clearMediaItems()
                        stopSelf()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
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
