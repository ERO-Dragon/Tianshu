# minecraft:entity.look

让身体面向指定世界坐标。

## 调用示例

```json
{"id":"minecraft:entity.look","args":{"body":"worker-1","x":118,"y":52,"z":-30}}
```

## 返回示例

```json
{"ok":true,"result":{"outcome":"DONE","detail":"ok"}}
```

目标在另一维度时重新确认身体位置。返回值给出动作结果，实际朝向可以从 entity.status 读取。
