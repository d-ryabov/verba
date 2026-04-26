package org.verba;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.SoundPool;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import androidx.annotation.MainThread;

import org.json.JSONObject;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;

import java.util.function.Consumer;

public class VoiceProcessor implements RecognitionListener {
    private static final String TAG = "VoiceProcessor";
    private static final int SAMPLE_RATE = 16000;
    private static final int READ_BUFFER_SIZE = 2048;

    private final Context context;
    private final VoiceConfig config;
    private final Consumer<String> onResultCallback;
    private final Consumer<String> onErrorCallback;
    private final Runnable onVoiceStartCallback;
    private final Runnable onVoiceStopCallback;
    private final AudioManager audioManager;
    private final boolean overlayEnabled;

    private SoundPool soundPool;
    private int startSoundId;
    private int stopSoundId;

    private Recognizer recognizer;
    private AudioRecord audioRecord;
    private NoiseSuppressor noiseSuppressor;
    private AcousticEchoCanceler echoCanceler;
    private AutomaticGainControl gainControl;
    private Thread recordingThread;
    private volatile boolean isListening = false;

    private VoiceRecognitionOverlay overlay;

    private final StringBuilder fullText = new StringBuilder();
    private String lastPartial = "";
    private int lastPartialStart = -1;
    private boolean hasReceivedFinalText = false;
    private boolean hasReceivedAnyInput = false;
    private boolean resultDispatched = false;

    private volatile boolean isReduced = false;
    private volatile int originalVolume;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;
    private Runnable overlayHideRunnable;

    public VoiceProcessor(Context context, VoiceConfig config,
                          boolean overlayEnabled,
                          Consumer<String> onResult, Consumer<String> onError,
                          Runnable onStart, Runnable onStop) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.overlayEnabled = overlayEnabled;
        this.onResultCallback = onResult;
        this.onErrorCallback = onError;
        this.onVoiceStartCallback = onStart;
        this.onVoiceStopCallback = onStop;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        initSoundPool();
    }

    public void startListening() {
        if (isListening) stopListening();
        resetRecognitionState();
        onVoiceStartCallback.run();
        try {
            recognizer = new Recognizer(ModelManager.getModel(), SAMPLE_RATE);
            initAudioRecord();
            audioRecord.startRecording();
            isListening = true;

            recordingThread = new Thread(this::recordingLoop, "VoiceRecordThread");
            recordingThread.setPriority(Thread.MAX_PRIORITY);
            recordingThread.setDaemon(true);
            recordingThread.start();

            playStartSound();
            reduceVolume();
            startLongTimeout();

            if (overlayEnabled) {
                if (overlay != null) overlay.release();
                overlay = new VoiceRecognitionOverlay(context);
                overlay.init();
                overlay.showOverlay("Слушаю...");
            }
        } catch (Exception e) {
            cleanupAudio();
            restoreVolume();
            onVoiceStopCallback.run();
            onErrorCallback.accept("Failed to start listening: " + e.getMessage());
        }
    }

    public void stopListening() {
        if (!isListening && audioRecord == null && recognizer == null) return;
        cancelTimeout();
        isListening = false;

        if (recordingThread != null) {
            recordingThread.interrupt();
            try {
                recordingThread.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            recordingThread = null;
        }
        cleanupAudio();
        restoreVolume();
        playStopSound();
        onVoiceStopCallback.run();
    }

    public void release() {
        cancelTimeout();
        cancelOverlayHide();
        stopListening();
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        if (overlay != null) {
            overlay.release();
            overlay = null;
        }
    }

    public void resetRecognitionState() {
        fullText.setLength(0);
        lastPartial = "";
        lastPartialStart = -1;
        hasReceivedFinalText = false;
        hasReceivedAnyInput = false;
        resultDispatched = false;
        cancelOverlayHide();
    }

    @SuppressLint("MissingPermission")
    private void initAudioRecord() {
        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        int recordBufferSize = Math.max(minBuffer * 4, READ_BUFFER_SIZE * 8);

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recordBufferSize
        );

        attachDspEffects(audioRecord.getAudioSessionId());
        selectDriverMicrophone();

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new RuntimeException("AudioRecord failed to initialize");
        }
    }

    private void attachDspEffects(int sessionId) {
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(sessionId);
            if (noiseSuppressor != null) {
                noiseSuppressor.setEnabled(true);
                Log.d(TAG, "NoiseSuppressor: ON");
            }
        }
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(sessionId);
            if (echoCanceler != null) {
                echoCanceler.setEnabled(true);
                Log.d(TAG, "AcousticEchoCanceler: ON");
            }
        }
        if (AutomaticGainControl.isAvailable()) {
            gainControl = AutomaticGainControl.create(sessionId);
            if (gainControl != null) {
                gainControl.setEnabled(true);
                Log.d(TAG, "AutomaticGainControl: ON");
            }
        }
    }

    private void selectDriverMicrophone() {
        AudioDeviceInfo[] inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);

        int preferredId = config.getPreferredMicDeviceId();
        AudioDeviceInfo best = null;

        for (AudioDeviceInfo d : inputs) {
            Log.d(TAG, "MIC device: id=" + d.getId()
                    + " type=" + d.getType()
                    + " name=" + d.getProductName());

            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                if (preferredId > 0) {
                    if (d.getId() == preferredId) {
                        best = d;
                        break;
                    }
                } else {
                    if (best == null) best = d;
                }
            }
        }

        if (best == null && preferredId > 0) {
            Log.w(TAG, "Preferred mic id=" + preferredId + " not found, falling back to first builtin");
            for (AudioDeviceInfo d : inputs) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    best = d;
                    break;
                }
            }
        }

        if (best != null) {
            boolean ok = audioRecord.setPreferredDevice(best);
            Log.d(TAG, "setPreferredDevice id=" + best.getId() + " success=" + ok);
        }
    }

    private void cleanupAudio() {
        if (noiseSuppressor != null) { noiseSuppressor.release(); noiseSuppressor = null; }
        if (echoCanceler != null) { echoCanceler.release(); echoCanceler = null; }
        if (gainControl != null) { gainControl.release(); gainControl = null; }
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception ignored) {}
            audioRecord = null;
        }
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
    }

    private void recordingLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        byte[] buffer = new byte[READ_BUFFER_SIZE];

        while (isListening && !Thread.currentThread().isInterrupted()) {
            int read = audioRecord.read(buffer, 0, buffer.length);
            if (read < 0) {
                Log.e(TAG, "AudioRecord.read error: " + read);
                handler.post(() -> onError(new RuntimeException("AudioRecord read error: " + read)));
                break;
            }
            if (read == 0) continue;

            if (recognizer != null) {
                if (recognizer.acceptWaveForm(buffer, read)) {
                    final String result = recognizer.getResult();
                    handler.post(() -> onResult(result));
                } else {
                    final String partial = recognizer.getPartialResult();
                    handler.post(() -> onPartialResult(partial));
                }
            }
        }
    }

    @MainThread
    @Override
    public void onPartialResult(String hypothesis) {
        handleVoskResult(hypothesis);
    }

    @MainThread
    @Override
    public void onResult(String hypothesis) {
        handleVoskResult(hypothesis);
    }

    @MainThread
    @Override
    public void onFinalResult(String hypothesis) {
        handleVoskResult(hypothesis);
        if (overlay != null) overlay.stopPulse();
        sendCurrentTextIfNeeded();
        scheduleOverlayHide();
        handler.post(this::stopListening);
    }

    @MainThread
    @Override
    public void onError(Exception e) {
        handler.post(() -> {
            cancelTimeout();
            isListening = false;
            if (overlay != null) {
                overlay.stopPulse();
                overlay.showOverlay("Ошибка распознания");
                scheduleOverlayHide();
            }
            cleanupAudio();
            restoreVolume();
            onVoiceStopCallback.run();
            onErrorCallback.accept(e.getMessage());
        });
    }

    @MainThread
    @Override
    public void onTimeout() {
        handler.post(this::onInternalTimeout);
    }

    private void handleVoskResult(String jsonStr) {
        try {
            JSONObject obj = new JSONObject(jsonStr);

            if (obj.has("partial")) {
                String partial = obj.getString("partial");
                if (!partial.isEmpty() && !partial.equals(lastPartial)) {
                    if (!lastPartial.isEmpty() && lastPartialStart != -1) {
                        fullText.delete(lastPartialStart, lastPartialStart + lastPartial.length());
                    }
                    if (fullText.length() > 0 && fullText.charAt(fullText.length() - 1) != ' ') {
                        fullText.append(" ");
                    }
                    lastPartialStart = fullText.length();
                    fullText.append(partial);
                    lastPartial = partial;
                    hasReceivedAnyInput = true;
                    if (hasReceivedFinalText) hasReceivedFinalText = false;
                    startLongTimeout();
                }
            }

            if (obj.has("text")) {
                String text = obj.getString("text");
                if (!text.isEmpty()) {
                    if (!lastPartial.isEmpty() && lastPartialStart != -1) {
                        fullText.replace(lastPartialStart, lastPartialStart + lastPartial.length(), text);
                        lastPartial = "";
                        lastPartialStart = -1;
                    } else {
                        if (fullText.length() > 0 && fullText.charAt(fullText.length() - 1) != ' ') {
                            fullText.append(" ");
                        }
                        fullText.append(text);
                    }
                    hasReceivedFinalText = true;
                    hasReceivedAnyInput = true;
                    startShortTimeout();
                }
            }

            String trimmed = fullText.toString().trim();
            if (overlay != null && !trimmed.isEmpty()) overlay.showOverlay(trimmed);
            if (!trimmed.isEmpty()) onResultCallback.accept(trimmed);

        } catch (Exception e) {
            handler.post(() -> onErrorCallback.accept("Result parse error: " + e.getMessage()));
        }
    }

    private void sendRecognizedText(String text) {
        Intent intent = new Intent(config.getIntentName());
        intent.putExtra(config.getIntentExtraKeyName(), text);
        context.sendBroadcast(intent);

        Intent resultIntent = new Intent("org.verba.VOICE_RESULT");
        resultIntent.putExtra("result", text);
        context.sendBroadcast(resultIntent);
    }

    private void sendCurrentTextIfNeeded() {
        if (resultDispatched) return;

        String text = fullText.toString().trim();
        if (!text.isEmpty()) {
            resultDispatched = true;
            sendRecognizedText(text);
        }
    }

    private void cancelTimeout() {
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    private void startLongTimeout() { scheduleTimeout(config.getTimeoutLong()); }
    private void startShortTimeout() { scheduleTimeout(config.getTimeoutShort()); }

    private void scheduleTimeout(long delayMs) {
        cancelTimeout();
        timeoutRunnable = this::onInternalTimeout;
        handler.postDelayed(timeoutRunnable, delayMs);
    }

    private void onInternalTimeout() {
        if (!isListening) return;

        timeoutRunnable = null;
        sendCurrentTextIfNeeded();

        if (overlay != null) {
            overlay.stopPulse();
            if (!hasReceivedAnyInput) overlay.showOverlay("Команда не распознана");
            scheduleOverlayHide();
        }
        stopListening();
    }

    private void cancelOverlayHide() {
        if (overlayHideRunnable != null) {
            handler.removeCallbacks(overlayHideRunnable);
            overlayHideRunnable = null;
        }
    }

    private void scheduleOverlayHide() {
        cancelOverlayHide();
        overlayHideRunnable = () -> {
            if (overlay != null) overlay.hideOverlay();
            overlayHideRunnable = null;
        };
        handler.postDelayed(overlayHideRunnable, config.getTimeoutLong());
    }

    private static class AudioStreamConfig {
        final int stream;
        final int usage;
        final int contentType;

        AudioStreamConfig(int stream, int usage, int contentType) {
            this.stream = stream;
            this.usage = usage;
            this.contentType = contentType;
        }
    }

    private AudioStreamConfig resolveAudioStreamConfig() {
        switch (config.getAudioStreamType()) {
            case "notification":
                return new AudioStreamConfig(
                        AudioManager.STREAM_NOTIFICATION,
                        AudioAttributes.USAGE_NOTIFICATION,
                        AudioAttributes.CONTENT_TYPE_SONIFICATION);
            case "media":
                return new AudioStreamConfig(
                        AudioManager.STREAM_MUSIC,
                        AudioAttributes.USAGE_MEDIA,
                        AudioAttributes.CONTENT_TYPE_MUSIC);
            case "sonification":
            default:
                return new AudioStreamConfig(
                        AudioManager.STREAM_RING,
                        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
                        AudioAttributes.CONTENT_TYPE_SONIFICATION);
        }
    }

    private void initSoundPool() {
        AudioStreamConfig cfg = resolveAudioStreamConfig();
        soundPool = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(cfg.usage)
                        .setContentType(cfg.contentType)
                        .build())
                .build();
        startSoundId = soundPool.load(context, R.raw.mic_on, 1);
        stopSoundId = soundPool.load(context, R.raw.mic_off, 1);
    }

    private void reduceVolume() {
        if (!isReduced) {
            int stream = resolveAudioStreamConfig().stream;
            originalVolume = audioManager.getStreamVolume(stream);
            int newVolume = Math.max(0, originalVolume * (100 - config.getVolumeReduceLevel()) / 100);
            audioManager.setStreamVolume(stream, newVolume, 0);
            isReduced = true;
        }
    }

    private void restoreVolume() {
        if (isReduced) {
            audioManager.setStreamVolume(resolveAudioStreamConfig().stream, originalVolume, 0);
            isReduced = false;
        }
    }

    private void playStartSound() {
        if (soundPool != null) soundPool.play(startSoundId, 1.0f, 1.0f, 1, 0, 1.0f);
    }

    private void playStopSound() {
        if (soundPool != null) soundPool.play(stopSoundId, 1.0f, 1.0f, 1, 0, 1.0f);
    }
}
