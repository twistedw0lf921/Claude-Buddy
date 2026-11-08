package com.example.claudecompanion;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Hooks into Simple Voice Chat on the server. Every microphone packet from a player who has a nearby Claude
 * is decoded to PCM; when they stop talking, the whole utterance is handed to ClaudeManager.
 * Simple Voice Chat discovers this class through the @ForgeVoicechatPlugin annotation.
 */
@ForgeVoicechatPlugin
public class ClaudeVoicechatPlugin implements VoicechatPlugin {
    private static final int SAMPLE_RATE = 48_000;

    private static final class Stream {
        final OpusDecoder decoder;
        final List<short[]> frames = new ArrayList<>();
        int samples;
        long lastPacketMs;

        Stream(OpusDecoder decoder) {
            this.decoder = decoder;
        }
    }

    private final Map<UUID, Stream> streams = new ConcurrentHashMap<>();
    private volatile VoicechatServerApi serverApi;
    private ScheduledExecutorService flusher;

    @Override
    public String getPluginId() {
        return ClaudeCompanionMod.MODID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "claude-voice-flush");
            t.setDaemon(true);
            return t;
        });
        flusher.scheduleAtFixedRate(this::checkSilence, 200, 100, TimeUnit.MILLISECONDS);
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, e -> serverApi = e.getVoicechat());
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
    }

    private void onMicrophone(MicrophonePacketEvent event) {
        VoicechatConnection con = event.getSenderConnection();
        VoicechatServerApi api = serverApi;
        if (con == null || api == null) {
            return;
        }
        UUID id = con.getPlayer().getUuid();
        if (!ClaudeManager.isListening(id)) {
            return;
        }
        byte[] data = event.getPacket().getOpusEncodedData();
        Stream s = streams.computeIfAbsent(id, k -> new Stream(api.createDecoder()));
        synchronized (s) {
            if (data == null || data.length == 0) {
                flush(id, s); // an empty packet means the player stopped talking
                return;
            }
            short[] pcm = s.decoder.decode(data);
            s.frames.add(pcm);
            s.samples += pcm.length;
            s.lastPacketMs = System.currentTimeMillis();
            if (s.samples > Config.MAX_SPEECH_S.get() * SAMPLE_RATE) {
                flush(id, s);
            }
        }
    }

    private void checkSilence() {
        try {
            long now = System.currentTimeMillis();
            long silence = Config.SILENCE_MS.get();
            for (Map.Entry<UUID, Stream> e : streams.entrySet()) {
                Stream s = e.getValue();
                synchronized (s) {
                    if (s.samples > 0 && now - s.lastPacketMs > silence) {
                        flush(e.getKey(), s);
                    }
                }
            }
        } catch (Exception ignored) {
            // never let the scheduler die
        }
    }

    /** Must be called while holding the stream's lock. */
    private void flush(UUID id, Stream s) {
        if (s.samples == 0) {
            return;
        }
        short[] pcm = new short[s.samples];
        int pos = 0;
        for (short[] f : s.frames) {
            System.arraycopy(f, 0, pcm, pos, f.length);
            pos += f.length;
        }
        s.frames.clear();
        s.samples = 0;
        s.decoder.resetState();
        long ms = pcm.length * 1000L / SAMPLE_RATE;
        if (ms >= Config.MIN_SPEECH_MS.get()) {
            ClaudeManager.onSpeech(id, pcm);
        }
    }
}
