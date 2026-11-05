# Echo (回响)

Echo is the local text-to-speech module for Tianshu. It converts text into speech on the local machine; once a model has been downloaded, it works without an internet connection.

> Echo requires the Tianshu base (Beichen) and cannot be used on its own.

**Game versions**: unrestricted. Echo is distributed as a single jar, and per-version compatibility is handled by Beichen.
**Loaders**: NeoForge / Fabric

---

## Features

- **Text to speech** — any module that needs text-to-speech can call Echo.
- **Custom voice** — a WAV recording can be imported as a reference voice, and speech is then read in that voice.
- **Adjustable parameters** — voice and speed can be adjusted in the settings, with immediate preview.
- **Selectable playback** — Echo can play directly, or output audio only for the caller to handle. Playback control is provided by Beichen, and both local and server playback are supported.
- **Offline use** — after a single model download, no connection is required.
- **Single distribution** — one jar serves all game versions; no per-version downloads.

## Choosing a model

| Model | Languages | Size | Quality | Speed | Custom voice | Overall |
|---|---|---|---|---|---|---|
| **PocketTTS ZH 4L** | Chinese | 178 MB | 7/10 | 9/10 | ✔ | ① Smallest and fastest; the everyday choice |
| **MOSS-TTS Nano 100M** | Chinese / English | 764 MB | 9/10 | 4/10 | ✔ | ② Best sound quality; highest CPU demand |
| **Kokoro zh/en v1.1** | Chinese / English | 427 MB | 8/10 | 8/10 | — | ③ Reliable for mixed Chinese and English |
| **MeloTTS zh/en** | Chinese / English | 231 MB | 7/10 | 8/10 | — | ④ Compact |

Model files must be downloaded over the internet; once downloaded, they can be used offline.

The overall ranking weighs size, speed and voice cloning together; on sound quality alone, MOSS ranks highest. Quality and speed ratings match those shown on the in-game settings page. Listening to a preview before deciding is recommended; models can be switched at any time without reinstalling.

## License and credits

This mod is open source under the MIT license (© 2026 Ero-Dragon).

The bundled voice models come from public projects on Hugging Face and remain the property of their respective authors:

- Kokoro — `csukuangfj/kokoro-multi-lang-v1_1`
- MeloTTS — `csukuangfj/vits-melo-tts-zh_en`
- MOSS-TTS Nano — `OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX`
- PocketTTS — `hustfyb/pocket-tts-zh-4l-onnx`

If you are developing other mods, Echo can also read text on their behalf; see Tianshu's *External Integration Guide*.