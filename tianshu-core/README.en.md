# Beichen (北辰)

Beichen is the Tianshu host mod. The UI, key bindings, settings page, world state, module hosting, local audio bridge and diagnostics all live here. Speech recognition, speech synthesis, cloud conversation, the dialogue brain and the HUD are provided by optional feature modules — install the ones you want.

> Every feature module requires Beichen; Beichen itself starts normally with no feature module installed, it simply has no features.

**Game versions**: NeoForge from 1.20.1, Fabric from 1.20, one jar per Minecraft version (see the [documentation index](../doc/README.md) for the per-line details).
**Loaders**: NeoForge / Fabric
**Dependencies**: `tianshu_libs` (local runtime library) — required; feature modules — optional.

---

## Features

- **Module host** — feature modules register themselves and assemble themselves; remove any of them and Beichen still starts.
- **Settings page** — opened from the pause menu as "Tianshu AI Console". Each module contributes its own page; drafts and saving form one transaction, and closing with unsaved changes asks first. The kernel page carries a debug switch (development builds only).
- **Keys and input** — hold `V` to speak; with Auxilium installed, press `H` for the text input bar. Feature modules can declare their own keys too; Beichen registers them all into the vanilla controls screen.
- **HUD host** — modules draw on their own HUD layers, and icon positions can be dragged. With no HUD-using module installed there is no per-frame work.
- **Local presentation** — chat line, action bar, toast, title and local sound, for modules that need to speak up locally.
- **World context** — health, hunger, level, inventory, equipment, effects, block target, open container, position and facing, player list — read on demand.
- **World events** — crafting, kills, deaths, pickups, trades, taming, effects, advancements, chat, dimension changes, opened containers and boss fights are turned into an event stream for feature modules (NeoForge and Fabric each provide a slightly different subset).
- **Server actions (optional, all off by default)** — give item / teleport / set time / set weather / spawn entity, plus a server-wide broadcast. One switch per action, operator-only, idempotent per request id, broadcast rate-limited.
- **Diagnostics** — everything goes to `logs/tianshu-diagnostics.log`: `WARN` / `ERROR` are always written, `TRACE` / `DEBUG` / `INFO` only with the debug switch on. Release builds never print debug output into the in-game chat.
- **Audio bridge** — microphone capture and local playback devices are enumerated, selected and torn down by Beichen; speech modules only use them.
- **Third-party integration** — other mods can probe Beichen, register trigger words, submit module status and contribute settings pages. NeoForge uses a registration event, Fabric uses a static entry point; both reach the same API. See the *External Integration Guide*.

## Feature modules

| Module | Role | Dependencies |
|---|---|---|
| **Intake (听澜)** | Speech recognition, transcript repair, **Intent** (dialogue arbitration) | Requires Presence |
| **Echo (回响)** | Local text-to-speech | Presence optional |
| **Weaving (织言)** | Cloud LLM (OpenAI-compatible endpoint, streaming) | Presence optional |
| **Auxilium (辅星)** | Dialogue brain: context, memory, turn orchestration, reply presentation | Requires Weaving and Presence; Intake and Echo optional |
| **Presence (映迹)** | World-state summary, HUD layers, context queries | — |

Installing all of them is the full form; any subset still runs, and missing abilities degrade quietly (with no Echo installed, for example, replies are text only).

## Settings and configuration

- Entry: pause menu → "Tianshu AI Console".
- Kernel config: `config/tianshu-client.toml` (debug switch only).
- Module config: `config/Tianshu/module/<moduleId>.properties`, owned by each module.
- Server action switches: `config/tianshu-server.toml`, all off by default; a world may override it with `serverconfig/tianshu-server.toml`.

## Privacy

Beichen has no account system and uploads no diagnostics. Voice audio never leaves your machine; conversation text and reply-related context are sent to the model endpoint you configured, under that provider's privacy policy.

## License and credits

This mod is open source under the MIT license (© 2026 Ero-Dragon).

More: [documentation index](../doc/README.md) · [architecture overview](../doc/architecture/模块架构.md) · [external integration guide](../EXTERNAL_INTEGRATION_GUIDE.md)