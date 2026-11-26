# minecraft:block.interact

操作可触及的按钮、拉杆、门等方块。

## 调用示例

```json
{"id":"minecraft:block.interact","args":{"x":120,"y":64,"z":-30}}
```

## 返回示例

```json
{"ok":true,"result":{"blockId":"minecraft:oak_door","interacted":true,"message":"operated"}}
```

用 interacted 确认是否生效；检查 message 后再决定是否移动或更换目标。
