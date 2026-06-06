package io.rg2.radio.wear.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Live-stream pause semantics for the watch (same as the phone's): the Icecast
 * feed is endless, so pause [Player.stop]s to drop the buffer + connection and
 * play re-[Player.prepare]s, rejoining the live edge instead of replaying
 * buffered audio. Wrapping at the player level gives the system media controls
 * the same behavior.
 */
class LiveStreamPlayer(wrapped: Player) : ForwardingPlayer(wrapped) {

    override fun play() {
        if (playbackState == Player.STATE_IDLE) prepare()
        super.play()
    }

    override fun pause() {
        super.pause()
        stop()
    }
}
