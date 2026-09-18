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

        Log.i(TAG, "onReceive triggered: action=" + action
                + ", deviceProtectedStorageAvailable="
                + (context.createDeviceProtectedStorageContext() != null));

        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !ACTION_QUICKBOOT_POWERON.equals(action)) {
            Log.d(TAG, "Ignoring action: " + action);
            return;
        }

        Log.i(TAG, "Accepted boot action: " + action + "; starting VoiceService warm-up");

        Intent serviceIntent = new Intent(context, VoiceService.class)
                .setAction(ACTION_VOICE_INIT);

        try {
            long t0 = System.currentTimeMillis();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Log.i(TAG, "startForegroundService/startService returned OK in "
                    + (System.currentTimeMillis() - t0) + " ms");
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot start VoiceService after boot", e);
        }
    }
}
