// JNI bridge: libtailscale (Tsnet* C API) -> Kotlin
//
// libtailscale.so is a Go c-shared library that exports plain C functions
// (TsnetNewServer / TsnetSetAuthKey / TsnetLoopback / TsnetDial / ...).
// It exports *no* Java_* symbols, so a JNI shim is required. This file is
// compiled per-ABI by CMake and linked against src/main/jniLibs/<abi>/libtailscale.so.

#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <unistd.h>
#include "libtailscale.h"

#define TAG "TailscaleJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static int ts_handle = -1;

extern "C" {

JNIEXPORT jint JNICALL
Java_com_tailscale_mobile_TailscaleJni_nativeConnect(
        JNIEnv *env, jclass clazz,
        jstring j_state_dir, jstring j_auth_key, jstring j_hostname)
{
    if (j_state_dir == nullptr || j_auth_key == nullptr || j_hostname == nullptr) {
        LOGE("connect: null argument");
        return -1;
    }

    if (ts_handle >= 0) {
        TsnetClose(ts_handle);
        ts_handle = -1;
    }

    const char *state_dir = env->GetStringUTFChars(j_state_dir, nullptr);
    if (state_dir == nullptr) return -1;

    // Set Android-compatible dirs from the Go side (os.Setenv, os.MkdirAll)
    // before TsnetNewServer, so logpolicy can find a writable directory.
    LOGI("state_dir=%s", state_dir);
    TsnetSetAndroidDirs(const_cast<char*>(state_dir));

    ts_handle = TsnetNewServer();
    if (ts_handle < 0) {
        LOGE("TsnetNewServer failed");
        env->ReleaseStringUTFChars(j_state_dir, state_dir);
        return -1;
    }

    const char *auth_key = env->GetStringUTFChars(j_auth_key, nullptr);
    const char *hostname = env->GetStringUTFChars(j_hostname, nullptr);
    if (auth_key == nullptr || hostname == nullptr) {
        if (auth_key) env->ReleaseStringUTFChars(j_auth_key, auth_key);
        if (hostname) env->ReleaseStringUTFChars(j_hostname, hostname);
        env->ReleaseStringUTFChars(j_state_dir, state_dir);
        TsnetClose(ts_handle);
        ts_handle = -1;
        return -1;
    }

    TsnetSetDir(ts_handle,      const_cast<char*>(state_dir));
    TsnetSetAuthKey(ts_handle,  const_cast<char*>(auth_key));
    TsnetSetHostname(ts_handle, const_cast<char*>(hostname));
    TsnetSetEphemeral(ts_handle, 1);
    TsnetSetLogFD(ts_handle, -1);  // discard Go logs; Java side logs status

    env->ReleaseStringUTFChars(j_state_dir, state_dir);
    env->ReleaseStringUTFChars(j_auth_key,  auth_key);
    env->ReleaseStringUTFChars(j_hostname,  hostname);

    // Userspace networking via Loopback (no TUN device): TUN requires
    // netlink_route_socket, which Android blocks for untrusted apps.
    LOGI("TsnetLoopback: starting userspace networking...");
    char addr_buf[256] = {0};
    char proxy_buf[33] = {0};
    char local_buf[33] = {0};
    int ret = TsnetLoopback(ts_handle, addr_buf, sizeof(addr_buf), proxy_buf, local_buf);
    if (ret != 0) {
        char errbuf[256] = {0};
        TsnetErrmsg(ts_handle, errbuf, sizeof(errbuf));
        LOGE("TsnetLoopback failed (%d): %s", ret, errbuf);
        TsnetClose(ts_handle);
        ts_handle = -1;
        return -1;
    }
    LOGI("TsnetLoopback: up, localapi=%s", addr_buf);
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_tailscale_mobile_TailscaleJni_nativeGetIp(JNIEnv *env, jclass clazz)
{
    if (ts_handle < 0) return env->NewStringUTF("");
    char buf[256] = {0};
    int ret = TsnetGetIps(ts_handle, buf, sizeof(buf));
    if (ret != 0 || buf[0] == '\0') return env->NewStringUTF("");
    // TsnetGetIps returns "ip4,ip6"; pick the 100.x tailnet address.
    char *p = buf;
    while (p && *p) {
        char *sep = strpbrk(p, ",\n");
        if (sep) *sep = '\0';
        if (strncmp(p, "100.", 4) == 0) {
            LOGI("TsnetGetIps: tailscale IP = %s", p);
            return env->NewStringUTF(p);
        }
        p = sep ? sep + 1 : nullptr;
    }
    return env->NewStringUTF("");
}

JNIEXPORT jint JNICALL
Java_com_tailscale_mobile_TailscaleJni_nativeDial(
        JNIEnv *env, jclass clazz, jstring j_addr)
{
    if (ts_handle < 0) {
        LOGE("dial called before connect");
        return -1;
    }
    if (j_addr == nullptr) return -1;
    const char *addr = env->GetStringUTFChars(j_addr, nullptr);
    if (addr == nullptr) return -1;
    char network[] = "tcp";
    int conn = -1;
    int ret = TsnetDial(ts_handle, network, const_cast<char*>(addr), &conn);
    if (ret != 0) {
        char errbuf[256] = {0};
        TsnetErrmsg(ts_handle, errbuf, sizeof(errbuf));
        LOGE("TsnetDial(%s) failed: %s", addr, errbuf);
        env->ReleaseStringUTFChars(j_addr, addr);
        return -1;
    }
    env->ReleaseStringUTFChars(j_addr, addr);
    LOGI("TsnetDial: fd=%d", conn);
    return (jint)conn;
}

JNIEXPORT void JNICALL
Java_com_tailscale_mobile_TailscaleJni_nativeCloseFd(JNIEnv *env, jclass clazz, jint fd)
{
    if (fd >= 0) {
        ::close(fd);
    }
}

JNIEXPORT void JNICALL
Java_com_tailscale_mobile_TailscaleJni_nativeClose(JNIEnv *env, jclass clazz)
{
    if (ts_handle >= 0) {
        TsnetClose(ts_handle);
        ts_handle = -1;
        LOGI("Tsnet closed");
    }
}

} // extern "C"
