package com.shuati.app;

import android.app.Application;

/**
 * 应用入口：最早安装崩溃处理器，保证任何线程的未捕获异常都能落盘，
 * 同时把非致命异常（Tailscale / WebView 初始化等）记录下来方便用户回传。
 */
public class ShuatiApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            CrashLogger.init(this);
            CrashLogger.installUncaughtHandler();
            CrashLogger.log("ShuatiApp", "Application.onCreate");
        } catch (Throwable t) {
            android.util.Log.e("ShuatiApp", "Application init failed", t);
        }
    }
}
