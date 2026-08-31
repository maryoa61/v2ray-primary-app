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
     *
     * @param mtu MUST match the MTU installed on the VpnService TUN interface
     *   (Builder.setMtu). A mismatch means packets are segmented for one MTU
     *   while the interface uses another -> random packet drops / stalled
     *   connections under load. Defaults to [TUNNEL_MTU] for safety.
     */
    fun writeConfig(
        destFile: File,
        socksPort: Int,
        mtu: Int = TUNNEL_MTU
    ): File {
        // MTU sanity range: below 1280 breaks IPv6 minimum; above 1500 risks
        // fragmentation on the underlying physical link.
        val safeMtu = mtu.coerceIn(1280, 1500)
        destFile.writeText(
            """
            tunnel:
              name: tun0
              mtu: $safeMtu
              # multi-queue needs a kernel multi-queue TUN, which Android's
              # VpnService doesn't provide — it makes hev exit cleanly (code 0)
              # a minute or two after connect. Keep single-queue on Android.
              multi-queue: false
            socks5:
              address: 127.0.0.1
              port: $socksPort
              udp: 'udp'
            misc:
              # The old value (20480) was far too small: lwip + SOCKS task
              # chains can legitimately need more stack, and a native stack
              # overflow manifests as a silent abort of the tunnel thread —
              # i.e. "tunnel died randomly". 86016 is upstream hev-socks5-tunnel's
              # documented default; v2rayNG-style Android configs use ~81920.
              task-stack-size: 86016
              # Fail a dead SOCKS connection attempt fast (default upstream is
              # 10000ms; we use 5000) so a half-open socket during a core
              # restart tears the session down quickly instead of stalling the
              # TUN consumer. Key names verified against the upstream
              # hev-socks5-tunnel README (misc.*: connect-timeout,
              # tcp-read-write-timeout, udp-read-write-timeout).
              connect-timeout: 5000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              # Avoid EMFILE under heavy connection churn (browsers open
              # hundreds of sockets). Raises the task's rlimit nofile cap.
              limit-nofile: 65535
              log-file: stderr
              log-level: warn
            """.trimIndent()
        )
        return destFile
    }
}
