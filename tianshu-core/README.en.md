# Beichen (北辰)

Beichen is the base of Tianshu: feature modules run on top of it, and it provides the UI, key bindings, settings, world state and local audio. Tianshu's features come from the optional feature modules — install the ones you want.

> Every feature module requires Beichen and cannot run on its own; Beichen itself starts normally with no feature module installed.

**Game versions**: NeoForge from 1.20.1, Fabric from 1.20, one jar per Minecraft version (see the [documentation index](../doc/README.md)).
**Loaders**: NeoForge / Fabric
**Dependencies**: `tianshu_libs` (local runtime library) — required; feature modules — optional.

---

## What it provides

- **One settings page** — "Tianshu Console" in the pause menu; each feature module contributes its own page there.
- **Shared building blocks** — keys, HUD, audio, chat lines and local notices, so modules do not each reimplement them.
- **World awareness** — player state, inventory, crosshair target and game events, prepared for the modules that need them.
- **Server actions (optional)** — give item, teleport, time and weather; off by default and enabled by an operator.
- **Diagnostic log** — attach it when reporting a problem.

## Feature modules

| Module | Role | Dependencies |
|---|---|---|
| **Intake (听澜)** | Speech recognition, transcript repair, **Intent** (dialogue arbitration) | Requires Presence |
| **Echo (回响)** | Local text-to-speech | Presence optional |
| **Weaving (织言)** | Cloud LLM (OpenAI-compatible endpoint, streaming) | Presence optional |
| **Auxilium (辅星)** | Dialogue brain: context, memory, turn orchestration, reply presentation | Requires Weaving and Presence; Intake and Echo optional |
| **Presence (映迹)** | World-state summary, HUD layers, context queries | — |

Installing all of them is the full form; any subset still runs, and missing abilities degrade gracefully (with no Echo installed, for example, replies are text only).

## License and credits

This mod is open source under the MIT license (© 2026 Ero-Dragon).

More: [documentation index](../doc/README.md) · [architecture overview](../doc/architecture/模块架构.md) · [external integration guide](../EXTERNAL_INTEGRATION_GUIDE.md)