package io.github.mio.autopickupfixture;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import dalvik.system.DelegateLastClassLoader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Fixture-only local PCR execution. Reads the original asset from the installed
 * module, checks its hash, and NEVER invokes ONNX, network or notification methods. */
final class OriginalPcrFixture {
    private static final String SHA = "6aba211cc9499c70b986dcbcef8669999211af1a8b49e828c4a7299e9e9cf0b4";
    private final Object processor;
    private final Method extract;
    private final Context assetContext;
    private final Method setContext;

    OriginalPcrFixture(Context fixture) throws Exception {
        Context module = fixture.createPackageContext("io.github.mio.autopickupisland", 0);
        File apk = new File(fixture.getCodeCacheDir(), "dom-audit-pcr-" + SHA + ".apk");
        if (!apk.exists()) {
            File temp = File.createTempFile("dom-audit-pcr-", ".apk", fixture.getCodeCacheDir());
            try (InputStream input = module.getAssets().open("coloros/pcr_plugin.apk");
                 FileOutputStream output = new FileOutputStream(temp)) {
                if (!temp.setReadOnly()) throw new IllegalStateException("ReadOnlyCodeFile");
                input.transferTo(output);
                output.getFD().sync();
            }
            if (!SHA.equals(hash(temp))) throw new IllegalStateException("OriginalPcrHashMismatch");
            if (!temp.renameTo(apk)) throw new IllegalStateException("CodeFileRename");
        }
        if (!SHA.equals(hash(apk))) throw new IllegalStateException("OriginalPcrHashMismatch");
        ClassLoader loader = new DelegateLastClassLoader(apk.getAbsolutePath(), getClass().getClassLoader());
        ResourcesProvider provider;
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY)) {
            provider = ResourcesProvider.loadFromApk(fd);
        }
        ResourcesLoader resourceLoader = new ResourcesLoader();
        resourceLoader.addProvider(provider);
        Resources resources = new Resources(fixture.getAssets(), fixture.getResources().getDisplayMetrics(),
                fixture.getResources().getConfiguration());
        resources.addLoaders(resourceLoader);
        assetContext = new ContextWrapper(fixture.getApplicationContext()) {
            @Override public AssetManager getAssets() { return resources.getAssets(); }
            @Override public Resources getResources() { return resources; }
            @Override public Context getApplicationContext() { return this; }
            @Override public ClassLoader getClassLoader() { return loader; }
        };
        setContext = loader.loadClass("com.oplus.aiunit.plugin.utils.AssetUtils")
                .getMethod("setApplicationContext", Context.class);
        Class<?> type = loader.loadClass("com.oplus.aiunit.plugin.business.OrderInfoProcessor");
        processor = type.getField("INSTANCE").get(null);
        extract = type.getMethod("oderInfoExtract", List.class, String.class, String.class);
    }

    synchronized Bundle extract(List<String> texts, String tag, String actualRoute) throws Exception {
        ArrayList<String> lines = new ArrayList<>();
        for (String text : texts) {
            for (String line : text.split("[\\r\\n]+")) {
                if (!line.trim().isEmpty()) lines.add(line.trim());
            }
        }
        setContext.invoke(null, assetContext);
        return (Bundle) extract.invoke(processor, lines, tag, actualRoute);
    }

    private static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[65536];
            int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
        }
        StringBuilder value = new StringBuilder();
        for (byte b : digest.digest()) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
}
