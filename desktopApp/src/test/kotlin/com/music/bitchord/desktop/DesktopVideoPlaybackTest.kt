package com.music.bitchord.desktop

import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopVideoPlaybackTest {
    /** Opt-in native integration check: provide a 1080p/30fps fixture with audio. */
    @Test
    fun `native video renders smoothly and owns pause and seek position`() = runBlocking {
        val fixture = System.getenv("BITCHORD_VIDEO_FIXTURE") ?: return@runBlocking
        check(Files.isRegularFile(Path.of(fixture)))
        val position = AtomicLong()
        val count = AtomicInteger()
        val session = DesktopVideoSession(fixture, fixture, emptyMap(), 0, true, 0f, 1f,
            onPosition = { time, _, _, _ -> position.set(time) },
            onEnded = {}, onError = { error -> error(error) }, audioOutput = "null")
        session.frames.setTargetSize(640, 360)
        val collector = launch(Dispatchers.Default) {
            session.frames.frames.collect { if (it != null) count.incrementAndGet() }
        }
        try {
            withTimeout(10000) { while (position.get() < 500) delay(20) }
            val before = count.get()
            delay(1000)
            assertTrue(count.get() - before >= 20, "Expected >=20 rendered frames/sec, got ${count.get() - before}")
            session.play(false)
            delay(200)
            val paused = position.get()
            delay(400)
            assertTrue(kotlin.math.abs(position.get() - paused) < 100, "Pause must stop the playback clock")
            session.seek(5000)
            withTimeout(3000) { while (kotlin.math.abs(position.get() - 5000) > 150) delay(20) }
            session.play(true)
            withTimeout(3000) { while (position.get() < 5500) delay(20) }
        } finally { collector.cancelAndJoin(); session.close() }
    }
}
