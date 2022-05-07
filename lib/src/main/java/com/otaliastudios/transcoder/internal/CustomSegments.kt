@file:Suppress("ReturnCount")

package com.otaliastudios.transcoder.internal

import android.media.MediaFormat
import com.otaliastudios.transcoder.common.TrackStatus
import com.otaliastudios.transcoder.common.TrackType
import com.otaliastudios.transcoder.internal.pipeline.Pipeline
import com.otaliastudios.transcoder.internal.utils.Logger
import com.otaliastudios.transcoder.internal.utils.mutableTrackMapOf
import com.otaliastudios.transcoder.source.DataSource

class CustomSegments(
    private val sources: DataSources,
    private val tracks: Tracks,
    private val factory: (TrackType, Int, TrackStatus, MediaFormat) -> Pipeline,
) {

    private val log = Logger("Segments")
    private var currentSegment: Segment? = null
    val currentIndex = mutableTrackMapOf(-1, -1)
    private val segmentMap = mutableMapOf<String, Segment?>()

    fun hasNext(type: TrackType): Boolean {
        if (!sources.has(type)) return false
        log.v(
            "hasNext($type): segment=${currentSegment} lastIndex=${sources.getOrNull(type)?.lastIndex}" +
                    " canAdvance=${currentSegment?.canAdvance()}"
        )
        val segment = currentSegment ?: return true // not started
        val lastIndex = sources.getOrNull(type)?.lastIndex ?: return false // no track!
        return segment.canAdvance() || segment.index < lastIndex
    }

    fun hasNext() = hasNext(TrackType.VIDEO)


    // it will be time dependent
    // 1. make segments work for thumbnails as is
    // 2. inject segments dynamically
    // 3. seek to segment and destroy previous ones
    // 4. destroy only if necessary, else reuse

    fun getSegment(id: String): Segment? {
        return segmentMap.getOrPut(id) {
            if (currentSegment != null) {
                destroySegment(currentSegment)
            }
            tryCreateSegment(id).also {
                currentSegment = it
            }
        }
    }

    fun release() = destroySegment(currentSegment)

    private fun tryCreateSegment(id: String): Segment? {
        val index = sources[TrackType.VIDEO].indexOfFirst { it.mediaId() == id }
        // Return null if out of bounds, either because segments are over or because the
        // source set does not have sources for this track type.
        val source = sources[TrackType.VIDEO].getOrNull(index) ?: return null
        source.init()
        log.i("tryCreateSegment(${TrackType.VIDEO}, $index): created!")
        if (tracks.active.has(TrackType.VIDEO)) {
            source.selectTrack(TrackType.VIDEO)
        }
        // Update current index before pipeline creation, for other components
        // who check it during pipeline init.
        currentIndex[TrackType.VIDEO] = index
        val pipeline = factory(
            TrackType.VIDEO,
            index,
            tracks.all[TrackType.VIDEO],
            tracks.outputFormats[TrackType.VIDEO]
        )
        return Segment(TrackType.VIDEO, index, pipeline)
    }

    private fun destroySegment(segment: Segment?) {
        segment?.let {
            segment.release()
            val source = sources[segment.type][segment.index]
            if (tracks.active.has(segment.type)) {
                source.releaseTrack(segment.type)
            }
//            source.deinit()
            val reversed = segmentMap.entries.associate { (k, v) -> v to k }

            val resultKey = reversed[segment]

            resultKey?.let {
                segmentMap[it] = null
            }
        }
    }
    private fun DataSource.init() = if (!isInitialized) initialize() else Unit

    private fun DataSource.deinit() = if (isInitialized) deinitialize() else Unit

}
