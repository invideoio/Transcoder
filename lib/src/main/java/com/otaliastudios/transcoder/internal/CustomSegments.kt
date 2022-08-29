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
    private val factory: (TrackType, DataSource, MediaFormat) -> Pipeline,
) {

    private val log = Logger("Segments")
    private var currentSegment: Segment? = null
    private var currentSegmentMapKey: String? = null
    private val segmentMap = mutableMapOf<String, Segment?>()

    fun hasNext(type: TrackType): Boolean {
        if (!sources.has(type)) return false
        log.v(
            "hasNext($type): segment=${currentSegment} lastIndex=${sources.getOrNull(type)?.lastIndex}" +
                    " canAdvance=${currentSegment?.canAdvance()}"
        )
        val segment = currentSegment ?: return true // not started
        return segment.canAdvance()
    }

    fun hasNext() = hasNext(TrackType.VIDEO)


    // it will be time dependent
    // 1. make segments work for thumbnails as is
    // 2. inject segments dynamically
    // 3. seek to segment and destroy previous ones
    // 4. destroy only if necessary, else reuse

    fun getSegment(id: String): Segment? {
        return segmentMap.getOrPut(id) {
            destroySegment()
            tryCreateSegment(id).also {
                currentSegment = it
                currentSegmentMapKey = id
            }
        }
    }

    fun releaseSegment(id: String) {
        val segment = segmentMap[id]
        segment?.let {
            it.release()
            val source = sources[it.type].firstOrNull { it.mediaId() == id }
            if (tracks.active.has(it.type)) {
                source?.releaseTrack(it.type)
            }
            segmentMap.remove(id)
            if(currentSegment == segment) {
                currentSegment = null
            }
        }
    }

    fun release() = destroySegment(true)

    private fun tryCreateSegment(id: String): Segment? {
        // Return null if out of bounds, either because segments are over or because the
        // source set does not have sources for this track type.
        val source = sources[TrackType.VIDEO].firstOrNull { it.mediaId() == id } ?: return null
        source.init()
        log.i("tryCreateSegment(${TrackType.VIDEO}, $id): created!")
        if (tracks.active.has(TrackType.VIDEO)) {
            source.selectTrack(TrackType.VIDEO)
        }
        // Update current index before pipeline creation, for other components
        // who check it during pipeline init.
        val pipeline = factory(
            TrackType.VIDEO,
            source,
            tracks.outputFormats[TrackType.VIDEO]
        )
        return Segment(TrackType.VIDEO, -1, pipeline, id)
    }

    private fun destroySegment(releaseAll: Boolean = false) {
        currentSegment?.let { segment ->
            segment.release()
            val source = sources[segment.type].firstOrNull { it.mediaId() == segment.source }
            if (tracks.active.has(segment.type)) {
                source?.releaseTrack(segment.type)
            }
            currentSegmentMapKey?.let {
                segmentMap.remove(it)
            }
            currentSegment = null
            currentSegmentMapKey = null
        }
        if (releaseAll) {
            segmentMap.forEach {
                it.value?.release()
            }
            segmentMap.clear()
        }
    }
    private fun DataSource.init() = if (!isInitialized) initialize() else Unit

    private fun DataSource.deinit() = if (isInitialized) deinitialize() else Unit

}
