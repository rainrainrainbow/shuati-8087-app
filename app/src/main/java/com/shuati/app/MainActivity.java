package com.shuati.app;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import com.shuati.app.BuildConfig;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.File;

public class MainActivity extends AppCompatActivity {

    private static final String DEFAULT_SERVER_URL = "http://100.122.70.99:8087";
    private static final String[] FALLBACK_URLS = {
        "http://100.122.70.99:8087",  // Tailscale IP
        "http://192.168.2.166:8087",  // LAN IP
        "http://127.0.0.1:8087"       // Localhost
    };
    private static final String PREF_TS_DISABLED = "tailscale_disabled";
    private static final int REQ_SETTINGS = 100;
    private static final int REQ_STORAGE = 101;

    private int currentFallbackIndex = 0;
    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private TextView tvStatus;
    private SharedPreferences prefs;

    private TailscaleManager tailscaleManager;
    private boolean useTailscaleVpn = true;
    private boolean webErrorShown = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        CrashLogger.log("MainActivity", "=== onCreate start (v" + BuildConfig.VERSION_NAME + ") ===");

        prefs = getSharedPreferences("shuati", MODE_PRIVATE);
        useTailscaleVpn = !prefs.getBoolean(PREF_TS_DISABLED, false);

        webView = findViewById(R.id.web_view);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        progressBar = findViewById(R.id.progress_bar);
        tvStatus = findViewById(R.id.tv_status);

        setupWebView();
        setupSwipeRefresh();
        requestLegacyStoragePermission();

        if (useTailscaleVpn) {
            // 先把直连地址显示出来，Tailscale 在后台连接；连上再切到本地代理端口。
            // 这样即使 Tailscale 任何一步失败，界面也不会一直白屏。
            String serverUrl = prefs.getString("server_url", DEFAULT_SERVER_URL);
            loadUrl(serverUrl);
            startTailscaleVpn();
        } else {
            showStatus("Tailscale 已禁用，使用直连地址");
            loadUrl(prefs.getString("server_url", DEFAULT_SERVER_URL));
        }
    }

    private void startTailscaleVpn() {
        CrashLogger.log("Tailscale", "启动内置 Tailscale VPN...");
        showStatus("正在连接 Tailscale...");
        try {
            tailscaleManager = new TailscaleManager(this, new TailscaleManager.TailscaleCallback() {
                @Override
                public void onVpnPermissionNeeded(Intent intent) {
                    CrashLogger.log("Tailscale", "需要 VPN 权限授权");
                    showStatus("请授权 VPN 权限（拒绝也能用直连）");
                    try {
                        startActivityForResult(intent, TailscaleManager.getVpnRequestCode());
                    } catch (Throwable t) {
                        CrashLogger.logError("Tailscale", "无法打开 VPN 授权页", t);
                        onTailscaleError("无法打开 VPN 授权页");
                    }
                }

                @Override
                public void onConnected(int localPort) {
                    CrashLogger.log("Tailscale", "已连接，本地代理端口: " + localPort);
                    if (isFinishing() || webView == null) return;
                    Toast.makeText(MainActivity.this, "Tailscale 已连接", Toast.LENGTH_SHORT).show();
                    loadUrl("http://127.0.0.1:" + localPort);
                    hideStatusAfter(2000);
                }

                @Override
                public void onError(String error) {
                    CrashLogger.logError("Tailscale", "连接失败: " + error, null);
                    onTailscaleError(error);
                }
            });
            tailscaleManager.start();
        } catch (Throwable t) {
            CrashLogger.logError("Tailscale", "初始化崩溃（已捕获）", t);
            onTailscaleError("Tailscale 初始化异常: " + t.getClass().getSimpleName());
        }
    }

    /** Tailscale 任何失败：提示 + 回退直连，绝不崩溃。 */
    private void onTailscaleError(String error) {
        useTailscaleVpn = false;
        // 记住失败，避免每次启动都卡在 50s 轮询；用户可在设置里改服务器地址后重试
        prefs.edit().putBoolean(PREF_TS_DISABLED, true).apply();
        showStatus("Tailscale 不可用（" + error + "），已切直连。可在设置里配置地址");
        webErrorShown = false;
        loadUrl(prefs.getString("server_url", DEFAULT_SERVER_URL));
    }

    private void requestLegacyStoragePermission() {
        try {
            if (Build.VERSION.SDK_INT <= 28
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            }
        } catch (Throwable t) {
            CrashLogger.logError("Permission", "request storage failed", t);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            CrashLogger.log("Permission", "WRITE_EXTERNAL_STORAGE result=" + (grantResults.length > 0 ? grantResults[0] : -1));
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
                CrashLogger.log("WebView", "加载: " + url);
                view.loadUrl(url);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                currentFallbackIndex = 0;
                webErrorShown = false;
                progressBar.setVisibility(View.GONE);
                tvStatus.setVisibility(View.GONE);
                CrashLogger.log("WebView", "加载完成: " + url);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                CrashLogger.logError("WebView", "加载失败: " + failingUrl + " - " + description, null);
                if (currentFallbackIndex < FALLBACK_URLS.length - 1) {
                    currentFallbackIndex++;
                    String nextUrl = FALLBACK_URLS[currentFallbackIndex];
                    CrashLogger.log("WebView", "尝试备用地址: " + nextUrl);
                    showStatus("连接失败，尝试备用地址...");
                    view.loadUrl(nextUrl);
                } else {
                    webErrorShown = true;
                    showStatus(buildFailureHint(description));
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    progressBar.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private String buildFailureHint(String description) {
        StringBuilder sb = new StringBuilder();
        sb.append("连接失败: ").append(description).append('\n');
        sb.append("请检查服务器地址（右上角菜单 → 设置）。\n");
        String crash = CrashLogger.getLastCrashPath();
        if (crash != null && crash.length() > 0) {
            sb.append("上次崩溃日志: ").append(crash).append('\n');
        }
        sb.append("可点菜单「发送日志」把日志发给开发者。");
        return sb.toString();
    }

    private void setupSwipeRefresh() {
        swipeRefresh.setColorSchemeResources(
            android.R.color.holo_blue_bright,
            android.R.color.holo_green_light,
            android.R.color.holo_orange_light
        );
        swipeRefresh.setOnRefreshListener(() -> {
            CrashLogger.log("SwipeRefresh", "下拉刷新");
            if (webView != null) webView.reload();
            swipeRefresh.setRefreshing(false);
        });
    }

    private void loadUrl(String url) {
        if (url == null) url = DEFAULT_SERVER_URL;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        currentFallbackIndex = 0;
        CrashLogger.log("MainActivity", "加载 URL: " + url);
        showStatus("正在连接...");
        webView.loadUrl(url);
    }

    private void showStatus(String text) {
        if (tvStatus == null) return;
        tvStatus.setText(text);
        tvStatus.setVisibility(View.VISIBLE);
    }

    private void hideStatusAfter(long delayMs) {
        if (tvStatus == null) return;
        tvStatus.postDelayed(() -> {
            if (tvStatus != null) tvStatus.setVisibility(View.GONE);
        }, delayMs);
    }

    private void openSettings() {
        try {
            // 打开设置页说明用户可能想换地址：解除 Tailscale 禁用标记
            prefs.edit().putBoolean(PREF_TS_DISABLED, false).apply();
            startActivityForResult(new Intent(this, SettingsActivity.class), REQ_SETTINGS);
        } catch (Throwable t) {
            CrashLogger.logError("MainActivity", "openSettings failed", t);
        }
    }

    private void shareLog() {
        try {
            File file = CrashLogger.getShareableLogFile();
            Uri crashUri = CrashLogger.getLastCrashUri();
            Uri uri = null;
            if (crashUri != null) {
                uri = crashUri;
            } else if (file != null && file.isFile()) {
                uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            }
            if (uri == null) {
                Toast.makeText(this, "暂无日志文件", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "刷题App 日志");
            send.putExtra(Intent.EXTRA_TEXT, "崩溃/运行日志，路径: " + CrashLogger.getLastCrashPath());
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "发送日志"));
        } catch (Throwable t) {
            CrashLogger.logError("MainActivity", "shareLog failed", t);
            Toast.makeText(this, "分享失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == TailscaleManager.getVpnRequestCode()) {
            if (resultCode == RESULT_OK) {
                CrashLogger.log("Tailscale", "VPN 权限已授予");
                showStatus("VPN 已授权，正在连接 Tailscale...");
                if (tailscaleManager != null) {
                    tailscaleManager.onVpnGranted();
                } else {
                    onTailscaleError("管理器已释放");
                }
            } else {
                CrashLogger.log("Tailscale", "VPN 权限被拒绝");
                Toast.makeText(this, "未授权 VPN，将使用直连", Toast.LENGTH_LONG).show();
                onTailscaleError("用户拒绝 VPN 授权");
            }
        } else if (requestCode == REQ_SETTINGS && resultCode == RESULT_OK) {
            String serverUrl = prefs.getString("server_url", "");
            if (serverUrl != null && !serverUrl.isEmpty()) {
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
            if (webView != null) webView.reload();
            return true;
        } else if (id == R.id.action_share_log) {
            shareLog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onDestroy() {
        try {
            if (tailscaleManager != null && !isChangingConfigurations()) {
                tailscaleManager.stop();
            }
        } catch (Throwable t) {
            CrashLogger.logError("MainActivity", "stop tailscale failed", t);
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
