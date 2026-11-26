# minecraft:entity.list

列出当前身体及其维度、坐标和能力位。

## 调用示例

```json
{"id":"minecraft:entity.list","args":{}}
```

## 返回示例

```json
{"ok":true,"result":{"bodies":[{"id":"worker-1","operator":false,"dimension":"minecraft:overworld","x":118.5,"y":64,"z":-30.5,"capabilities":["MOVE"]}]}}
```

用 capabilities 判断身体支持的动作。模组驱动时还须满足归属检查。
