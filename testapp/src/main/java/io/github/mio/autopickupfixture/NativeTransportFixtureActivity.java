package io.github.mio.autopickupfixture;

import android.app.Activity;
import android.os.*;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.mio.autopickupisland.coloros.UiAgentDomProtocol;
import io.github.mio.autopickupisland.coloros.UiAgentNativeProtocol;
import java.lang.reflect.Proxy;

/** Synthetic native Views. Default: ordinary Activity, system factory only.
 * Explicit self_register=true tests transport before the new system loader is
 * installed; it registers this fixture UID only, never another package. */
public final class NativeTransportFixtureActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean active;
    private long generation;
    private Object manager, client;
    private UiAgentDomProtocol.Target target;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        target = new UiAgentDomProtocol.Target(getPackageName(), getApplicationInfo().uid, getClass().getName());
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 80, 24, 24);
        for (String line : new String[]{"原生采集传输测试（合成页面）", "蜜雪冰城", "等待取餐", "取餐码", "8216",
                "草莓摇摇奶昔", "冰", "合成测试门店", "下单时间", "2026-09-06 20:00:00"}) {
            TextView text = new TextView(this); text.setText(line); text.setTextSize(20); layout.addView(text);
        }
        setContentView(layout);
    }
    @Override protected void onPostResume() {
        super.onPostResume(); active = true; generation++;
        if (!getIntent().getBooleanExtra("self_register", false)) return;
        try {
            Class<?> owner = Class.forName("miui.contentcatcher.sdk.ContentCatcherManager");
            manager = owner.getMethod("getInstance").invoke(null);
            Class<?> listener = Class.forName(UiAgentDomProtocol.CLIENT_DESCRIPTOR);
            Binder binder = new Binder() {
                @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                    if (code == INTERFACE_TRANSACTION) {
                        if (reply != null) reply.writeString(UiAgentDomProtocol.CLIENT_DESCRIPTOR); return true;
                    }
                    if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
                    try {
                        data.enforceInterface(UiAgentDomProtocol.CLIENT_DESCRIPTOR);
                        int uid = Binder.getCallingUid();
                        if (uid != 1000 && uid != 0) throw new SecurityException("SystemOnly");
                        Bundle request = data.readTypedObject(Bundle.CREATOR);
                        IBinder callback = data.readStrongBinder(); data.enforceNoDataAvail();
                        main.post(() -> {
                            try { UiAgentNativeProtocol.capture(NativeTransportFixtureActivity.this,
                                    request, callback, () -> active && !isDestroyed(), () -> generation); }
                            catch (Throwable ignored) { }
                        });
                    } catch (Throwable ignored) { }
                    if (reply != null) reply.writeNoException();
                    return true;
                }
            };
            client = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, (proxy, method, args) -> switch (method.getName()) {
                case "asBinder" -> binder;
                case "toString" -> "FixtureNativeClient";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> args != null && args.length == 1 && args[0] == proxy;
                default -> null;
            });
            owner.getMethod("unregisterUIAgentListener", String.class).invoke(manager, target.token());
            owner.getMethod("registerUIAgentListener", String.class, listener).invoke(manager, target.token(), client);
        } catch (Throwable error) {
            active = false;
            android.util.Log.e("MioNativeFixture", "Registration failed: " + error.getClass().getSimpleName());
        }
    }
    @Override protected void onPause() {
        active = false; generation++;
        if (client != null && manager != null) try {
            manager.getClass().getMethod("unregisterUIAgentListener", String.class).invoke(manager, target.token());
        } catch (Throwable ignored) { }
        client = null; manager = null;
        super.onPause();
    }
    @Override protected void onDestroy() {
        active = false; main.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
