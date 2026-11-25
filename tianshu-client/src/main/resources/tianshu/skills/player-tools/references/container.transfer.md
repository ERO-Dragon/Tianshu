# minecraft:container.transfer

在一个容器的某个槽位与玩家背包的某个槽位之间移动物品。

## 调用

```json
{"id":"minecraft:container.transfer","args":{"x":120,"y":64,"z":-30,"containerSlot":0,"playerSlot":36,"count":5,"toPlayer":true}}
```

## 返回

```json
{"ok":true,"result":{"moved":5,"item":"minecraft:diamond","from":0,"to":36},"durationMs":7}
```

## 失败与下一步

- `toPlayer:false` 且目标槽位已有别的物品且不可堆叠 → 先清空或换槽位。
- 数量不对：`count` 是上限，实际移动量看 `moved`，不要假设整叠都动了。
