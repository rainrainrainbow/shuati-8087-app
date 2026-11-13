package com.shuati.app;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.util.Log;

import com.tailscale.mobile.TailscaleConfig;
import com.tailscale.mobile.TailscaleProxyService;

/**
 * Tailscale VPN 管理器
 * 负责启动/停止内置 Tailscale VPN 服务
 */
public class TailscaleManager {
    private static final String TAG = "TailscaleManager";
    private static final int VPN_REQUEST_CODE = 200;
    
    private Activity activity;
    private TailscaleCallback callback;
    private boolean isConnecting = false;
    private int localPort = 0;
    
    // Tailscale 配置
    private static final String AUTH_KEY = "tskey-auth-kvy5amnx9T11CNTRL-PaRxwZykYcCcXisQjWL3dCCupp9ZEFCqA";
    private static final String PEER_IP = "100.122.70.99";
    private static final int PEER_PORT = 8087;
    
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
    
    /**
     * 启动 Tailscale VPN
     */
    public void start() {
        if (isConnecting) {
            Log.w(TAG, "Already connecting, ignoring start()");
            return;
        }
        
        isConnecting = true;
        Log.d(TAG, "Starting Tailscale VPN...");
        
        // 配置 Tailscale
        TailscaleConfig config = new TailscaleConfig(
            AUTH_KEY,           // authKey
            PEER_IP,            // peerIp
            PEER_PORT,          // peerPort
            "shuati-app"        // hostname
        );
        
        // 请求 VPN 权限
        Intent vpnIntent = VpnService.prepare(activity);
        if (vpnIntent != null) {
            Log.d(TAG, "Requesting VPN permission...");
            callback.onVpnPermissionNeeded(vpnIntent);
        } else {
            Log.d(TAG, "VPN permission already granted, starting service...");
            onVpnGranted();
        }
    }
    
    /**
     * VPN 权限已授予，启动服务
     */
    public void onVpnGranted() {
        Log.d(TAG, "VPN permission granted, starting service...");
        
        // 配置 Tailscale
        TailscaleConfig config = new TailscaleConfig(
            AUTH_KEY,           // authKey
            PEER_IP,            // peerIp
            PEER_PORT,          // peerPort
            "shuati-app"        // hostname
        );
        
        // 启动 VPN 服务
        TailscaleProxyService.Companion.start(activity, config);
        
        // 等待服务就绪并获取本地代理端口
        pollForPort();
    }
    
    /**
     * 轮询等待 Tailscale 服务就绪
     */
    private void pollForPort() {
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
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
    
    /**
     * 停止 Tailscale VPN
     */
    public void stop() {
        Log.d(TAG, "Stopping Tailscale VPN...");
        Intent serviceIntent = new Intent(activity, TailscaleProxyService.class);
        activity.stopService(serviceIntent);
        isConnecting = false;
        localPort = 0;
    }
    
    /**
     * 获取本地代理端口
     */
    public int getLocalPort() {
        return localPort;
    }
    
    /**
     * 是否正在连接
     */
    public boolean isConnecting() {
        return isConnecting;
    }
}
