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
 * Tailscale 内置 VPN 管理器
 * 使用 auth key 自动连接到 tailnet，创建本地代理
 */
public class TailscaleManager {
    private static final String TAG = "TailscaleManager";
    
    // Tailscale 配置
    private static final String AUTH_KEY = "tskey-auth-kvy5amnx9T11CNTRL-PaRxwZykYcCcXisQjWL3dCCupp9ZEFCqA";
    private static final String PEER_IP = "100.122.70.99";
    private static final int PEER_PORT = 8087;
    
    private static final int VPN_REQUEST_CODE = 1001;
    
    private Activity activity;
    private TailscaleCallback callback;
    private boolean isConnecting = false;
    private int localPort = -1;
    
    public interface TailscaleCallback {
        void onVpnPermissionNeeded(Intent intent);
        void onConnected(int localPort);
        void onError(String error);
    }
    
    public TailscaleManager(Activity activity, TailscaleCallback callback) {
        this.activity = activity;
        this.callback = callback;
    }
    
    /**
     * 启动 Tailscale 连接
     * 1. 检查 VPN 权限
     * 2. 启动代理服务
     * 3. 等待连接完成
     */
    public void start() {
        if (isConnecting) {
            Log.d(TAG, "已经在连接中");
            return;
        }
        isConnecting = true;
        
        // 检查 VPN 权限
        Intent vpnIntent = VpnService.prepare(activity);
        if (vpnIntent != null) {
            // 需要用户授权 VPN
            Log.d(TAG, "需要 VPN 权限");
            callback.onVpnPermissionNeeded(vpnIntent);
        } else {
            // 已有 VPN 权限，直接启动
            Log.d(TAG, "已有 VPN 权限，启动代理");
            launchProxy();
        }
    }
    
    /**
     * 用户授权 VPN 后调用
     */
    public void onVpnGranted() {
        Log.d(TAG, "VPN 权限已授予");
        launchProxy();
    }
    
    private void launchProxy() {
        try {
            TailscaleConfig config = new TailscaleConfig(AUTH_KEY, PEER_IP, PEER_PORT);
            TailscaleProxyService.start(activity, config);
            Log.d(TAG, "Tailscale 代理服务已启动");
            
            // 等待代理端口就绪（轮询）
            pollForPort();
        } catch (Exception e) {
            Log.e(TAG, "启动 Tailscale 失败", e);
            isConnecting = false;
            callback.onError("启动 Tailscale 失败: " + e.getMessage());
        }
    }
    
    private void pollForPort() {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            int attempts = 0;
            @Override
            public void run() {
                attempts++;
                // 检查服务是否已就绪
                if (TailscaleProxyService.Companion.isReady()) {
                    localPort = TailscaleProxyService.Companion.getProxyPort();
                    isConnecting = false;
                    Log.d(TAG, "Tailscale 已连接，本地端口: " + localPort);
                    callback.onConnected(localPort);
                } else if (attempts < 30) {
                    // 最多等 30 秒
                    handler.postDelayed(this, 1000);
                } else {
                    isConnecting = false;
                    callback.onError("Tailscale 连接超时");
                }
            }
        }, 1000);
    }
    
    public void stop() {
        try {
            TailscaleProxyService.stop(activity);
            Log.d(TAG, "Tailscale 已停止");
        } catch (Exception e) {
            Log.e(TAG, "停止 Tailscale 失败", e);
        }
        isConnecting = false;
        localPort = -1;
    }
    
    public boolean isConnected() {
        return localPort > 0;
    }
    
    public int getLocalPort() {
        return localPort;
    }
    
    public static int getVpnRequestCode() {
        return VPN_REQUEST_CODE;
    }
}
