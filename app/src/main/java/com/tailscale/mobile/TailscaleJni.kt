package com.tailscale.mobile

import android.util.Log

/**
 * JNI bridge to libtailscale.
 *
 * libtailscale.so 是 Go c-shared 库，只导出 C 符号（Tsnet*），
 * 因此必须通过 libtailscale_jni.so（app/src/main/cpp/tailscale_jni.cpp）转发。
 *
 * 这里所有 native 调用都用 try/catch(Throwable) 包住：
 * UnsatisfiedLinkError / NoSuchMethodError 都是 Error 而不是 Exception，
 * 一旦漏出去就会杀死进程（v5 打开即闪退的根因）。
 */
object TailscaleJni {
    private const val TAG = "TailscaleJni"

    @Volatile
    private var loaded = false

    @Volatile
    var lastLoadError: String? = null
        private set

    /** 尝试加载 JNI 桥，失败返回 false（绝不抛异常）。 */
    @Synchronized
    fun isAvailable(): Boolean {
        if (loaded) return true
        lastLoadError = null
        return try {
            System.loadLibrary("tailscale_jni")
            loaded = true
            Log.i(TAG, "libtailscale_jni loaded")
            true
        } catch (t: Throwable) {
            lastLoadError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "loadLibrary(tailscale_jni) failed: $lastLoadError", t)
            false
        }
    }

    /** 阻塞直到 loopback listener 起来。0 = 成功。 */
    fun connect(stateDir: String, authKey: String, hostname: String): Int {
        if (!isAvailable()) return -1
        return try {
            nativeConnect(stateDir, authKey, hostname)
        } catch (t: Throwable) {
            Log.e(TAG, "connect failed", t)
            -1
        }
    }

    /** 返回 100.x.y.z，未认证时返回空串。 */
    fun getIp(): String {
        if (!isAvailable()) return ""
        return try {
            nativeGetIp() ?: ""
        } catch (t: Throwable) {
            Log.e(TAG, "getIp failed", t)
            ""
        }
    }

    /** 通过 tailnet 建立 TCP 连接，返回 fd，失败返回 -1。 */
    fun dial(addr: String): Int {
        if (!isAvailable()) return -1
        return try {
            nativeDial(addr)
        } catch (t: Throwable) {
            Log.e(TAG, "dial($addr) failed", t)
            -1
        }
    }

    fun closeFd(fd: Int) {
        if (!isAvailable()) return
        try {
            nativeCloseFd(fd)
        } catch (t: Throwable) {
            Log.e(TAG, "closeFd failed", t)
        }
    }

    fun close() {
        if (!isAvailable()) return
        try {
            nativeClose()
        } catch (t: Throwable) {
            Log.e(TAG, "close failed", t)
        }
    }

    private external fun nativeConnect(stateDir: String, authKey: String, hostname: String): Int
    private external fun nativeGetIp(): String
    private external fun nativeDial(addr: String): Int
    private external fun nativeCloseFd(fd: Int)
    private external fun nativeClose()
}
