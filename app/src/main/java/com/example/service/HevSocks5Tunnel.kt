package com.example.service

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin Kotlin wrapper around the native hev-socks5-tunnel library
 * (https://github.com/heiher/hev-socks5-tunnel).
 */
object HevSocks5Tunnel {

    private val isRunning = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    @Volatile
    private var libraryLoaded = false

    private var libraryLoadError: Throwable? = null

    // ثابت کردن MTU برای جلوگیری از تداخل در سراسر اپلیکیشن
    const val TUNNEL_MTU = 1400

    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
            libraryLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            libraryLoadError = e
        }
    }

    /**
     * Blocking call — runs the tunnel's event loop on the calling thread
     * until [stop] is invoked or the tunnel exits on its own (e.g. TUN fd
     * closed). MUST be launched on a dedicated Thread.
     *
     * @return the native exit code (0 on clean shutdown via [stop]).
     */
    private external fun nativeMainFromFile(configPath: String, tunFd: Int): Int

    /** Signals the running tunnel loop to shut down. Safe from any thread. */
    private external fun nativeQuit()

    /**
     * Starts the tunnel and blocks until it stops. Call this from a
     * dedicated Thread, not from a coroutine.
     */
    fun start(configPath: String, tunFd: Int): Int {
        if (!libraryLoaded) {
            throw IllegalStateException(
                "libhev-socks5-tunnel.so failed to load (${libraryLoadError?.message}). " +
                "Check that it's bundled under jniLibs/<abi>/ for this device's ABI.",
                libraryLoadError
            )
        }
        stopRequested.set(false)
        isRunning.set(true)
        return try {
            nativeMainFromFile(configPath, tunFd)
        } finally {
            isRunning.set(false)
        }
    }

    /**
     * Requests a clean shutdown of the tunnel loop.
     * Safe to call multiple times.
     */
    fun stop() {
        if (isRunning.get() && stopRequested.compareAndSet(false, true)) {
            nativeQuit()
        }
    }

    fun isActive(): Boolean = isRunning.get()

    /**
     * Writes the YAML config hev-socks5-tunnel expects.
     */
    fun writeConfig(
        destFile: File,
        socksPort: Int
    ): File {
        destFile.writeText(
            """
            tunnel:
              name: tun0
              mtu: $TUNNEL_MTU
              # multi-queue needs a kernel multi-queue TUN, which Android's
              # VpnService doesn't provide — it makes hev exit cleanly (code 0)
              # a minute or two after connect. Keep single-queue on Android.
              multi-queue: false
            socks5:
              address: 127.0.0.1
              port: $socksPort
              udp: 'udp'
            misc:
              task-stack-size: 20480
            """.trimIndent()
        )
        return destFile
    }
}
