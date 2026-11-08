package com.example.claudecompanion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Minimal client for the Anthropic Messages API (with tool use). */
final class ClaudeApi {

    static JsonObject send(String system, JsonArray tools, JsonArray messages) throws IOException {
        String key = Config.anthropicKey();
        if (key.isEmpty()) {
            throw new IOException("No Anthropic API key set (config anthropicApiKey or ANTHROPIC_API_KEY env var).");
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", Config.MODEL.get());
        body.addProperty("max_tokens", Config.MAX_TOKENS.get());
        body.addProperty("system", system);
        body.add("tools", tools);
        body.add("messages", messages);

        Map<String, String> headers = Map.of(
                "content-type", "application/json",
                "x-api-key", key,
                "anthropic-version", "2023-06-01");
        String resp = Http.post("https://api.anthropic.com/v1/messages", headers,
                body.toString().getBytes(StandardCharsets.UTF_8), 60_000);
        return JsonParser.parseString(resp).getAsJsonObject();
    }

    static JsonArray tools() {
        JsonArray tools = new JsonArray();

        tools.add(tool("follow_me", "Start following the player around.", new JsonObject()));
        tools.add(tool("stay_here", "Stop moving and stay where you are standing.", new JsonObject()));

        JsonObject goProps = new JsonObject();
        goProps.add("x", num("Target X coordinate"));
        goProps.add("y", num("Target Y coordinate"));
        goProps.add("z", num("Target Z coordinate"));
        tools.add(tool("go_to", "Walk to the given world coordinates, then wait there.", goProps, "x", "y", "z"));

        JsonObject defProps = new JsonObject();
        JsonObject en = new JsonObject();
        en.addProperty("type", "boolean");
        en.addProperty("description", "true = attack nearby hostile mobs, false = don't fight");
        defProps.add("enabled", en);
        tools.add(tool("set_defend", "Turn automatic fighting of nearby hostile mobs on or off.", defProps, "enabled"));

        return tools;
    }

    private static JsonObject tool(String name, String description, JsonObject props, String... required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        JsonArray req = new JsonArray();
        for (String r : required) {
            req.add(r);
        }
        schema.add("required", req);
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("description", description);
        t.add("input_schema", schema);
        return t;
    }

    private static JsonObject num(String description) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "number");
        o.addProperty("description", description);
        return o;
    }

    private ClaudeApi() {}
}
