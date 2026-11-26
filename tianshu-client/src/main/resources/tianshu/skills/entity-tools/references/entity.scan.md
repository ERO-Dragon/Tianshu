# minecraft:entity.scan

在身体周围按方块 ID 或实体类型筛选目标。

## 调用示例

```json
{"id":"minecraft:entity.scan","args":{"body":"worker-1","radius":16,"blocks":"minecraft:iron_ore"}}
```

## 返回示例

```json
{"ok":true,"result":{"blocks":[{"blockId":"minecraft:iron_ore","dimension":"minecraft:overworld","x":118,"y":64,"z":-30}],"entities":[]}}
```

blocks 与 entities 至少提供一项筛选条件。动作坐标来自 blocks；实体动作使用 entities 中的 entityId。
