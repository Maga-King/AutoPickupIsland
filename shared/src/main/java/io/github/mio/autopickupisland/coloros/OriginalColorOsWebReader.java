package io.github.mio.autopickupisland.coloros;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import dalvik.system.DelegateLastClassLoader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.HashSet;
import java.util.function.Consumer;

/** Executes the UNMODIFIED ColorOS framework WebView reader and Reflect classes.
 * The host still owns Activity-token windows, request lifetime and IPC. Never
 * invokes the OEM service/Binder entry, global batch map or navigation type 6.
 * Loading remains fixture-only until ordinary-app compatibility is audited.
 */
public final class OriginalColorOsWebReader {
    public static final String FRAMEWORK_SHA256 = "3b47a02bec547b3218f8a9ff94e11bb443e985b9b980314a9e0bedd82df8af78";
    private final Method forward, reverse;
    private final Method reflectOn, reflectFind, reflectGet, reflectInvoke;
    private final Method[] reads = new Method[6];
    private OriginalColorOsWebReader(ClassLoader loader) throws Exception {
        Class<?> helper = loader.loadClass("android.view.viewextract.WebViewExtractHelper");
        Class<?> reflect = loader.loadClass("android.util.Reflect");
        if (helper.getClassLoader() != loader || reflect.getClassLoader() != loader)
            throw new IllegalStateException("OriginalClassLoaderMismatch");
        reflectOn = reflect.getMethod("on", Object.class);
        reflectFind = reflect.getMethod("methodWithParamCount", String.class, int.class);
        reflectGet = reflect.getMethod("getMethod");
        reflectInvoke = reflect.getMethod("invoke", Object[].class);
        forward = method(helper, "findWebViewTraversal", View.class, ArrayList.class, String.class, List.class, boolean.class);
        reverse = method(helper, "findWebViewTraversalReverse", View.class, ArrayList.class, String.class, List.class, boolean.class);
        String[] basic = {"getDocumentHTML", "getLeafNodes", "getNodes", "getPageId"};
        for (int i = 0; i < basic.length; i++) reads[i] = method(helper, basic[i], View.class, String.class, Consumer.class);
        reads[4] = method(helper, "getMiniProgramContentByRoute", View.class, String.class,
                List.class, boolean.class, boolean.class, boolean.class, Consumer.class);
        reads[5] = method(helper, "getMiniProgramNodes", View.class, String.class, boolean.class, Consumer.class);
    }
    private static Method method(Class<?> owner, String name, Class<?>... params) throws Exception {
        Method method = owner.getDeclaredMethod(name, params);
        method.setAccessible(true);
        return method;
    }

    /** Worker-thread, explicit fixture loading only; never replace the boot classpath. */
    public static OriginalColorOsWebReader loadForFixture(Context context) throws Exception {
        return loadForFixture(context, context);
    }
    /** The code-only carrier owns the asset; the fixture owns the private cache.
     * This does not widen the target-app gate to WeChat or another application.
     */
    public static OriginalColorOsWebReader loadForFixture(Context context, Context assets) throws Exception {
        if (context == null || !"io.github.mio.autopickupfixture".equals(context.getPackageName()))
            throw new SecurityException("FixtureOnly");
        return loadForCarrier(context, assets);
    }
    /** Called by the system-loaded, read-only carrier after Activity scope gates.
     * A permitted package alone is NOT permission to capture all its windows.
     */
    public static OriginalColorOsWebReader loadForCarrier(Context context, Context assets) throws Exception {
        if (context == null || !ColorOsCollectorScope.packageAllowed(context.getPackageName()))
            throw new SecurityException("CollectorHostNotAllowed");
        if (assets == null || !("io.github.mio.autopickupfixture".equals(assets.getPackageName())
                || "io.github.mio.autopickupisland".equals(assets.getPackageName())
                || "io.github.mio.collectorcarrier".equals(assets.getPackageName())))
            throw new SecurityException("OriginalAssetOwner");
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("LoadOffMainThread");
        File jar = new File(context.getCodeCacheDir(), "coloros-reader-" + FRAMEWORK_SHA256 + ".jar");
        if (!jar.exists()) {
            File temp = File.createTempFile("coloros-reader-", ".jar", context.getCodeCacheDir());
            try (InputStream input = assets.getAssets().open("coloros-original/oplus-framework.jar");
                 FileOutputStream output = new FileOutputStream(temp)) {
                if (!temp.setReadOnly()) throw new IllegalStateException("ReadOnlyCodeFile");
                input.transferTo(output);
                output.getFD().sync();
            }
            if (!FRAMEWORK_SHA256.equals(hash(temp))) throw new IllegalStateException("OriginalFrameworkHashMismatch");
            if (!temp.renameTo(jar)) throw new IllegalStateException("CodeFileRename");
        }
        if (!FRAMEWORK_SHA256.equals(hash(jar))) throw new IllegalStateException("OriginalFrameworkHashMismatch");
        return new OriginalColorOsWebReader(new DelegateLastClassLoader(jar.getAbsolutePath(),
                OriginalColorOsWebReader.class.getClassLoader()));
    }
    private static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte part : digest.digest()) {
            result.append(Character.forDigit((part >>> 4) & 15, 16)).append(Character.forDigit(part & 15, 16));
        }
        return result.toString();
    }

    /** Original selection order/visibility/class matching, with a host size guard.
     * The roots MUST already belong to the requested Activity token.
     */
    public ColorOsWebViewSelector.Selection select(List<View> roots, ColorOsWebViewSelector.Options options) {
        var failed = new ColorOsWebViewSelector.Selection(ColorOsWebViewSelector.Status.FAILED, List.of());
        if (Looper.myLooper() != Looper.getMainLooper() || roots == null || options == null) return failed;
        try {
            if (roots.size() > 64 || options.classes().size() > 256 || options.evaluateMethod().length() > 128
                    || !boundedTree(roots))
                return new ColorOsWebViewSelector.Selection(ColorOsWebViewSelector.Status.LIMIT, List.of());
            ArrayList<String> classes = new ArrayList<>(options.classes());
            ArrayList<View> selected = new ArrayList<>();
            if (options.lastOnly()) {
                for (int i = roots.size() - 1; i >= 0; i--) {
                    View view = roots.get(i);
                    if (view != null && Boolean.TRUE.equals(reverse.invoke(null, view, classes,
                            options.evaluateMethod(), selected, options.ignoreVisible()))) break;
                }
            } else {
                for (View view : roots) if (view != null)
                    forward.invoke(null, view, classes, options.evaluateMethod(), selected, options.enableWebGroup());
            }
            if (selected.size() > 32) return new ColorOsWebViewSelector.Selection(ColorOsWebViewSelector.Status.LIMIT, List.of());
            return new ColorOsWebViewSelector.Selection(selected.isEmpty()
                    ? ColorOsWebViewSelector.Status.NO_MATCH : ColorOsWebViewSelector.Status.OK, List.copyOf(selected));
        } catch (Throwable ignored) { return failed; }
    }
    private record Node(View view, int depth) { }
    private static boolean boundedTree(List<View> roots) {
        ArrayDeque<Node> pending = new ArrayDeque<>();
        for (View root : roots) if (root != null) pending.addLast(new Node(root, 0));
        int visited = 0;
        while (!pending.isEmpty()) {
            Node node = pending.removeLast();
            if (++visited > 8192 || node.depth > 128) return false;
            if (node.view instanceof ViewGroup group) {
                int count = group.getChildCount();
                if (count + pending.size() + visited > 8192) return false;
                for (int i = 0; i < count; i++) {
                    View child = group.getChildAt(i);
                    if (child != null) pending.addLast(new Node(child, node.depth + 1));
                }
            }
        }
        return true;
    }

    /** Script construction and method/ancestor lookup execute in the original DEX.
     * For custom Views, a small host adapter shields the OEM callback proxy from
     * Object methods and rejects ambiguous/unsafe signatures before invoking app
     * code. No original DEX is modified; this safety boundary is NOT OEM behavior.
     */
    void read(View view, String evaluateMethod, ColorOsWebViewClient.Request request, Consumer<String> callback) throws Exception {
        int type = request.resultType();
        if (Looper.myLooper() != Looper.getMainLooper() || type < 0 || type >= reads.length)
            throw new IllegalArgumentException("ReadOnlyMainThreadRequestRequired");
        if (!(view instanceof WebView)) {
            String name = evaluateMethod == null || evaluateMethod.isEmpty() ? "evaluateJavascript" : evaluateMethod;
            if (name.length() > 128) throw new UnsupportedRead();
            Object target = reflectOn.invoke(null, view);
            reflectFind.invoke(target, name, 2); // Original lookup, including private ancestors.
            Method actual = (Method) reflectGet.invoke(target);
            if (!safeSignature(view.getClass(), actual)) throw new UnsupportedRead();
            view = new CallbackGuardView(view, target, actual.getParameterTypes()[1]);
            evaluateMethod = "evaluateJavascript"; // Fixed bridge entry, not a guessed app method.
        }
        if (type == 4) reads[type].invoke(null, view, evaluateMethod, request.routes(), request.alipay(),
                request.labelPathChange(), request.rootPortal(), callback);
        else if (type == 5) reads[type].invoke(null, view, evaluateMethod, request.leafNodes(), callback);
        else reads[type].invoke(null, view, evaluateMethod, callback);
    }

    public static final class UnsupportedRead extends Exception { }

    private static boolean safeSignature(Class<?> type, Method actual) {
        if (actual == null || Modifier.isStatic(actual.getModifiers()) || actual.getReturnType() != void.class
                || actual.getParameterCount() != 2 || actual.getParameterTypes()[0] != String.class) return false;
        Class<?> callback = actual.getParameterTypes()[1];
        if (!callback.isInterface()) return false;
        int callbackMethods = 0;
        for (Method candidate : callback.getMethods()) {
            if (Modifier.isStatic(candidate.getModifiers()) || candidate.getDeclaringClass() == Object.class) continue;
            if (++callbackMethods != 1 || candidate.getReturnType() != void.class || candidate.getParameterCount() != 1
                    || !candidate.getParameterTypes()[0].isAssignableFrom(String.class)) return false;
        }
        if (callbackMethods != 1) return false;
        // Validate, never choose a replacement for the Method selected by OEM.
        // Its first public tier wins; otherwise check its selected declaration.
        Method[] tier = Modifier.isPublic(actual.getModifiers()) ? type.getMethods() : actual.getDeclaringClass().getDeclaredMethods();
        HashSet<List<Class<?>>> signatures = new HashSet<>();
        for (Method method : tier) if (!method.isBridge() && !Modifier.isStatic(method.getModifiers())
                && method.getName().equals(actual.getName()) && method.getParameterCount() == 2)
            signatures.add(Arrays.asList(method.getParameterTypes()));
        return signatures.size() == 1;
    }

    private final class CallbackGuardView extends View {
        private final Object target;
        private final Class<?> callbackType;
        private final ClassLoader callbackLoader;
        CallbackGuardView(View source, Object target, Class<?> callbackType) {
            super(source.getContext());
            this.target = target;
            this.callbackType = callbackType;
            callbackLoader = source.getClass().getClassLoader();
        }
        @SuppressWarnings("unused")
        public void evaluateJavascript(String script, ValueCallback<String> callback) throws Exception {
            Object guarded = safeCallback(callbackLoader, callbackType, callback);
            reflectInvoke.invoke(target, (Object) new Object[]{script, guarded});
        }
    }
    private static Object safeCallback(ClassLoader loader, Class<?> type, ValueCallback<String> callback) {
        return Proxy.newProxyInstance(loader, new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args != null && args.length == 1 && proxy == args[0];
                    case "toString" -> "ColorOsGuardedCallback";
                    default -> null;
                };
            }
            try {
                String value = args != null && args.length == 1 && args[0] instanceof String text ? text : null;
                if (callback != null) callback.onReceiveValue(value);
            } catch (Throwable ignored) { /* A callback consumer must not escape onto app threads. */ }
            return null;
        });
    }
}
