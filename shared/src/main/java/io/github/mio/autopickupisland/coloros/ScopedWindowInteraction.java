package io.github.mio.autopickupisland.coloros;

import android.os.Looper;
import android.view.MotionEvent;
import android.view.Window;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/** Public OS4 adaptation for when ContentCatcher's optional touch gate is off.
 * Main-thread only; no event storage, input consumption, polling or setting changes.
 * The caller owns exact-rule authorization and must detach on pause/destroy.
 */
public final class ScopedWindowInteraction {
    private final Window window;
    private final Window.Callback original;
    private final Window.Callback wrapper;
    private Runnable changed;

    private ScopedWindowInteraction(Window window, Runnable changed) {
        this.window = window;
        this.original = window.getCallback();
        if (original == null) throw new IllegalStateException("NoCallback");
        this.changed = changed;
        this.wrapper = (Window.Callback) Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),
                new Class<?>[]{Window.Callback.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                        case "toString" -> "MioScopedWindowInteraction";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> args != null && args.length == 1 && args[0] == proxy;
                        default -> null;
                    };
                    // Delegate first, preserve the host's exact return/exception semantics.
                    Object result;
                    try { result = method.invoke(original, args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                    try {
                        if (this.changed != null && "dispatchTouchEvent".equals(method.getName())
                                && args != null && args.length == 1 && args[0] instanceof MotionEvent event
                                && event.getActionMasked() == MotionEvent.ACTION_UP) this.changed.run();
                    } catch (Throwable ignored) { /* Module failure must not break host input. */ }
                    return result;
                });
    }

    public static ScopedWindowInteraction install(Window window, Runnable changed) {
        if (Looper.myLooper() != Looper.getMainLooper() || window == null || changed == null) return null;
        try {
            ScopedWindowInteraction result = new ScopedWindowInteraction(window, changed);
            window.setCallback(result.wrapper);
            return result;
        } catch (Throwable ignored) { return null; }
    }

    public void close() {
        if (Looper.myLooper() != Looper.getMainLooper()) return;
        changed = null; // Also disables a wrapper retained by a host's newer callback.
        try {
            if (window.getCallback() == wrapper) window.setCallback(original);
        } catch (Throwable ignored) { }
    }
}
