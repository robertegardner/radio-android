package io.rg2.radio.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Makes pause/resume behave correctly for a continuous live stream.
 *
 * The Icecast feed is endless. With a plain progressive player, pause just
 * holds the buffer, so resuming replays buffered audio from the pause point
 * instead of jumping back to live. Here, pause [Player.stop]s — discarding the
 * buffer and dropping the connection — and play re-[Player.prepare]s when
 * needed, reconnecting at the live edge.
 *
 * Wrapping at the player level (rather than handling it in the UI) means the
 * lock-screen and notification transport controls get the same behavior.
 */
class LiveStreamPlayer(wrapped: Player) : ForwardingPlayer(wrapped) {

    override fun play() {
        if (playbackState == Player.STATE_IDLE) prepare()
        super.play()
    }

    override fun pause() {
        super.pause()
        // Drop the buffered audio + connection so the next play() rejoins live.
        stop()
    }
}
