# Claude Companion (Forge 1.21.1)

Summon Claude with `/claude summon`. It spawns as a player-looking character next to you, follows you,
hears you through **Simple Voice Chat**, and answers in the normal Minecraft text chat as `<Claude> ...`.

> Status: the voice chat plugin class has been compile-checked against Simple Voice Chat
> 1.21.1-2.6.22 (the jar in `libs/`). The Minecraft/Forge parts could not be built here, so expect
> to fix a few compile errors on first build. See "If it doesn't compile".

## How it works

1. Simple Voice Chat plugin (`ClaudeVoicechatPlugin`) receives your mic packets on the server, decodes the Opus audio
   to PCM, and waits until you stop talking.
2. The clip is sent to a speech-to-text endpoint (OpenAI-compatible: OpenAI, Groq, or a local faster-whisper server).
   Claude's API takes text and images, not raw audio, so this step is required.
3. The transcript plus a short game-state summary (position, health, time, nearby mobs...) goes to the Claude API.
4. Claude's reply is broadcast to chat. Claude can also call tools: `follow_me`, `stay_here`, `go_to(x,y,z)`, `set_defend`.

You can also talk by typing `claude, ...` or `@claude ...` in chat, or `/claude ask <message>`.

## Commands

- `/claude summon` brings Claude to you (or teleports the existing one to you)
- `/claude dismiss` removes Claude
- `/claude ask <message>` talk by text

## Getting the .jar

This is a complete Gradle project, so there is nothing to merge. It needs internet access to download
Forge and Minecraft, which is why the jar isn't prebuilt in this zip. Pick one:

**A) Build online with GitHub (nothing to install)**
1. Create a new GitHub repository and upload everything in this folder (keep `.github/` and `libs/`).
2. Open the repo's **Actions** tab, run **Build mod jar** (or just push), wait a few minutes.
3. Download the `claude-companion-jar` artifact and unzip it. Inside is `claudecompanion-1.0.0.jar`.

**B) Build on your PC**
1. Install JDK 21 and Gradle 8.12.1 (or copy `gradlew`, `gradlew.bat` and the `gradle/` folder from the
   official Forge 1.21.1 MDK into this folder and use `gradlew` instead of `gradle`).
2. In this folder run `gradle build`.
3. Your jar is `build/libs/claudecompanion-1.0.0.jar`.

## Installing

1. Put `claudecompanion-1.0.0.jar` in your `mods` folder next to **Simple Voice Chat**
   (`voicechat-forge-1_21_1-2_6_22.jar`, also included in `libs/`). Forge 1.21.1 (52.1.0 or newer), Java 21.
   Install both on the server and on your client. Voice chat must already be working.
2. Start the game once, then edit `config/claudecompanion-common.toml` and set:
   - `anthropicApiKey` (or set the `ANTHROPIC_API_KEY` environment variable)
   - `sttApiKey` (or `OPENAI_API_KEY`). To use Groq: `sttUrl = "https://api.groq.com/openai/v1/audio/transcriptions"`,
     `sttModel = "whisper-large-v3-turbo"`.
3. Restart, run `/claude summon`, and talk.

## Config highlights

- `model`: defaults to `claude-sonnet-5-5`; try `claude-haiku-5-5` for faster replies
- `wakeWord`: set to `claude` if you don't want it reacting to everything you say
- `hearingRange`, `silenceMs`: how far away it can hear you / how long a pause ends your sentence
- `opOnly`: restrict `/claude` to operators (every message costs API credits)

## Limits

- Claude is a custom mob rendered with the player model, not a real fake player. It won't show in the tab list.
- It can follow, wait, walk to coordinates and fight hostile mobs. It cannot mine, craft, build or use items yet.
- Only the player who summoned it is heard by voice.
- Conversation memory is kept in RAM (last ~30 messages) and resets on server restart.
- Voice goes to your speech-to-text provider, and the transcript plus game state goes to Anthropic.

## If it doesn't compile

The code uses Forge 1.21.1 + official Mojang mappings. Spots most likely to need a tweak:
`mods.toml` and event class names in `ForgeEvents`.
Paste the compiler errors back to me and I'll fix them.
