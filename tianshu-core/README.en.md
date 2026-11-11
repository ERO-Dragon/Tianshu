# Tianshu

Tianshu is an extensible AI assistant: its base provides the UI, key bindings, settings, world state and local audio, and feature modules run on top of it.

**Game versions**: NeoForge from 1.20.1, Fabric from 1.20, one jar per Minecraft version (see the [documentation index](../doc/README.md)).
**Loaders**: NeoForge / Fabric
**Dependencies**: `tianshu_libs` (local runtime library) — required; feature modules — optional.

---

## What the base provides

- **One console** — "Tianshu Console" in the pause menu; feature modules put their settings pages there.
- **Shared building blocks** — keys, HUD, audio, chat lines and local notices.
- **World awareness** — player state, inventory, crosshair target and game events.
- **Server actions (optional)** — give item, teleport, time and weather; off by default and enabled by an operator.
- **Diagnostic log** — attach it when reporting a problem.

## Feature modules

| Module | Role | Dependencies |
|---|---|---|
| **Intake (听澜)** | Speech recognition, transcript repair, **Intent** (dialogue arbitration) | Requires Presence |
| **Echo (回响)** | Local text-to-speech | Presence optional |
| **Weaving (织言)** | Cloud LLM (OpenAI-compatible endpoint, streaming) | Presence optional |
| **Auxilium** | Dialogue brain: context, memory, turn orchestration, reply presentation | Requires Weaving and Presence; Intake and Echo optional |
| **Presence (映迹)** | World-state summary, HUD layers, context queries | — |

## License and credits

This mod is open source under the MIT license (© 2026 Ero-Dragon).

More: [documentation index](../doc/README.md) · [architecture overview](../doc/architecture/模块架构.md) · [external integration guide](../EXTERNAL_INTEGRATION_GUIDE.md)