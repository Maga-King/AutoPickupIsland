package io.github.mio.autopickupisland;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    static volatile String modulePath = "";

    @Override
    public void initZygote(StartupParam startupParam) {
        modulePath = startupParam.modulePath;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null) return;
        try {
            try { HookHealth.start(lpparam); } catch (Throwable ignored) { }
            switch (lpparam.packageName) {
                case "android" -> SystemCollectorLoader.install(lpparam);
                case Constants.PKG_AICR -> {
                    AicrHook.install(lpparam);
                    if (Constants.PKG_AICR.equals(lpparam.processName)
                            || lpparam.processName.endsWith(":cognitionService")) {
                        ManualIslandRepair.install(lpparam);
                    }
                }
                case Constants.PKG_VOICE_ASSIST -> {
                    VoiceAssistNativeIsland.install(lpparam);
                    ManualIslandRepair.install(lpparam);
                }
                case Constants.PKG_PRIVILEGED_ASSIST -> PrivilegedAssistHook.install(lpparam);
                case Constants.PKG_SYSTEM_UI -> {
                    SystemUiAnimationHook.install(lpparam);
                    if (Constants.PKG_SYSTEM_UI.equals(lpparam.processName)) {
                        SystemUiNotificationBackgroundHook.install(lpparam.classLoader);
                    }
                }
                default -> {
                }
            }
        } catch (Throwable error) {
            HookHealth.state("install_error", error.getClass().getSimpleName());
            XposedBridge.log("AutoPickupIsland install failed in " + lpparam.processName + ": " + error);
        }
    }
}
