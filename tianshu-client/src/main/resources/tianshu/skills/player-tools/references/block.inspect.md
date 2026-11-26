# minecraft:block.inspect

读取指定坐标的方块状态和可见存储内容。

## 调用示例

```json
{"id":"minecraft:block.inspect","args":{"x":120,"y":64,"z":-30}}
```

## 返回示例

```json
{"ok":true,"result":{"blockId":"minecraft:chest","blockEntityId":"minecraft:chest","state":{"facing":"north"},"items":[{"slot":0,"itemId":"minecraft:iron_ingot","count":12}],"fluids":[],"energy":{"stored":0,"capacity":0},"message":""}}
```

用 state 判断状态，用 items 查看物品；能量与流体信息取决于方块。
