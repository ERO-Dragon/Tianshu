# Presence · 映迹

Presence is the HUD and context capability on the Ferry base: it turns the world state into facts modules can use, gathers the product activities they publish, and draws them in game.

**Game versions**: unrestricted. Presence is distributed as a single universal jar with both NeoForge and Fabric entrypoints; per-version compatibility is handled by Tianshu.
**Loaders**: NeoForge / Fabric
**Runs with**: the Ferry base, on the client; servers do not need it.
**Dependencies**: Ferry and Foundry, both required; drawing, GUI gating and the position editor come from Ferry.
**Third-party integration**: other mods can publish their state to the HUD, or query the world state Presence maintains; see the [product activity protocol](../doc/function/presence/Presence_产品活动协议使用文档.md).

---

## Features

- **Animated icon** — a shader-drawn fluid sphere: small and quiet when idle, largest with outward ripples while listening, back to the reference size and aurora while thinking, with a soap-bubble surface while responding. A raymarched preset is also available.
- **Partner marker** — the icon and lease ring of the pre-winner or current owner, written by the arbitration module; visible before you speak.
- **World snapshot** — dimension, health, hunger, inventory, equipment, potion effects, crosshair target and environment, answered per requested field group.
- **Event window** — game events from one second are aggregated into a single counted record for subscribers.
- **Interaction context** — a summary is pushed whenever the crosshair, main hand, equipment, sneaking, interaction key or screen kind changes.
- **Adjustable display** — HUD, status text and animated icon toggle separately; icon sizes from 16 to 96 px; two visual presets; a position editor.

## HUD states

| Primary state | On screen |
|---|---|
| Idle | Small sphere, faint aurora, no text |
| Listening | Largest sphere with two outward ripples |
| Loading | The sphere collapses to a dot, then unfolds into six liquid dots |
| Thinking | Reference size and reference aurora |
| Responding | Same size as thinking, listening-level aurora, soap-bubble surface |
| Reconnecting / startup failure | Reuses the loading and background-task looks; only the text differs |

When several activities overlap, one primary state is chosen by a fixed priority. Background tasks never take the primary slot; they show as a small dot at the lower right of the icon. Activities clear when the world is left.

## World state and events

Presence reads the current world and turns it into platform-neutral facts: player status, inventory and equipment, crosshair and target, potion effects, dimension and environment. Game events are aggregated per second into counted groups. The same data answers other modules' queries and reaches subscribers as `PRESENCE.WORLD_EVENT`.

## Third-party integration

- **Product activity** (`PRESENCE.ACTIVITY`) — declare what a module is doing and when it ends; one module may expose several tasks at once, and ending one leaves the others untouched.
- **Context query** (`PRESENCE.QUERY_CONTEXT`) — request a world snapshot, reading only the field groups you need.
- **World events** (`GAME.WORLD_EVENT`) — publish one, and Presence aggregates it into a one-second `PRESENCE.WORLD_EVENT` window.
- **Partner marker** (`PRESENCE.PARTNER`) — written by the arbitration side; Presence only renders the icon and lease ring.

## Display settings

The settings page lives under *Tianshu console · Presence* and persists to `config/Tianshu/module/module.presence.properties`. Toggle the HUD, status text and animated icon, switch visual presets, resize the icon, and drag the anchor with the position editor.

## License and credits

This mod is open source under the GNU Lesser General Public License v3.0 (© 2026 Ero-Dragon). It bundles no third-party runtime or models.

More: [Presence docs index](../doc/function/presence/Presence_文档索引.md) · [Module design](../doc/function/presence/Presence_游戏内反馈与交互上下文模块设计.md) · [Product activity protocol](../doc/function/presence/Presence_产品活动协议使用文档.md) · [HUD browser preview](tools/hud-preview/README.md) · [Module architecture](../doc/architecture/模块架构.md)