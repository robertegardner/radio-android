package io.rg2.radio.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

/** Smoothed per-channel audio levels, 0..1. A mono source reports L == R. */
data class StereoLevels(val left: Float = 0f, val right: Float = 0f)

/**
 * Per-channel level tap on ExoPlayer's decoded PCM, for the L/R meters.
 *
 * This is deliberately NOT part of [AudioTapHub]: the hub wraps the one
 * `android.media.audiofx.Visualizer` (mono downmix, needs RECORD_AUDIO),
 * while this taps the player's own audio pipeline via [TeeAudioProcessor] —
 * true stereo, no permission, and it can't destabilize the Visualizer. The
 * ONE-Visualizer rule is untouched.
 *
 * Runs on the playback thread; publishes a fast-attack/slow-decay envelope
 * into [out] (a StateFlow, safe to write cross-thread). Note the tap sits
 * before AudioTrack volume, so duck-on-talk does not lower the meters.
 */
@UnstableApi
class StereoLevelTap(
    private val out: MutableStateFlow<StereoLevels>,
) : TeeAudioProcessor.AudioBufferSink {

    private var channelCount = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var envLeft = 0f
    private var envRight = 0f

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.channelCount = channelCount
        this.encoding = encoding
        envLeft = 0f
        envRight = 0f
        out.value = StereoLevels()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        // The pipeline output is 16-bit PCM on this stream (MP3 decode);
        // anything else just leaves the meters at rest rather than lying.
        if (encoding != C.ENCODING_PCM_16BIT || channelCount < 1) return
        val samples = buffer.asShortBuffer() // view; doesn't consume the buffer
        var peakLeft = 0
        var peakRight = 0
        val frames = samples.remaining() / channelCount
        var i = 0
        repeat(frames) {
            val l = abs(samples.get(i).toInt())
            if (l > peakLeft) peakLeft = l
            if (channelCount >= 2) {
                val r = abs(samples.get(i + 1).toInt())
                if (r > peakRight) peakRight = r
            }
            i += channelCount
        }
        if (channelCount < 2) peakRight = peakLeft

        // Instant attack, ~200 ms decay to 10% at MP3-frame buffer cadence.
        envLeft = max(peakLeft / 32768f, envLeft * DECAY)
        envRight = max(peakRight / 32768f, envRight * DECAY)
        out.value = StereoLevels(envLeft, envRight)
    }

    private companion object {
        const val DECAY = 0.75f
    }
}

/**
 * The default renderers with a [TeeAudioProcessor] feeding [sink] spliced into
 * the audio pipeline — how the L/R meters see the decoded stereo PCM.
 */
@UnstableApi
class TapRenderersFactory(
    context: Context,
    private val sink: TeeAudioProcessor.AudioBufferSink,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(TeeAudioProcessor(sink)),
            )
            .build()
}
