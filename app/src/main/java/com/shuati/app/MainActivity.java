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

    private static final String DEFAULT_TAILSCALE_URL = "http://100.122.70.99:8087";
    private static final String[] FALLBACK_URLS = {
        "http://100.122.70.99:8087",  // Tailscale IP
        "http://192.168.2.166:8087",  // LAN IP
        "http://127.0.0.1:8087"       // Localhost
    };
    private int currentFallbackIndex = 0;
    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private TextView tvStatus;
    private SharedPreferences prefs;
    private File logFile;
    
    // Tailscale 内置 VPN
    private TailscaleManager tailscaleManager;
    private boolean useTailscaleVpn = true;  // 默认使用内置 Tailscale

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

        // 启动内置 Tailscale VPN
        if (useTailscaleVpn) {
            startTailscaleVpn();
        } else {
            String serverUrl = prefs.getString("server_url", DEFAULT_TAILSCALE_URL);
            log("MainActivity", "加载地址: " + serverUrl);
            loadUrl(serverUrl);
        }
    }
    
    private void startTailscaleVpn() {
        log("Tailscale", "启动内置 Tailscale VPN...");
        tvStatus.setText("正在连接 Tailscale...");
        tvStatus.setVisibility(View.VISIBLE);
        
        tailscaleManager = new TailscaleManager(this, new TailscaleManager.TailscaleCallback() {
            @Override
            public void onVpnPermissionNeeded(Intent intent) {
                log("Tailscale", "需要 VPN 权限授权");
                Toast.makeText(MainActivity.this, "请授权 VPN 连接以访问服务器", Toast.LENGTH_LONG).show();
                startActivityForResult(intent, TailscaleManager.getVpnRequestCode());
            }
            
            @Override
            public void onConnected(int localPort) {
                log("Tailscale", "已连接！本地代理端口: " + localPort);
                String proxyUrl = "http://127.0.0.1:" + localPort;
                Toast.makeText(MainActivity.this, "Tailscale 已连接", Toast.LENGTH_SHORT).show();
                loadUrl(proxyUrl);
            }
            
            @Override
            public void onError(String error) {
                logError("Tailscale", "连接失败: " + error, null);
                Toast.makeText(MainActivity.this, "Tailscale 连接失败，尝试直接连接", Toast.LENGTH_SHORT).show();
                useTailscaleVpn = false;
                String serverUrl = prefs.getString("server_url", DEFAULT_TAILSCALE_URL);
                loadUrl(serverUrl);
            }
        });
        
        tailscaleManager.start();
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
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                currentFallbackIndex = 0; // 成功加载后重置备用索引
                progressBar.setVisibility(View.GONE);
                tvStatus.setVisibility(View.GONE);
                log("WebView", "加载完成: " + url);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                logError("WebView", "加载失败: " + failingUrl + " - " + description, null);
                
                // 尝试备用地址
                if (currentFallbackIndex < FALLBACK_URLS.length - 1) {
                    currentFallbackIndex++;
                    String nextUrl = FALLBACK_URLS[currentFallbackIndex];
                    log("WebView", "尝试备用地址: " + nextUrl);
                    tvStatus.setText("连接失败，尝试备用地址...");
                    tvStatus.setVisibility(View.VISIBLE);
                    view.loadUrl(nextUrl);
                } else {
                    // 所有地址都失败
                    tvStatus.setText("连接失败: " + description + "\n点击右上角设置按钮配置服务器地址");
                    tvStatus.setVisibility(View.VISIBLE);
                }
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
        currentFallbackIndex = 0; // 重置备用索引
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
        if (requestCode == TailscaleManager.getVpnRequestCode()) {
            if (resultCode == RESULT_OK) {
                log("Tailscale", "VPN 权限已授予");
                tailscaleManager.onVpnGranted();
            } else {
                log("Tailscale", "VPN 权限被拒绝");
                Toast.makeText(this, "VPN 权限被拒绝，无法连接 Tailscale", Toast.LENGTH_LONG).show();
                useTailscaleVpn = false;
                String serverUrl = prefs.getString("server_url", DEFAULT_TAILSCALE_URL);
                loadUrl(serverUrl);
            }
        } else if (requestCode == 100 && resultCode == RESULT_OK) {
            String serverUrl = prefs.getString("server_url", "");
            if (!serverUrl.isEmpty()) {
                useTailscaleVpn = false;
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
        if (tailscaleManager != null) {
            tailscaleManager.stop();
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
