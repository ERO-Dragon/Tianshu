# Lab (实验台)

Lab is a small bench for developers, not a mod for players. It places three stand-in companions in the world so you can watch one thing: when several of them could answer, who actually gets the turn — the creature you are looking at, the item in your hand, or the name you just said.

**Game versions**: unrestricted. Distributed as a single universal jar; per-version compatibility is handled by Ferry.
**Loaders**: NeoForge / Fabric
**Runs with**: the Tianshu client, together with Intake. Not meant for players' worlds.

---

## What it gives you

- **Three ways to ask for a turn, side by side** — one claims by crosshair, one by held item, one by wake word, each with its own priority so the winner is obvious.
- **A visible lease** — how long a winner keeps the turn can be switched, including never, so you can watch ownership expire or persist.
- **Editable wake words** — the default set can be replaced from the bench page.
- **Nothing of its own to say** — a stand-in answers with its name and ownership, nothing else.

## License and credits

This mod is open source under the GNU Lesser General Public License v3.0 (© 2026 Ero-Dragon).

See [Intake](../tianshu-intake/README.md) for the arbitration itself, and the [IA participant guide](../doc/function/intake/ia/IA_外部模组仲裁接入说明.md) for writing a participant of your own.