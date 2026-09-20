package io.github.mio.autopickupisland;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.os.Process;

final class BroadcastVerifier {
    private BroadcastVerifier() { }

    static String sender(BroadcastReceiver receiver, Context context) {
        String direct = receiver.getSentFromPackage();
        if (direct != null && !direct.isEmpty()) return direct;
        int uid = receiver.getSentFromUid();
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        return packages == null || packages.length == 0 ? "" : packages[0];
    }

    static boolean isSystem(BroadcastReceiver receiver) {
        return receiver.getSentFromUid() == Process.SYSTEM_UID;
    }

    static boolean isPackage(BroadcastReceiver receiver, Context context, String packageName) {
        if (receiver == null || context == null || packageName == null) return false;
        try {
            String direct = receiver.getSentFromPackage();
            if (packageName.equals(direct)) return true;
            String[] packages = context.getPackageManager()
                    .getPackagesForUid(receiver.getSentFromUid());
            if (packages == null) return false;
            for (String candidate : packages) {
                if (packageName.equals(candidate)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
