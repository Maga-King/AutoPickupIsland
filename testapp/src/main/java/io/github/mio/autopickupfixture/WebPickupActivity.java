package io.github.mio.autopickupfixture;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;

@SuppressLint("SetJavaScriptEnabled") // Deliberate: exercises system AssistStructure DOM extraction.
public final class WebPickupActivity extends Activity {
    private WebView webView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        setContentView(webView);
        load(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        load(intent);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    private void load(Intent intent) {
        boolean returned = intent != null && intent.getBooleanExtra("mio_from_island", false);
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<style>body{font-family:sans-serif;background:#fafafa;color:#222;padding:24px}" +
                "h1{color:#da3c48}#code{font-size:34px;font-weight:bold;margin:24px 0;white-space:pre-line}" +
                "button{display:block;width:100%;padding:13px;margin:9px 0;font-size:15px}" +
                ".ok{color:#087b4b;font-weight:bold}</style>" +
                "<script>window.__appId__='gh_mio_fixture_mixue';" +
                "window.__route__='pages/order_detail/take/index';" +
                "window.__queryString__='orderId=fixture-001';" +
                "var seq=0;function show(c){document.getElementById('state').textContent='制作完成，等待取餐';" +
                "document.getElementById('code').textContent='取餐码\\n'+(c||String(521+seq).padStart(4,'0'))+'\\n请凭号码取餐';}" +
                "function same(){show('0521')}function next(){seq++;show()}" +
                "function badOrigin(){window.__appId__='gh_not_whitelisted';show('X999')}" +
                "function badPath(){window.__appId__='gh_mio_fixture_mixue';window.__route__='pages/menu/index';show('X998')}" +
                "function restore(){window.__appId__='gh_mio_fixture_mixue';window.__route__='pages/order_detail/take/index';show('W522')}" +
                "setTimeout(function(){show('0521')},2500);</script></head><body>" +
                "<h1>蜜雪冰城 · WebView 小程序仿真</h1>" +
                "<p>精确模拟 ColorOS 读取的 __appId__、__route__、__queryString__ 与 body.innerText。</p>" +
                (returned ? "<p class='ok'>✓ 已由超级岛/通知精确返回本页</p>" : "<p>尚未通过岛或通知返回</p>") +
                "<p id='state'>门店制作中</p><div id='code'>请稍候，暂未生成号码</div>" +
                "<p>商品：草莓摇摇奶昔（冰）</p>" +
                "<button onclick=\"show('0521')\">立即显示</button>" +
                "<button onclick='same()'>重复写入（应去重）</button>" +
                "<button onclick='next()'>切换新码（应更新）</button>" +
                "<button onclick='badOrigin()'>错误 origin（不应触发）</button>" +
                "<button onclick='badPath()'>黑名单路径（不应触发）</button>" +
                "<button onclick='restore()'>恢复白名单并显示 W522</button>" +
                "</body></html>";
        webView.loadDataWithBaseURL(
                "https://servicewechat.com/gh_mio_fixture_mixue/1/page-frame.html",
                html, "text/html", "UTF-8", null);
    }
}
