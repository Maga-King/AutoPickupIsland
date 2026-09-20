package io.github.mio.autopickupisland;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.ParcelFileDescriptor;

import java.io.File;

@SuppressLint("StaticFieldLeak")
final class ModuleAccess {
    private static volatile Context cached;

    private ModuleAccess() {
    }

    static Context context(Context host) {
        Context current = cached;
        if (current != null) return current;
        try {
            current = host.createPackageContext(Constants.MODULE_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY);
            cached = current;
            return current;
        } catch (Exception ignored) {
            // Injected processes do not inherit this module's package-visibility declarations.
            // ResourcesLoader can read the APK path supplied by LSPosed without package lookup.
        }
        synchronized (ModuleAccess.class) {
            current = cached;
            if (current != null) return current;
            try {
                File apk = new File(HookEntry.modulePath);
                if (!apk.isFile()) throw new IllegalStateException("module APK path unavailable");
                ResourcesProvider provider;
                try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                        apk, ParcelFileDescriptor.MODE_READ_ONLY)) {
                    provider = ResourcesProvider.loadFromApk(descriptor);
                }
                ResourcesLoader loader = new ResourcesLoader();
                loader.addProvider(provider);
                Resources hostResources = host.getResources();
                Resources resources = new Resources(hostResources.getAssets(),
                        hostResources.getDisplayMetrics(), hostResources.getConfiguration());
                resources.addLoaders(loader);
                AssetManager assets = resources.getAssets();
                Context application = host.getApplicationContext() == null
                        ? host : host.getApplicationContext();
                current = new ContextWrapper(application) {
                    @Override public AssetManager getAssets() { return assets; }
                    @Override public Resources getResources() { return resources; }
                    @Override public Context getApplicationContext() { return this; }
                    @Override public String getPackageName() { return Constants.MODULE_PACKAGE; }
                    @Override public ClassLoader getClassLoader() {
                        return HookEntry.class.getClassLoader();
                    }
                };
                cached = current;
                return current;
            } catch (Exception error) {
                throw new IllegalStateException("Cannot open module resources", error);
            }
        }
    }
}
