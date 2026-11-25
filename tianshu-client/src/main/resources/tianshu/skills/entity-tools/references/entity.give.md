# 交付

`minecraft:entity.give` 把背包里的物品交给够得着的目标（玩家或容器）。

- 距离不够会失败——先靠近，不要反复重试同一次调用。
- 容器的一面必须是可放入的；放不进去会说明原因。
- 交付是不可逆的：东西换到别人包里了。

## 范例

```json
{"id":"minecraft:entity.give","args":{"slot":36,"count":8,"target":"changsui"}}
```

```json
{"ok":true,"result":{"given":"minecraft:bread x8","to":"changsui"},"durationMs":7}
```

`to` 是实体 id 时先 `minecraft:entity.inspect` 确认它是谁。
