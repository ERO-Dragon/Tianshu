# minecraft:server.setblock

在指定坐标设置一个方块。

## 调用示例

```json
{"id":"minecraft:server.setblock","args":{"x":120,"y":64,"z":-30,"block":"minecraft:stone"}}
```

## 返回示例

```json
{"ok":true,"result":{"ok":true,"detail":"placed minecraft:stone at 120 64 -30"}}
```

核对目标位置和方块 ID，已有方块可能被替换。
