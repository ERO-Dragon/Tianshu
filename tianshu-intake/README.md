# Intake · 听澜

Intake is Tianshu's voice input and dialogue arbitration module. It recognizes speech locally, repairs Minecraft-specific mishearings, and lets Intent hand each turn to the right participant.

**Game versions**: unrestricted. Intake is distributed as a single universal jar with both NeoForge and Fabric entrypoints; per-version compatibility is handled by Tianshu.
**Loaders**: NeoForge / Fabric
**Runs with**: the Tianshu client, together with Ferry, Foundry and Presence.
**Third-party integration**: external mods can register dialogue participants; see the [external integration guide](../EXTERNAL_INTEGRATION_GUIDE.md).

---

## Why Intake

- **Hears Minecraft** — hold-to-talk full-utterance recognition on the local machine; a pinyin index repairs item, entity and command names, so Chinese Minecraft terms land on the right object.
- **Open to other mods** — a `DialogueParticipant` can claim by wake word, crosshair target, held item or interaction state and join the same arbitration.
- **Fallback owner** — when nothing claims and no lease is active, the turn goes to the player-selected default participant; it can be set to "none".
- **Local-first** — speech recognition runs on the local machine; models download on demand and then run offline.

## Intent: the right owner, visibly

On each turn, Intent combines the structured result of transcript repair (IR) with the interaction context frozen at speech start. With a hard claim, context conditions such as crosshair, held item and sneaking come before wake words; participant priority and stable order then decide the owner. Without a hard claim, a previous owner keeps the turn while its lease is valid; otherwise the turn falls back to the default participant.

The pre-winner, owner and lease ring are drawn on the Presence HUD, so the handoff is visible before speaking. The HUD and the arbitration read the same result — one decision, not two parallel systems.

## Models

| Model | Languages | Size | Notes |
|---|---|---|---|
| **SenseVoice ZH/EN/JA/KO/YUE Int8** | Chinese, English, Japanese, Korean, Cantonese | 226 MB | Default recommendation; offline full-utterance, best for push-to-talk |
| **Zipformer Multilingual Int8** | Arabic, English, Indonesian, Japanese, Russian, Thai, Vietnamese, Chinese | 324 MB | The only streaming model in the catalogue, built for continuous input |
| **Whisper Turbo Multilingual** | Chinese, English, Japanese, Korean, French, German, Russian, Spanish | 1.25 GB | Widest language coverage; highest size and CPU demand |

Models download on demand and then run offline; they can be switched in the settings without reinstalling.

## Third-party participants

Other mods can register a `DialogueParticipant` and declare claims such as wake words, crosshair targets, held items, equipment or interaction state. Intake collects the claims, picks the owner, creates the turn session and delivers it only to the winner. See the [external integration guide](../EXTERNAL_INTEGRATION_GUIDE.md) and the [IA participant guide](../doc/function/intake/ia/IA_外部模组仲裁接入说明.md).

## License and credits

This mod is open source under the GNU Lesser General Public License v3.0 (© 2026 Ero-Dragon).

The speech runtime and models come from public projects and remain the property of their respective authors:

- sherpa-onnx — k2-fsa
- SenseVoice — FunAudioLLM
- Zipformer / Icefall — k2-fsa
- Whisper — OpenAI
- ONNX Runtime — Microsoft
- pinyin4j — pinyin index

Models download on demand from the model catalogue on first use.

More: [ASR architecture](../doc/function/intake/ASR_模块架构说明.md) · [IR architecture](../doc/function/intake/repair/IR_模块架构说明.md) · [IA technical guide](../doc/function/intake/ia/IA_模块开发技术说明.md) · [In-game test guide](../doc/function/intake/听澜游戏内测试说明.md)