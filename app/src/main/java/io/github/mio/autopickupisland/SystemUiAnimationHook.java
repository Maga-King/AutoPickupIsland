package io.github.mio.autopickupisland;

import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Starts the animated WebP used by our native Focus template.
 *
 * Xiaomi's image-text template loads URI icons through ImageView#setImageIcon,
 * but does not start AnimatedImageDrawable. Hooking this stable framework API
 * avoids depending on MIUISystemUIPlugin's obfuscated/dynamic class loader. The
 * URI predicate makes the hook a no-op for every icon except this module's
 * read-only pickup model provider.
 */
final class SystemUiAnimationHook {
    private static final String ASSET_URI_PREFIX =
            "content://" + Constants.PROVIDER_AUTHORITY + "/asset/";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean START_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean TYPE_LOGGED = new AtomicBoolean();
    private static final Map<ImageView, String> WAITING =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ImageView, String> SOURCES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ImageView, String> DECODING =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ImageView, VisibilityWatch> WATCHES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadPoolExecutor DECODER = new ThreadPoolExecutor(
            0, 1, 10L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), task -> {
                Thread thread = new Thread(task, "MioPickupWebpDecode");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!Constants.PKG_SYSTEM_UI.equals(lpparam.processName)
                || !INSTALLED.compareAndSet(false, true)) {
            return;
        }

        try {
            // RemoteViews.applyAsync uses these setters, not setImageIcon/setImageURI.
            // The returned Runnable applies the decoded first frame on the UI thread.
            // Only start our replacement AFTER that runnable; starting earlier is overwritten.
            hookAsyncSetter("setImageIconAsync");
            hookAsyncSetter("setImageURIAsync");
            XposedBridge.hookAllMethods(ImageView.class, "setImageIcon", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.thisObject instanceof ImageView imageView)
                                || param.args.length != 1
                                || !(param.args[0] instanceof Icon icon)
                                || !isPickupAsset(icon)) {
                            return;
                        }

                        markAndStartWhenVisible(imageView, icon.getUri());
                    } catch (Throwable error) {
                        // Rendering must never be allowed to destabilize SystemUI.
                        if (TYPE_LOGGED.compareAndSet(false, true)) {
                            XposedBridge.log("AutoPickupIsland/SystemUI animation skipped: " + error);
                        }
                    }
                }
            });
            XposedBridge.hookAllMethods(ImageView.class, "setImageURI", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.thisObject instanceof ImageView imageView)
                                || param.args.length != 1
                                || !(param.args[0] instanceof Uri uri)
                                || !uri.toString().startsWith(ASSET_URI_PREFIX)) return;
                        markAndStartWhenVisible(imageView, uri);
                    } catch (Throwable error) {
                        if (TYPE_LOGGED.compareAndSet(false, true)) {
                            XposedBridge.log("AutoPickupIsland/SystemUI URI animation skipped: "
                                    + error);
                        }
                    }
                }
            });
            XposedBridge.hookAllMethods(View.class, "onVisibilityAggregated",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args.length != 1 || !(param.args[0] instanceof Boolean shown)
                                        || !(param.thisObject instanceof View view)) {
                                    return;
                                }
                                if (view instanceof ImageView imageView) {
                                    String source = SOURCES.get(imageView);
                                    if (source != null) {
                                        if (shown) markAndStartWhenVisible(imageView, Uri.parse(source));
                                        else {
                                            WAITING.put(imageView, source);
                                            if (imageView.getDrawable() instanceof AnimatedImageDrawable animated) animated.stop();
                                            VisibilityWatch watch = WATCHES.get(imageView);
                                            if (watch != null) watch.unobserve();
                                        }
                                    }
                                }
                                if (shown) startPickupMarquee(view);
                            } catch (Throwable ignored) {
                                // This callback is system-wide. Every failure is intentionally
                                // swallowed so a malformed/changed view can never affect SystemUI.
                            }
                        }
                    });
            XposedBridge.hookAllMethods(View.class, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.thisObject instanceof View view)) return;
                        SystemUiPickupGuard.observe(view);
                        if (view instanceof ImageView imageView) startMarked(imageView);
                        startPickupMarquee(view);
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log("AutoPickupIsland/SystemUI: visibility-gated animation hook installed");
        } catch (Throwable error) {
            INSTALLED.set(false);
            XposedBridge.log("AutoPickupIsland/SystemUI install skipped: " + error);
        }
    }

    private static boolean isPickupAsset(Icon icon) {
        try {
            int type = icon.getType();
            if (type != Icon.TYPE_URI && type != Icon.TYPE_URI_ADAPTIVE_BITMAP) {
                return false;
            }
            return icon.getUri() != null
                    && icon.getUri().toString().startsWith(ASSET_URI_PREFIX);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void hookAsyncSetter(String name) {
        try {
            XposedBridge.hookAllMethods(ImageView.class, name, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.thisObject instanceof ImageView view)
                                || param.args.length != 1
                                || !(param.getResult() instanceof Runnable apply)) return;
                        Uri uri = null;
                        if (param.args[0] instanceof Icon icon && isPickupAsset(icon)) {
                            uri = icon.getUri();
                        } else if (param.args[0] instanceof Uri value
                                && value.toString().startsWith(ASSET_URI_PREFIX)) {
                            uri = value;
                        }
                        if (uri == null) return;
                        Uri source = uri;
                        param.setResult((Runnable) () -> {
                            apply.run(); // Preserve the host's original behavior/exceptions.
                            try { markAndStartWhenVisible(view, source); }
                            catch (Throwable ignored) { }
                        });
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable ignored) {
            // Missing async API on another ROM is not a reason to disable the sync path.
        }
    }

    private static void markAndStartWhenVisible(ImageView imageView, Uri uri) {
        if (uri == null || imageView.getId() != R.id.coloros_pickup_model) return;
        String source = uri.toString();
        SOURCES.put(imageView, source);
        WAITING.put(imageView, source);
        if (!WATCHES.containsKey(imageView)) {
            VisibilityWatch watch = new VisibilityWatch(imageView);
            WATCHES.put(imageView, watch);
            imageView.addOnAttachStateChangeListener(watch);
        }
        VisibilityWatch pendingWatch = WATCHES.get(imageView);
        if (pendingWatch != null) pendingWatch.attach();
        // Icon.loadDrawable() in HyperOS deliberately decodes content-URI WebP files with
        // BitmapFactory and therefore returns only a BitmapDrawable first frame. Decode only
        // our D19 model view again through ImageDecoder; all Xiaomi/third-party ImageViews are
        // excluded by both the provider URI and this resource id.
        if (imageView.getId() == R.id.coloros_pickup_model
                && !(imageView.getDrawable() instanceof AnimatedImageDrawable)) {
            decodeAnimated(imageView, uri, source);
        }
        if (imageView.isAttachedToWindow() && imageView.isShown()) {
            imageView.post(() -> {
                try {
                    startMarked(imageView);
                } catch (Throwable ignored) {
                }
            });
        }
    }

    private static void decodeAnimated(ImageView imageView, Uri uri, String source) {
        synchronized (DECODING) {
            if (source.equals(DECODING.get(imageView))) return;
            DECODING.put(imageView, source);
        }
        Runnable decode = () -> {
            Drawable drawable = null;
            try {
                ImageDecoder.Source imageSource = ImageDecoder.createSource(
                        imageView.getContext().getContentResolver(), uri);
                drawable = ImageDecoder.decodeDrawable(imageSource, (decoder, info, sourceInfo) -> {
                    int width = info.getSize().getWidth();
                    int height = info.getSize().getHeight();
                    if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
                        throw new IllegalArgumentException("Unexpected pickup model dimensions");
                    }
                    float scale = Math.min(1f, Math.min(400f / width, 468f / height));
                    decoder.setTargetSize(Math.max(1, Math.round(width * scale)),
                            Math.max(1, Math.round(height * scale)));
                });
            } catch (Throwable error) {
                if (TYPE_LOGGED.compareAndSet(false, true)) {
                    XposedBridge.log("AutoPickupIsland/SystemUI animated decode skipped: "
                            + error);
                }
            }
            Drawable decoded = drawable;
            new Handler(Looper.getMainLooper()).post(() -> {
                synchronized (DECODING) {
                    if (source.equals(DECODING.get(imageView))) DECODING.remove(imageView);
                }
                try {
                    if (!source.equals(WAITING.get(imageView))) {
                        if (decoded instanceof AnimatedImageDrawable stale) stale.stop();
                        return;
                    }
                    if (decoded instanceof AnimatedImageDrawable) {
                        imageView.setImageDrawable(decoded);
                        startMarked(imageView);
                    } else {
                        WAITING.remove(imageView);
                        VisibilityWatch watch = WATCHES.get(imageView);
                        if (watch != null) watch.close();
                        if (TYPE_LOGGED.compareAndSet(false, true)) {
                            XposedBridge.log("AutoPickupIsland/SystemUI ImageDecoder returned "
                                    + (decoded == null ? "null" : decoded.getClass().getName()));
                        }
                    }
                } catch (Throwable error) {
                    if (decoded instanceof AnimatedImageDrawable failed) failed.stop();
                }
            });
        };
        try {
            DECODER.execute(decode);
        } catch (Throwable error) {
            DECODING.remove(imageView);
            WAITING.remove(imageView);
            VisibilityWatch watch = WATCHES.get(imageView);
            if (watch != null) watch.close();
        }
    }

    private static void startMarked(ImageView imageView) {
        if (WAITING.get(imageView) == null || !isActuallyVisible(imageView)) return;
        Drawable drawable = imageView.getDrawable();
        if (drawable instanceof AnimatedImageDrawable animated) {
            WAITING.remove(imageView);
            // Android counts repeats after the first playback. Zero is exactly once.
            animated.setRepeatCount(0);
            animated.stop();
            animated.start();
            VisibilityWatch watch = WATCHES.get(imageView);
            if (watch != null) watch.unobserve();
            if (START_LOGGED.compareAndSet(false, true)) {
                XposedBridge.log("AutoPickupIsland/SystemUI: ColorOS model started once");
            }
        } else if (TYPE_LOGGED.compareAndSet(false, true)) {
            XposedBridge.log("AutoPickupIsland/SystemUI: pickup URI decoded as "
                    + (drawable == null ? "null" : drawable.getClass().getName()));
        }
    }

    /** Per-image pre-draw: attachment may occur before layout (width/height are still zero).
     * No timers/polling and no global draw hook. Stops watching after the first visible play. */
    private static final class VisibilityWatch implements View.OnAttachStateChangeListener,
            ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<ImageView> reference;
        private ViewTreeObserver observer;
        VisibilityWatch(ImageView view) { reference = new WeakReference<>(view); }
        void attach() {
            ImageView view = reference.get();
            if (view == null || !view.isAttachedToWindow() || observer != null) return;
            observer = view.getViewTreeObserver();
            if (observer.isAlive()) observer.addOnPreDrawListener(this);
        }
        void unobserve() {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
        }
        void close() {
            unobserve();
            ImageView view = reference.get();
            if (view != null) {
                view.removeOnAttachStateChangeListener(this);
                WATCHES.remove(view);
            }
        }
        @Override public boolean onPreDraw() {
            try {
                ImageView view = reference.get();
                if (view == null || WAITING.get(view) == null) unobserve();
                else startMarked(view);
            } catch (Throwable ignored) { close(); }
            return true;
        }
        @Override public void onViewAttachedToWindow(View view) {
            try {
                ImageView image = reference.get();
                if (image != null && SOURCES.get(image) != null) WAITING.put(image, SOURCES.get(image));
                attach();
            } catch (Throwable ignored) { }
        }
        @Override public void onViewDetachedFromWindow(View view) {
            try {
                unobserve();
                if (view instanceof ImageView image
                        && image.getDrawable() instanceof AnimatedImageDrawable animated) {
                    animated.stop();
                }
            } catch (Throwable ignored) { }
        }
    }

    private static boolean isActuallyVisible(ImageView imageView) {
        if (!imageView.isAttachedToWindow() || !imageView.isShown()
                || imageView.getWindowVisibility() != View.VISIBLE
                || imageView.getAlpha() <= 0.01f) return false;
        // Preinflated expanded cards can be shown/layout-ready under an alpha-zero parent.
        // Do not spend the one-shot animation while that host is still hidden.
        android.view.ViewParent parent = imageView.getParent();
        while (parent instanceof View ancestor) {
            if (ancestor.getAlpha() <= 0.01f || ancestor.getVisibility() != View.VISIBLE) return false;
            parent = ancestor.getParent();
        }
        int width = imageView.getWidth();
        int height = imageView.getHeight();
        if (width <= 0 || height <= 0) return false;
        Rect visible = new Rect();
        if (!imageView.getGlobalVisibleRect(visible)) return false;
        long visibleArea = (long) visible.width() * visible.height();
        long fullArea = (long) width * height;
        return visibleArea * 2L >= fullArea;
    }

    private static void startPickupMarquee(View view) {
        if (!(view instanceof TextView textView)) return;
        Object tag = textView.getTag();
        if (!"mio_auto_pickup_marquee".equals(tag)) return;
        textView.setSelected(true);
    }

    private SystemUiAnimationHook() {
    }
}
