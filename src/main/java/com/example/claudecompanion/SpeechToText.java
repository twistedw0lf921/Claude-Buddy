package com.example.claudecompanion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Sends 48 kHz mono PCM to an OpenAI-compatible /audio/transcriptions endpoint. */
final class SpeechToText {

    static String transcribe(short[] pcm48k) throws IOException {
        byte[] wav = toWav(downsampleTo16k(pcm48k));
        String boundary = "----ClaudeCompanion" + System.nanoTime();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        field(out, boundary, "model", Config.STT_MODEL.get());
        field(out, boundary, "response_format", "json");
        String lang = Config.STT_LANGUAGE.get().trim();
        if (!lang.isEmpty()) {
            field(out, boundary, "language", lang);
        }
        out.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(wav);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        String key = Config.sttKey();
        if (!key.isEmpty()) {
            headers.put("Authorization", "Bearer " + key);
        }
        String resp = Http.post(Config.STT_URL.get(), headers, out.toByteArray(), 30_000);
        JsonObject o = JsonParser.parseString(resp).getAsJsonObject();
        return o.has("text") ? o.get("text").getAsString().trim() : "";
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static short[] downsampleTo16k(short[] in) {
        int n = in.length / 3;
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            out[i] = (short) ((in[3 * i] + in[3 * i + 1] + in[3 * i + 2]) / 3);
        }
        return out;
    }

    private static byte[] toWav(short[] pcm) {
        int dataLen = pcm.length * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        b.putInt(36 + dataLen);
        b.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        b.putInt(16);
        b.putShort((short) 1);      // PCM
        b.putShort((short) 1);      // mono
        b.putInt(16000);            // sample rate
        b.putInt(16000 * 2);        // byte rate
        b.putShort((short) 2);      // block align
        b.putShort((short) 16);     // bits per sample
        b.put("data".getBytes(StandardCharsets.US_ASCII));
        b.putInt(dataLen);
        for (short s : pcm) {
            b.putShort(s);
        }
        return b.array();
    }

    private SpeechToText() {}
}
