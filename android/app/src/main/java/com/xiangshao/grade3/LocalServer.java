package com.xiangshao.grade3;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 零依赖本地静态服务器（仅用 java.net.ServerSocket，不引入任何第三方/AndroidX 库，
 * 也不依赖各 OEM 实现不一致的 com.sun.net.httpserver）。
 *
 * 职责：
 *  1. 首次启动（或 APK 更新后）把打包进 APK 的 assets/www（整站 + 离线发音 mp3）解压到
 *     应用私有目录 getFilesDir()/www，避免直接读 assets 时压缩文件拿不到 Content-Length、
 *     大文件无法 Range 等问题；
 *  2. 在 127.0.0.1:PORT 起一个只监听本机的 HTTP 服务，WebView 用 http://127.0.0.1:PORT/ 加载，
 *     彻底离线、无网络/连接失败；
 *  3. 支持 MIME 识别、SPA 回退、HTTP Range（音频拖动/续传）、目录穿越防护。
 *
 * 为什么不用 file:///android_asset/ 直接加载：
 *   站点 H5 构建产物以 <script type="module"> 加载 chunk，file:// 下浏览器会因 CORS（origin=null）
 *   拦截 ES Module，导致整页白屏；localhost 的 http 才能正常加载模块。
 */
public class LocalServer {

    private static final String TAG = "LocalServer";
    private static final int PORT = 8080;          // 仅监听本机回环，外部不可访问
    private static final String ASSET_ROOT = "www";

    private final Context context;
    private final File wwwDir;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean running = false;

    public LocalServer(Context context) {
        this.context = context.getApplicationContext();
        this.wwwDir = new File(this.context.getFilesDir(), "www");
    }

    public int getPort() {
        return PORT;
    }

    /**
     * 解压（按需）+ 绑定端口 + 启动 accept 循环。bind 成功返回即代表端口已就绪，
     * 调用方可以放心 loadUrl；accept 循环在独立线程中运行。
     */
    public void start() throws IOException {
        ensureExtracted();

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("127.0.0.1", PORT));

        executor = Executors.newCachedThreadPool();
        running = true;
        Thread acceptThread = new Thread(this::acceptLoop, "LocalServer-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        Log.i(TAG, "local server started -> http://127.0.0.1:" + PORT + "/");
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Throwable ignore) {
        }
        try {
            if (executor != null) executor.shutdownNow();
        } catch (Throwable ignore) {
        }
    }

    // ───────────────────────── 解压：assets/www → 私有目录 ─────────────────────────

    private void ensureExtracted() throws IOException {
        long stamp = getApkUpdateTime();
        File stampFile = new File(wwwDir, ".stamp");
        if (wwwDir.isDirectory() && wwwDir.list().length > 0 && readStamp(stampFile) == stamp) {
            Log.i(TAG, "www already extracted (stamp matches), skip");
            verifyCriticalFiles();
            return;
        }
        Log.i(TAG, "extracting www from assets (stamp=" + stamp + ") …");
        deleteRecursively(wwwDir);
        wwwDir.mkdirs();
        try {
            copyAssetDir(context.getAssets(), ASSET_ROOT, wwwDir);
        } catch (IOException e) {
            Log.e(TAG, "extract failed", e);
            throw new IOException("解压整站失败：" + e.getMessage(), e);
        }
        verifyCriticalFiles();
        writeStamp(stampFile, stamp);
        Log.i(TAG, "extraction complete");
    }

    private void verifyCriticalFiles() throws IOException {
        if (!new File(wwwDir, "index.html").exists()) {
            throw new IOException("解压后找不到 index.html（整站可能不完整）");
        }
    }

    private long getApkUpdateTime() {
        try {
            PackageInfo pi = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return pi.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private long readStamp(File f) {
        if (f == null || !f.exists()) return -1;
        try {
            return Long.parseLong(new String(readAll(new FileInputStream(f))).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private void writeStamp(File f, long stamp) {
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(String.valueOf(stamp).getBytes("UTF-8"));
        } catch (IOException e) {
            Log.e(TAG, "write stamp failed", e);
        }
    }

    private void copyAssetDir(AssetManager am, String assetPath, File outDir) throws IOException {
        String[] list = am.list(assetPath);
        if (list == null) return;
        if (list.length == 0) {
            // am.list 对“文件”返回空数组
            copyAssetFile(am, assetPath, outDir);
            return;
        }
        for (String name : list) {
            File child = new File(outDir, name);
            String childAsset = assetPath + "/" + name;
            try {
                String[] sub = am.list(childAsset);
                if (sub != null && sub.length > 0) {
                    child.mkdirs();
                    copyAssetDir(am, childAsset, child);
                } else {
                    copyAssetFile(am, childAsset, outDir);
                }
            } catch (IOException e) {
                Log.e(TAG, "copy failed: " + childAsset, e);
            }
        }
    }

    private void copyAssetFile(AssetManager am, String assetPath, File outDir) throws IOException {
        File out = new File(outDir, new File(assetPath).getName());
        try (InputStream in = am.open(assetPath);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
        }
    }

    private static void deleteRecursively(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteRecursively(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    // ───────────────────────── 极简 HTTP/1.1 服务 ─────────────────────────

    private void acceptLoop() {
        while (running) {
            try {
                Socket sock = serverSocket.accept();
                executor.execute(() -> handleSocket(sock));
            } catch (IOException e) {
                if (running) Log.e(TAG, "accept failed", e);
            }
        }
    }

    private void handleSocket(Socket sock) {
        try {
            sock.setSoTimeout(30000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), "ISO-8859-1"));
            OutputStream out = sock.getOutputStream();

            String requestLine = reader.readLine();
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendStatus(out, 400, "Bad Request", "text/plain; charset=utf-8", "Bad Request");
                return;
            }
            String method = parts[0];
            String uri = parts[1];

            // 读取请求头（仅关心 Range）
            String range = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) break;
                int idx = line.indexOf(':');
                if (idx > 0) {
                    String h = line.substring(0, idx).trim();
                    if (h.equalsIgnoreCase("Range")) range = line.substring(idx + 1).trim();
                }
            }

            if (!method.equals("GET") && !method.equals("HEAD")) {
                sendStatus(out, 405, "Method Not Allowed", "text/plain; charset=utf-8",
                        "Method Not Allowed");
                return;
            }
            serveFile(out, uri, range, method.equals("HEAD"));
        } catch (Throwable t) {
            Log.e(TAG, "handleSocket error", t);
        } finally {
            try {
                sock.close();
            } catch (Throwable ignore) {
            }
        }
    }

    private void serveFile(OutputStream out, String uri, String range, boolean head)
            throws IOException {
        int q = uri.indexOf('?');
        if (q >= 0) uri = uri.substring(0, q);
        if (uri.isEmpty() || uri.equals("/")) uri = "/index.html";
        String clean = uri;
        while (clean.startsWith("/")) clean = clean.substring(1);

        File target = new File(wwwDir, clean).getCanonicalFile();
        String base = wwwDir.getCanonicalPath();
        if (!target.getPath().equals(base)
                && !target.getPath().startsWith(base + File.separator)) {
            sendStatus(out, 403, "Forbidden", "text/plain; charset=utf-8", "Forbidden");
            return;
        }
        if (target.isDirectory()) target = new File(target, "index.html");
        if (!target.exists()) {
            // 站点用 hash 路由：真实请求只可能是静态资源。找不到的静态资源必须回 404，
            // 绝不能回退到 index.html——否则 .js/.css 请求拿到 HTML 会引发 MIME 错误。
            String lastName = new File(clean).getName();
            if (lastName.indexOf('.') >= 0) {
                sendStatus(out, 404, "Not Found", "text/plain; charset=utf-8", "Not Found");
                return;
            }
            target = new File(wwwDir, "index.html"); // 路由请求 → SPA 回退
            if (!target.exists()) {
                sendStatus(out, 404, "Not Found", "text/plain; charset=utf-8", "Not Found");
                return;
            }
        }

        String name = target.getName().toLowerCase();
        String mime = MIME.getOrDefault(ext(name), "application/octet-stream");
        long total = target.length();

        long start = 0, end = total - 1;
        boolean isRange = false;
        if (range != null && range.startsWith("bytes=")) {
            String[] rp = range.substring(6).split("-");
            try {
                start = Long.parseLong(rp[0]);
                if (rp.length > 1 && !rp[1].isEmpty()) end = Long.parseLong(rp[1]);
                if (start >= 0 && start <= end && end < total) isRange = true;
            } catch (NumberFormatException ignore) {
                // 非法 Range，退化为整文件
            }
        }

        StringBuilder headers = new StringBuilder();
        if (isRange) {
            long len = end - start + 1;
            headers.append("HTTP/1.1 206 Partial Content\r\n");
            headers.append("Content-Range: bytes ").append(start).append("-")
                    .append(end).append("/").append(total).append("\r\n");
            headers.append("Content-Length: ").append(len).append("\r\n");
        } else {
            headers.append("HTTP/1.1 200 OK\r\n");
            headers.append("Content-Length: ").append(total).append("\r\n");
        }
        headers.append("Content-Type: ").append(mime).append("\r\n");
        headers.append("Access-Control-Allow-Origin: *\r\n");
        headers.append("Accept-Ranges: bytes\r\n");
        if (name.endsWith(".html") || name.endsWith(".webmanifest")) {
            headers.append("Cache-Control: no-cache\r\n");
        } else {
            headers.append("Cache-Control: public, max-age=31536000, immutable\r\n");
        }
        headers.append("Connection: close\r\n");
        headers.append("\r\n");

        out.write(headers.toString().getBytes("ISO-8859-1"));
        out.flush();

        if (!head) {
            try (FileInputStream fis = new FileInputStream(target)) {
                if (isRange) {
                    fis.skip(start);
                    pipe(fis, out, end - start + 1);
                } else {
                    pipe(fis, out, total);
                }
            }
        }
        out.flush();
    }

    private void sendStatus(OutputStream out, int code, String reason, String mime, String body)
            throws IOException {
        byte[] bodyBytes = body.getBytes("UTF-8");
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(" ").append(reason).append("\r\n");
        h.append("Content-Type: ").append(mime).append("\r\n");
        h.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        h.append("Connection: close\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes("ISO-8859-1"));
        out.write(bodyBytes);
        out.flush();
    }

    private void pipe(FileInputStream fis, OutputStream os, long len) throws IOException {
        byte[] buf = new byte[8192];
        long remaining = len;
        int n;
        while (remaining > 0
                && (n = fis.read(buf, 0, (int) Math.min(buf.length, remaining))) != -1) {
            os.write(buf, 0, n);
            remaining -= n;
        }
    }

    private String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    private static final Map<String, String> MIME = buildMime();

    private static Map<String, String> buildMime() {
        Map<String, String> m = new HashMap<>();
        m.put(".html", "text/html; charset=utf-8");
        m.put(".htm", "text/html; charset=utf-8");
        m.put(".js", "application/javascript; charset=utf-8");
        m.put(".mjs", "application/javascript; charset=utf-8");
        m.put(".css", "text/css; charset=utf-8");
        m.put(".json", "application/json; charset=utf-8");
        m.put(".webmanifest", "application/manifest+json; charset=utf-8");
        m.put(".png", "image/png");
        m.put(".jpg", "image/jpeg");
        m.put(".jpeg", "image/jpeg");
        m.put(".gif", "image/gif");
        m.put(".webp", "image/webp");
        m.put(".svg", "image/svg+xml");
        m.put(".ico", "image/x-icon");
        m.put(".mp3", "audio/mpeg");
        m.put(".wav", "audio/wav");
        m.put(".ogg", "audio/ogg");
        m.put(".woff", "font/woff");
        m.put(".woff2", "font/woff2");
        m.put(".ttf", "font/ttf");
        m.put(".txt", "text/plain; charset=utf-8");
        m.put(".xml", "application/xml");
        m.put(".map", "application/json");
        return m;
    }
}
