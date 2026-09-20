package io.github.mio.autopickupisland;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DelegateLastClassLoader;

@SuppressLint("StaticFieldLeak")
final class ColorOsRecognizer {
    static final class Result {
        String code = "";
        String product = "";
        String status = "";
        String engine = "";
        String orderTime = "";
        String processType = "";
        String temperature = "";
        boolean orderPage;
        int orderState;
        String failure = "";
        String failureSite = "";
        Bundle original = new Bundle();

        boolean found() {
            return !code.isEmpty();
        }

        boolean waitingForCode(PickupEvent event) {
            // g6.f.C: need_waiting_status && empty orderCodeList && status=waiting.
            // Keep this semantic state separate from the real code and its dedupe key.
            return event != null && event.needWaitingStatus && !found()
                    && failure.isEmpty() && "waiting".equals(status);
        }
    }

    private static volatile Plugin plugin;
    private static volatile String lastFailure = "";

    static ClassLoader originalLoader(Context context) throws Exception { return plugin(context).loader; }

    /** Display-only enrichment. Reuses the original full-line keyword helper, which
     * the OEM dictionary-first branch otherwise skips. Does not change original PCR,
     * code, order status or its product used by recognition/model selection.
     */
    String displayProduct(Context context, PickupEvent event, Result result) {
        if (event == null || result == null || !result.found() || result.product.isEmpty()
                || !event.pathObserved || !event.webView) return "";
        try {
            Plugin loaded = plugin(context);
            synchronized (loaded) {
                Class<?> type = loaded.loader.loadClass("com.oplus.aiunit.plugin.business.OrderInfoProcessor");
                Method method = type.getDeclaredMethod("getProductNameByKeywords", List.class);
                method.setAccessible(true);
                Object raw = method.invoke(type.getField("INSTANCE").get(null), contentLines(event.content));
                if (!(raw instanceof List<?> names)) return "";
                java.util.Set<String> unique = new java.util.LinkedHashSet<>();
                for (Object name : names) {
                    if (!(name instanceof String candidate)) continue;
                    candidate = PickupEvent.clean(candidate);
                    if (!candidate.endsWith(result.product) || candidate.length() > 32
                            || !candidate.matches("[\\p{L}0-9·•（）()\\- ]{2,32}")
                            || candidate.matches(".*(券|优惠|推荐|加购|赠送|兑换|折扣|减免|购买|试试|再来).*")) continue;
                    // Must be a full observed line (OEM slash-separated spec suffix allowed).
                    for (String line : contentLines(event.content)) {
                        if (line.equals(candidate) || line.startsWith(candidate + "/")) { unique.add(candidate); break; }
                    }
                }
                return unique.size() == 1 ? unique.iterator().next() : "";
            }
        } catch (Throwable ignored) { return ""; }
    }

    Result recognize(Context host, PickupEvent event, boolean allowModel) {
        if (event == null) return new Result();
        if (event.isMiniProgram() && (!event.pathObserved || event.recognitionPath().isEmpty())) {
            Result missingPath = new Result();
            missingPath.failure = "ActualMiniProgramPathUnavailable"; // g6.f.n: do not invent a path.
            missingPath.engine = "ColorOS-input-gate";
            return missingPath;
        }
        ArrayList<String> lines = contentLines(event.content);
        if (lines.isEmpty()) return new Result();
        try {
            Plugin loaded = plugin(host);
            // OrderInfoProcessor.INSTANCE has mutable static per-order fields. Keep the
            // complete local/model transaction serialized, including direct/manual callers.
            synchronized (loaded) {
                Bundle local = invokeLocal(loaded, lines, event.tagAppName, event.recognitionPath());
                Result result = fromBundle(local, "ColorOS-local", event);
                if (result.found()) return result;
                // PCRCodeManager.processLocal falls back to local ONNX independently of
                // XML useCloud. The host's allowModel guard still has priority (not image OCR).
                if (allowModel && Build.SUPPORTED_ABIS.length > 0
                        && Build.SUPPORTED_ABIS[0].contains("arm64")) {
                    Result model = invokeModel(loaded, lines, event.tagAppName, event.recognitionPath(), event);
                    if (model.found()) return model;
                }
                return result;
            }
        } catch (Throwable error) {
            Throwable cause = error;
            while (cause instanceof java.lang.reflect.InvocationTargetException
                    && cause.getCause() != null) cause = cause.getCause();
            Result failed = new Result();
            failed.engine = "ColorOS-error";
            failed.failure = cause.getClass().getSimpleName();
            StackTraceElement[] frames = cause.getStackTrace();
            if (frames.length > 0) failed.failureSite = frames[0].getClassName() + "#" + frames[0].getMethodName();
            // Don't log source text or exception messages, which can contain page data.
            if (!failed.failure.equals(lastFailure)) {
                lastFailure = failed.failure;
                Log.e("MioColorOsPCR", "Original PCR failed closed: " + failed.failure);
            }
            return failed;
        }
    }

    private static Bundle invokeLocal(Plugin plugin, List<String> lines,
                                      String appName, String appPath) throws Exception {
        Class<?> assetUtils = plugin.loader.loadClass("com.oplus.aiunit.plugin.utils.AssetUtils");
        assetUtils.getMethod("setApplicationContext", Context.class)
                .invoke(null, plugin.assetContext);
        Class<?> processor = plugin.loader.loadClass(
                "com.oplus.aiunit.plugin.business.OrderInfoProcessor");
        Field instanceField = processor.getField("INSTANCE");
        Object instance = instanceField.get(null);
        Method method = processor.getMethod("oderInfoExtract", List.class, String.class, String.class);
        return (Bundle) method.invoke(instance, lines, safeAppName(appName), PickupEvent.observedPath(appPath));
    }

    private static Result invokeModel(Plugin plugin, List<String> lines,
                                      String appName, String appPath,
                                      PickupEvent event) throws Exception {
        Class<?> managerClass = plugin.loader.loadClass("com.oplus.aiunit.plugin.pcr.PCRCodeManager");
        Object manager = managerClass.getConstructor().newInstance();
        Method release = managerClass.getMethod("releaseSDK");
        try {
            boolean initialized = Boolean.TRUE.equals(managerClass.getMethod("initSDK", Context.class)
                    .invoke(manager, plugin.assetContext));
            if (!initialized) return new Result();
            JSONObject input = new JSONObject();
            input.put("appName", safeAppName(appName));
            input.put("appPath", PickupEvent.observedPath(appPath));
            input.put("contentList", String.join(", ", lines)); // g6.f.n, no invented [ / ] tokens.
            input.put("processMethod", "local");
            Bundle output = (Bundle) managerClass.getMethod("processContent", byte[].class, String.class)
                    .invoke(manager, null, input.toString());
            return fromBundle(output, "ColorOS-ONNX", event);
        } finally {
            try {
                release.invoke(manager);
            } catch (Throwable ignored) {
            }
        }
    }

    private static Result fromBundle(Bundle bundle, String engine, PickupEvent event) {
        Result result = new Result();
        if (bundle == null) return result;
        result.original = new Bundle(bundle);
        ArrayList<String> codes = bundle.getStringArrayList("orderCodeList");
        if (codes != null && !codes.isEmpty() && codes.get(0) != null) {
            // ColorOS g6.f.C also takes the first original candidate. Never transform a
            // date/price/store identifier into a plausible code by stripping punctuation.
            result.code = codes.get(0);
        }
        result.product = PickupEvent.clean(bundle.getString("productName", ""));
        result.status = PickupEvent.clean(bundle.getString("orderStatus", ""));
        result.orderTime = PickupEvent.clean(bundle.getString("orderTime", ""));
        result.processType = PickupEvent.clean(bundle.getString("processType", ""));
        result.temperature = PickupEvent.clean(bundle.getString("drinkTemperature", ""));
        result.orderPage = bundle.getBoolean("orderPageFlag", false);
        result.engine = engine;
        return result;
    }

    static ArrayList<String> contentLines(String content) {
        ArrayList<String> lines = new ArrayList<>();
        for (String raw : PickupEvent.clean(content).split("[\\r\\n]+")) {
            String line = PickupEvent.clean(raw);
            // Repeated labels delimit separate orders; global distinct() destroys the
            // +/-3 element neighborhoods used by the original OrderInfoProcessor.
            if (!line.isEmpty()) lines.add(line);
            if (lines.size() >= 6_000) break;
        }
        return lines;
    }

    private static String safeAppName(String appName) {
        String value = PickupEvent.clean(appName);
        return value.isEmpty() ? "common" : value;
    }

    private static Plugin plugin(Context host) throws Exception {
        Plugin current = plugin;
        if (current != null) return current;
        synchronized (ColorOsRecognizer.class) {
            current = plugin;
            if (current == null) {
                Context application = host.getApplicationContext();
                current = installPlugin(application == null ? host : application);
                plugin = current;
            }
        }
        return current;
    }

    private static Plugin installPlugin(Context host) throws Exception {
        Context module = ModuleAccess.context(host);
        File root = new File(host.getCodeCacheDir(), "mio_coloros_pcr_1060");
        File libDir = new File(root, "lib");
        if (!libDir.exists() && !libDir.mkdirs()) throw new IllegalStateException("lib directory");
        File apk = new File(root, "pcr_code_1060.apk");
        if (!apk.isFile() || apk.length() < 1_000_000L) {
            File temp = new File(root, "pcr_code_1060.tmp");
            try (InputStream input = module.getAssets().open(Constants.PCR_PLUGIN_ASSET);
                 FileOutputStream output = new FileOutputStream(temp)) {
                copy(input, output);
                output.getFD().sync();
            }
            if (apk.exists() && !apk.delete()) throw new IllegalStateException("old plugin apk");
            if (!temp.renameTo(apk)) throw new IllegalStateException("plugin rename");
            apk.setReadOnly();
        }
        extractLibraries(apk, libDir);
        ClassLoader loader = new DelegateLastClassLoader(apk.getAbsolutePath(),
                libDir.getAbsolutePath(), ColorOsRecognizer.class.getClassLoader());
        Context assetContext = assetContext(host, apk, loader);
        return new Plugin(loader, assetContext);
    }

    private static Context assetContext(Context host, File apk, ClassLoader loader) throws Exception {
        ResourcesProvider provider;
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                apk, ParcelFileDescriptor.MODE_READ_ONLY)) {
            provider = ResourcesProvider.loadFromApk(descriptor);
        }
        ResourcesLoader resourcesLoader = new ResourcesLoader();
        resourcesLoader.addProvider(provider);
        Resources resources = new Resources(host.getResources().getAssets(),
                host.getResources().getDisplayMetrics(), host.getResources().getConfiguration());
        resources.addLoaders(resourcesLoader);
        AssetManager assets = resources.getAssets();
        return new ContextWrapper(host) {
            @Override public AssetManager getAssets() { return assets; }
            @Override public Resources getResources() { return resources; }
            @Override public Context getApplicationContext() { return this; }
            @Override public ClassLoader getClassLoader() { return loader; }
        };
    }

    private static void extractLibraries(File apk, File libDir) throws Exception {
        try (ZipFile zip = new ZipFile(apk)) {
            String abi = Build.SUPPORTED_ABIS.length == 0 ? "arm64-v8a" : Build.SUPPORTED_ABIS[0];
            for (String name : new String[]{"libonnxruntime.so", "libonnxruntime4j_jni.so",
                    "libaiunit_sdk_core.so"}) {
                ZipEntry entry = zip.getEntry("lib/" + abi + "/" + name);
                if (entry == null) entry = zip.getEntry("lib/arm64-v8a/" + name);
                if (entry == null) continue;
                File output = new File(libDir, name);
                if (output.isFile() && output.length() == entry.getSize()) continue;
                File temp = new File(libDir, name + ".tmp");
                try (InputStream input = zip.getInputStream(entry);
                     FileOutputStream stream = new FileOutputStream(temp)) {
                    copy(input, stream);
                    stream.getFD().sync();
                }
                if (output.exists() && !output.delete()) throw new IllegalStateException("old library");
                if (!temp.renameTo(output)) throw new IllegalStateException("library rename");
                output.setReadOnly();
            }
        }
    }

    private static void copy(InputStream input, FileOutputStream output) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
    }

    private record Plugin(ClassLoader loader, Context assetContext) {
    }
}
