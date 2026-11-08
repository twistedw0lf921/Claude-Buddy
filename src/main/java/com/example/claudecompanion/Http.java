package com.example.claudecompanion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class Http {
    static String post(String url, Map<String, String> headers, byte[] body, int readTimeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(readTimeoutMs);
        headers.forEach(c::setRequestProperty);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body);
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
        if (code >= 400) {
            throw new IOException("HTTP " + code + ": " + text);
        }
        return text;
    }

    private Http() {}
}
