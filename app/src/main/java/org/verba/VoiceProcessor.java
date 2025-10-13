package org.verba;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
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
    private SpeechService speechService;
    private AudioManager audioManager;
    private SoundPool soundPool;
    private int startSoundId, stopSoundId;
    private Handler handler = new Handler();
    private Runnable timeoutRunnable;
    private final StringBuilder fullText = new StringBuilder();
    private String lastPartial = "";
    private boolean isReduced = false;
    private int originalVolume;

    public VoiceProcessor(Context context, VoiceConfig config, Consumer<String> onResult, Consumer<String> onError, Runnable onStart, Runnable onStop) {
        this.context = context;
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
        resetRecognitionState();
        onVoiceStartCallback.run();
        try {
            Recognizer rec = new Recognizer(ModelManager.getModel(), 16000.0f);
            speechService = new SpeechService(rec, 16000.0f);
            speechService.startListening(this);
            playStartSound();
            reduceVolume();
            startTimeoutTimer(config.getTimeoutLong());
        } catch (Exception e) {
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
        }
        onVoiceStopCallback.run();
    }

    private void startTimeoutTimer(long timeout) {
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
        }
        timeoutRunnable = () -> {
            stopListening();
        };
        handler.postDelayed(timeoutRunnable, timeout);
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
        soundPool.play(startSoundId, 1.0f, 1.0f, 1, 0, 1.0f);
        handler.postDelayed(() -> {}, 800);
    }

    private void playStopSound() {
        soundPool.play(stopSoundId, 1.0f, 1.0f, 1, 0, 1.0f);
        handler.postDelayed(() -> {}, 800);
    }

    public void release() {
        stopListening();
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        if (handler != null && timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
        }
    }

    public void resetRecognitionState() {
        fullText.setLength(0);
        lastPartial = "";
    }

    @Override
    public void onResult(String hypothesis) {
        handleVoskResult(hypothesis);
    }

    @Override
    public void onFinalResult(String hypothesis) {
        handleVoskResult(hypothesis);
        sendRecognizedText(fullText.toString().trim());
    }

    @Override
    public void onPartialResult(String hypothesis) {
        handleVoskResult(hypothesis);
    }

    @Override
    public void onError(Exception e) {
        onErrorCallback.accept(e.getMessage());
    }

    @Override
    public void onTimeout() {}

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
                    if (fullText.length() > 0 && fullText.charAt(fullText.length() - 1) != ' ') fullText.append(" ");
                    fullText.append(partial);
                    lastPartial = partial;
                }
                if (!partial.isEmpty()) {
                    startTimeoutTimer(config.getTimeoutShort());
                }
            } else if (obj.has("text")) {
                String text = obj.getString("text");
                if (text.isEmpty()) return;
                if (!lastPartial.isEmpty()) {
                    int start = fullText.lastIndexOf(lastPartial);
                    if (start != -1) fullText.replace(start, start + lastPartial.length(), text);
                    lastPartial = "";
                } else {
                    if (fullText.length() > 0 && fullText.charAt(fullText.length() - 1) != ' ') fullText.append(" ");
                    fullText.append(text);
                }
                startTimeoutTimer(config.getTimeoutShort());
            }
            onResultCallback.accept(fullText.toString().trim());
        } catch (Exception e) {
            e.printStackTrace();
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
}