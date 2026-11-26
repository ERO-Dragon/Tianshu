# minecraft:client.audio.list

列出当前客户端音量通道。

## 调用示例

```json
{"id":"minecraft:client.audio.list","args":{}}
```

## 返回示例

```json
{"ok":true,"result":[{"id":"music","label":"Music","percent":35}]}
```

通道 ID 从 client.audio.list 读取，音量采用 0–100 的百分比。
