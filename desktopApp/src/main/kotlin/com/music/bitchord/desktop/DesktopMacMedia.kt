package com.music.bitchord.desktop

import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.util.concurrent.atomic.AtomicBoolean

/** macOS Control Center and media keys, backed by Apple's MediaPlayer framework. */
internal object DesktopMacMedia {
    internal class Controller(
        val onPlay: () -> Unit,
        val onPause: () -> Unit,
        val onToggle: () -> Unit,
        val onNext: () -> Unit,
        val onPrevious: () -> Unit,
        val onSeek: (Long) -> Unit,
    )

    @Volatile private var controller: Controller? = null
    private val started = AtomicBoolean(false)
    private val shutdownHookAdded = AtomicBoolean(false)
    private val available by lazy {
        DesktopPlatform.isMac && !GraphicsEnvironment.isHeadless() &&
            runCatching { DesktopAnalysisRuntime.loadNative("bitchord_nowplaying") }
                .onFailure { DesktopTrackLog.log("macOS media controls unavailable: ${it.message}") }
                .isSuccess
    }

    fun start(controller: Controller) {
        this.controller = controller
        if (!available || !started.compareAndSet(false, true)) return
        if (!runCatching { nativeStart() }.getOrDefault(false)) {
            started.set(false)
            DesktopTrackLog.log("macOS media controls could not be created")
            return
        }
        if (shutdownHookAdded.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(Thread(::stop, "bitchord-nowplaying-stop"))
        }
    }

    fun stop() {
        controller = null
        if (started.compareAndSet(true, false)) runCatching { nativeStop() }
    }

    fun publish(state: DesktopPlaybackState, speed: Float) {
        if (!started.get()) return
        val song = state.song
        runCatching {
            nativeUpdate(
                song != null, song?.title.orEmpty(), song?.artist.orEmpty(),
                song?.albumName.orEmpty(), song?.thumbnailUrl.orEmpty(),
                state.isPlaying && !state.awaitingAudio, speed.toDouble(),
                state.positionMs, state.durationMs,
            )
        }.onFailure { DesktopTrackLog.log("macOS Now Playing update failed: ${it.message}") }
    }

    /** Native commands arrive outside Compose's UI thread. Recheck ownership after dispatch. */
    @JvmStatic
    fun onCommand(command: Int, positionSeconds: Double) {
        val target = controller ?: return
        EventQueue.invokeLater {
            if (controller !== target) return@invokeLater
            when (command) {
                0 -> target.onPlay()
                1 -> target.onPause()
                2 -> target.onToggle()
                3 -> target.onNext()
                4 -> target.onPrevious()
                5 -> if (positionSeconds.isFinite() && positionSeconds >= 0) {
                    target.onSeek((positionSeconds * 1_000).toLong())
                }
            }
        }
    }

    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeUpdate(
        hasSong: Boolean, title: String, artist: String, album: String, artUrl: String,
        isPlaying: Boolean, speed: Double, positionMs: Long, durationMs: Long,
    )
}
