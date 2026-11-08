package com.example.claudecompanion;

import net.minecraftforge.common.ForgeConfigSpec;

public final class Config {
    private static final ForgeConfigSpec.Builder B = new ForgeConfigSpec.Builder();

    // ---- Claude ----
    public static final ForgeConfigSpec.ConfigValue<String> ANTHROPIC_API_KEY = B
            .comment("Anthropic API key. If empty, the ANTHROPIC_API_KEY environment variable is used.")
            .define("anthropicApiKey", "");
    public static final ForgeConfigSpec.ConfigValue<String> MODEL = B
            .comment("Claude model id. A smaller model (e.g. claude-haiku-5-5) gives snappier replies.")
            .define("model", "claude-sonnet-5-5");
    public static final ForgeConfigSpec.IntValue MAX_TOKENS = B
            .defineInRange("maxTokens", 400, 50, 4000);
    public static final ForgeConfigSpec.ConfigValue<String> DISPLAY_NAME = B
            .comment("Name shown in chat, e.g. <Claude> hi")
            .define("displayName", "Claude");
    public static final ForgeConfigSpec.ConfigValue<String> PERSONALITY = B
            .comment("Extra personality text added to the system prompt.")
            .define("personality", "Friendly, curious and a little playful. A good teammate who keeps replies short.");

    // ---- Speech to text (Claude cannot take raw audio, so voice is transcribed first) ----
    public static final ForgeConfigSpec.ConfigValue<String> STT_URL = B
            .comment("OpenAI-compatible transcription endpoint. Works with OpenAI, Groq, or a local faster-whisper server.")
            .define("sttUrl", "https://api.openai.com/v1/audio/transcriptions");
    public static final ForgeConfigSpec.ConfigValue<String> STT_API_KEY = B
            .comment("API key for the transcription service. If empty, the OPENAI_API_KEY environment variable is used.")
            .define("sttApiKey", "");
    public static final ForgeConfigSpec.ConfigValue<String> STT_MODEL = B
            .define("sttModel", "whisper-1");
    public static final ForgeConfigSpec.ConfigValue<String> STT_LANGUAGE = B
            .comment("Optional ISO language code like 'en'. Empty = auto-detect.")
            .define("sttLanguage", "en");

    // ---- Voice chat ----
    public static final ForgeConfigSpec.IntValue HEARING_RANGE = B
            .comment("Claude only hears you when it is within this many blocks.")
            .defineInRange("hearingRange", 48, 4, 256);
    public static final ForgeConfigSpec.ConfigValue<String> WAKE_WORD = B
            .comment("If set (e.g. 'claude'), voice is ignored unless the transcript contains this word. Empty = reacts to everything you say.")
            .define("wakeWord", "");
    public static final ForgeConfigSpec.IntValue SILENCE_MS = B
            .comment("How long you must be quiet before your speech is sent off.")
            .defineInRange("silenceMs", 900, 300, 5000);
    public static final ForgeConfigSpec.IntValue MIN_SPEECH_MS = B
            .defineInRange("minSpeechMs", 400, 100, 5000);
    public static final ForgeConfigSpec.IntValue MAX_SPEECH_S = B
            .defineInRange("maxSpeechSeconds", 20, 3, 60);
    public static final ForgeConfigSpec.BooleanValue SHOW_TRANSCRIPT = B
            .comment("Show what Claude heard in your action bar.")
            .define("showTranscript", true);

    // ---- Misc ----
    public static final ForgeConfigSpec.BooleanValue OP_ONLY = B
            .comment("Only operators (permission level 2) can use /claude. Useful on servers since every message costs API credits.")
            .define("opOnly", false);

    public static final ForgeConfigSpec SPEC = B.build();

    public static String anthropicKey() {
        String k = ANTHROPIC_API_KEY.get().trim();
        if (k.isEmpty()) {
            String env = System.getenv("ANTHROPIC_API_KEY");
            k = env == null ? "" : env.trim();
        }
        return k;
    }

    public static String sttKey() {
        String k = STT_API_KEY.get().trim();
        if (k.isEmpty()) {
            String env = System.getenv("OPENAI_API_KEY");
            k = env == null ? "" : env.trim();
        }
        return k;
    }

    private Config() {}
}
