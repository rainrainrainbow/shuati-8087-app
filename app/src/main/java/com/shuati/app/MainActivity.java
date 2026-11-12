package com.shuati.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private TextView tvStatus;
    private SharedPreferences prefs;
    private File logFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化日志
        initLogger();
        log("MainActivity", "=== onCreate start ===");

        // 设置全局异常捕获
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            logError("CRASH", "Thread " + t.getName() + " crashed", e);
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(1);
        });

        prefs = getSharedPreferences("shuati", MODE_PRIVATE);

        webView = findViewById(R.id.web_view);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        progressBar = findViewById(R.id.progress_bar);
        tvStatus = findViewById(R.id.tv_status);

        setupWebView();
        setupSwipeRefresh();

        // 首次启动或无地址时，打开设置
        String serverUrl = prefs.getString("server_url", "");
        if (serverUrl.isEmpty()) {
            log("MainActivity", "首次启动，打开设置");
            openSettings();
        } else {
            log("MainActivity", "加载地址: " + serverUrl);
            loadUrl(serverUrl);
        }
    }

    private void initLogger() {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ShuatiApp");
            if (!dir.exists()) dir.mkdirs();
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date());
            logFile = new File(dir, "log_" + timestamp + ".log");
            log("Logger", "日志文件: " + logFile.getAbsolutePath());
        } catch (Exception e) {
            android.util.Log.e("Logger", "初始化日志失败", e);
        }
    }

    private void log(String tag, String message) {
        if (logFile == null) return;
        try {
            String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA).format(new Date());
            FileWriter fw = new FileWriter(logFile, true);
            BufferedWriter bw = new BufferedWriter(fw);
            bw.write(String.format("[%s] %s: %s\n", timestamp, tag, message));
            bw.newLine();
            bw.close();
            android.util.Log.d(tag, message);
        } catch (Exception e) {
            android.util.Log.e("Logger", "写日志失败", e);
        }
    }

    private void logError(String tag, String message, Throwable t) {
        if (logFile == null) return;
        try {
            String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA).format(new Date());
            FileWriter fw = new FileWriter(logFile, true);
            BufferedWriter bw = new BufferedWriter(fw);
            PrintWriter pw = new PrintWriter(bw);
            pw.printf("[%s] %s: %s\n", timestamp, tag, message);
            if (t != null) t.printStackTrace(pw);
            pw.flush();
            pw.close();
            android.util.Log.e(tag, message, t);
        } catch (Exception e) {
            android.util.Log.e("Logger", "写错误日志失败", e);
        }
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                log("WebView", "加载: " + url);
                view.loadUrl(url);
                return true;
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                logError("WebView", "加载失败: " + failingUrl + " - " + description, null);
                tvStatus.setText("连接失败: " + description);
                tvStatus.setVisibility(View.VISIBLE);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                    tvStatus.setVisibility(View.GONE);
                    log("WebView", "加载完成");
                } else {
                    progressBar.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void setupSwipeRefresh() {
        swipeRefresh.setColorSchemeResources(
            android.R.color.holo_blue_bright,
            android.R.color.holo_green_light,
            android.R.color.holo_orange_light
        );
        swipeRefresh.setOnRefreshListener(() -> {
            log("SwipeRefresh", "下拉刷新");
            webView.reload();
            swipeRefresh.setRefreshing(false);
        });
    }

    private void loadUrl(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        log("MainActivity", "加载 URL: " + url);
        tvStatus.setText("正在连接...");
        tvStatus.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivityForResult(intent, 100);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && resultCode == RESULT_OK) {
            String serverUrl = prefs.getString("server_url", "");
            if (!serverUrl.isEmpty()) {
                loadUrl(serverUrl);
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            openSettings();
            return true;
        } else if (id == R.id.action_refresh) {
            webView.reload();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
