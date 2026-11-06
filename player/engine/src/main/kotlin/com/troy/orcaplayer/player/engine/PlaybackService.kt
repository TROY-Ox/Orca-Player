package com.troy.orcaplayer.player.engine

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Keeps playback alive through rotation, folding, picture-in-picture and background play,
 * and exposes the player to notifications, the lock screen, Bluetooth buttons and TV remotes.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val engine = PlaybackEngine.get(this)
        val builder = MediaSession.Builder(this, engine.player)
        sessionActivity()?.let { builder.setSessionActivity(it) }
        session = builder.build()
        engine.onPlayerReplaced = { next -> session?.player = next }
    }

    /** Tapping the notification reopens the player screen of the host app. */
    private fun sessionActivity(): PendingIntent? {
        val intent = Intent(ACTION_OPEN_PLAYER).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (packageManager.resolveActivity(intent, 0) == null) return null
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            player?.pause()
            stopSelf()
        }
    }

    override fun onDestroy() {
        PlaybackEngine.peek()?.onPlayerReplaced = null
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        /** The host app's player activity handles this action. */
        const val ACTION_OPEN_PLAYER = "com.troy.orcaplayer.action.OPEN_PLAYER"
    }
}
