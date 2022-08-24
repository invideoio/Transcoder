@file:Suppress("ReturnCount")

package com.otaliastudios.transcoder.internal.audio

import android.media.MediaFormat
import android.media.MediaFormat.KEY_CHANNEL_COUNT
import android.media.MediaFormat.KEY_SAMPLE_RATE
import android.view.Surface
import com.otaliastudios.transcoder.internal.audio.remix.AudioRemixer
import com.otaliastudios.transcoder.internal.codec.DecoderChannel
import com.otaliastudios.transcoder.internal.codec.DecoderData
import com.otaliastudios.transcoder.internal.codec.DecoderTimerData
import com.otaliastudios.transcoder.internal.codec.EncoderChannel
import com.otaliastudios.transcoder.internal.codec.EncoderData
import com.otaliastudios.transcoder.internal.pipeline.QueuedStep
import com.otaliastudios.transcoder.internal.pipeline.State
import com.otaliastudios.transcoder.internal.utils.Logger
import java.util.concurrent.atomic.AtomicInteger

/**
 * Performs audio rendering, from decoder output to encoder input, applying sample rate conversion,
 * remixing, stretching using sonic.
 */
class SonicAudioEngine(
    private val targetFormat: MediaFormat,
    private val stretch : Float = 1.0f
) : QueuedStep<DecoderData, DecoderChannel, EncoderData, EncoderChannel>(), DecoderChannel {

    companion object {
        private val ID = AtomicInteger(0)
    }
    private val log = Logger("AudioEngine(${ID.getAndIncrement()})")

    override val channel = this
    private val buffers = ShortBuffers()

    private val MediaFormat.sampleRate get() = getInteger(KEY_SAMPLE_RATE)
    private val MediaFormat.channels get() = getInteger(KEY_CHANNEL_COUNT)

    private lateinit var rawFormat: MediaFormat
    private lateinit var chunks: ChunkQueue
    private lateinit var remixer: AudioRemixer
    private lateinit var sonicExo: SonicAudioProcessor
    override fun handleSourceFormat(sourceFormat: MediaFormat): Surface? = null

    override fun handleRawFormat(rawFormat: MediaFormat) {
        log.i("handleRawFormat($rawFormat)")
        this.rawFormat = rawFormat
        remixer = AudioRemixer[rawFormat.channels, targetFormat.channels]
        chunks = ChunkQueue(rawFormat.sampleRate, rawFormat.channels)
        sonicExo = SonicAudioProcessor(rawFormat.sampleRate, rawFormat.channels, stretch,1f, targetFormat.sampleRate)
    }

    override fun enqueueEos(data: DecoderData) {
        log.i("enqueueEos()")
        data.release(false)
        chunks.enqueueEos()
        sonicExo.queueEndOfStream()
    }

    override fun enqueue(data: DecoderData) {
        val stretch = (data as? DecoderTimerData)?.timeStretch ?: 1.0
        log.v("Enqueue: ${data.timeUs}   $this")
        chunks.enqueue(data.buffer.asShortBuffer(), data.timeUs, stretch) {
            data.release(false)
        }
    }

    override fun drain(): State<EncoderData> {
        if (chunks.isEmpty()) {
            log.i("drain(): no chunks, waiting...")
            return State.Wait
        }
        val (outBytes, outId) = next.buffer() ?: return run {
            log.i("drain(): no next buffer, waiting...")
            State.Wait
        }
        val outBuffer = outBytes.asShortBuffer()
        return chunks.drain(
            eos = State.Eos(EncoderData(outBytes, outId, 0))
        ) { inBuffer, timeUs, stretch ->

            sonicExo.queueInput(inBuffer)

            val stretchBuffer = buffers.acquire("stretch", outBuffer.capacity())

            sonicExo.getOutput(stretchBuffer)
            stretchBuffer.flip()

            // Remix
            remixer.remix(stretchBuffer, outBuffer)
            outBuffer.flip()

            // Adjust position and dispatch.
            outBytes.clear()
            outBytes.limit(outBuffer.limit() * BYTES_PER_SHORT)
            outBytes.position(outBuffer.position() * BYTES_PER_SHORT)

            log.v("Drain: ts: $timeUs  size: ${outBuffer.limit()}")
            State.Ok(EncoderData(outBytes, outId, timeUs))
        }
    }
}
