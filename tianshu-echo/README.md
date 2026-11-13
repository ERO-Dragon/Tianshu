# Echo · 回响

Echo is the text-to-speech capability on the Ferry base: it turns text into speech on the local machine, and once a model has been downloaded it works offline. It is open to every module — read a whole document, speak each sentence as it is generated, or take the audio and play it yourself.

**Game versions**: unrestricted. Distributed as a single universal jar with both NeoForge and Fabric entrypoints; per-version compatibility is handled by Tianshu.
**Loaders**: NeoForge / Fabric
**Dependencies**: Ferry and Foundry; Presence is optional — with it installed, playback state shows on the HUD.
**Callers**: every module. Tianshu (AX) is one caller; other mods can connect directly.

---

## What it gives you

- **Speaks offline** — one model download, then synthesis stays on the local machine.
- **A voice for your machine** — four models from 178 MB to 764 MB: the lightest starts almost instantly, the heaviest sounds closest to a person.
- **Your own voice** — clone-capable models accept a WAV recording as a reference voice, and speech comes out in that voice.
- **Speaks while writing** — feed sentences as they are generated and playback starts with the first finished sentence, instead of waiting for the whole text.
- **Audio only, if you prefer** — Echo can return PCM and let the caller decide how and when to play it.
- **Interrupts and queues** — drop when busy, queue, insert after the current document or sentence, cut the current sentence, or cut the whole document.
- **One voice per caller** — each caller manages its own voices; model choice is a host-level setting.

## How it speaks

- **Whole document** (`DOCUMENT`) — give the complete text at once; the simplest path.
- **Sentence stream** (`SENTENCE_STREAM`) — the caller has already split sentences; one packet per sentence, spoken as it arrives.
- **Raw text stream** (`RAW_TEXT_STREAM`) — send arbitrary fragments; Echo buffers partial sentences, keeps whitespace across packets, and flushes on end of stream.
- **Insert and interrupt** — a new request can drop when busy, queue, insert after the current document or sentence, cut the current sentence, or cut the whole document; nested inserts resume in most-recent-first order.

## Choosing a model

| Model | Languages | Size | Quality | Performance | Recommendation | Custom voice |
|---|---|---|---|---|---|---|
| **PocketTTS ZH 4L** | Chinese | 178 MB | 7/10 | 9/10 | 9/10 | ✔ |
| **MOSS-TTS Nano 100M** | Chinese / English | 764 MB | 9/10 | 4/10 | 8/10 | ✔ |
| **MeloTTS zh/en** | Chinese / English | 231 MB | 7/10 | 8/10 | 7/10 | — |
| **Kokoro zh/en v1.1** | Chinese / English | 427 MB | 8/10 | 8/10 | 6/10 | — |

The three scores are the same data the settings page sorts by, and the table is ordered by recommendation. PocketTTS is the everyday choice — smallest and fastest, and clone-capable for Chinese; MOSS sounds better at the cost of CPU; MeloTTS and Kokoro sit in between, with Kokoro stronger on multiple voices and mixed Chinese/English.

Models are downloaded from the settings page, with pause, resume, cancel and delete; downloads can resume across restarts when the server supports Range. Models download on demand and then run offline.

## Voices, speed and settings

- Speed from 0.1 to 5.0, with immediate preview.
- Multi-speaker models can switch voices by speaker id (Kokoro ships several).
- Clone-capable models: add a WAV to the voice library and pick it as the clone voice; the settings page says so when the current model cannot clone.
- Streaming synthesis is on by default and can be turned off for whole-document synthesis.
- An explicitly named voice that does not exist fails the request instead of quietly falling back to another voice.

## Third-party integration

- `TTS_SPEAK` — synthesize and play, with three input modes and six placements.
- `TTS_SYNTHESIZE` — synthesize only, delivering complete PCM that the caller ACKs and plays.
- `TTS_CONTROL` — stop, reload the model, manage voices.
- `TTS.PLAYBACK` / `TTS_REQUEST_STATUS` — playback state and per-request state.

Voices belong to the caller; model choice is a host-level setting. See the [TTS protocol guide](../doc/function/echo/TTS_协议中心使用文档.md) for the full contract.

## License and credits

This mod is open source under the GNU Lesser General Public License v3.0 (© 2026 Ero-Dragon).

Voice models come from public projects on Hugging Face, downloaded on demand, and remain the property of their respective authors:

- Kokoro — `csukuangfj/kokoro-multi-lang-v1_1`
- MeloTTS — `csukuangfj/vits-melo-tts-zh_en`
- MOSS-TTS Nano — `OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX` (audio tokenizer: `MOSS-Audio-Tokenizer-Nano-ONNX`)
- PocketTTS — `hustfyb/pocket-tts-zh-4l-onnx`

Runtime from public projects: sherpa-onnx (k2-fsa), ONNX Runtime (Microsoft, provided through Foundry).
