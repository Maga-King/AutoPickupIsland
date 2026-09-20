package io.github.mio.autopickupisland.coloros;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONArray;

/**
 * In-process, one-shot adapter for the original read-only ColorOS WebView scripts.
 * No hooks, Binder endpoint, JS interface, observer, polling or network is installed.
 * A trusted cross-process transport must select the actual Activity/View separately.
 */
public final class ColorOsWebViewClient implements AutoCloseable {
    public enum Status { OK, UNSUPPORTED, FAILED, TIMEOUT, STALE, CANCELLED, LIMIT }
    public record Result(Status status, String raw, String failureClass, long elapsedMs) { }
    public record Request(int resultType, List<String> routes, boolean alipay,
                          boolean labelPathChange, boolean rootPortal, boolean leafNodes) {
        public Request {
            routes = routes == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(routes));
        }
        public static Request pageId() { return new Request(3, List.of(), false, false, false, true); }
        public static Request content(List<String> routes, boolean alipay, boolean label, boolean portal) {
            return new Request(4, routes, alipay, label, portal, true);
        }
        private String script() {
            return switch (resultType) {
                case 0 -> ColorOsWebProtocol.documentHtml();
                case 1 -> ColorOsWebProtocol.legacyNodes(true);
                case 2 -> ColorOsWebProtocol.legacyNodes(false);
                case 3 -> ColorOsWebProtocol.pageId();
                case 4 -> ColorOsWebProtocol.contentByRoute(routes, alipay, labelPathChange, rootPortal);
                case 5 -> ColorOsWebProtocol.miniProgramNodes(leafNodes);
                default -> null; // Type 6 navigates: not a read-only capture.
            };
        }
    }

    private static final int MAX_PENDING = 64;
    private static final int MAX_RESULT_CHARS = 1_048_576;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Ticket> pending = new HashSet<>(); // Main-thread confined.
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long timeoutMs;
    private final OriginalColorOsWebReader originalReader;

    public ColorOsWebViewClient() { this(5_000L); }
    // Shorter bound is useful for fixture timeout tests. Never extend the original 5s request budget.
    public ColorOsWebViewClient(long timeoutMs) { this(timeoutMs, null); }
    public ColorOsWebViewClient(long timeoutMs, OriginalColorOsWebReader originalReader) {
        this.timeoutMs = Math.max(50L, Math.min(5_000L, timeoutMs));
        this.originalReader = originalReader;
    }

    public ColorOsWebViewSelector.Selection selectViews(List<View> roots, ColorOsWebViewSelector.Options options,
                                                        ColorOsWebViewSelector.Visibility visibility) {
        return originalReader == null ? ColorOsWebViewSelector.select(roots, options, visibility)
                : originalReader.select(roots, options);
    }

    public final class Ticket {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final long started = SystemClock.elapsedRealtime();
        private Consumer<Result> callback;
        private BooleanSupplier current;
        private boolean finished;
        private final List<Ticket> children = new ArrayList<>();
        private final Runnable timeout = () -> complete(this, Status.TIMEOUT, "", "");
        private Ticket(BooleanSupplier current, Consumer<Result> callback) {
            this.current = current;
            this.callback = callback;
        }
        public void cancel() {
            cancelled.set(true);
            dispatch(() -> complete(this, Status.CANCELLED, "", ""));
        }
    }

    public Ticket request(View view, String evaluateMethod, Request request,
                          BooleanSupplier stillCurrent, Consumer<Result> callback) {
        Ticket ticket = new Ticket(stillCurrent, callback);
        WeakReference<View> reference = new WeakReference<>(view);
        dispatch(() -> begin(ticket, reference.get(), evaluateMethod, request));
        return ticket;
    }

    /** Original reportBatchJsResult uses callback ARRIVAL order, not view index.
     * These views must already have been selected from the actual target Activity.
     * A failed/expired child rejects the entire batch; no partial page is published.
     */
    public Ticket requestBatch(List<View> views, String evaluateMethod, Request request,
                               BooleanSupplier stillCurrent, Consumer<Result> callback) {
        Ticket ticket = new Ticket(stillCurrent, callback);
        List<WeakReference<View>> references = new ArrayList<>();
        if (views != null && views.size() <= 32) for (View view : views) references.add(new WeakReference<>(view));
        dispatch(() -> {
            if (ticket.finished) return;
            try {
                if (closed.get() || ticket.cancelled.get()) { complete(ticket, Status.CANCELLED, "", ""); return; }
                if (stillCurrent == null || !stillCurrent.getAsBoolean()) { complete(ticket, Status.STALE, "", ""); return; }
                if (references.isEmpty()) { complete(ticket, Status.UNSUPPORTED, "", ""); return; }
                if (pending.size() + references.size() + 1 > MAX_PENDING) { complete(ticket, Status.LIMIT, "", ""); return; }
                // Resolve weak refs before invoking anything. A vanished window
                // must not cause a mixed batch of remaining/stale windows.
                List<View> selected = new ArrayList<>();
                for (WeakReference<View> ref : references) {
                    View view = ref.get();
                    if (view == null) { complete(ticket, Status.STALE, "", ""); return; }
                    selected.add(view);
                }
                pending.add(ticket);
                main.postDelayed(ticket.timeout, timeoutMs);
                List<String> arrived = new ArrayList<>();
                int[] chars = {0};
                for (View view : selected) {
                    if (ticket.finished) break;
                    Ticket child = request(view, evaluateMethod, request,
                            () -> !ticket.finished && !ticket.cancelled.get() && stillCurrent.getAsBoolean(), result -> {
                                if (ticket.finished) return;
                                if (result.status() != Status.OK) { complete(ticket, result.status(), "", result.failureClass()); return; }
                                chars[0] += result.raw().length();
                                if (chars[0] > MAX_RESULT_CHARS) { complete(ticket, Status.LIMIT, "", ""); return; }
                                arrived.add(result.raw());
                                if (arrived.size() == selected.size()) {
                                    String raw = arrived.size() == 1 ? arrived.get(0) : new JSONArray(arrived).toString();
                                    received(ticket, raw); // Also bounds JSON quoting expansion.
                                }
                            });
                    if (!ticket.finished && !child.finished) ticket.children.add(child);
                }
            } catch (Throwable error) { complete(ticket, Status.FAILED, "", causeName(error)); }
        });
        return ticket;
    }

    private void begin(Ticket ticket, View view, String methodName, Request request) {
        if (ticket.finished) return;
        if (closed.get() || ticket.cancelled.get()) { complete(ticket, Status.CANCELLED, "", ""); return; }
        if (pending.size() >= MAX_PENDING) { complete(ticket, Status.LIMIT, "", ""); return; }
        if (view == null || request == null) { complete(ticket, Status.UNSUPPORTED, "", ""); return; }
        try {
            if (ticket.current == null || !ticket.current.getAsBoolean()) {
                complete(ticket, Status.STALE, "", ""); return;
            }
            if (request.routes.size() > 256) { complete(ticket, Status.LIMIT, "", ""); return; }
            int length = 0;
            for (String route : request.routes) {
                if (route != null) length += route.length();
                if (length > 65_536) { complete(ticket, Status.LIMIT, "", ""); return; }
            }
            if (request.resultType < 0 || request.resultType > 5) { complete(ticket, Status.UNSUPPORTED, "", ""); return; }
            String script = originalReader == null ? request.script() : null;
            pending.add(ticket);
            main.postDelayed(ticket.timeout, timeoutMs);
            if (originalReader != null) {
                originalReader.read(view, methodName, request, value -> dispatch(() -> {
                    // Original ReflectException is reported as an empty callback,
                    // which is not a valid type 0..5 JSON result. Never publish it.
                    if (value != null && value.isEmpty()) complete(ticket, Status.FAILED, "", "OriginalEmptyResult");
                    else received(ticket, value);
                }));
            } else if (view instanceof WebView webView) {
                webView.evaluateJavascript(script, value -> dispatch(() -> received(ticket, value)));
            } else {
                Method method = resolve(view.getClass(), methodName);
                if (method == null) { complete(ticket, Status.UNSUPPORTED, "", ""); return; }
                Class<?> callbackType = method.getParameterTypes()[1];
                Method sam = callbackMethod(callbackType);
                Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType},
                        (proxy, called, args) -> {
                            if (called.getDeclaringClass() == Object.class) {
                                return switch (called.getName()) {
                                    case "hashCode" -> System.identityHashCode(proxy);
                                    case "equals" -> args != null && args.length == 1 && proxy == args[0];
                                    case "toString" -> "ColorOsReadOnlyCallback";
                                    default -> null;
                                };
                            }
                            if (called.equals(sam)) {
                                String raw = args != null && args.length == 1 && args[0] instanceof String s ? s : null;
                                dispatch(() -> received(ticket, raw));
                            }
                            return null;
                        });
                method.setAccessible(true);
                method.invoke(view, script, callback);
            }
        } catch (OriginalColorOsWebReader.UnsupportedRead ignored) {
            complete(ticket, Status.UNSUPPORTED, "", "");
        } catch (Throwable error) {
            complete(ticket, Status.FAILED, "", causeName(error));
        }
    }

    private static Method resolve(Class<?> type, String configuredName) {
        String name = configuredName == null || configuredName.isEmpty() ? "evaluateJavascript" : configuredName;
        if (name.length() > 128) return null;
        Method found = null;
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || Modifier.isStatic(method.getModifiers())
                    || method.isBridge() || method.getReturnType() != void.class) continue;
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length != 2 || parameters[0] != String.class
                    || callbackMethod(parameters[1]) == null) continue;
            if (found != null) return null; // Ambiguous overloads fail closed, not an arbitrary choice.
            found = method;
        }
        return found;
    }

    private static Method callbackMethod(Class<?> type) {
        if (!type.isInterface()) return null;
        Method found = null;
        for (Method method : type.getMethods()) {
            if (!Modifier.isAbstract(method.getModifiers()) || Modifier.isStatic(method.getModifiers())
                    || method.getDeclaringClass() == Object.class) continue;
            Class<?>[] args = method.getParameterTypes();
            if (found != null || method.getReturnType() != void.class || args.length != 1
                    || !args[0].isAssignableFrom(String.class)) return null;
            found = method;
        }
        return found;
    }

    private void received(Ticket ticket, String raw) {
        if (ticket.finished) return;
        if (raw == null) { complete(ticket, Status.FAILED, "", "NullResult"); return; }
        if (raw.length() > MAX_RESULT_CHARS) { complete(ticket, Status.LIMIT, "", ""); return; }
        complete(ticket, Status.OK, raw, "");
    }

    private void complete(Ticket ticket, Status status, String raw, String failure) {
        if (ticket.finished) return;
        ticket.finished = true;
        main.removeCallbacks(ticket.timeout);
        pending.remove(ticket);
        for (Ticket child : ticket.children) child.cancel();
        ticket.children.clear();
        if (closed.get() || ticket.cancelled.get()) { status = Status.CANCELLED; raw = ""; }
        else {
            try {
                if (ticket.current == null || !ticket.current.getAsBoolean()) { status = Status.STALE; raw = ""; }
            } catch (Throwable error) { status = Status.FAILED; raw = ""; failure = causeName(error); }
        }
        Consumer<Result> callback = ticket.callback;
        ticket.current = null;
        ticket.callback = null;
        if (callback != null) {
            try { callback.accept(new Result(status, raw, failure, SystemClock.elapsedRealtime() - ticket.started)); }
            catch (Throwable ignored) { /* Never propagate consumer failures into the host UI thread. */ }
        }
    }

    private void dispatch(Runnable action) {
        if (Looper.myLooper() == main.getLooper()) action.run();
        else main.post(action);
    }
    private static String causeName(Throwable error) {
        if (error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null)
            error = error.getCause();
        return error.getClass().getSimpleName(); // No source text or exception messages in diagnostics.
    }
    @Override public void close() {
        closed.set(true);
        dispatch(() -> {
            for (Ticket ticket : new ArrayList<>(pending)) complete(ticket, Status.CANCELLED, "", "");
        });
    }
}
