package org.verba;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class VoiceActivity extends android.app.Activity {
    private static final int OVERLAY_PERMISSION_REQ_CODE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            showErrorAndFinish("Сначала откройте основное приложение для настройки микрофона.");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                showErrorAndFinish("Разрешите игнорировать оптимизацию батареи в настройках.");
                return;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE);
            return;
        }

        startVoiceService();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                startVoiceService();
            } else {
                showErrorAndFinish("Разрешите отображение поверх других окон в настройках.");
            }
        }
    }

    private void startVoiceService() {
        Intent serviceIntent = new Intent(this, VoiceService.class);
        serviceIntent.setAction("org.verba.VOICE");
        serviceIntent.putExtra("from_voice_activity", true);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            showErrorAndFinish("Не удалось запустить службу: " + e.getMessage());
            return;
        }

        finish();
    }

    private void showErrorAndFinish(String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(VoiceActivity.this, message, Toast.LENGTH_LONG).show()
        );
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        finish();
    }
}
