package com.shuati.app;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tailscale.mobile.TailscaleConfig;
import com.tailscale.mobile.TailscaleProxyService;

/**
 * Tailscale VPN 管理器。
 *
 * 设计原则：任何一步失败（JNI 缺失、VPN 权限被拒、前台服务受限、认证超时）
 * 都只回调 onError，由 UI 降级到直连地址，绝不让异常冒到主线程。
 */
public class TailscaleManager {
    private static final String TAG = "TailscaleManager";
    private static final int VPN_REQUEST_CODE = 200;
    private static final int MAX_POLL_ATTEMPTS = 50; // 50s，比服务内部 45s 认证窗口略长

    private final Activity activity;
    private final TailscaleCallback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isConnecting = false;
    private boolean completed = false;
    private int localPort = 0;
    private int attempts = 0;

    // Tailscale 配置
    private static final String AUTH_KEY = "tskey-auth-kvy5amnx9T11CNTRL-PaRxwZykYcCcXisQjWL3dCCupp9ZEFCqA";
    private static final String PEER_IP = "100.122.70.99";
    private static final int PEER_PORT = 8087;
    private static final String HOSTNAME = "shuati-app";

    public interface TailscaleCallback {
        void onVpnPermissionNeeded(Intent intent);
        void onConnected(int localPort);
        void onError(String error);
    }

    public TailscaleManager(Activity activity, TailscaleCallback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    public static int getVpnRequestCode() {
        return VPN_REQUEST_CODE;
    }

    private static TailscaleConfig buildConfig() {
        return new TailscaleConfig(AUTH_KEY, PEER_IP, PEER_PORT, HOSTNAME);
    }

    /** 启动 Tailscale：申请 VPN 权限 → 启动服务 → 轮询代理端口。 */
    public void start() {
        if (isConnecting) {
            Log.w(TAG, "Already connecting, ignoring start()");
            return;
        }
        isConnecting = true;
        completed = false;
        try {
            Log.d(TAG, "Starting Tailscale VPN...");
            Intent vpnIntent = VpnService.prepare(activity);
            if (vpnIntent != null) {
                Log.d(TAG, "Requesting VPN permission...");
                callback.onVpnPermissionNeeded(vpnIntent);
            } else {
                Log.d(TAG, "VPN permission already granted");
                onVpnGranted();
            }
        } catch (Throwable t) {
            Log.e(TAG, "start() failed", t);
            fail("VPN 初始化失败: " + t.getClass().getSimpleName());
        }
    }

    /** VPN 权限已授予（Activity.onActivityResult RESULT_OK 调用）。 */
    public void onVpnGranted() {
        try {
            Log.d(TAG, "VPN granted, starting service...");
            TailscaleProxyService.Companion.start(activity, buildConfig());
            pollForPort();
        } catch (Throwable t) {
            Log.e(TAG, "onVpnGranted failed", t);
            fail("启动 VPN 服务失败: " + t.getClass().getSimpleName());
        }
    }

    private void pollForPort() {
        attempts = 0;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (completed) return;
                try {
                    attempts++;
                    String err = TailscaleProxyService.Companion.getLastError();
                    if (TailscaleProxyService.Companion.isReady()) {
                        int port = TailscaleProxyService.Companion.getProxyPort();
                        if (port > 0) {
                            completed = true;
                            isConnecting = false;
                            localPort = port;
                            Log.d(TAG, "Tailscale connected, local port: " + port);
                            callback.onConnected(port);
                            return;
                        }
                    }
                    if (err != null && err.length() > 0) {
                        fail(err);
                        return;
                    }
                    if (attempts >= MAX_POLL_ATTEMPTS) {
                        fail("Tailscale 连接超时（" + MAX_POLL_ATTEMPTS + "s）");
                        return;
                    }
                    handler.postDelayed(this, 1000);
                } catch (Throwable t) {
                    Log.e(TAG, "poll failed", t);
                    fail("Tailscale 状态检查失败: " + t.getClass().getSimpleName());
                }
            }
        }, 800);
    }

    private void fail(String reason) {
        if (completed) return;
        completed = true;
        isConnecting = false;
        localPort = 0;
        Log.w(TAG, "Tailscale unavailable: " + reason);
        try {
            callback.onError(reason);
        } catch (Throwable t) {
            Log.e(TAG, "callback.onError threw", t);
        }
    }

    /** 停止 Tailscale（不会抛异常）。 */
    public void stop() {
        try {
            completed = true;
            isConnecting = false;
            localPort = 0;
            handler.removeCallbacksAndMessages(null);
            if (activity != null) {
                Intent serviceIntent = new Intent(activity, TailscaleProxyService.class);
                activity.stopService(serviceIntent);
            }
        } catch (Throwable t) {
            Log.w(TAG, "stop() failed", t);
        }
    }

    public int getLocalPort() {
        return localPort;
    }

    public boolean isConnecting() {
        return isConnecting;
    }
}
