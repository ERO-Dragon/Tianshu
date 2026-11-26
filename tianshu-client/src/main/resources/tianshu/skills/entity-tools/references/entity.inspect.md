# minecraft:entity.inspect

从身体的位置检查目标实体。

## 调用示例

```json
{"id":"minecraft:entity.inspect","args":{"body":"worker-1","entity":"entity-42"}}
```

## 返回示例

```json
{"ok":true,"result":{"entityId":"entity-42","typeId":"minecraft:wolf","name":"Wolf","dimension":"minecraft:overworld","x":120,"y":64,"z":-30,"health":16,"maxHealth":20,"alive":true,"tamed":false,"ownerId":"","baby":false,"heldItemId":"","message":""}}
```

entity 使用扫描返回的实体 ID。目标离开后重新扫描。
