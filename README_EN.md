# MC Conversation Framework (MCCF)

简体中文（[中文 README](README.md)） | **English (current)**

---

## What is this? What's in it for me?

Ever been on an international Minecraft server where someone typed a wall of
text in a language you don't read, or you wanted to trade with a nearby
player but couldn't understand a word they said?

**MCCF fixes that.** Install it, and players on the same server can each
chat in their own language — Chinese, English, Japanese, Korean, German,
French, Spanish, Russian, and more — while everyone else automatically sees
a translated version. No more alt-tabbing to a translator and copy-pasting
back and forth; chat just works across languages.

On top of translation, it also makes multiplayer chat feel more like actual
in-person talking:

- **Translations only reach people who could actually hear you.** It's not
  a server-wide broadcast — MCCF checks real in-game distance and line-of-
  sight to decide who "hears" a message. If someone can see you, the
  translated text floats near you like a subtitle; if they're out of sight
  but still in range, it shows above their hotbar instead. No more "the
  whole server saw me muttering in a corner."
- **You pick the translation service, you bring your own API key.** Works
  with OpenAI, Claude, Gemini, DeepL, Kimi, DeepSeek, or a fully local/free
  option via Ollama. Server admins manage all of this from an in-game
  settings screen — no config file editing, no server restarts.
- **Don't want the server dictating your translation setup?** You can run
  MCCF client-side only, with your own translation service, even if the
  server you're on hasn't installed the mod at all (see "Client-Only Mode"
  below).
- **Missed something? Check the chat history.** Subtitles fade away, but
  there's a built-in Chat History screen that lets you scroll back through
  everything said during the session, automatically grouped by
  conversation.

## How do I use it?

1. **Download** the latest `.jar` from the
   [GitHub Releases page](../../releases/latest), or search for the mod on
   CurseForge / Modrinth.
2. **Install** by dropping the `.jar` into your Minecraft `mods` folder.
   - If you're a **server owner**: install it on both the server and your
     own client to get the full "spatial translation" experience (who can
     hear whom, floating subtitle positions, etc.).
   - If you're just a **player** on a server that doesn't have the mod
     installed: install it on your client anyway — it will automatically
     fall back to **Client-Only Mode**, translating chat locally just for
     you. See below for details.
3. **Also install [Fabric API](https://modrinth.com/mod/fabric-api)**
   (required) and [Fabric Loader](https://fabricmc.net/use/).
   [ModMenu](https://modrinth.com/mod/modmenu) is optional but gives you a
   graphical settings entry point.
4. **Configure a translation service in-game.** Open the pause menu (`Esc`)
   and look for the MCCF settings entry (or open it via ModMenu), pick a
   translation provider (e.g. DeepL or OpenAI), and enter your own API key.
   That's it — translation starts working immediately, no restart needed.

## A few things worth knowing before you start

- **Both sides need the mod for the full experience.** If only the server
  has MCCF installed but your client doesn't, you'll just see the untouched
  original chat with no translation. If only your client has it and the
  server doesn't, you get Client-Only Mode (translation, but no distance/
  line-of-sight logic — see below).
- **Client-Only Mode is a real, fully independent path**, not a degraded
  afterthought. If the server you're on doesn't have MCCF, your client
  detects that automatically and switches to translating chat locally with
  whatever provider and API key *you* configured — completely separate from
  any server-side setup. You can also force this mode manually even when
  the server *does* have MCCF, if you'd rather manage your own translation
  settings independently.
- **You need your own API key** for most translation providers (OpenAI,
  Claude, Gemini, DeepL, Kimi, DeepSeek). The only exception is Ollama,
  which runs a model locally on your own machine for free, with no key
  required — but you'll need to set that up yourself first.
- **Only server operators (op) can change the server-wide translation
  settings** (which provider, which API key, etc.). Regular players can
  view what's currently active but can't edit it — this prevents any player
  from silently changing everyone's translation provider. Your personal
  Client-Only Mode settings, on the other hand, are yours alone to edit,
  regardless of your op status.
- **This is Minecraft 1.21.1 only.** It's intentionally pinned to this
  version — see the Chinese README's technical section for why (short
  version: a Fabric API rendering hook this mod depends on for floating
  subtitles was removed in later 1.21.x releases, with no stable
  replacement yet).

## Want the full technical details?

This page is intentionally kept short and player-focused. For the complete
technical documentation — architecture, build instructions, configuration
file reference, translation provider internals, how to add your own
provider, full changelog, and more — see the
**[Chinese README](README.md)**, which is the primary, most up-to-date
technical reference for this project. (Machine translation tools handle
Chinese-to-English reasonably well if needed; a full line-by-line English
translation of the technical sections is not currently maintained, to avoid
the two documents drifting out of sync.)

If you run into a bug or have a feature request, please open an issue on
[GitHub Issues](../../issues).
