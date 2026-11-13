package com.shuati.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * 崩溃/运行日志落盘。
 *
 * 崩溃报告写到（按优先级）：
 *   1. /sdcard/Download/ShuatiApp/crash_yyyyMMdd_HHmmss.log  (API<=28 直接写文件)
 *   2. MediaStore Downloads/ShuatiApp/crash_*.log            (API>=29 免权限)
 *   3. <app外部私有目录>/crash_*.log                          (任何版本都可用，兜底)
 *
 * 任何失败都不会抛出异常（崩溃处理路径里绝不能再崩）。
 */
public final class CrashLogger {

    public static final String TAG = "ShuatiApp";
    private static final String SUBDIR = "ShuatiApp";
    private static final int MAX_BUFFER_LINES = 500;
    private static final Deque<String> BUFFER = new ArrayDeque<>();

    private static Context appContext;
    private static File sessionFile;
    private static volatile Uri lastCrashUri;
    private static volatile String lastCrashPath = "";
    private static volatile String lastCrashSummary = "";

    private CrashLogger() {
    }

    public static synchronized void init(Context context) {
        if (appContext != null) return;
        try {
            appContext = context.getApplicationContext();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File dir = publicDir();
            if (dir != null) {
                sessionFile = new File(dir, "app_" + ts + ".log");
            }
            if (sessionFile == null || !canWrite(sessionFile)) {
                File ext = appContext.getExternalFilesDir(null);
                if (ext == null) ext = appContext.getFilesDir();
                File d = new File(ext, "logs");
                if (!d.exists()) d.mkdirs();
                sessionFile = new File(d, "app_" + ts + ".log");
            }
            writeToFile(sessionFile, deviceHeader() + "\n=== session start ===\n", false);
        } catch (Throwable t) {
            Log.e(TAG, "CrashLogger.init failed", t);
            sessionFile = null;
        }
    }

    /** 安装默认未捕获异常处理器（必须在 Application#onCreate 里调用）。 */
    public static void installUncaughtHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                String report = buildReport(thread, ex);
                lastCrashSummary = String.valueOf(ex);
                String path = persistCrash(report);
                lastCrashPath = path == null ? "" : path;
                Log.e(TAG, "FATAL crash captured -> " + lastCrashPath, ex);
            } catch (Throwable inner) {
                Log.e(TAG, "CrashLogger failed to persist crash", inner);
            }
            try {
                Thread.sleep(400L); // 给写文件留点时间
            } catch (InterruptedException ignored) {
            }
            if (previous != null) {
                try {
                    previous.uncaughtException(thread, ex);
                    return;
                } catch (Throwable ignored) {
                }
            }
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        });
    }

    public static void log(String tag, String message) {
        String line = timestamp() + " [" + tag + "] " + message;
        Log.d(TAG, tag + ": " + message);
        append(line);
    }

    public static void logError(String tag, String message, Throwable t) {
        String line = timestamp() + " [" + tag + "] ERROR " + message;
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            line = line + "\n" + sw;
        }
        Log.e(TAG, tag + ": " + message, t);
        append(line);
    }

    public static String getLastCrashPath() {
        return lastCrashPath;
    }

    public static String getLastCrashSummary() {
        return lastCrashSummary;
    }

    public static Uri getLastCrashUri() {
        return lastCrashUri;
    }

    public static File getSessionFile() {
        return sessionFile;
    }

    /** 可分享给用户的日志文件（优先崩溃日志，其次本次会话日志）。 */
    public static File getShareableLogFile() {
        try {
            File f = new File(lastCrashPath);
            if (lastCrashPath.length() > 0 && f.isFile() && f.canRead()) return f;
        } catch (Throwable ignored) {
        }
        File f = sessionFile;
        return (f != null && f.isFile()) ? f : null;
    }

    // ---------------------------------------------------------------- internal

    private static synchronized void append(String line) {
        if (BUFFER.size() >= MAX_BUFFER_LINES) BUFFER.removeFirst();
        BUFFER.addLast(line);
        if (sessionFile == null) return;
        try {
            writeToFile(sessionFile, line + "\n", true);
        } catch (Throwable ignored) {
        }
    }

    private static String persistCrash(String report) {
        String name = "crash_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".log";

        // 1) 直接写公共 Download 目录（API<=28 有 WRITE_EXTERNAL_STORAGE 时可用）
        try {
            File dir = publicDir();
            if (dir != null && (dir.exists() || dir.mkdirs())) {
                File f = new File(dir, name);
                if (writeToFile(f, report, false) && f.length() > 0) {
                    lastCrashUri = null;
                    return f.getAbsolutePath();
                }
            }
        } catch (Throwable ignored) {
        }

        // 2) MediaStore Downloads（Android 10+ 免权限）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && appContext != null) {
            try {
                ContentResolver cr = appContext.getContentResolver();
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR);
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    try (OutputStream os = cr.openOutputStream(uri)) {
                        if (os != null) {
                            os.write(report.getBytes(StandardCharsets.UTF_8));
                            os.flush();
                        }
                    }
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    cr.update(uri, done, null, null);
                    lastCrashUri = uri;
                    return "content://media" + uri.getPath();
                }
            } catch (Throwable ignored) {
            }
        }

        // 3) 应用外部私有目录兜底
        try {
            File ext = appContext != null ? appContext.getExternalFilesDir(null) : null;
            if (ext == null) return null;
            File dir = new File(ext, "logs");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, name);
            File latest = new File(dir, "latest_crash.log");
            writeToFile(f, report, false);
            writeToFile(latest, report, false);
            lastCrashUri = null;
            return f.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    private static File publicDir() {
        try {
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloads == null) return null;
            File dir = new File(downloads, SUBDIR);
            if (!dir.exists()) dir.mkdirs();
            return dir.isDirectory() ? dir : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean canWrite(File f) {
        try {
            File parent = f.getParentFile();
            return parent != null && parent.isDirectory() && parent.canWrite();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean writeToFile(File f, String text, boolean append) {
        if (f == null) return false;
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f, append);
            Writer w = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            w.write(text);
            w.flush();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "writeToFile failed: " + f, t);
            return false;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String buildReport(Thread thread, Throwable ex) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append(deviceHeader()).append('\n');
        sb.append("=== FATAL EXCEPTION ===\n");
        sb.append("Time    : ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(new Date())).append('\n');
        sb.append("Thread  : ").append(thread == null ? "?" : thread.getName()).append('\n');
        sb.append("Exception: ").append(String.valueOf(ex)).append("\n\n");

        if (ex != null) {
            Throwable t = ex;
            int depth = 0;
            while (t != null && depth < 12) {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                sb.append(sw).append('\n');
                t = t.getCause();
                depth++;
            }
        }

        sb.append("\n=== recent app log (last ").append(BUFFER.size()).append(" lines) ===\n");
        synchronized (CrashLogger.class) {
            for (String line : BUFFER) {
                sb.append(line).append('\n');
            }
        }

        sb.append("\n=== device ===\n");
        sb.append(dumpRuntime());
        return sb.toString();
    }

    private static String deviceHeader() {
        return "===== ShuatiApp crash log =====\n" + "app   : " + appLabel() + "\ndevice: " + Build.MANUFACTURER + " " + Build.MODEL
                + " (" + Build.DEVICE + ")\nandroid: API " + Build.VERSION.SDK_INT + " (" + Build.VERSION.RELEASE + ")\nabis  : " + join(Build.SUPPORTED_ABIS);
    }

    private static String dumpRuntime() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("threads: ").append(Thread.activeCount()).append('\n');
            sb.append("java.vm: ").append(System.getProperty("java.vm.version")).append('\n');
            sb.append("uptime : ").append(android.os.SystemClock.elapsedRealtime() / 1000).append("s\n");
            sb.append("mem    : ").append(Runtime.getRuntime().totalMemory() / 1024 / 1024).append("MB total / ")
                    .append(Runtime.getRuntime().maxMemory() / 1024 / 1024).append("MB max\n");
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static String appLabel() {
        try {
            return appContext.getPackageName() + " v" + appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "com.shuati.app";
        }
    }

    private static String join(String[] arr) {
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    private static String timestamp() {
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    /** 用于把文本塞进分享 Intent 的辅助方法。 */
    public static String readText(File f) {
        if (f == null || !f.isFile()) return "";
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }
}
