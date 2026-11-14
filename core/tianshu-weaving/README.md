# Weaving · 织言

Weaving is a base capability on Tianshu's AI foundation: it turns "talking to a large model" into a shared capability. It carries a caller's messages to an OpenAI-compatible endpoint and sends the answer back while it is still being generated; alongside that it offers an optional local vector capability — turning text into vectors, keeping them on this machine and searching them. What gets remembered and what is done with a search result belong to the caller; Weaving provides the channel and the data plane.

> Weaving is its own capability mod on the foundation and is installed separately. It runs alongside the Tianshu client, and its callers do not have to be Tianshu.

**Game versions**: unrestricted. Weaving is distributed as a single universal jar with both NeoForge and Fabric entrypoints; per-version compatibility is handled by Tianshu.
**Loaders**: NeoForge / Fabric
**Runs with**: the Tianshu client; installed separately as an independent capability mod.
**Callers**: every mod. Tianshu (AX) is one of them; others can integrate directly.
**Third-party integration**: see the [external integration guide](../EXTERNAL_INTEGRATION_GUIDE.md).

---

## What it gives you

- **Your own model** — enter a key and you are done; switch whenever you like without reinstalling.
- **Retrieval can stay entirely local** — the vector model is optional: install it and vectors are computed on this machine and search works offline; skip it and chat still works.
- **Separate by default** — every mod registers its own library, and the registrant decides who can see it.
- **Built for long conversations** — the budget is published from the selected model's window, with room for plenty of world detail.
- **Open to developers** — any mod can call it to ask a model, compute vectors, store and search; see the integration guide above.

## One capability, three uses

- **Ask a model** — send messages, get one answer; ask for streaming and read it as it is generated, and cancel it midway.
- **Compute vectors, store entries** — write text into a local library as vectors and query back the closest ones; or compute for a single query without storing anything.
- **Measure** — estimate tokens before sending, turn text into vectors, and look at the current model's runtime state.

## What it does not do

Weaving does not manage memory and does not decide content for its callers: prompt assembly, injecting search results, persona, dialogue ownership and tool scheduling all belong to the caller. It guarantees that messages go out, fragments come back in order, and vectors and retrieval work locally. When the vector model is not installed or fails to start, the cloud channel keeps working; only retrieval and vector queries report unavailable, and retrieval degrades to lexical matching.

## License and credits

This mod is open source under the GNU Lesser General Public License v3.0 (© 2026 Ero-Dragon).

- BGE-M3 — BAAI, MIT
- llama.cpp — MIT

More: [LLM protocol design](../doc/function/weaving/LLM接口设计.md) · [black-box test record](../doc/function/weaving/黑盒测试.md)