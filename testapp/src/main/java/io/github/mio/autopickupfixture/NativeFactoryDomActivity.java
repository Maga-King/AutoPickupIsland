package io.github.mio.autopickupfixture;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Ordinary Activity: NO carrier loading, UIAgent registration, hooks, reflection
 * or custom capture client. Only the OS4 framework can provide the tested bridge.
 */
@SuppressLint("SetJavaScriptEnabled")
public final class NativeFactoryDomActivity extends Activity {
    private WebView page;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        page = getIntent().getBooleanExtra("delay_first_eval", false) ? new DelayedWebView() : new WebView(this);
        page.getSettings().setJavaScriptEnabled(true);
        page.getSettings().setBlockNetworkLoads(true);
        page.getSettings().setAllowFileAccess(false);
        page.getSettings().setAllowContentAccess(false);
        page.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                getSharedPreferences("native_factory_dom", MODE_PRIVATE).edit().putString("page", "READY").apply();
            }
        });
        if (getIntent().getBooleanExtra("configured_wrapper", false)) {
            // A synthetic custom renderer with NO WebView-like class name. Its
            // real DOM backend is hidden: selection must use the XML class rule.
            a wrapper = new a(page);
            setContentView(wrapper);
        } else setContentView(page);
        page.loadDataWithBaseURL("https://fixture.invalid/native/index.html", "<!doctype html><html><head>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<script>window.__route__='pages/order/detail/detail';window.__appId__='wx0123456789abcdef';"
                + "window.__queryString__='orderId=fixture-001';</script></head><body>"
                + "<div>系统原工厂装载测试：此页面没有自行注册采集接口</div>"
                + "<div>瑞幸咖啡</div><div>0521</div><div>取餐码</div><div>生椰拿铁</div>"
                + "</body></html>", "text/html", "UTF-8", null);
    }
    public interface b { void a(String value); }
    /** Synthetic slow renderer; never changes or registers the production reader. */
    public final class DelayedWebView extends WebView {
        private boolean delayed;
        DelayedWebView() { super(NativeFactoryDomActivity.this); }
        @Override public void evaluateJavascript(String script, android.webkit.ValueCallback<String> callback) {
            if (!delayed) {
                delayed = true;
                getSharedPreferences("native_factory_dom", MODE_PRIVATE).edit().putString("delayed", "PENDING").apply();
                postDelayed(() -> {
                    getSharedPreferences("native_factory_dom", MODE_PRIVATE).edit().putString("delayed", "DELIVERED").apply();
                    super.evaluateJavascript(script, callback);
                }, 4_000L);
            } else super.evaluateJavascript(script, callback);
        }
    }
    public final class a extends FrameLayout {
        private final WebView backend;
        a(WebView backend) {
            super(NativeFactoryDomActivity.this);
            this.backend = backend;
            backend.setVisibility(View.GONE);
            addView(backend);
            TextView label = new TextView(NativeFactoryDomActivity.this);
            label.setText("原 XML 类规则 → 无 WebView 类名容器 → 原读取器测试（合成页面）");
            label.setTextSize(20);
            addView(label);
        }
        public void evaluateJavascript(String script, b callback) {
            // Exercise the guarded callback over the real system-loaded carrier.
            if (callback.toString() == null || callback.hashCode() != System.identityHashCode(callback)
                    || !callback.equals(callback)) throw new IllegalStateException("UnsafeProxy");
            backend.evaluateJavascript(script, callback::a);
        }
    }
    @Override protected void onDestroy() { if (page != null) page.destroy(); super.onDestroy(); }
}
