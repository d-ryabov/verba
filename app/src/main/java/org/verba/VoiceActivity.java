package org.verba;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class VoiceActivity extends android.app.Activity {

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