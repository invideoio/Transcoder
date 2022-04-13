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
import com.otaliastudios.transcoder.resample.AudioResampler
import com.otaliastudios.transcoder.stretch.AudioStretcher
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Performs audio rendering, from decoder output to encoder input, applying sample rate conversion,
 * remixing, stretching.
 */
class AudioEngine(
    private val stretcher: AudioStretcher,
    private val resampler: AudioResampler,
    private val targetFormat: MediaFormat
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

    override fun handleSourceFormat(sourceFormat: MediaFormat): Surface? = null

    override fun handleRawFormat(rawFormat: MediaFormat) {
        log.i("handleRawFormat($rawFormat)")
        this.rawFormat = rawFormat
        remixer = AudioRemixer[rawFormat.channels, targetFormat.channels]
        chunks = ChunkQueue(rawFormat.sampleRate, rawFormat.channels)
        createStream(rawFormat.sampleRate, targetFormat.sampleRate, targetFormat.channels)
    }

    override fun enqueueEos(data: DecoderData) {
        log.i("enqueueEos()")
        data.release(false)
        chunks.enqueueEos()
        destroyStream()
    }

    override fun enqueue(data: DecoderData) {
        val stretch = (data as? DecoderTimerData)?.timeStretch ?: 1.0
        log.v("Enqueue: ${data.timeUs}   $this")
        chunks.enqueue(data.buffer.asShortBuffer(), data.timeUs, stretch) {
            data.release(false)
        }
    }
    val outputFloatBuffer = ByteBuffer.allocateDirect(32096 * Float.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .asFloatBuffer()

    override fun drain(): State<EncoderData> {
        if (chunks.isEmpty()) {
            log.i("drain(): no chunks, waiting...")
            return State.Wait
        }
        val (outBytes, outId) = next.buffer() ?: return run {
            log.i("drain(): no next buffer, waiting...")
            State.Wait
        }
        val outShortBuffer = outBytes.asShortBuffer()
        return chunks.drain(
            eos = State.Eos(EncoderData(outBytes, outId, 0))
        ) { inBuffer, timeUs, stretch ->
            val outSize = outShortBuffer.remaining()
            val inSize = inBuffer.remaining()

            // Compute the desired output size based on all steps that we'll go through
            var desiredOutSize = ceil(inSize * stretch) // stretch
            desiredOutSize = remixer.getRemixedSize(desiredOutSize.toInt()).toDouble() // remix
            desiredOutSize = ceil(desiredOutSize * targetFormat.sampleRate / rawFormat.sampleRate) // resample

            // See if we have enough room to process the whole input
            val processableSize = if (desiredOutSize <= outSize) inSize else {
                val factor = desiredOutSize / inSize
                floor(outSize / factor).toInt()
            }
            inBuffer.limit(inBuffer.position() + processableSize)

            // Stretching
            val stretchSize = ceil(processableSize * stretch)
            val stretchBuffer = buffers.acquire("stretch", stretchSize.toInt())
            stretcher.stretch(inBuffer, stretchBuffer, rawFormat.channels)
            stretchBuffer.flip()

            // Remix
            val remixSize = remixer.getRemixedSize(stretchSize.toInt())
            val remixShortBuffer = buffers.acquire("remix", remixSize)
            remixer.remix(stretchBuffer, remixShortBuffer)
            remixShortBuffer.flip()

            // Resample
//            resampler.resample(
//                remixShortBuffer,
//                rawFormat.sampleRate,
//                outShortBuffer,
//                targetFormat.sampleRate,
//                targetFormat.channels
//            )
            log.v("Drain to Resampler: ts: $timeUs  limit: ${remixShortBuffer.limit()} capacity: ${remixShortBuffer.capacity()}")

            val size = remixShortBuffer.limit()
            val input = ByteBuffer.allocateDirect(size * Float.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer()
            for(i in 0 until size) {
                input.put(remixShortBuffer[i].toFloat())
            }

            outputFloatBuffer.position(0)

            val dataRead = getSampledByteBuffer(targetFormat.channels,input, outputFloatBuffer, remixShortBuffer.limit() / targetFormat.channels)

            outputFloatBuffer.limit(dataRead)


//            val outShortBuffer1 = outBytes.asShortBuffer()
            for(i in 0 until outputFloatBuffer.limit()) {
                outShortBuffer.put(outputFloatBuffer[i].toInt().toShort())
            }
            outShortBuffer.flip()

            outBytes.clear()
            // Adjust position and dispatch.
            outBytes.limit(outShortBuffer.limit() * Short.SIZE_BYTES)
            outBytes.position(outShortBuffer.position() * Float.SIZE_BYTES)

            State.Ok(EncoderData(outBytes, outId, timeUs))
        }
    }

    // Creates and starts Oboe stream to play audio
    private external fun createStream(inputSampleRate: Int, outputSampleRate: Int, channelCount: Int) : Int

    // Closes and destroys Oboe stream when app goes out of focus
    private external fun destroyStream()

    private external fun getSampledByteBuffer(channelCount: Int, inputBuffers: FloatBuffer, outputBuffers: FloatBuffer, inputFrames:Int) : Int
}
