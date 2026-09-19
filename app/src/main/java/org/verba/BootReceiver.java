package org.verba;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "VerbaBoot";
    private static final String ACTION_QUICKBOOT_POWERON =
            "android.intent.action.QUICKBOOT_POWERON";
    private static final String ACTION_VOICE_INIT = "org.verba.VOICE_INIT";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;

        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !ACTION_QUICKBOOT_POWERON.equals(action)) {
            return;
        }

        Intent serviceIntent = new Intent(context, VoiceService.class)
                .setAction(ACTION_VOICE_INIT);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot start VoiceService after boot", e);
        }
    }
}
