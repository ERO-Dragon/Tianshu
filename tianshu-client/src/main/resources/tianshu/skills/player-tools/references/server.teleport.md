# minecraft:server.teleport

将玩家传送到当前维度的绝对坐标。

## 调用示例

```json
{"id":"minecraft:server.teleport","args":{"x":120,"y":70,"z":-30}}
```

## 返回示例

```json
{"ok":true,"result":{"ok":true,"detail":"teleported"}}
```

先核对维度和落点。
