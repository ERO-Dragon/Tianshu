# Tianshu (天枢)

Tianshu is the companion itself — the part that decides what to say. Each turn it looks at where you are standing, what is in your hand and how you are doing, remembers what you told it before, notices what just happened in the world, asks the model for an answer, and hands that answer to Echo to be spoken. It is not a command line and not a search box: you talk, it answers, like a person would.

**Game versions**: unrestricted. Distributed as a single universal jar with both NeoForge and Fabric entrypoints; per-version compatibility is handled by Ferry.
**Loaders**: NeoForge / Fabric
**Runs with**: the Tianshu client, together with Weaving and Presence. Intake and Echo are optional.
**Third-party integration**: other mods can ask questions, add context or publish events; see the [external integration guide](../EXTERNAL_INTEGRATION_GUIDE.md).

---

## What it gives you

- **It remembers you** — what you said, what you prefer, and the big things that happened in this world stay with it and come back when they matter.
- **It knows the moment** — weather, the mob in front of you, the block you just picked up, the ore you are mining can all reach the conversation.
- **It does not nag** — the world's noise is folded into the next reply instead of interrupting you.
- **It speaks** — Echo reads the answer aloud, and you can follow along on screen.
- **It is open** — other mods can talk to it, or hand it events worth announcing.

## How a reply is made

1. You speak — typed or by voice — and the turn begins.
2. Whatever the world just did is merged into memory first, so nothing you just saw is lost.
3. It recalls relevant memories and takes a look at your current state.
4. It turns all of that into one question and hands it to Weaving.
5. The answer comes back, the body is written to history, and Echo speaks it.

## Memory

Short-term memory is the recent exchange; long-term memory is the notes, events and preferences it keeps for itself. Older material is compressed into summaries, so a long-lived world does not turn into an endless transcript. Memory is kept separate per save and per player.

## World events

More than forty built-in events, from a change in weather to your first diamond, ranked by how much they matter. You choose what is spoken, what is only recorded, and what is ignored. The default is quiet: it speaks when there is something worth saying.

## Delegation

When something on the field can do a job, Tianshu can hand a subtask off and fold the result into the reply — what you hear stays one coherent answer, not a stream of tool logs.

## License and credits

This mod is open source under the GNU Lesser General Public License v3.0 (© 2026 Ero-Dragon).

More: [architecture](../doc/function/auxilium/AX_运行架构.md) · [context assembly](../doc/function/auxilium/AX_上下文编排.md) · [memory strategy](../doc/function/auxilium/AX_记忆策略.md) · [event system](../doc/function/auxilium/AX_事件系统.md)