package com.otaliastudios.transcoder.internal

import com.otaliastudios.transcoder.common.TrackType
import com.otaliastudios.transcoder.internal.pipeline.Pipeline
import com.otaliastudios.transcoder.internal.pipeline.State
import com.otaliastudios.transcoder.internal.utils.Logger

class Segment(
    val type: TrackType,
    val index: Int,
    private val pipeline: Pipeline,
    val source: String = "",
) {

    private val log = Logger("Segment($type,$index,$source)")
    private var state: State<Unit>? = null

    fun advance(): Boolean {
        state = pipeline.execute()
        return state is State.Ok
    }

    fun canAdvance(): Boolean {
        log.v("canAdvance(): state=$state")
        return state == null || state !is State.Eos
    }

    fun release() {
        pipeline.release()
    }
}
