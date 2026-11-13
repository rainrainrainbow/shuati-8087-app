package com.tailscale.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Embeds Tailscale in-process and exposes a local TCP proxy port.
 *
 * VpnService 只是为了拿到 NETLINK_ROUTE 权限（Tailscale 的 netmon 需要枚举网卡），
 * 从不调用 Builder.establish()，不创建 TUN。
 *
 * 连接失败/超时/前台服务受限 时只会设置 [lastError] 并停止自身，
 * 绝不抛异常到主线程（v5 的 Android 14 闪退就是这样来的）。
 */
class TailscaleProxyService : VpnService() {

    companion object {
        private const val TAG = "TailscaleProxyService"
        private const val CHANNEL_ID = "tailscale_proxy"
        private const val NOTIF_ID = 2

        const val ACTION_START = "com.tailscale.mobile.PROXY_START"
        const val ACTION_STOP = "com.tailscale.mobile.PROXY_STOP"
        private const val EXTRA_CONFIG = "config"

        /** True once the local proxy ServerSocket is listening. */
        @Volatile
        var isReady = false
            private set

        /** 失败原因；null 表示尚未失败。供 TailscaleManager 轮询。 */
        @Volatile
        var lastError: String? = null
            private set

        /** Status message suitable for display in a UI. */
        @Volatile
        var status = "Tailscale: idle"
            private set

        /** Local port on 127.0.0.1 that proxies TCP to the configured peer. */
        @Volatile
        var proxyPort = 0
            private set

        fun start(context: Context, config: TailscaleConfig) {
            try {
                val intent = Intent(context, TailscaleProxyService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_CONFIG, config)
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                lastError = "startForegroundService failed: ${t.javaClass.simpleName}: ${t.message}"
                Log.e(TAG, "start() failed", t)
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, TailscaleProxyService::class.java).setAction(ACTION_STOP)
                )
            } catch (t: Throwable) {
                Log.w(TAG, "stop() failed", t)
            }
        }
    }

    @Volatile
    private var config: TailscaleConfig? = null

    @Volatile
    private var proxyServer: ServerSocket? = null

    @Volatile
    private var stopped = false

    override fun onCreate() {
        super.onCreate()
        try {
            createChannelIfNeeded()
        } catch (t: Throwable) {
            Log.w(TAG, "createChannel failed", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (intent?.action == ACTION_STOP) {
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }

            @Suppress("DEPRECATION")
            val cfg = intent?.getSerializableExtra(EXTRA_CONFIG) as? TailscaleConfig
            if (cfg == null) {
                lastError = "no config"
                stopSelf()
                return START_NOT_STICKY
            }
            config = cfg
            lastError = null

            // 前台服务：类型必须与 AndroidManifest 的 foregroundServiceType 一致，
            // 否则 Android 14 会直接抛异常杀掉进程。失败就降级退出，不崩。
            if (!startForegroundSafely()) {
                lastError = "前台服务启动失败（系统限制），已回退直连"
                stopSelf()
                return START_NOT_STICKY
            }

            Thread { startTailscale() }.also { it.isDaemon = true; it.name = "tailscale-init" }.start()
            return START_STICKY
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "onStartCommand failed", t)
            runCatching { stopSelf() }
            return START_NOT_STICKY
        }
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked")
        runCatching { shutdown() }
        runCatching { stopSelf() }
        super.onRevoke()
    }

    override fun onDestroy() {
        runCatching { shutdown() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    private fun startForegroundSafely(): Boolean {
        val notif: Notification = try {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("刷题")
                .setContentText("Tailscale 连接中…")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "build notification failed", t)
            return false
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground(type) failed, retry plain", t)
            try {
                startForeground(NOTIF_ID, notif)
                true
            } catch (t2: Throwable) {
                Log.e(TAG, "startForeground plain failed", t2)
                false
            }
        }
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Tailscale 连接", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun shutdown() {
        stopped = true
        isReady = false
        proxyPort = 0
        proxyServer?.runCatching { close() }
        proxyServer = null
        TailscaleJni.close()
        runCatching { stopForeground(true) }
        updateStatus("Tailscale: stopped")
    }

    private fun startTailscale() {
        val cfg = config ?: return
        if (stopped) return
        try {
            updateStatus("Tailscale: connecting...")
            if (!TailscaleJni.isAvailable()) {
                lastError = "Tailscale 组件不可用（${TailscaleJni.lastLoadError ?: "native lib load failed"}）"
                return
            }
            val ret = TailscaleJni.connect(filesDir.absolutePath, cfg.authKey, cfg.hostname)
            if (stopped) return
            if (ret != 0) {
                lastError = "Tailscale 初始化失败（connect=$ret）"
                updateStatus("Tailscale: connect failed")
                return
            }
            updateStatus("Tailscale: authenticating...")
            waitForAuth()
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "startTailscale failed", t)
            updateStatus("Tailscale error: ${t.message}")
        }
    }

    private fun waitForAuth() {
        val deadline = System.currentTimeMillis() + 45_000L
        var attempt = 0
        while (System.currentTimeMillis() < deadline && !stopped) {
            attempt++
            val ip = TailscaleJni.getIp()
            if (ip.isNotEmpty()) {
                Log.i(TAG, "Authenticated, ip=$ip (attempt $attempt)")
                updateStatus("Tailscale: authenticated ($ip)")
                startLocalProxy()
                return
            }
            updateStatus("Tailscale: authenticating (attempt $attempt)...")
            try {
                Thread.sleep(1500L)
            } catch (ignored: InterruptedException) {
                return
            }
        }
        if (!stopped && !isReady) {
            lastError = "Tailscale 认证超时（45s）"
            updateStatus("Tailscale error: auth timeout")
        }
    }

    private fun startLocalProxy() {
        val cfg = config ?: return
        try {
            val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            proxyServer = ss
            proxyPort = ss.localPort
            isReady = true
            lastError = null
            updateStatus("Tailscale: ready (proxy :${ss.localPort})")
            Log.i(TAG, "Proxy listening on 127.0.0.1:${ss.localPort} -> ${cfg.peerIp}:${cfg.peerPort}")

            while (!ss.isClosed && !stopped) {
                val client: Socket = try {
                    ss.accept()
                } catch (e: Exception) {
                    break
                }
                Thread { runCatching { handleClient(client, cfg) } }
                    .also { it.isDaemon = true; it.name = "proxy-${client.port}" }
                    .start()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Proxy error", t)
            isReady = false
            lastError = "本地代理启动失败: ${t.message}"
            updateStatus("Tailscale error: proxy failed")
        }
    }

    private fun handleClient(client: Socket, cfg: TailscaleConfig) {
        val target = "${cfg.peerIp}:${cfg.peerPort}"
        val fd = TailscaleJni.dial(target)
        if (fd < 0) {
            Log.e(TAG, "TsnetDial to $target failed")
            client.runCatching { close() }
            return
        }
        Log.i(TAG, "Proxy: fd=$fd connected to $target")
        updateStatus("Connected")

        val pfd = ParcelFileDescriptor.adoptFd(fd)
        val tsIn = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        val tsOut = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
        val clientIn = client.getInputStream()
        val clientOut = client.getOutputStream()
        val bufUp = ByteArray(8192)
        val bufDown = ByteArray(8192)

        val up = Thread {
            try {
                while (true) {
                    val n = clientIn.read(bufUp)
                    if (n < 0) break
                    tsOut.write(bufUp, 0, n)
                    tsOut.flush()
                }
            } catch (ignored: Throwable) {
            } finally {
                client.runCatching { close() }
                runCatching { pfd.close() }
            }
        }.also { it.isDaemon = true; it.start() }

        try {
            while (true) {
                val n = tsIn.read(bufDown)
                if (n < 0) break
                clientOut.write(bufDown, 0, n)
                clientOut.flush()
            }
        } catch (ignored: Throwable) {
        } finally {
            client.runCatching { close() }
            up.interrupt()
        }
        Log.i(TAG, "Proxy: connection closed")
    }

    private fun updateStatus(msg: String) {
        status = msg
        Log.i(TAG, msg)
    }
}
