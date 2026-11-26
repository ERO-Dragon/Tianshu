# minecraft:entity.mine

用身体当前装备挖掘目标方块。

## 调用示例

```json
{"id":"minecraft:entity.mine","args":{"body":"worker-1","x":118,"y":52,"z":-30}}
```

## 返回示例

```json
{"ok":true,"result":{"outcome":"DONE","detail":"ok"}}
```

检查 outcome 与 detail。需要采集掉落时再执行 entity.pickup。
