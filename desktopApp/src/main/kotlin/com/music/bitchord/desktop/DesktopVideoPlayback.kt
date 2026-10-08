package com.music.bitchord.desktop

import com.music.bitchord.desktop.mpv.*
import com.sun.jna.Pointer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** Shared by every player surface; video keeps playing when the sheet is closed. */
internal object DesktopVideoPlayback {
    private val source = MutableStateFlow<MpvVideoFrameSource?>(null)
    val frames = source.asStateFlow()
    fun publish(value: MpvVideoFrameSource?) { source.value = value }
}

/** One mpv handle owns audio, video, buffering, and the position reported to the UI. */
internal class DesktopVideoSession(
    videoUrl: String,
    audioUrl: String?,
    headers: Map<String, String>,
    startMs: Long,
    playing: Boolean,
    volume: Float,
    speed: Float,
    private val onPosition: (Long, Long, Boolean, Boolean) -> Unit,
    private val onEnded: () -> Unit,
    private val onError: (String) -> Unit,
    audioOutput: String? = null,
) : AutoCloseable {
    private val lib = checkNotNull(MpvLibrary.INSTANCE) { "Video playback requires libmpv (brew install mpv)" }
    private val ctx: Pointer = checkNotNull(lib.mpv_create()) { "Could not create video player" }
    val frames = MpvVideoFrameSource()
    private val active = AtomicBoolean(true)
    private val secondaryAudioUrl = audioUrl
    private var loaded = false
    private val lock = Any()
    private lateinit var thread: Thread

    init {
        try {
            option("vo", "libmpv")
            option("config", "no")
            option("terminal", "no")
            option("ytdl", "no")
            option("cache", "yes")
            option("cache-secs", "15")
            option("hwdec", "auto-safe")
            option("pause", if (playing) "no" else "yes")
            option("start", (startMs / 1000.0).toString())
            option("volume", (volume * 100).toString())
            option("speed", speed.toString())
            if (audioOutput != null) option("ao", audioOutput)
            else if (DesktopPlatform.isMac) option("ao", "avfoundation")
            if (headers.isNotEmpty()) option("http-header-fields", headers.entries.joinToString(",") {
                "${it.key}: ${it.value}".replace(",", "\\,")
            })
            check(lib.mpv_initialize(ctx) >= 0) { "Could not initialize video player" }
            check(frames.attach(ctx)) { "Could not attach video renderer" }
            lib.mpv_observe_property(ctx, 1L, "time-pos", MpvFormat.DOUBLE)
            lib.mpv_observe_property(ctx, 2L, "duration", MpvFormat.DOUBLE)
            lib.mpv_observe_property(ctx, 3L, "pause", MpvFormat.FLAG)
            lib.mpv_observe_property(ctx, 4L, "paused-for-cache", MpvFormat.FLAG)
            lib.mpv_observe_property(ctx, 5L, "video-params/aspect", MpvFormat.DOUBLE)
            check(lib.mpv_command(ctx, arrayOf("loadfile", videoUrl, null)) >= 0) { "Could not load video" }
            thread = Thread(::pump, "BitChord-Video").apply { isDaemon = true; start() }
        } catch (failure: Throwable) {
            frames.detach()
            lib.mpv_terminate_destroy(ctx)
            throw failure
        }
    }

    private fun option(name: String, value: String) {
        check(lib.mpv_set_option_string(ctx, name, value) >= 0) { "Unsupported video option: $name" }
    }

    private fun property(name: String): String? {
        val value = lib.mpv_get_property_string(ctx, name) ?: return null
        return try { value.getString(0) } finally { lib.mpv_free(value) }
    }

    private fun pump() {
        while (active.get()) {
            val ptr = lib.mpv_wait_event(ctx, 0.1) ?: continue
            val event = MpvEvent(ptr).apply { read() }
            if (event.event_id == MpvEventId.FILE_LOADED) {
                // `audio-file` is a runtime property in current libmpv builds, not a startup
                // option. Attach it after the video is loaded so video-only 1080p streams still
                // use the separately resolved audio rendition.
                secondaryAudioUrl?.let { audio ->
                    val rc = lib.mpv_command(ctx, arrayOf("audio-add", audio, "select", null))
                    if (rc < 0) onError(lib.mpv_error_string(rc) ?: "Could not attach audio")
                }
                loaded = true
            }
            if (event.event_id == MpvEventId.END_FILE) {
                val end = event.data?.let { MpvEventEndFile(it).apply { read() } }
                if (end?.reason == MpvEndFileReason.EOF) onEnded()
                if (end?.reason == MpvEndFileReason.ERROR) onError(lib.mpv_error_string(end.error) ?: "Video playback failed")
                return
            }
            if (loaded && active.get() && event.event_id == MpvEventId.PROPERTY_CHANGE) {
                val position = ((property("time-pos")?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
                val duration = ((property("duration")?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
                val buffering = property("paused-for-cache") == "yes"
                onPosition(position, duration, property("pause") != "yes", buffering)
                frames.setAspectRatio(property("video-params/aspect")?.toDoubleOrNull())
            }
        }
    }

    private fun set(name: String, value: String) = synchronized(lock) {
        if (active.get()) lib.mpv_set_property_string(ctx, name, value)
    }
    fun play(playing: Boolean) { set("pause", if (playing) "no" else "yes") }
    fun seek(positionMs: Long) { set("time-pos", (positionMs.coerceAtLeast(0) / 1000.0).toString()) }
    fun volume(value: Float) { set("volume", (value * 100).toString()) }
    fun speed(value: Float) { set("speed", value.toString()) }

    override fun close() = synchronized(lock) {
        if (active.compareAndSet(true, false)) {
            lib.mpv_wakeup(ctx)
            check(Thread.currentThread() !== thread) { "Close video sessions outside their event thread" }
            thread.join()
            frames.detach()
            lib.mpv_terminate_destroy(ctx)
        }
    }
}
