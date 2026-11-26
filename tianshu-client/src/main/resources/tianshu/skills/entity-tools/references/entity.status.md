# minecraft:entity.status

读取这具身体的维度、位置、朝向与生存状态。

## 调用示例

```json
{"id":"minecraft:entity.status","args":{"body":"worker-1"}}
```

## 返回示例

```json
{"ok":true,"result":{"id":"worker-1","kind":"body","dimension":"minecraft:overworld","x":118.5,"y":64,"z":-30.5,"yaw":0,"pitch":0,"health":20,"maxHealth":20,"foodLevel":20,"saturation":5,"air":300,"onFire":false,"inWater":false,"alive":true,"gameMode":"","message":""}}
```

坐标配合 dimension 使用；查看背包请调用 entity.inventory。
