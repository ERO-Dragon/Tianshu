# minecraft:client.audio.set

设置当前客户端音量通道。

## 调用示例

```json
{"id":"minecraft:client.audio.set","args":{"channel":"music","volume":35}}
```

## 返回示例

```json
{"ok":true,"result":{"id":"music","label":"Music","percent":35}}
```

通道 ID 从 client.audio.list 读取，音量采用 0–100 的百分比。
