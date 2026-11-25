# minecraft:client.audio.set

把某个音量通道设成 0..100 的百分比。

## 调用

```json
{"id":"minecraft:client.audio.set","args":{"channel":"music","volume":35}}
```

## 返回

```json
{"ok":true,"result":{"channel":"music","percent":35},"durationMs":2}
```

未知通道 id 会被明确拒绝：先用 `client.audio.list` 取准确 id。
