package org.verba;

public class VoiceConfig {
    private final int volumeReduceLevel;
    private final String intentName;
    private final String intentExtraKeyName;
    private final long timeoutLong;
    private final long timeoutShort;

    public VoiceConfig(int volumeReduceLevel, String intentName, String intentExtraKeyName,
                       long timeoutLong, long timeoutShort) {
        this.volumeReduceLevel = volumeReduceLevel;
        this.intentName = intentName;
        this.intentExtraKeyName = intentExtraKeyName;
        this.timeoutLong  = Math.max(200, Math.min(10000, timeoutLong));
        this.timeoutShort = Math.max(200, Math.min(10000, timeoutShort));
    }

    public int getVolumeReduceLevel()    { return volumeReduceLevel; }
    public String getIntentName()        { return intentName; }
    public String getIntentExtraKeyName(){ return intentExtraKeyName; }
    public long getTimeoutLong()         { return timeoutLong; }
    public long getTimeoutShort()        { return timeoutShort; }
}