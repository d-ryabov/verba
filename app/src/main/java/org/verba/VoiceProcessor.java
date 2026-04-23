package org.verba;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
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
    private SoundPool soundPool;
    private int startSoundId, stopSoundId;
    private SpeechService speechService;
    private final StringBuilder fullText = new StringBuilder();
    private String lastPartial = "";
    private boolean hasReceivedFinalText = false;
    private boolean isReduced = false;
    private int originalVolume;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;

    public VoiceProcessor(Context context, VoiceConfig config, Consumer<String> onResult, Consumer<String> onError, Runnable onStart, Runnable onStop) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.onResultCallback = onResult;
        this.onErrorCallback = onError;
        this.onVoiceStartCallback = onStart;
        this.onVoiceStopCallback = onStop;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        initSoundPool();
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
        stopSoundId = soundPool.load(context, R.raw.mic_off, 1);
    }

    public void startListening() {
        if (speechService != null) {
            stopListening();
        }
        resetRecognitionState();
        hasReceivedFinalText = false;
        onVoiceStartCallback.run();
        try {
            Recognizer rec = new Recognizer(ModelManager.getModel(), 16000.0f);
            speechService = new SpeechService(rec, 16000.0f);
            speechService.startListening(this);
            playStartSound();
            reduceVolume();
            startLongTimeout();
        } catch (Exception e) {
            restoreVolume();
            onVoiceStopCallback.run();
            onErrorCallback.accept("Failed to start listening: " + e.getMessage());
        }
    }

    public void stopListening() {
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
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
        stopListening();
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
    }

    public void resetRecognitionState() {
        fullText.setLength(0);
        lastPartial = "";
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
        if (soundPool != null) {
            soundPool.play(startSoundId, 1.0f, 1.0f, 1, 0, 1.0f);
        }
        // Intentional delay: keeps the handler queue busy so the sound
        // has time to play before any subsequent audio focus changes.
        handler.postDelayed(() -> {
        }, 800);
    }

    private void playStopSound() {
        if (soundPool != null) {
            soundPool.play(stopSoundId, 1.0f, 1.0f, 1, 0, 1.0f);
        }
        // Intentional delay: keeps the handler queue busy so the sound
        // has time to play before any subsequent audio focus changes.
        handler.postDelayed(() -> {
        }, 800);
    }

    private void startLongTimeout() {
        scheduleTimeout(config.getTimeoutLong());
    }

    private void startShortTimeout() {
        scheduleTimeout(config.getTimeoutShort());
    }

    private void scheduleTimeout(long delayMs) {
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
        }
        timeoutRunnable = this::stopListening;
        handler.postDelayed(timeoutRunnable, delayMs);
    }

    private void handleVoskResult(String jsonStr) {
        try {
            JSONObject obj = new JSONObject(jsonStr);
            boolean hasPartial = obj.has("partial");
            boolean hasText = obj.has("text");

            if (hasPartial) {
                String partial = obj.getString("partial");
                if (!partial.isEmpty() && !partial.equals(lastPartial)) {
                    if (!lastPartial.isEmpty()) {
                        int start = fullText.lastIndexOf(lastPartial);
                        if (start != -1) {
                            fullText.delete(start, start + lastPartial.length());
                        }
                    }
                    if (fullText.length() > 0 && fullText.charAt(fullText.length() - 1) != ' ') {
                        fullText.append(" ");
                    }
                    fullText.append(partial);
                    lastPartial = partial;

                    if (hasReceivedFinalText) {
                        hasReceivedFinalText = false;
                    }
                    startLongTimeout();
                }
            }

            if (hasText) {
                String text = obj.getString("text");
                if (!text.isEmpty()) {
                    if (!lastPartial.isEmpty()) {
                        int start = fullText.lastIndexOf(lastPartial);
                        if (start != -1) {
                            fullText.replace(start, start + lastPartial.length(), text);
                        }
                        lastPartial = "";
                    } else {
                        if (fullText.length() > 0 && fullText.charAt(fullText.length() - 1) != ' ') {
                            fullText.append(" ");
                        }
                        fullText.append(text);
                    }

                    hasReceivedFinalText = true;
                    startShortTimeout();
                }
            }

            onResultCallback.accept(fullText.toString().trim());
        } catch (Exception e) {
            handler.post(() -> {
                onErrorCallback.accept("Result parse error: " + e.getMessage());
            });
        }
    }

    private void sendRecognizedText(String text) {
        Intent intent = new Intent();
        intent.setAction(config.getIntentName());
        intent.putExtra(config.getIntentExtraKeyName(), text);
        context.sendBroadcast(intent);
        Intent resultIntent = new Intent();
        resultIntent.setAction("org.verba.VOICE_RESULT");
        resultIntent.putExtra("result", text);
        context.sendBroadcast(resultIntent);
        if (config.isFromVoiceActivity() && config.isVoiceDebug()) {
            String displayText;
            String appName = context.getString(R.string.app_name);
            if (text == null || text.trim().isEmpty()) {
                displayText = appName + ": Команда не распознана";
            } else {
                displayText = appName + ": Слышу \"" + text + "\"";
            }
            final String finalDisplayText = displayText;
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Toast.makeText(context, finalDisplayText, Toast.LENGTH_LONG).show();
            } else {
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(context, finalDisplayText, Toast.LENGTH_LONG).show()
                );
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
        sendRecognizedText(fullText.toString().trim());
        handler.post(this::stopListening);
    }

    @Override
    public void onError(Exception e) {
        handler.post(() -> {
            if (timeoutRunnable != null) {
                handler.removeCallbacks(timeoutRunnable);
                timeoutRunnable = null;
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
        handler.post(this::stopListening);
    }
}