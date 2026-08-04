/*
 * jni_bridge.c
 *
 * Thin JNI glue between:
 *   Kotlin: com.example.service.HevSocks5Tunnel
 *           external fun nativeMainFromFile(configPath: String, tunFd: Int): Int
 *           external fun nativeQuit()
 * and:
 *   C:      hev-socks5-tunnel's public API (src/hev-main.h in
 *           https://github.com/heiher/hev-socks5-tunnel)
 *
 * WHY THIS FILE EXISTS (read this before touching it):
 * The upstream hev-socks5-tunnel repo ships its OWN Android JNI wrapper
 * (built via its Android.mk), but that wrapper's exported symbol is
 * Java_TProxyService_StartService — i.e. it assumes a Java/Kotlin class
 * literally named "TProxyService" in the default package. Our app's class
 * is com.example.service.HevSocks5Tunnel, so that prebuilt wrapper's
 * exported symbols will NEVER match ours, no matter how the .so is placed
 * in jniLibs/. That mismatch is the root cause of the UnsatisfiedLinkError
 * / native crash we chased earlier.
 *
 * The fix: don't use upstream's JNI wrapper at all. Link directly against
 * hev-socks5-tunnel's underlying C library (built as a static lib via its
 * own Makefile — see CMakeLists.txt) and write our own tiny JNI layer
 * here, with symbol names generated for OUR class's package/name.
 *
 * JNI symbol naming reference: Java_<package_with_underscores>_<Class>_<method>
 *   com.example.service.HevSocks5Tunnel.nativeMainFromFile
 *     -> Java_com_example_service_HevSocks5Tunnel_nativeMainFromFile
 *   com.example.service.HevSocks5Tunnel.nativeQuit
 *     -> Java_com_example_service_HevSocks5Tunnel_nativeQuit
 *
 * If the Kotlin object is ever renamed or moved to a different package,
 * BOTH of the exported function names below must be renamed to match, or
 * you're back to square one with UnsatisfiedLinkError.
 */

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "jni_bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/*
 * Declared (not defined) here rather than #include-ing hev-socks5-tunnel's
 * own src/hev-main.h, because that header lives inside the ExternalProject
 * checkout, which does not exist yet at CMake *configure* time on a clean
 * build (only after the external build step runs). Declaring the exact
 * signatures ourselves avoids an include-path chicken-and-egg problem.
 *
 * Signatures verified against upstream src/hev-main.h as of this writing:
 *   int  hev_socks5_tunnel_main_from_file (const char *config_path, int tun_fd);
 *   void hev_socks5_tunnel_quit (void);
 * If you bump the pinned hev-socks5-tunnel version/commit in CMakeLists.txt,
 * re-check these signatures against that tag's src/hev-main.h before relying
 * on this file again.
 */
extern int hev_socks5_tunnel_main_from_file(const char *config_path, int tun_fd);
extern void hev_socks5_tunnel_quit(void);

JNIEXPORT jint JNICALL
Java_com_example_service_HevSocks5Tunnel_nativeMainFromFile(
    JNIEnv *env, jobject thiz, jstring configPath, jint tunFd)
{
    (void) thiz;

    if (configPath == NULL) {
        LOGE("nativeMainFromFile: configPath is null");
        return -1;
    }

    const char *path = (*env)->GetStringUTFChars(env, configPath, NULL);
    if (path == NULL) {
        /* Out of memory inside the JVM; an exception is already pending. */
        LOGE("nativeMainFromFile: GetStringUTFChars failed");
        return -1;
    }

    LOGI("Starting hev-socks5-tunnel: config_path=%s tun_fd=%d", path, (int) tunFd);

    /* Blocks until nativeQuit()/hev_socks5_tunnel_quit() is called, the TUN
     * fd is closed, or an internal error occurs. Runs on whatever thread
     * called us — the Kotlin side is responsible for using a dedicated
     * Thread, not a shared coroutine dispatcher (see HevSocks5Tunnel.kt). */
    int ret = hev_socks5_tunnel_main_from_file(path, (int) tunFd);

    LOGI("hev-socks5-tunnel event loop exited with code %d", ret);

    (*env)->ReleaseStringUTFChars(env, configPath, path);

    return (jint) ret;
}

JNIEXPORT void JNICALL
Java_com_example_service_HevSocks5Tunnel_nativeQuit(JNIEnv *env, jobject thiz)
{
    (void) env;
    (void) thiz;

    LOGI("Requesting hev-socks5-tunnel shutdown");
    hev_socks5_tunnel_quit();
}
