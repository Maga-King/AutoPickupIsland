package io.github.mio.autopickupfixture;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.WindowManager;
import android.view.Gravity;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.PopupWindow;
import io.github.mio.autopickupisland.coloros.ColorOsWebViewClient;
import io.github.mio.autopickupisland.coloros.UiAgentDomProtocol;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixture-only bootstrap for the OS4 transport prototype. Replaces ONLY this
 * activity/package's registered UIAgent client, never another app's registration.
 * It does NOT demonstrate automatic loading into WeChat or patch ContentCatcher.
 */
@SuppressLint("SetJavaScriptEnabled")
public final class UiAgentDomFixtureActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ColorOsWebViewClient client = new ColorOsWebViewClient();
    private UiAgentDomProtocol.Target target;
    private Object manager;
    private Object clientProxy; // Keep the Binder identity alive while registered.
    private volatile boolean active, ready;
    private TextView status;
    private WebView page;
    private int generation;
    private Object carrier;
    private boolean carrierRequested;
    private PopupWindow popup;
    private WebView popupPage;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        target = new UiAgentDomProtocol.Target(getPackageName(), getApplicationInfo().uid, getClass().getName());
        carrierRequested = getIntent().getBooleanExtra("use_carrier", false);
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(20, 80, 20, 30);
        status = new TextView(this); status.setText("系统采集桥接测试\n仅此测试进程注册扩展，不发卡、不联网、不读取微信。");
        panel.addView(status);
        page = new WebView(this);
        page.getSettings().setJavaScriptEnabled(true);
        page.getSettings().setBlockNetworkLoads(true);
        page.getSettings().setAllowFileAccess(false);
        page.getSettings().setAllowContentAccess(false);
        page.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (getIntent().getBooleanExtra("with_popup", false) && popup == null) showFixturePopup();
                else { ready = true; publish("READY", -1); }
            }
        });
        panel.addView(page, new LinearLayout.LayoutParams(-1, -1));
        setContentView(panel);
        page.loadDataWithBaseURL("https://fixture.invalid/uiagent-dom/index.html", "<!doctype html><html><head>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<script>window.__route__='pages/order/detail/detail';window.__appId__='wx0123456789abcdef';"
                + "window.__queryString__='orderId=fixture-001';</script></head><body>"
                + "<div>瑞幸咖啡</div><div>等待取餐</div><div>0521</div><div>取餐码</div><div>生椰拿铁</div>"
                + "<div>冰</div><div>下单时间</div><div>2026-09-05 12:40:00</div></body></html>", "text/html", "UTF-8", null);
    }
    @Override protected void onPostResume() {
        super.onPostResume(); active = true; generation++;
        try {
            if (carrierRequested) {
                if (carrier == null) {
                    Context context = createPackageContext("io.github.mio.collectorcarrier", Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                    Class<?> loaded = Class.forName("com.miui.contentcatcher.Interceptor", true, context.getClassLoader());
                    carrier = loaded.getConstructor(Activity.class).newInstance(this);
                    invokeCarrier("notifyActivityCreate"); invokeCarrier("notifyActivityStart");
                    getSharedPreferences("uiagent_dom", MODE_PRIVATE).edit()
                            .putString("carrier_capability", String.valueOf(loaded.getMethod("captureCapabilities").invoke(carrier)))
                            .putBoolean("carrier_loaded", true).apply();
                }
                invokeCarrier("notifyActivityResume");
            }
            Class<?> type = Class.forName("miui.contentcatcher.sdk.ContentCatcherManager");
            manager = type.getMethod("getInstance").invoke(null);
            Class<?> listener = Class.forName(UiAgentDomProtocol.CLIENT_DESCRIPTOR);
            IBinder binder = new Binder() {
                @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                    if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(UiAgentDomProtocol.CLIENT_DESCRIPTOR); return true; }
                    if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
                    int sender = Binder.getCallingUid();
                    // DataHub invokes from system_server. No third-party caller can request this directly.
                    if (sender != 1000 && sender != 0) { if (reply != null) reply.writeException(new SecurityException("SystemOnly")); return true; }
                    try {
                        data.enforceInterface(UiAgentDomProtocol.CLIENT_DESCRIPTOR);
                        Bundle request = data.readTypedObject(Bundle.CREATOR);
                        IBinder callback = data.readStrongBinder(); data.enforceNoDataAvail();
                        main.post(() -> handle(request, callback, sender));
                        if (reply != null) reply.writeNoException();
                    } catch (Throwable ignored) { if (reply != null) reply.writeNoException(); }
                    return true;
                }
            };
            clientProxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, (proxy, method, args) -> switch (method.getName()) {
                case "asBinder" -> binder;
                case "toString" -> "FixtureOnlyDomClient";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> args != null && args.length == 1 && proxy == args[0];
                default -> null;
            });
            // The token comes solely from this Activity's own package/UID. No generic registration API is exported.
            type.getMethod("unregisterUIAgentListener", String.class).invoke(manager, target.token());
            type.getMethod("registerUIAgentListener", String.class, listener).invoke(manager, target.token(), clientProxy);
            publish(ready ? "READY" : "LOADING", -1);
        } catch (Throwable error) { active = false; publish("REGISTER_FAILED:" + error.getClass().getSimpleName(), -1); }
    }
    private void handle(Bundle request, IBinder callback, int sender) {
        String nonce = "";
        try {
            if (carrierRequested) {
                if (carrier == null) { publish("CARRIER_NOT_LOADED", sender); return; }
                Class<?> stub = Class.forName(UiAgentDomProtocol.CALLBACK_DESCRIPTOR + "$Stub");
                Object proxy = stub.getMethod("asInterface", IBinder.class).invoke(null, callback);
                carrier.getClass().getMethod("onUiAgent", Bundle.class, Class.forName(UiAgentDomProtocol.CALLBACK_DESCRIPTOR))
                        .invoke(carrier, request, proxy);
                publish("CARRIER_DISPATCHED", sender); return;
            }
            if (request != null) nonce = request.getString(UiAgentDomProtocol.NONCE_KEY, "");
            if (!active || !ready || !UiAgentDomProtocol.validRequest(request, target)) {
                UiAgentDomProtocol.send(callback, target, nonce, "REJECTED", ""); publish("REJECTED", sender); return;
            }
            if (!busy.compareAndSet(false, true)) { UiAgentDomProtocol.send(callback, target, nonce, "BUSY", ""); return; }
            String replyNonce = nonce;
            int version = generation;
            client.request(page, "", UiAgentDomProtocol.options(request),
                    () -> active && ready && generation == version && !isDestroyed(), result -> {
                        busy.set(false);
                        String resultStatus = result.status().name();
                        boolean sent = UiAgentDomProtocol.send(callback, target, replyNonce, resultStatus, result.raw());
                        publish(sent ? resultStatus : "SEND_FAILED", sender);
                    });
        } catch (Throwable error) {
            busy.set(false);
            UiAgentDomProtocol.send(callback, target, nonce, "FAILED", "");
            publish("FAILED:" + error.getClass().getSimpleName(), sender);
        }
    }
    private void publish(String value, int sender) {
        if (status != null) status.setText("系统采集桥接测试\n" + value + " / Binder caller UID=" + sender
                + "\n这是测试进程自己的注册，不代表微信已接通。");
        getSharedPreferences("uiagent_dom", MODE_PRIVATE).edit().putString("status", value).putInt("caller_uid", sender).apply();
    }
    @Override protected void onPause() {
        active = false; generation++;
        invokeCarrier("notifyActivityPause");
        if (manager != null && target != null) try {
            manager.getClass().getMethod("unregisterUIAgentListener", String.class).invoke(manager, target.token());
        } catch (Throwable ignored) { }
        super.onPause();
    }
    @Override protected void onDestroy() {
        active = false; client.close();
        invokeCarrier("notifyActivityStop"); invokeCarrier("notifyActivityDestroy"); carrier = null;
        main.removeCallbacksAndMessages(null);
        if (popup != null) try { popup.dismiss(); } catch (Throwable ignored) { }
        if (popupPage != null) popupPage.destroy();
        if (page != null) page.destroy();
        clientProxy = null; manager = null;
        super.onDestroy();
    }
    private void invokeCarrier(String name) {
        if (carrier != null) try { carrier.getClass().getMethod(name).invoke(carrier); }
        catch (Throwable ignored) { }
    }
    private void showFixturePopup() {
        try {
            ready = false;
            popupPage = new WebView(this);
            popupPage.getSettings().setJavaScriptEnabled(true);
            popupPage.getSettings().setBlockNetworkLoads(true);
            popupPage.getSettings().setAllowFileAccess(false);
            popupPage.getSettings().setAllowContentAccess(false);
            popupPage.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) { ready = true; publish("POPUP_READY", -1); }
            });
            popup = new PopupWindow(popupPage, getResources().getDisplayMetrics().widthPixels - 80, 500, true);
            popup.showAtLocation(page, Gravity.CENTER, 0, 0);
            popupPage.loadDataWithBaseURL("https://fixture.invalid/popup/index.html", "<html><head>"
                    + "<script>window.__route__='pages/order/detail/detail';window.__appId__='wx0123456789abcdef';"
                    + "window.__queryString__='orderId=fixture-popup';</script></head><body>"
                    + "<div>9402</div><div>弹层测试拿铁</div><div>取餐码</div></body></html>", "text/html", "UTF-8", null);
        } catch (Throwable ignored) { publish("POPUP_FAILED", -1); }
    }
}
