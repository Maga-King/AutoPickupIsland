package io.github.mio.autopickupisland;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

final class BridgeClient {
    private BridgeClient() {
    }

    static boolean publish(Context context, PickupEvent event) {
        try {
            Bundle result = context.getContentResolver().call(
                    Constants.PROVIDER_URI, "publish", null, event.toBundle());
            if (result != null && result.getBoolean("accepted", false)) return true;
        } catch (Exception ignored) {
        }
        // Injected code cannot add <queries> to the host manifest. An explicit component
        // broadcast remains addressable under package-visibility filtering and is event-only.
        try {
            context.sendBroadcast(new Intent(Constants.ACTION_INGRESS)
                    .setComponent(new ComponentName(Constants.MODULE_PACKAGE,
                            Constants.MODULE_RECEIVER))
                    .putExtras(event.toBundle())
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    static void storeSystemEvent(Context context, PickupEvent event) {
        try {
            context.getContentResolver().call(
                    Constants.PROVIDER_URI, "system_publish", null, event.toBundle());
        } catch (Exception ignored) {
        }
    }

    static void shareRouteWithRunningSystemApps(Context context, PickupEvent event) {
        for (String target : new String[]{Constants.PKG_AICR, Constants.PKG_VOICE_ASSIST}) {
            try {
                context.sendBroadcast(new Intent(Constants.ACTION_ROUTE)
                        .setPackage(target).putExtras(event.toBundle()));
            } catch (Exception ignored) {
            }
        }
    }

    static PickupEvent peek(Context context, boolean navigation) {
        try {
            Bundle extras = new Bundle();
            extras.putBoolean("navigation", navigation);
            Bundle result = context.getContentResolver().call(
                    Constants.PROVIDER_URI, "peek", null, extras);
            return PickupEvent.fromBundle(result);
        } catch (Exception ignored) {
            return null;
        }
    }

    static boolean modelEnabled(Context context) {
        try {
            Bundle result = context.getContentResolver().call(
                    Constants.PROVIDER_URI, "config", null, null);
            return result == null || result.getBoolean("model_enabled", true);
        } catch (Exception ignored) {
            return true;
        }
    }

    static boolean localImageOcrEnabled(Context context) {
        return fallbackEnabled(context, "local_image_ocr");
    }

    record CloudTextSettings(boolean enabled, long revision) { }
    static CloudTextSettings cloudTextSettings(Context context) {
        try {
            Bundle out = context.getContentResolver().call(Constants.PROVIDER_URI, "config", null, null);
            if (out != null && out.containsKey("rules_revision"))
                return new CloudTextSettings(out.getBoolean("cloud_text_name", false), out.getLong("rules_revision", -1));
        } catch (Throwable ignored) { }
        return new CloudTextSettings(false, -1);
    }

    static boolean remoteImageRecognitionEnabled(Context context) {
        return fallbackEnabled(context, "remote_image_recognition");
    }

    private static boolean fallbackEnabled(Context context, String key) {
        try {
            Bundle result = context.getContentResolver().call(
                    Constants.PROVIDER_URI, "config", null, null);
            return result != null && result.getBoolean(key, false);
        } catch (Exception ignored) {
            // Fail closed: image capture/upload is never enabled by an IPC failure.
            return false;
        }
    }

    static void heartbeat(Context context) {
        try {
            context.getContentResolver().call(Constants.PROVIDER_URI, "heartbeat", null, null);
        } catch (Exception ignored) {
        }
    }

    static void acknowledge(Context context, PickupEvent event, String state,
                            String code, String detail) {
        try {
            Bundle extras = event.toBundle();
            extras.putString("state", PickupEvent.clean(state));
            extras.putString("code", PickupEvent.clean(code));
            extras.putString("detail", PickupEvent.truncate(PickupEvent.clean(detail), 1_000));
            context.getContentResolver().call(Constants.PROVIDER_URI, "ack", null, extras);
        } catch (Exception ignored) {
        }
    }
}
