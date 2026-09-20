package io.github.mio.autopickupisland;

import android.app.Notification;
import android.os.Bundle;

final class PickupNotificationScope {
    static boolean owns(String pkg, Notification notification) {
        if (!Constants.PKG_VOICE_ASSIST.equals(pkg) || notification == null || notification.extras == null) return false;
        try {
            Bundle extras = notification.extras;
            return extras.getBoolean("mio.auto_pickup", false)
                    && extras.getBoolean("mio.voiceassist.native_memory_scene", false);
        } catch (RuntimeException ignored) { return false; }
    }
}
