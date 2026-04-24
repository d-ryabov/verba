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

import org.json.JSONObject;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;

import java.util.function.Consumer;

public class VoiceProcessor implements RecognitionListener {
    private static final String TAG = "VoiceProcessor";
    private static final int SAMPLE_RATE = 16000;
    private static final int READ_BUFFER_SIZE = 1024;

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
    private boolean hasReceivedFinalText = false;
    private boolean hasReceivedAnyInput = false;

    private boolean isReduced = false;
    private int originalVolume;

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
        if (!isListening && audioRecord == null) return;
        cancelTimeout();
        isListening = false;

        if (recordingThread != null) {
            recordingThread.interrupt();
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
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
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
        hasReceivedFinalText = false;
        hasReceivedAnyInput = false;
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
        AudioDeviceInfo best = null;
        for (AudioDeviceInfo d : inputs) {
            Log.d(TAG, "MIC device: id=" + d.getId()
                    + " type=" + d.getType()
                    + " name=" + d.getProductName());
            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                // Первый встроенный мик обычно соответствует микрофону водителя.
                // Если в логах видно, что нужный имеет другой id — задай его явно здесь.
                if (best == null) best = d;
            }
        }
        if (best != null) {
            boolean ok = audioRecord.setPreferredDevice(best);
            Log.d(TAG, "setPreferredDevice id=" + best.getId() + " success=" + ok);
        }
    }

    private void cleanupAudio() {
        if (noiseSuppressor != null) { noiseSuppressor.release(); noiseSuppressor = null; }
        if (echoCanceler != null)    { echoCanceler.release();    echoCanceler = null; }
        if (gainControl != null)     { gainControl.release();     gainControl = null; }
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

    @Override
    public void onPartialResult(String hypothesis) {
        handleVoskResult(hypothesis);
    }

    @Override
    public void onResult(String hypothesis) {
        handleVoskResult(hypothesis);
    }

    @Override
    public void onFinalResult(String hypothesis) {
        handleVoskResult(hypothesis);
        String finalText = fullText.toString().trim();
        if (!finalText.isEmpty()) {
            if (overlay != null) overlay.stopPulse();
            sendRecognizedText(finalText);
            scheduleOverlayHide();
            handler.post(this::stopListening);
        }
    }

    @Override
    public void onError(Exception e) {
        handler.post(() -> {
            cancelTimeout();
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
                    if (!lastPartial.isEmpty()) {
                        int start = fullText.lastIndexOf(lastPartial);
                        if (start != -1) fullText.delete(start, start + lastPartial.length());
                    }
                    if (fullText.length() > 0 && fullText.charAt(fullText.length() - 1) != ' ') {
                        fullText.append(" ");
                    }
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
                    if (!lastPartial.isEmpty()) {
                        int start = fullText.lastIndexOf(lastPartial);
                        if (start != -1) fullText.replace(start, start + lastPartial.length(), text);
                        lastPartial = "";
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

    private void cancelTimeout() {
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    private void startLongTimeout()  { scheduleTimeout(config.getTimeoutLong()); }
    private void startShortTimeout() { scheduleTimeout(config.getTimeoutShort()); }

    private void scheduleTimeout(long delayMs) {
        cancelTimeout();
        timeoutRunnable = this::onInternalTimeout;
        handler.postDelayed(timeoutRunnable, delayMs);
    }

    private void onInternalTimeout() {
        timeoutRunnable = null;
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

    private int getAudioStream() {
        switch (config.getAudioStreamType()) {
            case "notification":  return AudioManager.STREAM_NOTIFICATION;
            case "media":         return AudioManager.STREAM_MUSIC;
            case "sonification":  return AudioManager.STREAM_RING;
            default:              return AudioManager.STREAM_RING;
        }
    }

    private void initSoundPool() {
        int usage, contentType;
        switch (config.getAudioStreamType()) {
            case "notification":
                usage = AudioAttributes.USAGE_NOTIFICATION;
                contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION;
                break;
            case "media":
                usage = AudioAttributes.USAGE_MEDIA;
                contentType = AudioAttributes.CONTENT_TYPE_MUSIC;
                break;
            case "sonification":
            default:
                usage = AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;
                contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION;
                break;
        }
        soundPool = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(contentType)
                        .build())
                .build();
        startSoundId = soundPool.load(context, R.raw.mic_on, 1);
        stopSoundId  = soundPool.load(context, R.raw.mic_off, 1);
    }

    private void reduceVolume() {
        if (!isReduced) {
            int stream = getAudioStream();
            originalVolume = audioManager.getStreamVolume(stream);
            int newVolume = Math.max(0, originalVolume * (100 - config.getVolumeReduceLevel()) / 100);
            audioManager.setStreamVolume(stream, newVolume, 0);
            isReduced = true;
        }
    }

    private void restoreVolume() {
        if (isReduced) {
            audioManager.setStreamVolume(getAudioStream(), originalVolume, 0);
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
