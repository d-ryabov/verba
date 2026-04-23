package org.verba;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.util.function.Consumer;

public class VoiceProcessor implements RecognitionListener {
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

    private SpeechService speechService;
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
        if (speechService != null) {
            stopListening();
        }
        resetRecognitionState();
        onVoiceStartCallback.run();
        try {
            Recognizer rec = new Recognizer(ModelManager.getModel(), 16000.0f);
            speechService = new SpeechService(rec, 16000.0f);
            speechService.startListening(this);
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
            restoreVolume();
            onVoiceStopCallback.run();
            onErrorCallback.accept("Failed to start listening: " + e.getMessage());
        }
    }

    public void stopListening() {
        cancelTimeout();
        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
            speechService = null;
            restoreVolume();
            playStopSound();
            onVoiceStopCallback.run();
        }
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
        hasReceivedFinalText = false;
        hasReceivedAnyInput = false;
        cancelOverlayHide();
    }

    private void cancelTimeout() {
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    private void startLongTimeout() {
        scheduleTimeout(config.getTimeoutLong());
    }

    private void startShortTimeout() {
        scheduleTimeout(config.getTimeoutShort());
    }

    private void scheduleTimeout(long delayMs) {
        cancelTimeout();
        timeoutRunnable = this::onInternalTimeout;
        handler.postDelayed(timeoutRunnable, delayMs);
    }

    private void onInternalTimeout() {
        timeoutRunnable = null;
        if (overlay != null) {
            overlay.stopPulse();
            if (!hasReceivedAnyInput) {
                overlay.showOverlay("Команда не распознана");
            }
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

    private void initSoundPool() {
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        soundPool = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(audioAttributes)
                .build();
        startSoundId = soundPool.load(context, R.raw.mic_on, 1);
        stopSoundId  = soundPool.load(context, R.raw.mic_off, 1);
    }

    private void reduceVolume() {
        if (!isReduced) {
            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            int newVolume = Math.max(0, originalVolume * (100 - config.getVolumeReduceLevel()) / 100);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0);
            isReduced = true;
        }
    }

    private void restoreVolume() {
        if (isReduced) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0);
            isReduced = false;
        }
    }

    private void playStartSound() {
        if (soundPool != null) soundPool.play(startSoundId, 1.0f, 1.0f, 1, 0, 1.0f);
        // Intentional: keeps the Handler queue occupied so the sound has time
        // to play before any subsequent audio focus change takes effect.
        handler.postDelayed(() -> {}, 800);
    }

    private void playStopSound() {
        if (soundPool != null) soundPool.play(stopSoundId, 1.0f, 1.0f, 1, 0, 1.0f);
        // Intentional: same reason as in playStartSound().
        handler.postDelayed(() -> {}, 800);
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
            if (overlay != null && !trimmed.isEmpty()) {
                overlay.showOverlay(trimmed);
            }
            if (!trimmed.isEmpty()) {
                onResultCallback.accept(trimmed);
            }
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
        }
        // Empty final result with no prior input: let timeout handle stop
        if (finalText.isEmpty() && !hasReceivedFinalText) return;
        handler.post(this::stopListening);
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
            if (speechService != null) {
                try { speechService.shutdown(); } catch (Exception ignored) {}
                speechService = null;
            }
            restoreVolume();
            onVoiceStopCallback.run();
            onErrorCallback.accept(e.getMessage());
        });
    }

    @Override
    public void onTimeout() {
        handler.post(this::onInternalTimeout);
    }
}
