package com.xiangshao.grade3;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
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
 * 设计目标（解决 TWA 回退成"浏览器打开网页"的痛点）：
 *  1. 真正的全屏无地址栏、无浏览器工具栏；
 *  2. 独立应用图标，从桌面启动，返回键在 App 内历史栈导航，空了才退出回桌面，绝不跳浏览器；
 *  3. 不依赖 Digital Asset Links / 自有域名，零外部前提；
 *  4. 自动隐藏站点里的"装到手机桌面"提示条（已是 App，不需要它）。
 */
public class MainActivity extends Activity {

    // 本地内嵌服务器的访问地址（整站 + 离线发音已打包进 APK，彻底离线，不连任何远端）。
    private static final String LOCAL_BASE_URL = "http://127.0.0.1:8080/";

    private WebView webView;
    private ProgressBar progressBar;
    private View offlineView;
    private TextView offlineDetail;
    private LocalServer localServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 在 App 壳内禁用站点 PWA 的 Service Worker：
        // 站点在 PROD 且浏览器支持 SW 时会注册 sw.js 做「离线发音」缓存。但 WebView 内的 SW 缓存
        // 与线上 Chrome 相互独立，离线下载会把音频/资源写进 WebView 自己的 Cache Storage，偶发
        // 「下载发音后再次打开白屏」（导航请求被 SW 返回了与当前构建不匹配的缓存）。
        // 作为已安装的 App，不需要 PWA 的可安装/离线能力，直接让 sw.js 加载失败即可彻底规避。
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

        // 让 WebView 与站点的会话 Cookie 持久化，首次带 sharecode 加载即建立登录态
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // 追加自定义 UA，便于站点识别"已在 App 内"并隐藏桌面安装提示
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
                // 隐藏站点"装到手机桌面"提示条（按文字内容定位，不依赖具体选择器）
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

            // 兼容部分旧版 WebView 实现：它们仍走这个已废弃的重载
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

        // 点击离线提示的"重试"重新加载
        offlineView.setOnClickListener(v -> {
            offlineView.setVisibility(View.GONE);
            webView.reload();
        });

        // 每次冷启动清空 WebView 缓存：本地服务器对 html 返回 no-cache、静态资源 immutable，
        // 清空缓存可彻底避免WebView残留旧构建导致的 chunk 404（白屏）。
        try {
            webView.clearCache(true);
        } catch (Throwable t) {
            // 个别 ROM / 低版本不支持，忽略
        }

        // 启动本地内嵌服务器（首启动需把 assets/www 解压到私有目录，可能耗时 1~3s），
        // 放后台线程避免阻塞 onCreate 触发 ANR；服务器就绪后在主线程 loadUrl。
        // 端口绑定后即意味着本地 HTTP 服务可用，WebView 加载 http://127.0.0.1:8080/ 不会连远端，
        // 因此即便无网也能完整离线运行（整站 + 512 个离线发音 mp3 均已内嵌）。
        localServer = new LocalServer(this);
        new Thread(() -> {
            try {
                localServer.start();
                runOnUiThread(() -> {
                    if (!isFinishing() && webView != null) {
                        webView.loadUrl(LOCAL_BASE_URL);
                    }
                });
            } catch (Throwable t) {
                Log.e("MainActivity", "local server start failed", t);
                runOnUiThread(() -> showOffline("本地服务器启动失败：" + t.getMessage()));
            }
        }, "LocalServer-Thread").start();
    }

    /**
     * 展示离线/失败提示，并把具体错误写进 offlineDetail，方便排查（如 ERR_CLEARTEXT_NOT_PERMITTED）。
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
        if (localServer != null) {
            localServer.stop();
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
