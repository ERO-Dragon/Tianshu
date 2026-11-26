# minecraft:container.transfer

在目标容器和玩家背包之间转移物品。

## 调用示例

```json
{"id":"minecraft:container.transfer","args":{"x":120,"y":64,"z":-30,"containerSlot":0,"playerSlot":2,"count":5,"toPlayer":true}}
```

## 返回示例

```json
{"ok":true,"result":{"itemId":"minecraft:diamond","moved":5,"message":"moved 5"}}
```

toPlayer 为 true 时取出，false 时存入。count 为上限，实际移动量看 moved。
