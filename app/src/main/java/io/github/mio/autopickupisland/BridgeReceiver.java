package io.github.mio.autopickupisland;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public final class BridgeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Constants.ACTION_INGRESS.equals(intent.getAction())) return;
        PickupEvent event = PickupEvent.fromBundle(intent.getExtras());
        String sender = BroadcastVerifier.sender(this, context);
        if (event == null || !Constants.isSourcePackage(sender)
                || !sender.equals(event.sourcePackage)) return;
        try {
            Bundle result = context.getContentResolver().call(
                    Constants.PROVIDER_URI, "publish", null, event.toBundle());
            if (result == null || !result.getBoolean("accepted", false)) {
                throw new IllegalStateException("provider rejected ingress");
            }
        } catch (Exception ignored) {
        }
    }
}
