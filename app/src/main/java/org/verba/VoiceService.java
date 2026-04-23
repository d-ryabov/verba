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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import org.vosk.LibVosk;
import org.vosk.LogLevel;

public class VoiceService extends Service {
    private static final String ACTION_VOICE_INIT  = "org.verba.VOICE_INIT";
    private static final String ACTION_VOICE       = "org.verba.VOICE";
    private static final String ACTION_VOICE_START = "org.verba.VOICE_START";
    private static final String ACTION_VOICE_STOP  = "org.verba.VOICE_STOP";

    private static final String KEY_VOLUME_LEVEL  = "volume_level";
    private static final String KEY_INTENT_NAME   = "intent_name";
    private static final String KEY_TEXT_KEY      = "text_key";
    private static final String KEY_VOICE_DEBUG   = "voice_debug";
    private static final String KEY_TIMEOUT_LONG  = "timeout_long";
    private static final String KEY_TIMEOUT_SHORT = "timeout_short";

    private static final int    DEFAULT_VOLUME_LEVEL  = 60;
    private static final String DEFAULT_INTENT_NAME   = "com.dusiassistant.INPUT";
    private static final String DEFAULT_TEXT_KEY      = "text";
    private static final long   DEFAULT_TIMEOUT_LONG  = 3200L;
    private static final long   DEFAULT_TIMEOUT_SHORT = 400L;

    private static final int NOTIFICATION_ID         = 1;
    private static final String NOTIFICATION_CHANNEL_ID = "voice_channel";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SharedPreferences sharedPref;
    private VoiceConfig config;
    private VoiceProcessor voiceProcessor;
    private boolean isListening  = false;
    private boolean isModelReady = false;
    private boolean pendingStart = false;

    private void loadSettings() {
        int volumeLevel     = sharedPref.getInt(KEY_VOLUME_LEVEL, DEFAULT_VOLUME_LEVEL);
        String intentName   = sharedPref.getString(KEY_INTENT_NAME, DEFAULT_INTENT_NAME);
        String textKey      = sharedPref.getString(KEY_TEXT_KEY, DEFAULT_TEXT_KEY);
        long timeoutLong    = sharedPref.getLong(KEY_TIMEOUT_LONG, DEFAULT_TIMEOUT_LONG);
        long timeoutShort   = sharedPref.getLong(KEY_TIMEOUT_SHORT, DEFAULT_TIMEOUT_SHORT);
        config = new VoiceConfig(volumeLevel, intentName, textKey, timeoutLong, timeoutShort);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        LibVosk.setLogLevel(LogLevel.INFO);
        sharedPref = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        loadSettings();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        }

        startForeground(NOTIFICATION_ID, createNotification());

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            initModel();
        } else {
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_VOICE_INIT.equals(action)) {
            loadSettings();
        } else if (ACTION_VOICE.equals(action)) {
            loadSettings();
            if (isModelReady) {
                startListeningInternal();
            } else {
                pendingStart = true;
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new VoiceBinder();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (voiceProcessor != null) {
            voiceProcessor.stopListening();
            voiceProcessor.release();
            voiceProcessor = null;
        }
        isListening = false;
    }

    private void initModel() {
        ModelManager.loadModel(
                this,
                () -> mainHandler.post(() -> {
                    isModelReady = true;
                    if (pendingStart) {
                        pendingStart = false;
                        startListeningInternal();
                    }
                }),
                ex -> mainHandler.post(this::stopSelf)
        );
    }

    private void startListeningInternal() {
        if (isListening || !isModelReady) return;

        if (voiceProcessor != null) voiceProcessor.release();
        boolean overlayEnabled = sharedPref.getBoolean(KEY_VOICE_DEBUG, false);
        voiceProcessor = new VoiceProcessor(this,
                config,
                overlayEnabled,
                result -> { /* broadcast sent inside VoiceProcessor.sendRecognizedText() */ },
                error -> isListening = false,
                this::notifyVoiceStarted,
                this::notifyVoiceStopped
        );
        voiceProcessor.startListening();
        isListening = true;
    }

    private void notifyVoiceStarted() {
        sendBroadcast(new Intent(ACTION_VOICE_START));
    }

    private void notifyVoiceStopped() {
        sendBroadcast(new Intent(ACTION_VOICE_STOP));
        isListening = false;
    }

    private Notification createNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Voice Recognition",
                    NotificationManager.IMPORTANCE_MIN
            );
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Voice Recognition")
                .setContentText("Listening...")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .build();
    }

    public class VoiceBinder extends Binder {
        public VoiceService getService() {
            return VoiceService.this;
        }
    }
}
