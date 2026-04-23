package org.verba;

import static android.app.Service.START_STICKY;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;

import org.vosk.LibVosk;
import org.vosk.LogLevel;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "app_settings";
    private static final String KEY_THEME = "app_theme";
    private static final String KEY_VOLUME_LEVEL = "volume_level";
    private static final String KEY_INTENT_NAME = "intent_name";
    private static final String KEY_TEXT_KEY = "text_key";
    private static final String KEY_VOICE_DEBUG = "voice_debug";
    private static final String KEY_TIMEOUT_LONG = "timeout_long";
    private static final String KEY_TIMEOUT_SHORT = "timeout_short";
    private static final long DEFAULT_TIMEOUT_LONG = 3200L;
    private static final long DEFAULT_TIMEOUT_SHORT = 400L;
    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;
    private static final String ACTION_VOICE_RESULT = "org.verba.VOICE_RESULT";
    private static final String ACTION_VOICE_START = "org.verba.VOICE_START";
    private static final String ACTION_VOICE_STOP = "org.verba.VOICE_STOP";

    private SharedPreferences sharedPref;
    private VoiceConfig config;
    private ImageButton micButton;
    private TextView tvVolumeValue, statusText;
    private TextInputEditText etResult, etIntentName, etTextKey, etTimeoutLong, etTimeoutShort;
    private android.widget.Spinner spinnerTheme;
    private Slider sliderVolume;
    private CheckBox cbDebugVoiceInput;
    private boolean isRecording = false;
    private boolean isSpinnerSettingProgrammatically = false;
    private Animation pulseAnimation;
    private VoiceProcessor voiceProcessor;

    private BroadcastReceiver voiceStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_VOICE_START.equals(action)) {
                isRecording = true;
                updateMicButtonState();
            } else if (ACTION_VOICE_STOP.equals(action)) {
                isRecording = false;
                updateMicButtonState();
            } else if (ACTION_VOICE_RESULT.equals(action)) {
                String result = intent.getStringExtra("result");
                if (result != null) {
                    etResult.setText(result);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setThemeBasedOnPreferences();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();
        loadSettings();
        setupListeners();
        TextView tvCopyright = findViewById(R.id.tvCopyright);
        String appName = getString(R.string.app_name);
        tvCopyright.setText("© 2026 " + appName + "\nРазработано с использованием vosk\nТолько для некоммерческого использования");
        statusText.setText(R.string.preparing);
        LibVosk.setLogLevel(LogLevel.INFO);
        int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
        } else {
            initModel();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(android.net.Uri.parse("package:" + getPackageName())));
            }
        }
    }

    private void setThemeBasedOnPreferences() {
        sharedPref = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String theme = sharedPref.getString(KEY_THEME, "system");
        int mode;
        switch (theme) {
            case "light": mode = AppCompatDelegate.MODE_NIGHT_NO; break;
            case "dark": mode = AppCompatDelegate.MODE_NIGHT_YES; break;
            default: mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    private void initViews() {
        micButton = findViewById(R.id.btnMicrophone);
        statusText = findViewById(R.id.tvStatus);
        etResult = findViewById(R.id.etResult);
        etIntentName = findViewById(R.id.etIntentName);
        etTextKey = findViewById(R.id.etTextKey);
        etTimeoutLong = findViewById(R.id.etTimeoutLong);
        etTimeoutShort = findViewById(R.id.etTimeoutShort);
        spinnerTheme = findViewById(R.id.spinnerTheme);
        sliderVolume = findViewById(R.id.sliderVolume);
        tvVolumeValue = findViewById(R.id.tvVolumeValue);
        cbDebugVoiceInput = findViewById(R.id.cbDebugVoiceInput);
        pulseAnimation = AnimationUtils.loadAnimation(this, R.anim.pulse_animation);
        micButton.setOnClickListener(v -> {
            if (isRecording) {
                stopListening();
            } else {
                startListening();
            }
        });
        micButton.setEnabled(false);
    }

    private void setupListeners() {
        spinnerTheme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isSpinnerSettingProgrammatically) return;
                String[] labels = getResources().getStringArray(R.array.theme_options);
                String selected = labels[position];
                String themeValue = "system";
                if ("Светлая".equals(selected)) themeValue = "light";
                else if ("Тёмная".equals(selected)) themeValue = "dark";
                String currentTheme = sharedPref.getString(KEY_THEME, "system");
                if (!currentTheme.equals(themeValue)) {
                    sharedPref.edit().putString(KEY_THEME, themeValue).apply();
                    recreate();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        sliderVolume.addOnChangeListener((slider, value, fromUser) -> {
            int level = (int) value;
            tvVolumeValue.setText(String.valueOf(level));
            if (fromUser) {
                sharedPref.edit().putInt(KEY_VOLUME_LEVEL, level).apply();
            }
        });

        View.OnFocusChangeListener saveListener = (v, hasFocus) -> {
            if (!hasFocus && v instanceof TextInputEditText) {
                TextInputEditText editText = (TextInputEditText) v;
                String input = editText.getText().toString();
                long correctedValue;
                long defaultValue;

                if (v.getId() == R.id.etTimeoutLong) {
                    defaultValue = DEFAULT_TIMEOUT_LONG;
                    correctedValue = parseLongSafely(input, defaultValue);
                    if (!String.valueOf(correctedValue).equals(input)) {
                        editText.setText(String.valueOf(correctedValue));
                        editText.setSelection(editText.getText().length());
                    }
                } else if (v.getId() == R.id.etTimeoutShort) {
                    defaultValue = DEFAULT_TIMEOUT_SHORT;
                    correctedValue = parseLongSafely(input, defaultValue);
                    if (!String.valueOf(correctedValue).equals(input)) {
                        editText.setText(String.valueOf(correctedValue));
                        editText.setSelection(editText.getText().length());
                    }
                }

                saveSettings();
            }
        };

        etIntentName.setOnFocusChangeListener(saveListener);
        etTextKey.setOnFocusChangeListener(saveListener);
        etTimeoutLong.setOnFocusChangeListener(saveListener);
        etTimeoutShort.setOnFocusChangeListener(saveListener);

        cbDebugVoiceInput.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPref.edit().putBoolean(KEY_VOICE_DEBUG, isChecked).apply();
        });
    }

    private void loadSettings() {
        String savedTheme = sharedPref.getString(KEY_THEME, "system");
        int spinnerSelection = 0;
        if ("light".equals(savedTheme)) spinnerSelection = 1;
        else if ("dark".equals(savedTheme)) spinnerSelection = 2;
        isSpinnerSettingProgrammatically = true;
        spinnerTheme.setSelection(spinnerSelection);
        isSpinnerSettingProgrammatically = false;

        int volumeReduceLevelDefault = 60;
        int volumeLevel = sharedPref.getInt(KEY_VOLUME_LEVEL, volumeReduceLevelDefault);
        sliderVolume.setValue(volumeLevel);
        tvVolumeValue.setText(String.valueOf(volumeLevel));

        String intentDefault = "com.dusiassistant.INPUT";
        String intentName = sharedPref.getString(KEY_INTENT_NAME, intentDefault);
        etIntentName.setText(intentName);

        String keyDefault = "text";
        String textKey = sharedPref.getString(KEY_TEXT_KEY, keyDefault);
        etTextKey.setText(textKey);

        long timeoutLong = sharedPref.getLong(KEY_TIMEOUT_LONG, DEFAULT_TIMEOUT_LONG);
        long timeoutShort = sharedPref.getLong(KEY_TIMEOUT_SHORT, DEFAULT_TIMEOUT_SHORT);
        etTimeoutLong.setText(String.valueOf(timeoutLong));
        etTimeoutShort.setText(String.valueOf(timeoutShort));

        boolean debugVoiceInput = sharedPref.getBoolean(KEY_VOICE_DEBUG, false);
        cbDebugVoiceInput.setChecked(debugVoiceInput);

        config = new VoiceConfig(volumeLevel, intentName, textKey, timeoutLong, timeoutShort);
    }

    private void saveSettings() {
        long timeoutLong  = parseLongSafely(etTimeoutLong.getText().toString(), DEFAULT_TIMEOUT_LONG);
        long timeoutShort = parseLongSafely(etTimeoutShort.getText().toString(), DEFAULT_TIMEOUT_SHORT);
        config = new VoiceConfig(
                (int) sliderVolume.getValue(),
                etIntentName.getText().toString(),
                etTextKey.getText().toString(),
                timeoutLong,
                timeoutShort
        );
        sharedPref.edit()
                .putString(KEY_INTENT_NAME, config.getIntentName())
                .putString(KEY_TEXT_KEY, config.getIntentExtraKeyName())
                .putLong(KEY_TIMEOUT_LONG, config.getTimeoutLong())
                .putLong(KEY_TIMEOUT_SHORT, config.getTimeoutShort())
                .apply();
    }


    private long parseLongSafely(String input, long defaultValue) {
        try {
            long val = Long.parseLong(input.trim());
            return Math.max(200, Math.min(10000, val));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void initModel() {
        ModelManager.loadModel(
                this,
                () -> {
                    runOnUiThread(() -> {
                        statusText.setText(R.string.ready);
                        micButton.setEnabled(true);
                    });
                },
                (ex) -> {
                    runOnUiThread(() -> setErrorState("Failed to load model: " + ex.getMessage()));
                }
        );
    }

    private void startListening() {
        isRecording = true;
        updateMicButtonState();
        if (voiceProcessor == null) {
            voiceProcessor = new VoiceProcessor(
                    this,
                    config,
                    false,
                    result -> runOnUiThread(() -> etResult.setText(result)),
                    error -> runOnUiThread(() -> setErrorState(error)),
                    () -> runOnUiThread(() -> {
                        isRecording = true;
                        updateMicButtonState();
                    }),
                    () -> runOnUiThread(() -> {
                        isRecording = false;
                        updateMicButtonState();
                    })
            );
        }
        voiceProcessor.startListening();
    }

    private void stopListening() {
        isRecording = false;
        updateMicButtonState();
        if (voiceProcessor != null) {
            voiceProcessor.stopListening();
        }
    }

    private void updateMicButtonState() {
        if (isRecording) {
            micButton.setBackground(ContextCompat.getDrawable(this, R.drawable.shape_mic_button_active));
            micButton.setColorFilter(Color.WHITE);
            statusText.setText(R.string.say_something);
            micButton.startAnimation(pulseAnimation);
        } else {
            micButton.setBackground(ContextCompat.getDrawable(this, R.drawable.shape_mic_button));
            micButton.clearColorFilter();
            statusText.setText(R.string.ready);
            micButton.clearAnimation();
        }
    }

    private void setErrorState(String message) {
        etResult.setText(message);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_VOICE_START);
        filter.addAction(ACTION_VOICE_STOP);
        filter.addAction(ACTION_VOICE_RESULT);
        registerReceiver(voiceStateReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(voiceStateReceiver);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initModel();
            } else {
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (voiceProcessor != null) {
            voiceProcessor.release();
            voiceProcessor = null;
        }
    }
}