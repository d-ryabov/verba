package org.verba;

public class VoiceConfig {
    private final int volumeReduceLevel;
    private final String intentName;
    private final String intentExtraKeyName;
    private final boolean debugVoiceInput;
    private final boolean fromVoiceActivity;

    public VoiceConfig(int volumeReduceLevel, String intentName, String intentExtraKeyName, boolean debugVoiceInput, boolean fromVoiceActivity) {
        this.volumeReduceLevel = volumeReduceLevel;
        this.intentName = intentName;
        this.intentExtraKeyName = intentExtraKeyName;
        this.debugVoiceInput = debugVoiceInput;
        this.fromVoiceActivity = fromVoiceActivity;
    }

    public int getVolumeReduceLevel() {
        return volumeReduceLevel;
    }

    public String getIntentName() {
        return intentName;
    }

    public String getIntentExtraKeyName() {
        return intentExtraKeyName;
    }

    public boolean isVoiceDebug() {
        return debugVoiceInput;
    }

    public boolean isFromVoiceActivity() {
        return fromVoiceActivity;
    }
}