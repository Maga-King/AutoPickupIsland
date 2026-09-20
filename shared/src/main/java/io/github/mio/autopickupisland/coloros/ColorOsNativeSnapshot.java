package io.github.mio.autopickupisland.coloros;

import android.app.Activity;
import android.content.ComponentName;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStructure;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.UUID;
import io.github.mio.autopickupisland.ColorOsNativeExtractor;

/** On-demand source-process native snapshot, not a system service or DOM protocol.
 * ColorOS uses Autofill flag 0x10000001: include all ordered children, keep full
 * non-password TextView text, append live metadata. OS4 does not implement that
 * OEM flag, so adapt only those verified branches instead of sending a magic flag.
 * This entry installs no hooks; the scoped system factory supplies its Activity.
 */
public final class ColorOsNativeSnapshot {
    public record Ticket(ComponentName component, int uid, int task, IBinder token,
                         long generation, long created, String nonce) { }
    public record Result(String status, List<ColorOsNativeExtractor.Node> roots, int visited,
                         String nonce, long generation) { }
    private static final int MAX_NODES = 6000, MAX_CHILDREN = 500, MAX_DEPTH = 128, MAX_TEXT = 40_000;
    private static final long TTL = 5000, WORK_MS = 150;
    private record ProviderShape(boolean virtual, boolean dispatcher, boolean text) { }
    // Merchant pages repeat the same widget classes hundreds of times. Avoid
    // enumerating every method/superclass for every node on the UI thread.
    // ClassValue follows class-loader lifetime; it retains no View or Activity.
    private static final ClassValue<ProviderShape> PROVIDERS = new ClassValue<>() {
        @Override protected ProviderShape computeValue(Class<?> type) {
            try {
                Class<?> owner = type.getMethod("dispatchProvideStructure", ViewStructure.class).getDeclaringClass();
                return new ProviderShape(customVirtualProvider(type), owner == View.class || owner == ViewGroup.class,
                        !TextView.class.isAssignableFrom(type) || frameworkTextProvider(type));
            } catch (Throwable ignored) { return new ProviderShape(true, false, false); }
        }
    };
    private ColorOsNativeSnapshot() { }
    private static final class Access {
        static String failure = "";
        static final Access INSTANCE = create();
        final Method token, drawingOrder;
        Access() throws Exception {
            token = Activity.class.getMethod("getActivityToken");
            // Protected SDK API, NOT the restricted getChildrenForAutofill.
            drawingOrder = ViewGroup.class.getDeclaredMethod("isChildrenDrawingOrderEnabled");
            drawingOrder.setAccessible(true);
        }
        static Access create() {
            try { return new Access(); }
            catch (Throwable error) { failure = error.getClass().getSimpleName() + ":" + error.getMessage(); return null; }
        }
    }
    public static String capabilities() { return Access.INSTANCE == null ? "UNSUPPORTED:" + Access.failure : "OK"; }
    public static Ticket ticket(Activity activity, long generation) {
        try {
            if (!main() || activity == null || Access.INSTANCE == null) return null;
            return new Ticket(activity.getComponentName(), activity.getApplicationInfo().uid, activity.getTaskId(),
                    (IBinder) Access.INSTANCE.token.invoke(activity), generation, SystemClock.elapsedRealtime(), UUID.randomUUID().toString());
        } catch (Throwable ignored) { return null; }
    }
    /** The caller must supply its CURRENT generation, not echo the ticket value.
     * This is a local lifetime gate, NOT caller/Binder authorization. */
    public static Result capture(Activity activity, Ticket ticket, long currentGeneration) {
        int visited = 0;
        try {
            if (!main()) return empty("WRONG_THREAD", ticket, 0);
            if (Access.INSTANCE == null) return empty("UNSUPPORTED_API", ticket, 0);
            if (!valid(activity, ticket, currentGeneration)) return empty("STALE", ticket, 0);
            List<View> all = WindowInspector.getGlobalWindowViews();
            List<View> roots = ColorOsActivityCapture.filterSnapshot(all, ticket.token);
            if (roots.isEmpty() || roots.size() > 8) return empty("WINDOW_LIMIT", ticket, 0);
            for (View root : roots) {
                if (!(root.getLayoutParams() instanceof WindowManager.LayoutParams params)
                        || (params.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0)
                    return empty("SECURE_WINDOW", ticket, 0);
            }
            long deadline = SystemClock.uptimeMillis() + WORK_MS;
            ArrayList<Binding> bindings = new ArrayList<>();
            IdentityHashMap<View, Boolean> seen = new IdentityHashMap<>();
            ArrayDeque<Visit> pending = new ArrayDeque<>();
            ArrayList<NativeNodeWriter> nodes = new ArrayList<>();
            Access access = Access.INSTANCE;
            for (View root : roots) {
                NativeNodeWriter builder = new NativeNodeWriter();
                nodes.add(builder); pending.push(new Visit(root, builder, 0));
                while (!pending.isEmpty()) {
                    Visit entry = pending.pop(); View view = entry.view;
                    if (++visited > MAX_NODES || entry.depth > MAX_DEPTH || seen.put(view, true) != null)
                        return empty("NODE_LIMIT", ticket, visited);
                    if (SystemClock.uptimeMillis() > deadline) return empty("WORK_LIMIT", ticket, visited);
                    if (!view.isAttachedToWindow()) return empty("STALE_VIEW", ticket, visited);
                    if (password(view)) {
                        entry.builder.blocked = true; continue;
                    }
                    ProviderShape provider = PROVIDERS.get(view.getClass());
                    if (provider.virtual) return empty("UNSUPPORTED_VIRTUAL_TREE", ticket, visited);
                    if (!provider.dispatcher)
                        return empty("CUSTOM_DISPATCH", ticket, visited);
                    if (!provider.text)
                        return empty("CUSTOM_TEXT_PROVIDER", ticket, visited);
                    // Let framework View.dispatchProvideStructure enforce its
                    // own assist-blocked gate. The one-node sink prevents its
                    // ViewGroup implementation from reading children here.
                    view.dispatchProvideStructure(entry.builder);
                    if (entry.builder.blocked) continue;
                    // Capture one node, not ViewGroup.dispatch*: manual bounded
                    // traversal keeps the OEM no-isLaidOut-gate behavior.
                    entry.builder.setAutofillId(view.getAutofillId());
                    view.onProvideAutofillStructure(entry.builder, View.AUTOFILL_FLAG_INCLUDE_NOT_IMPORTANT_VIEWS);
                    view.onProvideAutofillVirtualStructure(entry.builder, View.AUTOFILL_FLAG_INCLUDE_NOT_IMPORTANT_VIEWS);
                    if (view instanceof TextView text) {
                        // Do not overwrite custom provider redaction. Only patch
                        // framework-owned TextView output verified in OEM smali.
                        CharSequence full = text.getText();
                        if (full != null && full.length() > MAX_TEXT) return empty("TEXT_LIMIT", ticket, visited);
                        entry.builder.setText(full == null ? null : full.toString()); // No retained app spans.
                    }
                    var metadata = ColorOsViewMetadata.read(view);
                    if (!metadata.status().equals("OK")) return empty("MISSING_METADATA", ticket, visited);
                    entry.builder.metadata = metadata.extras();
                    List<View> children = view instanceof ViewGroup group ? ordered(group, access) : List.of();
                    if (children.size() > MAX_CHILDREN) return empty("CHILD_LIMIT", ticket, visited);
                    bindings.add(new Binding(view, view.getParent(), children));
                    for (int i = 0; i < children.size(); i++) entry.builder.children.add(new NativeNodeWriter());
                    for (int i = children.size() - 1; i >= 0; i--)
                        pending.push(new Visit(children.get(i), entry.builder.children.get(i), entry.depth + 1));
                }
            }
            int chars = 0;
            ArrayDeque<NativeNodeWriter> checked = new ArrayDeque<>(nodes);
            while (!checked.isEmpty()) {
                var node = checked.pop();
                chars += node.text.length();
                if (chars > MAX_TEXT) return empty("TEXT_LIMIT", ticket, visited);
                checked.addAll(node.children);
            }
            // Reject re-entrant provider mutations and window changes. All View
            // references are local to this call and discarded before returning.
            for (Binding binding : bindings) {
                if (!binding.view.isAttachedToWindow() || binding.view.getParent() != binding.parent
                        || binding.view instanceof ViewGroup group && !binding.children.equals(ordered(group, access)))
                    return empty("STALE_VIEW", ticket, visited);
            }
            for (View root : roots)
                if (!(root.getLayoutParams() instanceof WindowManager.LayoutParams params)
                        || (params.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0)
                    return empty("SECURE_WINDOW", ticket, visited);
            if (!valid(activity, ticket, currentGeneration)
                    || !roots.equals(ColorOsActivityCapture.filterSnapshot(WindowInspector.getGlobalWindowViews(), ticket.token)))
                return empty("STALE", ticket, visited);
            ArrayList<ColorOsNativeExtractor.Node> frozen = new ArrayList<>();
            for (NativeNodeWriter node : nodes) frozen.add(node.freeze());
            return new Result("OK", List.copyOf(frozen), visited, ticket.nonce, ticket.generation);
        } catch (Throwable ignored) { return empty("FAILED", ticket, visited); }
    }
    private record Visit(View view, NativeNodeWriter builder, int depth) { }
    private record Binding(View view, Object parent, List<View> children) { }
    private static List<View> ordered(ViewGroup group, Access access) throws Exception {
        int count = group.getChildCount();
        if (count > MAX_CHILDREN) throw new IllegalStateException("ChildLimit");
        boolean custom = Boolean.TRUE.equals(access.drawingOrder.invoke(group));
        boolean[] seen = new boolean[count];
        ArrayList<View> ordered = new ArrayList<>();
        // ViewGroup.buildOrderedChildList: stable ascending Z, respecting enabled
        // drawing order before insertion; all children included, no visibility gate.
        for (int position = 0; position < count; position++) {
            int index = custom ? group.getChildDrawingOrder(position) : position;
            if (index < 0 || index >= count || seen[index]) throw new IllegalStateException("ChildOrder");
            seen[index] = true;
            View child = group.getChildAt(index);
            if (child == null) throw new IllegalStateException("InvalidChild");
            float z = child.getZ();
            int insert = ordered.size();
            while (insert > 0 && ordered.get(insert - 1).getZ() > z) insert--;
            ordered.add(insert, child);
        }
        return List.copyOf(ordered);
    }
    private static boolean frameworkTextProvider(Class<?> type) {
        // No app-generated class-name matching. Unknown overrides fail closed.
        for (Class<?> current = type; current != null && current != TextView.class; current = current.getSuperclass())
            for (Method method : current.getDeclaredMethods())
                if (method.getName().equals("onProvideStructure") || method.getName().equals("onProvideAutofillStructure")) return false;
        return true;
    }
    private static boolean customVirtualProvider(Class<?> type) {
        // Async virtual providers may retain the builder and mutate it after
        // return. Not supported until their commit/lifetime protocol is ported.
        for (Class<?> current = type; current != null && current != View.class; current = current.getSuperclass())
            for (Method method : current.getDeclaredMethods())
                if (method.getName().equals("onProvideVirtualStructure")
                        || method.getName().equals("onProvideAutofillVirtualStructure")) return true;
        return false;
    }
    private static boolean password(View view) {
        if (!(view instanceof TextView text)) return false;
        if (text.getTransformationMethod() instanceof PasswordTransformationMethod) return true;
        int input = text.getInputType(), type = input & InputType.TYPE_MASK_CLASS, variation = input & InputType.TYPE_MASK_VARIATION;
        return type == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                || type == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }
    private static boolean valid(Activity activity, Ticket ticket, long generation) throws Exception {
        if (activity == null || ticket == null || activity.isDestroyed() || activity.isFinishing()
                || ticket.component == null || ticket.token == null || ticket.generation != generation || ticket.nonce == null
                || ticket.nonce.length() != 36
                || !ticket.nonce.matches("[a-f0-9-]{36}") || !ticket.component.equals(activity.getComponentName())
                || ticket.uid != activity.getApplicationInfo().uid || ticket.task != activity.getTaskId()
                || ticket.token != Access.INSTANCE.token.invoke(activity)) return false;
        long age = SystemClock.elapsedRealtime() - ticket.created;
        return age >= 0 && age <= TTL;
    }
    private static boolean main() { return Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper(); }
    private static Result empty(String status, Ticket ticket, int visited) {
        return new Result(status, List.of(), visited, ticket == null ? "" : ticket.nonce, ticket == null ? -1 : ticket.generation);
    }
}
