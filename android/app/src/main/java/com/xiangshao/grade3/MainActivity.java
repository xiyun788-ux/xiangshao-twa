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
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.os.Build;
import android.util.Log;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebResourceResponse;

/**
 * 英语小花园 —— 全屏 WebView 原生壳。
 *
 * 加载方式（v1.2.0 起，重大重构）：
 *   用 file:///android_asset/www/index.html 直接加载 App 内嵌的「单文件整站」。
 *   · 整站（index.html）在构建期由 vite-plugin-singlefile 把 JS/CSS 全内联进一个文件；
 *   · 音频 / 字体 / 图标 / 插画作为同目录的 static/ 兄弟资源随包发布；
 *   · 资源 URL 在运行期拼成「文档根绝对地址」（file:///android_asset/www/static/...），
 *     既绕过 uni-app 把相对 src 当成页面相对而前缀路由目录的坑，又能在 file:// 下正确加载。
 *
 * 相比早期「本地 HTTP 服务器（127.0.0.1:8080）」方案的优势：
 *   · 无服务器进程、无端口、无明文、无 AssetManager 解压、无 OEM 明文策略差异；
 *   · 不依赖「国产 ROM 是否放行 127.0.0.1 明文」这类设备相关行为 → 跨机型稳定；
 *   · 本机即可用无头浏览器以 file:// 真实渲染验证，杜绝「只解包看字符串」式假验证。
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 禁用站点 PWA 的 Service Worker：站点在 PROD 会注册 sw.js 做离线缓存，但 App 内已用 file:// 直载，
        // 不需要 SW；且 SW 缓存曾在 WebView 内偶发「下载发音后再次打开白屏」。这里让 sw.js 直接 404 规避。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                        String u = request.getUrl() != null ? request.getUrl().toString() : "";
                        if (u.contains("/sw.js")) {
                            return new WebResourceResponse("application/javascript", "UTF-8",
                                    404, "Not Found", null, null);
                        }
                        return null;
                    }
                });
            } catch (Throwable t) {
                // 个别 ROM 可能不支持，忽略
            }
        }

        progressBar = findViewById(R.id.progressBar);
        offlineView = findViewById(R.id.offlineView);
        offlineDetail = findViewById(R.id.offlineDetail);
        webView = findViewById(R.id.webView);

        // 让 WebView 与站点的会话 Cookie 持久化（站点无 sharecode 时本就不需要，但保留无副作用）。
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        // file:///android_asset/ 直载必须允许文件访问（android_asset 为 App 私有资源，开启无安全风险）。
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // 追加自定义 UA，便于站点识别"已在 App 内"并隐藏桌面安装提示。
        ws.setUserAgentString(ws.getUserAgentString() + " XiangshaoApp/1.0");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                offlineView.setVisibility(View.GONE);
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(0);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                // 隐藏站点"装到手机桌面"提示条（按文字内容定位，不依赖具体选择器）。
                view.evaluateJavascript(
                        "(function(){try{var els=document.querySelectorAll('*');"
                                + "for(var i=0;i<els.length;i++){"
                                + "var t=els[i].textContent||'';"
                                + "if(t.indexOf('装到手机桌面')>=0){els[i].style.display='none';break;}}}"
                                + "catch(e){}})();",
                        null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                       WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    String desc = (error != null) ? error.getDescription().toString() : "未知错误";
                    showOffline("页面加载失败：" + desc);
                }
            }

            // 兼容部分旧版 WebView 实现：它们仍走这个已废弃的重载。
            @Deprecated
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (view != null && view.getUrl() != null && view.getUrl().equals(failingUrl)) {
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
     * 展示离线/失败提示，并把具体错误写进 offlineDetail，方便排查（如资源缺失导致的 ERR_FILE_NOT_FOUND）。
     */
    private void showOffline(String detail) {
        if (isFinishing()) return;
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        if (offlineDetail != null) offlineDetail.setText(detail != null ? detail : "");
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
