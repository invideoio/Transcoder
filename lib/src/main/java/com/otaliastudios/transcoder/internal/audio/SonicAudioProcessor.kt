package com.otaliastudios.transcoder.internal.audio

import java.nio.ShortBuffer
import java.util.Arrays

/**
 * Sonic audio stream processor for time/pitch stretching.
 *
 *
 * Based on https://github.com/waywardgeek/sonic.
 */
/* package */
internal class SonicAudioProcessor(
    private val inputSampleRateHz: Int,
    private val channelCount: Int,
    private val speed: Float,
    private val pitch: Float,
    outputSampleRateHz: Int
) {
    private val rate: Float
    private val minPeriod: Int
    private val maxPeriod: Int
    private val maxRequiredFrameCount: Int
    private val downSampleBuffer: ShortArray
    private var inputBuffer: ShortArray
    private var inputFrameCount = 0
    private var outputBuffer: ShortArray
    private var outputFrameCount = 0
    private var pitchBuffer: ShortArray
    private var pitchFrameCount = 0
    private var oldRatePosition = 0
    private var newRatePosition = 0
    private var remainingInputToCopyFrameCount = 0
    private var prevPeriod = 0
    private var prevMinDiff = 0
    private var minDiff = 0
    private var maxDiff = 0

    /**
     * Creates a new Sonic audio stream processor.
     *
     * @param inputSampleRateHz The sample rate of input audio, in hertz.
     * @param channelCount The number of channels in the input audio.
     * @param speed The speedup factor for output audio.
     * @param pitch The pitch factor for output audio.
     * @param outputSampleRateHz The sample rate for output audio, in hertz.
     */
    init {
        rate = inputSampleRateHz.toFloat() / outputSampleRateHz
        minPeriod = inputSampleRateHz / MAXIMUM_PITCH
        maxPeriod = inputSampleRateHz / MINIMUM_PITCH
        maxRequiredFrameCount = 2 * maxPeriod
        downSampleBuffer = ShortArray(maxRequiredFrameCount)
        inputBuffer = ShortArray(maxRequiredFrameCount * channelCount)
        outputBuffer = ShortArray(maxRequiredFrameCount * channelCount)
        pitchBuffer = ShortArray(maxRequiredFrameCount * channelCount)
    }

    /**
     * Returns the number of bytes that have been input, but will not be processed until more input
     * data is provided.
     */
    val pendingInputBytes: Int
        get() = inputFrameCount * channelCount * BYTES_PER_SAMPLE

    /**
     * Queues remaining data from `buffer`, and advances its position by the number of bytes
     * consumed.
     *
     * @param buffer A [ShortBuffer] containing input data between its position and limit.
     */
    fun queueInput(buffer: ShortBuffer) {
        val framesToWrite = buffer.remaining() / channelCount
        val bytesToWrite = framesToWrite * channelCount * 2
        inputBuffer = ensureSpaceForAdditionalFrames(inputBuffer, inputFrameCount, framesToWrite)
        buffer[inputBuffer, inputFrameCount * channelCount, bytesToWrite / 2]
        inputFrameCount += framesToWrite
        processStreamInput()
    }

    /**
     * Gets available output, outputting to the start of `buffer`. The buffer's position will be
     * advanced by the number of bytes written.
     *
     * @param buffer A [ShortBuffer] into which output will be written.
     */
    fun getOutput(buffer: ShortBuffer) {
        val framesToRead = Math.min(buffer.remaining() / channelCount, outputFrameCount)
        buffer.put(outputBuffer, 0, framesToRead * channelCount)
        outputFrameCount -= framesToRead
        System.arraycopy(
            outputBuffer,
            framesToRead * channelCount,
            outputBuffer,
            0,
            outputFrameCount * channelCount
        )
    }

    /**
     * Forces generating output using whatever data has been queued already. No extra delay will be
     * added to the output, but flushing in the middle of words could introduce distortion.
     */
    fun queueEndOfStream() {
        val remainingFrameCount = inputFrameCount
        val s = speed / pitch
        val r = rate * pitch
        val expectedOutputFrames =
            outputFrameCount + ((remainingFrameCount / s + pitchFrameCount) / r + 0.5f).toInt()

        // Add enough silence to flush both input and pitch buffers.
        inputBuffer = ensureSpaceForAdditionalFrames(
            inputBuffer, inputFrameCount, remainingFrameCount + 2 * maxRequiredFrameCount
        )
        for (xSample in 0 until 2 * maxRequiredFrameCount * channelCount) {
            inputBuffer[remainingFrameCount * channelCount + xSample] = 0
        }
        inputFrameCount += 2 * maxRequiredFrameCount
        processStreamInput()
        // Throw away any extra frames we generated due to the silence we added.
        if (outputFrameCount > expectedOutputFrames) {
            outputFrameCount = expectedOutputFrames
        }
        // Empty input and pitch buffers.
        inputFrameCount = 0
        remainingInputToCopyFrameCount = 0
        pitchFrameCount = 0
    }

    /** Clears state in preparation for receiving a new stream of input buffers.  */
    fun flush() {
        inputFrameCount = 0
        outputFrameCount = 0
        pitchFrameCount = 0
        oldRatePosition = 0
        newRatePosition = 0
        remainingInputToCopyFrameCount = 0
        prevPeriod = 0
        prevMinDiff = 0
        minDiff = 0
        maxDiff = 0
    }

    /** Returns the size of output that can be read with [.getOutput], in bytes.  */
    val outputSize: Int
        get() = outputFrameCount * channelCount * BYTES_PER_SAMPLE
    // Internal methods.
    /**
     * Returns `buffer` or a copy of it, such that there is enough space in the returned buffer
     * to store `newFrameCount` additional frames.
     *
     * @param buffer The buffer.
     * @param frameCount The number of frames already in the buffer.
     * @param additionalFrameCount The number of additional frames that need to be stored in the
     * buffer.
     * @return A buffer with enough space for the additional frames.
     */
    private fun ensureSpaceForAdditionalFrames(
        buffer: ShortArray, frameCount: Int, additionalFrameCount: Int
    ): ShortArray {
        val currentCapacityFrames = buffer.size / channelCount
        return if (frameCount + additionalFrameCount <= currentCapacityFrames) {
            buffer
        } else {
            val newCapacityFrames = 3 * currentCapacityFrames / 2 + additionalFrameCount
            Arrays.copyOf(buffer, newCapacityFrames * channelCount)
        }
    }

    private fun removeProcessedInputFrames(positionFrames: Int) {
        val remainingFrames = inputFrameCount - positionFrames
        System.arraycopy(
            inputBuffer,
            positionFrames * channelCount,
            inputBuffer,
            0,
            remainingFrames * channelCount
        )
        inputFrameCount = remainingFrames
    }

    private fun copyToOutput(samples: ShortArray, positionFrames: Int, frameCount: Int) {
        outputBuffer = ensureSpaceForAdditionalFrames(outputBuffer, outputFrameCount, frameCount)
        System.arraycopy(
            samples,
            positionFrames * channelCount,
            outputBuffer,
            outputFrameCount * channelCount,
            frameCount * channelCount
        )
        outputFrameCount += frameCount
    }

    private fun copyInputToOutput(positionFrames: Int): Int {
        val frameCount = Math.min(maxRequiredFrameCount, remainingInputToCopyFrameCount)
        copyToOutput(inputBuffer, positionFrames, frameCount)
        remainingInputToCopyFrameCount -= frameCount
        return frameCount
    }

    private fun downSampleInput(samples: ShortArray, position: Int, skip: Int) {
        // If skip is greater than one, average skip samples together and write them to the down-sample
        // buffer. If channelCount is greater than one, mix the channels together as we down sample.
        var position = position
        val frameCount = maxRequiredFrameCount / skip
        val samplesPerValue = channelCount * skip
        position *= channelCount
        for (i in 0 until frameCount) {
            var value = 0
            for (j in 0 until samplesPerValue) {
                value += samples[position + i * samplesPerValue + j].toInt()
            }
            value /= samplesPerValue
            downSampleBuffer[i] = value.toShort()
        }
    }

    private fun findPitchPeriodInRange(
        samples: ShortArray,
        position: Int,
        minPeriod: Int,
        maxPeriod: Int
    ): Int {
        // Find the best frequency match in the range, and given a sample skip multiple. For now, just
        // find the pitch of the first channel.
        var position = position
        var bestPeriod = 0
        var worstPeriod = 255
        var minDiff = 1
        var maxDiff = 0
        position *= channelCount
        for (period in minPeriod..maxPeriod) {
            var diff = 0
            for (i in 0 until period) {
                val sVal = samples[position + i]
                val pVal = samples[position + period + i]
                diff += Math.abs(sVal - pVal)
            }
            // Note that the highest number of samples we add into diff will be less than 256, since we
            // skip samples. Thus, diff is a 24 bit number, and we can safely multiply by numSamples
            // without overflow.
            if (diff * bestPeriod < minDiff * period) {
                minDiff = diff
                bestPeriod = period
            }
            if (diff * worstPeriod > maxDiff * period) {
                maxDiff = diff
                worstPeriod = period
            }
        }
        this.minDiff = minDiff / bestPeriod
        this.maxDiff = maxDiff / worstPeriod
        return bestPeriod
    }

    /**
     * Returns whether the previous pitch period estimate is a better approximation, which can occur
     * at the abrupt end of voiced words.
     */
    private fun previousPeriodBetter(minDiff: Int, maxDiff: Int): Boolean {
        if (minDiff == 0 || prevPeriod == 0) {
            return false
        }
        if (maxDiff > minDiff * 3) {
            // Got a reasonable match this period.
            return false
        }
        return if (minDiff * 2 <= prevMinDiff * 3) {
            // Mismatch is not that much greater this period.
            false
        } else true
    }

    private fun findPitchPeriod(samples: ShortArray, position: Int): Int {
        // Find the pitch period. This is a critical step, and we may have to try multiple ways to get a
        // good answer. This version uses AMDF. To improve speed, we down sample by an integer factor
        // get in the 11 kHz range, and then do it again with a narrower frequency range without down
        // sampling.
        var period: Int
        val retPeriod: Int
        val skip = if (inputSampleRateHz > AMDF_FREQUENCY) inputSampleRateHz / AMDF_FREQUENCY else 1
        if (channelCount == 1 && skip == 1) {
            period = findPitchPeriodInRange(samples, position, minPeriod, maxPeriod)
        } else {
            downSampleInput(samples, position, skip)
            period = findPitchPeriodInRange(downSampleBuffer, 0, minPeriod / skip, maxPeriod / skip)
            if (skip != 1) {
                period *= skip
                var minP = period - skip * 4
                var maxP = period + skip * 4
                if (minP < minPeriod) {
                    minP = minPeriod
                }
                if (maxP > maxPeriod) {
                    maxP = maxPeriod
                }
                period = if (channelCount == 1) {
                    findPitchPeriodInRange(samples, position, minP, maxP)
                } else {
                    downSampleInput(samples, position, 1)
                    findPitchPeriodInRange(downSampleBuffer, 0, minP, maxP)
                }
            }
        }
        retPeriod = if (previousPeriodBetter(minDiff, maxDiff)) {
            prevPeriod
        } else {
            period
        }
        prevMinDiff = minDiff
        prevPeriod = period
        return retPeriod
    }

    private fun moveNewSamplesToPitchBuffer(originalOutputFrameCount: Int) {
        val frameCount = outputFrameCount - originalOutputFrameCount
        pitchBuffer = ensureSpaceForAdditionalFrames(pitchBuffer, pitchFrameCount, frameCount)
        System.arraycopy(
            outputBuffer,
            originalOutputFrameCount * channelCount,
            pitchBuffer,
            pitchFrameCount * channelCount,
            frameCount * channelCount
        )
        outputFrameCount = originalOutputFrameCount
        pitchFrameCount += frameCount
    }

    private fun removePitchFrames(frameCount: Int) {
        if (frameCount == 0) {
            return
        }
        System.arraycopy(
            pitchBuffer,
            frameCount * channelCount,
            pitchBuffer,
            0,
            (pitchFrameCount - frameCount) * channelCount
        )
        pitchFrameCount -= frameCount
    }

    private fun interpolate(
        `in`: ShortArray,
        inPos: Int,
        oldSampleRate: Int,
        newSampleRate: Int
    ): Short {
        val left = `in`[inPos]
        val right = `in`[inPos + channelCount]
        val position = newRatePosition * oldSampleRate
        val leftPosition = oldRatePosition * newSampleRate
        val rightPosition = (oldRatePosition + 1) * newSampleRate
        val ratio = rightPosition - position
        val width = rightPosition - leftPosition
        return ((ratio * left + (width - ratio) * right) / width).toShort()
    }

    private fun adjustRate(rate: Float, originalOutputFrameCount: Int) {
        if (outputFrameCount == originalOutputFrameCount) {
            return
        }
        var newSampleRate = (inputSampleRateHz / rate).toInt()
        var oldSampleRate = inputSampleRateHz
        // Set these values to help with the integer math.
        while (newSampleRate > 1 shl 14 || oldSampleRate > 1 shl 14) {
            newSampleRate /= 2
            oldSampleRate /= 2
        }
        moveNewSamplesToPitchBuffer(originalOutputFrameCount)
        // Leave at least one pitch sample in the buffer.
        for (position in 0 until pitchFrameCount - 1) {
            while ((oldRatePosition + 1) * newSampleRate > newRatePosition * oldSampleRate) {
                outputBuffer = ensureSpaceForAdditionalFrames(
                    outputBuffer, outputFrameCount,  /* additionalFrameCount= */1
                )
                for (i in 0 until channelCount) {
                    outputBuffer[outputFrameCount * channelCount + i] = interpolate(
                        pitchBuffer,
                        position * channelCount + i,
                        oldSampleRate,
                        newSampleRate
                    )
                }
                newRatePosition++
                outputFrameCount++
            }
            oldRatePosition++
            if (oldRatePosition == oldSampleRate) {
                oldRatePosition = 0
                //        Assertions.checkState(newRatePosition == newSampleRate);
                newRatePosition = 0
            }
        }
        removePitchFrames(pitchFrameCount - 1)
    }

    private fun skipPitchPeriod(
        samples: ShortArray,
        position: Int,
        speed: Float,
        period: Int
    ): Int {
        // Skip over a pitch period, and copy period/speed samples to the output.
        val newFrameCount: Int
        if (speed >= 2.0f) {
            newFrameCount = (period / (speed - 1.0f)).toInt()
        } else {
            newFrameCount = period
            remainingInputToCopyFrameCount = (period * (2.0f - speed) / (speed - 1.0f)).toInt()
        }
        outputBuffer = ensureSpaceForAdditionalFrames(outputBuffer, outputFrameCount, newFrameCount)
        overlapAdd(
            newFrameCount,
            channelCount,
            outputBuffer,
            outputFrameCount,
            samples,
            position,
            samples,
            position + period
        )
        outputFrameCount += newFrameCount
        return newFrameCount
    }

    private fun insertPitchPeriod(
        samples: ShortArray,
        position: Int,
        speed: Float,
        period: Int
    ): Int {
        // Insert a pitch period, and determine how much input to copy directly.
        val newFrameCount: Int
        if (speed < 0.5f) {
            newFrameCount = (period * speed / (1.0f - speed)).toInt()
        } else {
            newFrameCount = period
            remainingInputToCopyFrameCount =
                (period * (2.0f * speed - 1.0f) / (1.0f - speed)).toInt()
        }
        outputBuffer =
            ensureSpaceForAdditionalFrames(outputBuffer, outputFrameCount, period + newFrameCount)
        System.arraycopy(
            samples,
            position * channelCount,
            outputBuffer,
            outputFrameCount * channelCount,
            period * channelCount
        )
        overlapAdd(
            newFrameCount,
            channelCount,
            outputBuffer,
            outputFrameCount + period,
            samples,
            position + period,
            samples,
            position
        )
        outputFrameCount += period + newFrameCount
        return newFrameCount
    }

    private fun changeSpeed(speed: Float) {
        if (inputFrameCount < maxRequiredFrameCount) {
            return
        }
        val frameCount = inputFrameCount
        var positionFrames = 0
        do {
            positionFrames += if (remainingInputToCopyFrameCount > 0) {
                copyInputToOutput(positionFrames)
            } else {
                val period = findPitchPeriod(inputBuffer, positionFrames)
                if (speed > 1.0) {
                    period + skipPitchPeriod(inputBuffer, positionFrames, speed, period)
                } else {
                    insertPitchPeriod(inputBuffer, positionFrames, speed, period)
                }
            }
        } while (positionFrames + maxRequiredFrameCount <= frameCount)
        removeProcessedInputFrames(positionFrames)
    }

    private fun processStreamInput() {
        // Resample as many pitch periods as we have buffered on the input.
        val originalOutputFrameCount = outputFrameCount
        val s = speed / pitch
        val r = rate * pitch
        if (s > 1.00001 || s < 0.99999) {
            changeSpeed(s)
        } else {
            copyToOutput(inputBuffer, 0, inputFrameCount)
            inputFrameCount = 0
        }
        if (r != 1.0f) {
            adjustRate(r, originalOutputFrameCount)
        }
    }

    companion object {
        private const val MINIMUM_PITCH = 65
        private const val MAXIMUM_PITCH = 400
        private const val AMDF_FREQUENCY = 4000
        private const val BYTES_PER_SAMPLE = 2
        private fun overlapAdd(
            frameCount: Int,
            channelCount: Int,
            out: ShortArray,
            outPosition: Int,
            rampDown: ShortArray,
            rampDownPosition: Int,
            rampUp: ShortArray,
            rampUpPosition: Int
        ) {
            for (i in 0 until channelCount) {
                var o = outPosition * channelCount + i
                var u = rampUpPosition * channelCount + i
                var d = rampDownPosition * channelCount + i
                for (t in 0 until frameCount) {
                    out[o] =
                        ((rampDown[d] * (frameCount - t) + rampUp[u] * t) / frameCount).toShort()
                    o += channelCount
                    d += channelCount
                    u += channelCount
                }
            }
        }
    }
}