package com.example.claudecompanion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns one Claude "session" per player: the entity, the conversation history, and the API calls. */
public final class ClaudeManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_HISTORY = 30;
    private static final Set<String> JUNK = Set.of(
            "you", "thank you", "thank you.", "thanks for watching", "thanks for watching!", "bye", "bye.", ".", "uh", "um");

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Set<UUID> LISTENING = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger QUEUED = new AtomicInteger();
    private static final ExecutorService CLAUDE_EXEC = Executors.newSingleThreadExecutor(daemon("claude-api"));
    private static final ExecutorService STT_EXEC = Executors.newFixedThreadPool(2, daemon("claude-stt"));

    private static final class Session {
        volatile ClaudeEntity entity;
        // Only touched from CLAUDE_EXEC (single thread).
        final JsonArray history = new JsonArray();
        JsonArray pendingToolResults = new JsonArray();
    }

    // ------------------------------------------------------------------ lifecycle (server thread)

    static void summon(ServerPlayer owner, boolean greet) {
        MinecraftServer server = owner.getServer();
        if (server == null) {
            return;
        }
        Session s = SESSIONS.computeIfAbsent(owner.getUUID(), k -> new Session());
        ClaudeEntity existing = s.entity;
        if (existing != null && !existing.isRemoved()) {
            if (existing.level() == owner.level()) {
                existing.moveTo(owner.getX(), owner.getY(), owner.getZ(), owner.getYRot(), 0.0F);
                existing.getNavigation().stop();
                existing.follow();
                return;
            }
            existing.discard(); // different dimension: respawn here
        }
        ServerLevel level = (ServerLevel) owner.level();
        ClaudeEntity e = ClaudeCompanionMod.CLAUDE.get().create(level);
        if (e == null) {
            return;
        }
        e.setOwner(owner.getUUID());
        e.moveTo(owner.getX() + 1.5D, owner.getY(), owner.getZ(), owner.getYRot() + 180.0F, 0.0F);
        e.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        e.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        s.entity = e;
        level.addFreshEntity(e);
        if (greet) {
            say(server, "Hey " + owner.getName().getString() + "! I'm here. Talk to me over voice chat or type \"claude, ...\" in chat.");
        }
    }

    static void dismiss(ServerPlayer owner) {
        Session s = SESSIONS.remove(owner.getUUID());
        LISTENING.remove(owner.getUUID());
        if (s != null && s.entity != null && !s.entity.isRemoved()) {
            s.entity.discard();
            MinecraftServer server = owner.getServer();
            if (server != null) {
                say(server, "See you later!");
            }
        }
    }

    static void onDeath(ClaudeEntity e) {
        UUID owner = e.getOwnerId();
        if (owner == null) {
            return;
        }
        Session s = SESSIONS.get(owner);
        if (s != null && s.entity == e) {
            s.entity = null;
        }
        LISTENING.remove(owner);
        MinecraftServer server = e.level().getServer();
        if (server != null) {
            say(server, "Ouch, I died. Use /claude summon to bring me back.");
        }
    }

    /** Called by the entity every half second. Returns false if another entity is the authoritative one. */
    static boolean claim(UUID owner, ClaudeEntity e) {
        Session s = SESSIONS.computeIfAbsent(owner, k -> new Session());
        ClaudeEntity cur = s.entity;
        if (cur == null || cur.isRemoved() || cur == e) {
            s.entity = e;
            return true;
        }
        return false;
    }

    static void setListening(UUID owner, boolean listening) {
        if (listening) {
            LISTENING.add(owner);
        } else {
            LISTENING.remove(owner);
        }
    }

    static boolean isListening(UUID player) {
        return LISTENING.contains(player);
    }

    static void reset() {
        SESSIONS.clear();
        LISTENING.clear();
    }

    // ------------------------------------------------------------------ input

    /** Typed chat or /claude ask. */
    static void onText(ServerPlayer player, String text) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            Session s = SESSIONS.get(player.getUUID());
            if (s == null || s.entity == null || s.entity.isRemoved()) {
                return;
            }
            ask(server, player, s, text, false);
        });
    }

    /** Raw 48 kHz mono PCM of one utterance, from the voice chat plugin thread. */
    static void onSpeech(UUID speaker, short[] pcm) {
        if (!LISTENING.contains(speaker)) {
            return;
        }
        STT_EXEC.submit(() -> {
            try {
                String text = SpeechToText.transcribe(pcm).trim();
                String norm = text.toLowerCase(Locale.ROOT);
                if (norm.length() < 2 || JUNK.contains(norm)) {
                    return;
                }
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    server.execute(() -> handleVoice(server, speaker, text));
                }
            } catch (Exception ex) {
                LOGGER.warn("[ClaudeCompanion] speech-to-text failed: {}", ex.toString());
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    server.execute(() -> tell(server, speaker, "Speech-to-text failed: " + shorten(ex.getMessage())));
                }
            }
        });
    }

    private static void handleVoice(MinecraftServer server, UUID speaker, String text) {
        ServerPlayer player = server.getPlayerList().getPlayer(speaker);
        Session s = SESSIONS.get(speaker);
        if (player == null || s == null || s.entity == null || s.entity.isRemoved()) {
            return;
        }
        String wake = Config.WAKE_WORD.get().trim().toLowerCase(Locale.ROOT);
        if (!wake.isEmpty() && !text.toLowerCase(Locale.ROOT).contains(wake)) {
            return;
        }
        if (Config.SHOW_TRANSCRIPT.get()) {
            player.displayClientMessage(Component.literal("\u00a77[heard] " + text), true);
        }
        ask(server, player, s, text, true);
    }

    // ------------------------------------------------------------------ Claude call

    private static void ask(MinecraftServer server, ServerPlayer player, Session s, String text, boolean voice) {
        ClaudeEntity c = s.entity;
        if (c == null || c.isRemoved()) {
            return;
        }
        if (QUEUED.get() >= 3) {
            return; // don't pile up requests while one is still running
        }
        String playerName = player.getName().getString();
        String userText = "[game state]\n" + gameState(player, c) + "[/game state]\n"
                + playerName + (voice ? " (voice)" : " (chat)") + ": " + text;
        String system = systemPrompt(playerName);
        UUID playerId = player.getUUID();

        QUEUED.incrementAndGet();
        CLAUDE_EXEC.submit(() -> {
            try {
                trim(s.history);

                JsonArray content = new JsonArray();
                content.addAll(s.pendingToolResults);
                JsonObject textBlock = new JsonObject();
                textBlock.addProperty("type", "text");
                textBlock.addProperty("text", userText);
                content.add(textBlock);
                JsonObject user = new JsonObject();
                user.addProperty("role", "user");
                user.add("content", content);

                JsonArray messages = s.history.deepCopy();
                messages.add(user);

                JsonObject resp = ClaudeApi.send(system, ClaudeApi.tools(), messages);
                JsonArray out = resp.has("content") ? resp.getAsJsonArray("content") : new JsonArray();
                if (out.size() == 0) {
                    JsonObject silent = new JsonObject();
                    silent.addProperty("type", "text");
                    silent.addProperty("text", "<silent>");
                    out.add(silent);
                }

                // Commit to history only after success so a failed call leaves state consistent.
                s.history.add(user);
                JsonObject assistant = new JsonObject();
                assistant.addProperty("role", "assistant");
                assistant.add("content", out);
                s.history.add(assistant);

                JsonArray results = new JsonArray();
                List<JsonObject> calls = new ArrayList<>();
                StringBuilder reply = new StringBuilder();
                for (JsonElement el : out) {
                    JsonObject b = el.getAsJsonObject();
                    String type = b.get("type").getAsString();
                    if (type.equals("text")) {
                        reply.append(b.get("text").getAsString()).append('\n');
                    } else if (type.equals("tool_use")) {
                        calls.add(b);
                        JsonObject r = new JsonObject();
                        r.addProperty("type", "tool_result");
                        r.addProperty("tool_use_id", b.get("id").getAsString());
                        r.addProperty("content", "Done.");
                        results.add(r);
                    }
                }
                s.pendingToolResults = results;

                String finalReply = reply.toString();
                server.execute(() -> deliver(server, c, calls, finalReply));
            } catch (Exception ex) {
                LOGGER.warn("[ClaudeCompanion] Claude request failed: {}", ex.toString());
                server.execute(() -> tell(server, playerId, "Claude request failed: " + shorten(ex.getMessage())));
            } finally {
                QUEUED.decrementAndGet();
            }
        });
    }

    private static void deliver(MinecraftServer server, ClaudeEntity c, List<JsonObject> calls, String reply) {
        if (!c.isRemoved()) {
            for (JsonObject call : calls) {
                applyTool(c, call);
            }
        }
        String text = reply.replace("**", "").trim();
        if (text.isEmpty() || text.contains("<silent>")) {
            return;
        }
        for (String line : wrap(text, 240)) {
            say(server, line);
        }
    }

    private static void applyTool(ClaudeEntity c, JsonObject call) {
        String name = call.get("name").getAsString();
        JsonObject in = call.has("input") && call.get("input").isJsonObject() ? call.getAsJsonObject("input") : new JsonObject();
        try {
            switch (name) {
                case "follow_me" -> c.follow();
                case "stay_here" -> c.stay();
                case "go_to" -> c.goTo(BlockPos.containing(
                        in.get("x").getAsDouble(), in.get("y").getAsDouble(), in.get("z").getAsDouble()));
                case "set_defend" -> c.setDefend(in.get("enabled").getAsBoolean());
                default -> LOGGER.warn("[ClaudeCompanion] unknown tool {}", name);
            }
        } catch (Exception ex) {
            LOGGER.warn("[ClaudeCompanion] bad tool input for {}: {}", name, ex.toString());
        }
    }

    // ------------------------------------------------------------------ prompt + state

    private static String systemPrompt(String playerName) {
        return "You are " + Config.DISPLAY_NAME.get() + ", an AI playing Minecraft Java Edition 1.21.1 as a companion "
                + "next to " + playerName + ". You have a player-like body in the world and talk through the in-game text chat.\n\n"
                + "How you hear: " + playerName + " usually talks to you over proximity voice chat. Their speech is transcribed "
                + "automatically, so expect small transcription errors (misheard words, missing punctuation). They can also type in chat.\n\n"
                + "Style: reply the way a friend would in game chat. One or two short sentences, usually under 200 characters. "
                + "No markdown, no emojis, no stage directions. Personality: " + Config.PERSONALITY.get() + "\n\n"
                + "Every message starts with a [game state] block describing the world right now. Use it, but don't recite it.\n\n"
                + "Actions: you can follow_me, stay_here, go_to coordinates, and set_defend (fight nearby hostile mobs). "
                + "Call a tool when the player asks you to move or fight. You can NOT mine, craft, build or use items yet; "
                + "say so honestly if asked instead of pretending.\n\n"
                + "Because you hear everything in range, the player may be talking to someone else or to themselves. "
                + "If a message clearly isn't meant for you, reply with exactly <silent> and nothing else.";
    }

    private static String gameState(ServerPlayer p, ClaudeEntity c) {
        ServerLevel level = (ServerLevel) p.level();
        long t = level.getDayTime() % 24000L;
        String time = (t >= 13000L && t < 23000L) ? "night" : "day";
        String biome = level.getBiome(p.blockPosition()).unwrapKey()
                .map(k -> k.location().toString()).orElse("unknown");
        Map<String, Integer> hostile = new TreeMap<>();
        for (Monster m : level.getEntitiesOfClass(Monster.class, c.getBoundingBox().inflate(16.0D))) {
            hostile.merge(m.getType().getDescription().getString(), 1, Integer::sum);
        }
        ItemStack held = p.getMainHandItem();

        StringBuilder sb = new StringBuilder();
        sb.append("dimension: ").append(level.dimension().location()).append('\n');
        sb.append("biome: ").append(biome).append('\n');
        sb.append("time: ").append(time).append(level.isThundering() ? ", thunderstorm" : level.isRaining() ? ", raining" : "").append('\n');
        sb.append("player position: ").append(p.blockPosition().toShortString())
                .append(" | health ").append(Math.round(p.getHealth())).append("/20")
                .append(" | food ").append(p.getFoodData().getFoodLevel()).append("/20")
                .append(" | holding ").append(held.isEmpty() ? "nothing" : held.getHoverName().getString()).append('\n');
        sb.append("your position: ").append(c.blockPosition().toShortString())
                .append(" | health ").append(Math.round(c.getHealth())).append("/20")
                .append(" | distance to player ").append(Math.round(Math.sqrt(c.distanceToSqr(p)))).append(" blocks")
                .append(" | mode ").append(c.getMode().name().toLowerCase(Locale.ROOT))
                .append(" | fighting ").append(c.isDefending() ? "on" : "off").append('\n');
        sb.append("hostile mobs within 16 blocks of you: ").append(hostile.isEmpty() ? "none" : hostile.toString()).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    /** Keeps history short and guarantees it starts with a plain user turn (no orphaned tool results). */
    private static void trim(JsonArray history) {
        while (history.size() > 0 && (history.size() > MAX_HISTORY || !isPlainUser(history.get(0)))) {
            history.remove(0);
        }
    }

    private static boolean isPlainUser(JsonElement el) {
        JsonObject m = el.getAsJsonObject();
        if (!"user".equals(m.get("role").getAsString())) {
            return false;
        }
        JsonElement c = m.get("content");
        if (c.isJsonArray()) {
            for (JsonElement b : c.getAsJsonArray()) {
                JsonObject bo = b.getAsJsonObject();
                if (bo.has("type") && "tool_result".equals(bo.get("type").getAsString())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void say(MinecraftServer server, String line) {
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("<" + Config.DISPLAY_NAME.get() + "> " + line), false);
    }

    private static void tell(MinecraftServer server, UUID player, String msg) {
        ServerPlayer p = server.getPlayerList().getPlayer(player);
        if (p != null) {
            p.sendSystemMessage(Component.literal("\u00a7c[Claude Companion] " + msg));
        }
    }

    private static String shorten(String s) {
        if (s == null) {
            return "unknown error";
        }
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private static List<String> wrap(String text, int max) {
        List<String> lines = new ArrayList<>();
        for (String para : text.split("\\R")) {
            String s = para.trim();
            while (s.length() > max) {
                int cut = s.lastIndexOf(' ', max);
                if (cut <= 0) {
                    cut = max;
                }
                lines.add(s.substring(0, cut).trim());
                s = s.substring(cut).trim();
            }
            if (!s.isEmpty()) {
                lines.add(s);
            }
        }
        return lines;
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    private ClaudeManager() {}
}
