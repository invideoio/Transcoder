package com.otaliastudios.transcoder.internal.codec

import android.media.MediaFormat

interface TranscoderEventsListener {

    fun onDecoderConfigureFailure(codecName: String, format: MediaFormat, exception: Exception)

    fun onDecoderStartFailure(codecName: String, format: MediaFormat, exception: Exception)

    fun onDecoderReleaseFailure(codecName: String, format: MediaFormat, exception: Exception)

}