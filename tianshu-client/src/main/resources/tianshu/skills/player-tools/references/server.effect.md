# minecraft:server.effect

给予玩家状态效果，或清除效果。

## 调用示例

```json
{"id":"minecraft:server.effect","args":{"effect":"minecraft:speed"}}
```

## 返回示例

```json
{"ok":true,"result":{"ok":true,"detail":"effect minecraft:speed for 30s"}}
```

适配器默认持续 30 秒，效果放大值为 0。effect 为 clear 会清除全部效果。
