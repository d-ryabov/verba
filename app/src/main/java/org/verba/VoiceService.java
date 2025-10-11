package org.verba;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.vosk.LibVosk;
import org.vosk.LogLevel;

public class VoiceService extends Service {

    private static final String KEY_VOLUME_LEVEL = "volume_level";
    private static final String KEY_INTENT_NAME = "intent_name";
    private static final String KEY_TEXT_KEY = "text_key";
    private static final String KEY_VOICE_DEBUG = "voice_debug";
    private static final int NOTIFICATION_ID = 1;
    private static final String NOTIFICATION_CHANNEL_ID = "voice_channel";

    private static final String ACTION_VOICE_START = "org.verba.VOICE_START";
    private static final String ACTION_VOICE_STOP = "org.verba.VOICE_STOP";

    private SharedPreferences sharedPref;
    private VoiceConfig config;
    private VoiceProcessor voiceProcessor;
    private boolean isListening = false;
    private boolean launchedFromVoiceActivity = false;

    @Override
    public void onCreate() {
        super.onCreate();

        LibVosk.setLogLevel(LogLevel.INFO);

        sharedPref = getSharedPreferences("app_settings", Context.MODE_PRIVATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        }

        Notification notification = createNotification();
        startForeground(NOTIFICATION_ID, notification);

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            initModel();
        } else {
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if ("org.verba.VOICE".equals(intent.getAction())) {
            launchedFromVoiceActivity = intent.getBooleanExtra("from_voice_activity", false);
            loadSettings();
            if (!isListening && voiceProcessor != null) {
                voiceProcessor.startListening();
                isListening = true;
            }
        }
        return START_NOT_STICKY;
    }

    private Notification createNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Voice Recognition",
                    NotificationManager.IMPORTANCE_MIN
            );
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }

        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Voice Recognition")
                .setContentText("Listening...")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .build();
    }

    private void loadSettings() {
        int volumeReduceLevelDefault = 60;
        int volumeLevel = sharedPref.getInt(KEY_VOLUME_LEVEL, volumeReduceLevelDefault);

        String intentDefault = "com.dusiassistant.INPUT";
        String intentName = sharedPref.getString(KEY_INTENT_NAME, intentDefault);

        String keyDefault = "text";
        String textKey = sharedPref.getString(KEY_TEXT_KEY, keyDefault);

        boolean debugVoiceInput = sharedPref.getBoolean(KEY_VOICE_DEBUG, false);

        config = new VoiceConfig(volumeLevel, intentName, textKey, debugVoiceInput, launchedFromVoiceActivity);
    }

    private void initModel() {
        ModelManager.loadModel(
                this,
                () -> {
                    voiceProcessor = new VoiceProcessor(
                            this,
                            config,
                            result -> {},
                            error -> stopSelf(),
                            this::notifyVoiceStarted,
                            this::notifyVoiceStopped
                    );
                },
                ex -> stopSelf()
        );
    }

    private void notifyVoiceStarted() {
        Intent intent = new Intent(ACTION_VOICE_START);
        sendBroadcast(intent);
    }

    private void notifyVoiceStopped() {
        Intent intent = new Intent(ACTION_VOICE_STOP);
        sendBroadcast(intent);
        isListening = false;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new VoiceBinder();
    }

    public class VoiceBinder extends Binder {
        public VoiceService getService() {
            return VoiceService.this;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (voiceProcessor != null) {
            voiceProcessor.release();
            voiceProcessor = null;
        }
        isListening = false;
    }
}