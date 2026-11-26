# minecraft:entity.move

将身体移动到指定维度内的绝对坐标。

## 调用示例

```json
{"id":"minecraft:entity.move","args":{"body":"worker-1","x":118,"y":64,"z":-31,"mode":"walk"}}
```

## 返回示例

```json
{"ok":true,"result":{"outcome":"DONE","detail":"ok","position":{"dimension":"minecraft:overworld","x":118.5,"y":64,"z":-30.5}}}
```

省略 mode 时使用 any。生存档从身体声明的原生方式中选择，可用方式见 entity.list 的 nativeMovementModes。显式指定额外移动方式需要作弊档。要求步行时显式传 walk。读取 outcome 和实际 position；失败后先检查目的地与身体是否同维度、路径是否可达。
