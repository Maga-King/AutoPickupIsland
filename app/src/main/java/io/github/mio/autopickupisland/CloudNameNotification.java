package io.github.mio.autopickupisland;

import android.app.Notification;
import android.widget.RemoteViews;

/** Pure copy operation; no publishing, ownership decisions or PendingIntent creation. */
final class CloudNameNotification {
    static final String[] VIEWS = {"miui.focus.rv", "miui.focus.rvNight", "miui.focus.rv.island.expand"};

    static Notification copy(Notification source, String instance, String name) {
        if (source == null || source.extras == null || instance == null || instance.isEmpty()
                || !instance.equals(source.extras.getString(NativePickupOwnership.INSTANCE))
                || !source.extras.getBoolean("mio.auto_pickup")
                || !source.extras.getBoolean("mio.voiceassist.native_memory_scene")
                || name == null || name.isBlank() || name.length() > 48) return null;
        Notification updated = source.clone();
        for (String key : VIEWS) {
            RemoteViews original = source.extras.getParcelable(key, RemoteViews.class);
            if (original == null || !Constants.MODULE_PACKAGE.equals(original.getPackage())
                    || original.getLayoutId() != R.layout.coloros_pickup_focus) return null;
            RemoteViews copy = original.clone();
            copy.setTextViewText(R.id.coloros_pickup_product, name);
            updated.extras.putParcelable(key, copy);
        }
        updated.extras.putCharSequence(Notification.EXTRA_TEXT, name);
        updated.extras.putString("mio.pickup.cloudName.v1", name);
        updated.flags |= Notification.FLAG_ONLY_ALERT_ONCE;
        return updated;
    }
    private CloudNameNotification() { }
}
