package com.xiangshao.grade3;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.util.Collections;

/**
 * 英语小花园 —— 全屏 WebView 原生壳。
 *
 * 加载方式（v1.2.1 起，file:// 单文件直载 + WebView 配置对齐）：
 *   用 file:///android_asset/www/index.html 直接加载 App 内嵌的「单文件整站」。
 *   · 整站（index.html）在构建期由 vite-plugin-singlefile 把 JS/CSS 全内联进一个文件；
 *   · 音频 / 字体 / 图标 / 插画作为同目录的 static/ 兄弟资源随包发布；
 *   · 资源 URL 在运行期拼成「文档根绝对地址」（file:///android_asset/www/static/...）。
 *
 * ── file:// 下的三个 WebSettings 开关（务必区分，历史故障根源之一）──────────
 *  1) setAllowFileAccess(true)：控制 WebView 能否加载 file:// 资源。
 *     官方语义："Enables or disables file access within WebView ... The default value is
 *     true for apps targeting Build.VERSION_CODES.Q and below, and false when targeting
 *     Build.VERSION_CODES.R and above." 本工程 targetSdk=36 → 默认 false，必须显式开。
 *  2) setAllowFileAccessFromFileURLs(true)：控制 **JavaScript 发起的跨源请求**
 *     （XHR / fetch）能否从 file:// 上下文读取其它 file:// URL。
 *     官方语义："Sets whether cross-origin requests in the context of a file scheme URL
 *     should be allowed to access content from other file scheme URLs. Note that some
 *     accesses such as image HTML elements don't follow same-origin rules and aren't
 *     affected by this setting." 默认 API>=16 为 false。
 *     → <img>/<audio>/CSS url() 字体插画 **不受它影响**；但 InstallHint.vue 里
 *       fetch('file:///android_asset/www/static/audio-manifest.json') 与批量下载音频
 *       的 fetch(url) **受它影响**。不开这个开关，真机上「离线下载全部发音」必失败，
 *       而桌面无头 Chrome 若加了 --allow-file-access-from-files 却会假绿。
 *     ⚠️ 该 API 自 API 30 起被标记 deprecated（推荐 WebViewAssetLoader）。本 App 只加载
 *        自己签名 APK 内、构建期固定的可信资产，不打开任何外部/可写文件、不加载外部网页
 *        （见 shouldOverrideUrlLoading），故风险可控；同时保持
 *        setAllowUniversalAccessFromFileURLs(false)，file:// JS **不能**访问任何 web 源。
 *  3) setAllowUniversalAccessFromFileURLs(false)：比 2) 更宽（可访问任意源），本项目不需要，
 *     显式关闭，也避免部分 ROM 的默认值差异把 2) 的设置覆盖掉
 *     （官方文档：2) 的值在 3) 为 true 时会被忽略）。
 *
 * 设计目标（不变）：
 *  1. 真正全屏无地址栏；2. 独立图标、返回键在 App 内导航；3. 不依赖域名验证；
 *  4. 自动隐藏站点里的"装到手机桌面"提示条（已是 App，不需要它）。
 */
public class MainActivity extends Activity {

    // APK 内整站以 file:// 直载（单文件 index.html + 同目录 static/）。
    private static final String FILE_URL = "file:///android_asset/www/index.html";

    private WebView webView;
    private ProgressBar progressBar;
    private View offlineView;
    private TextView offlineDetail;

    /**
     * 「主文档已成功渲染出 #app 内容」的标记，由 onPageFinished 里的 JS 探测回填。
     *
     * 用途：防止 onReceivedError 的误报把**已经渲染好的页面**盖成失败页
     * （历史故障：offlineView 是 #FFFFFF 全屏白底，用户看到的「白屏」很可能就是它，
     *  见提交 e89bad3 的自述「offlineView 实为白色失败页」）。
     *
     * 默认 false 是安全默认：JS 探测尚未完成 / 主帧真的失败时，失败页照常显示。
     * 所有 WebViewClient 回调都在 UI 线程，普通 boolean 即可，无需 volatile。
     */
    private boolean pageRendered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        progressBar = findViewById(R.id.progressBar);
        offlineView = findViewById(R.id.offlineView);
        offlineDetail = findViewById(R.id.offlineDetail);
        webView = findViewById(R.id.webView);

        // 让 WebView 与站点的会话 Cookie 持久化（站点无 sharecode 时本就不需要，但保留无副作用）。
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        configureWebSettings(webView.getSettings());

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                offlineView.setVisibility(View.GONE);
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(0);
                // 新一次主文档加载开始：上一次的「已渲染」标记作废。
                pageRendered = false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                // 一次 JS 调用同时做两件事：隐藏站点"装到手机桌面"提示条 +
                // 探测 #app 是否真的渲染出了实质内容（作为「页面是好的」判据）。
                //
                // 判据与 verify_apk_payload.mjs 的 guard probe 断言保持一致：
                // 真实主帧失败时 WebView 显示的是内部错误页，没有 #app → 返回 '0'；
                // 页面成功挂载 → 返回 '1'。
                view.evaluateJavascript(
                        "(function(){try{var els=document.querySelectorAll('*');"
                                + "for(var i=0;i<els.length;i++){"
                                + "var t=els[i].textContent||'';"
                                + "if(t.indexOf('装到手机桌面')>=0){els[i].style.display='none';break;}}"
                                + "var a=document.getElementById('app');"
                                + "return (a && a.childElementCount>0 && (a.innerText||'').trim().length>50) ? '1':'0';"
                                + "}catch(e){return '0';}})();",
                        value -> {
                            pageRendered = value != null && value.contains("1");
                            // 页面确实已渲染成功 → 撤掉此前可能因瞬时错误 / OEM 误报盖上的失败页。
                            // 这不是「隐藏 offlineView 绕过」：只有 #app 有实质内容才收起，
                            // 真实主帧失败时 #app 不存在，失败页保持可见，错误详情也始终保留。
                            if (pageRendered && offlineView.getVisibility() == View.VISIBLE) {
                                offlineView.setVisibility(View.GONE);
                            }
                        });
            }

            /**
             * 纵深防御：让 sw.js 永远是 404。
             *
             * 前端 main.ts 已在 file:// 下条件跳过 SW 注册（file:// 不是安全上下文，
             * 且 SW 一旦用缓存的旧 index.html 接管页面就会在下次启动白屏）。
             * 这一层拦的是「将来有人误删前端条件、或站点被以 http(s) 打开」的情况。
             *
             * 早期版本把 404 写在 ServiceWorkerController.setServiceWorkerClient() 里，
             * 但该回调**只在 Service Worker 已安装、且由 SW 自己发起请求时**才触发，
             * 拦不到注册阶段对 sw.js 的请求 —— 是一层看似有效、实则无效的安慰性防护。
             * 改到 WebViewClient.shouldInterceptRequest 后对本 WebView 的所有请求生效。
             */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String u = request.getUrl() != null ? request.getUrl().toString() : "";
                if (u.contains("/sw.js")) {
                    return new WebResourceResponse(
                            "application/javascript", "UTF-8", 404, "Not Found",
                            Collections.singletonMap("Cache-Control", "no-store"),
                            new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }

            /**
             * 本 App 完全离线，页面只应停留在 file:// 资产内。
             * 由于上面开启了 file→file 的 JS 读取能力，这里显式阻断 http(s) 导航，
             * 避免把外部（不可信）网页引入同一个 WebView 上下文。
             * 哈希路由是同文档片段跳转，不走这个回调，不会影响 App 内页面切换。
             */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl() != null ? request.getUrl().getScheme() : null;
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    return true;
                }
                return false;
            }

            /**
             * 主帧错误处理。
             *
             * 官方语义（AOSP frameworks/base/core/java/android/webkit/WebViewClient.java）：
             *   "Note that unlike the deprecated version of the callback, the new version will be
             *    called for any resource (iframe, image, etc), not just for the main page."
             *   → 所以**必须**用 request.isForMainFrame() 过滤，否则一个图标 / 字体 / manifest
             *     的 404 就会把整页盖成失败页（历史「白屏」的一种成因）。
             *   另：AOSP 基类默认实现在 isForMainFrame() 时会把错误**转发到旧版 deprecated
             *   重载**；本 override 不调用 super，避免同一错误触发两次 showOffline。
             *   个别 OEM ROM 若直接调用旧重载，由下面那个 override 单独兜底。
             *
             * 残余风险：OEM 对 file:// 下 isForMainFrame() 的实现偏差、以及页面渲染完成后
             * 才到达的延迟错误，仍可能误判为主帧。这一层由 showOffline() 的 pageRendered
             * 守卫兜底（见其注释），且失败页已改为 #FFF4E5 非白底，不会再呈现「白屏」。
             */
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                       WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    String desc = (error != null) ? error.getDescription().toString() : "未知错误";
                    showOffline("页面加载失败：" + desc);
                }
            }

            /**
             * 兼容部分 OEM 实现：它们仍可能走这个已废弃的重载
             * （e89bad3 当初为「便于真机排查」加的，保留但修正判定）。
             *
             * ⚠️ 旧判定 `view.getUrl().equals(failingUrl)` 在 file:// + uni-app 哈希路由下不可靠：
             *    view.getUrl() 可能带 #/pages/... 后缀，主帧加载失败时甚至为 null；
             *    而 AOSP 基类转发过来的 failingUrl 是 request.getUrl()（不含 hash）。
             *    两边不相等 → **真实主帧失败反而不显示失败页**，用户只看到
             *    FrameLayout 的 #7C3AED 紫底，同样被当成「白屏/空白」。
             *    改为与固定 FILE_URL 比较（忽略 hash / query），只认主文档、不认子资源。
             */
            @Deprecated
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (isMainDocumentUrl(failingUrl)) {
                    showOffline("页面加载失败(code=" + errorCode + ")：" + description);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });

        // 点击离线提示的"重试"重新加载。
        offlineView.setOnClickListener(v -> {
            offlineView.setVisibility(View.GONE);
            webView.reload();
        });

        // file:// 直载：整站随包发布、无版本错位，直接加载即可（无需清空缓存）。
        webView.loadUrl(FILE_URL);
    }

    /**
     * WebView 配置集中在一处，便于对照 Android 官方 WebSettings 语义复核。
     * setAllowFileAccessFromFileURLs 自 API 30 起 deprecated，但本工程仍需它来保证
     * file:// 下 fetch() 可用；用方法级 SuppressWarnings 显式标注该取舍。
     */
    @SuppressWarnings("deprecation")
    private void configureWebSettings(WebSettings ws) {
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        // 1) targetSdk>=30 时默认 false → 必须显式 true，否则 file:// 资源（含子资源）加载被拒。
        //    官方：即使为 false，file:///android_asset 与 file:///android_res 仍可访问；
        //    但显式开启能消除 OEM 实现差异，且本 App 只加载自己 APK 内的资产。
        ws.setAllowFileAccess(true);
        // 2) 让 file:// 页面的 fetch/XHR 能读取同目录 file:// 资源（audio-manifest.json、
        //    批量下载音频）。只影响 JS 跨源请求，<img>/<audio>/CSS url() 不受影响。
        ws.setAllowFileAccessFromFileURLs(true);
        // 3) 不需要「任意源」访问；显式 false，避免覆盖 2) 且不留 web 源访问面。
        ws.setAllowUniversalAccessFromFileURLs(false);

        // content:// _provider 本 App 完全不用；关闭后不会误触发主帧错误。
        ws.setAllowContentAccess(false);
        // 页面是 file://，没有 http 子资源；此模式对 file:// 无影响，保留为纯安全配置。
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // 追加自定义 UA，便于站点识别"已在 App 内"并隐藏桌面安装提示。
        ws.setUserAgentString(ws.getUserAgentString() + " XiangshaoApp/1.0");
    }

    /**
     * failingUrl 是否指向本 App 的主文档（忽略 hash 与 query）。
     * 用于旧版 onReceivedError 重载过滤子资源，且不受 uni-app 哈希路由影响。
     */
    private static boolean isMainDocumentUrl(String url) {
        if (url == null) return false;
        String path = url;
        int cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        return FILE_URL.equals(path);
    }

    /**
     * 展示失败提示，并把具体错误写进 offlineDetail（含 FILE_URL），方便排查
     * （如资源缺失导致的 ERR_FILE_NOT_FOUND）。
     *
     * 失败页是 #FFF4E5 浅橙底 + 深色标题 + 红字详情：既区别于「纯白空屏」，
     * 也区别于 WebView 真空白时透出的 FrameLayout #7C3AED 紫底。
     * e89bad3 时期的 #FFFFFF 白底全屏覆盖层是用户口中「白屏」的直接形态，
     * 本轮不再复现该形态，且**不通过隐藏 offlineView 来掩盖真实错误**。
     *
     * ⚠️ 防「把好页面盖成白页」：若 onPageFinished 的 JS 探测已确认 #app 有实质内容，
     *    说明当前页面其实渲染成功，这次错误至多是延迟子资源失败或 OEM 的 isForMainFrame
     *    误判 —— 此时**只保留错误详情，不用失败页覆盖好页面**。
     *    这不是绕过：真实主帧失败时 #app 不存在，pageRendered 恒为 false，
     *    失败页照常全屏显示；错误文本始终写入 offlineDetail，绝不会被掩盖。
     */
    private void showOffline(String detail) {
        if (isFinishing()) return;
        if (offlineDetail != null) {
            offlineDetail.setText((detail != null ? detail : "") + "\n" + FILE_URL);
        }
        if (pageRendered) return;
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        if (offlineView != null) offlineView.setVisibility(View.VISIBLE);
    }

    /**
     * 返回键：优先在 WebView 历史栈内回退；栈空才退出 App（回桌面，不会跳回浏览器）。
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
