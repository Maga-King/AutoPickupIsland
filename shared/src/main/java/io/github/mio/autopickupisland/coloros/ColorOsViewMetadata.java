package io.github.mio.autopickupisland.coloros;

import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import java.lang.reflect.Method;

/** Live-View metadata adapter, transcribed from ColorOS16 ViewExtImpl and
 * OplusViewExtractManager smali. NOT a capture service: no hooks, text reads,
 * window enumeration, timers, IPC or retained Views. The caller must enforce
 * activity/window identity, secure-window and password/assist-blocked policy.
 * Missing hidden API access is reported, never replaced by guessed coordinates
 * or a fabricated isVisibleToUserIgnoreRoot=true.
 */
public final class ColorOsViewMetadata {
    public record Result(Bundle extras, String status, boolean visibilityAvailable,
                         boolean boundsAvailable) { }
    private static final int MAX_ANCESTORS = 256;
    private ColorOsViewMetadata() { }

    // Lazy, once per class loader. Both success and absence are cached; no
    // repeated reflective discovery per node. These are framework names, not
    // generated/obfuscated merchant class names.
    private static final class Access {
        static final Method TRANSITION = method("getTransitionAlpha");
        static final Method BOUNDS = method("getBoundsOnScreen", Rect.class);
        static final Class<?> DECOR = decor();
        static Method method(String name, Class<?>... args) {
            try { return View.class.getMethod(name, args); }
            catch (Throwable ignored) { return null; }
        }
        static Class<?> decor() {
            try { return Class.forName("com.android.internal.policy.DecorView", false, View.class.getClassLoader()); }
            catch (Throwable ignored) { return null; }
        }
    }

    public static Result read(View view) {
        Bundle extras = new Bundle();
        if (view == null) return new Result(extras, "NO_VIEW", false, false);
        if (Looper.getMainLooper() == null || Looper.myLooper() != Looper.getMainLooper())
            return new Result(extras, "WRONG_THREAD", false, false);
        boolean bounds = false, visibility = false;
        try {
            extras.putString("extraClassName", view.getClass().getName());
            if (Access.DECOR != null && Access.DECOR.isInstance(view))
                extras.putInt("rootHashCode", System.identityHashCode(view));
            if (Access.BOUNDS != null) {
                try {
                    Rect screen = new Rect();
                    Access.BOUNDS.invoke(view, screen);
                    extras.putParcelable("getBoundsOnScreen", screen);
                    bounds = true;
                } catch (Throwable ignored) { /* Independent capability. */ }
            }
            Rect visible = new Rect();
            view.getGlobalVisibleRect(visible);
            extras.putParcelable("getVisibleBoundsOnScreen", visible);
            try {
                boolean[] flags = visibility(view);
                if (flags != null) {
                    extras.putBoolean("isVisibleToUser", flags[0]);
                    extras.putBoolean("isVisibleToUserIgnoreRoot", flags[1]);
                    visibility = true;
                }
            } catch (Throwable ignored) { /* Omit both flags on failure. */ }
            try {
                if (view instanceof ImageView image) {
                    if (image.getScaleType() != null) extras.putString("scaleType", image.getScaleType().name());
                    if (image.getScaleType() == ImageView.ScaleType.MATRIX) {
                        Matrix matrix = image.getImageMatrix();
                        if (matrix != null) {
                            float[] values = new float[9];
                            matrix.getValues(values);
                            extras.putFloatArray("imageMatrix", values);
                        }
                    }
                }
            } catch (Throwable ignored) { /* OEM also isolates image metadata. */ }
            return new Result(extras, visibility && bounds ? "OK" : "PARTIAL", visibility, bounds);
        } catch (Throwable ignored) {
            return new Result(new Bundle(), "FAILED", false, false);
        }
    }

    private static boolean[] visibility(View view) throws Exception {
        // Public isAttachedToWindow/getWindowVisibility correspond to OEM
        // mAttachInfo != null / mAttachInfo.mWindowVisibility; no private writes.
        if (!view.isAttachedToWindow()) return new boolean[]{false, false};
        if (Access.TRANSITION == null || Access.DECOR == null) return null;
        boolean normal = view.getWindowVisibility() == View.VISIBLE, ignoreRoot = true;
        Object current = view;
        int ancestors = 0;
        while (current instanceof View ancestor) {
            if (++ancestors > MAX_ANCESTORS) return null;
            // Preserve OEM short-circuit ordering, including NaN semantics.
            if (ancestor.getAlpha() <= 0f || ((Number) Access.TRANSITION.invoke(ancestor)).floatValue() <= 0f
                    || ancestor.getVisibility() != View.VISIBLE) {
                normal = false;
                ignoreRoot = Access.DECOR.isInstance(ancestor);
                if (!ignoreRoot) return new boolean[]{false, false};
            }
            current = ancestor.getParent();
        }
        if (!view.getGlobalVisibleRect(new Rect(), new Point())) return new boolean[]{false, false};
        return new boolean[]{normal, ignoreRoot};
    }
}
