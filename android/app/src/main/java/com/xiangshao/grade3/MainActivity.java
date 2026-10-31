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

    private static final String START_URL =
            "https://suike.app.workbuddy.host/?sharecode=24NRJSLYLH";

    private WebView webView;
    private ProgressBar progressBar;
    private View offlineView;

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
                    progressBar.setVisibility(View.GONE);
                    offlineView.setVisibility(View.VISIBLE);
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

        if (savedInstanceState == null) {
            webView.loadUrl(START_URL);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        webView.restoreState(savedInstanceState);
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
