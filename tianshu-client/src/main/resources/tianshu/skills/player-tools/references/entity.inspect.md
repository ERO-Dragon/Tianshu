# minecraft:entity.inspect

按实体 id 读：类型、名字、位置、维度、血量、是否驯服及归属。

## 调用

```json
{"id":"minecraft:entity.inspect","args":{"entity":"entity-42"}}
```

## 返回

```json
{"ok":true,"result":{"type":"minecraft:wolf","name":"Wolf","position":{"x":120,"y":64,"z":-30},"health":20.0,"tame":true,"owner":"changsui"},"durationMs":3}
```

实体 id 从 `entity.scan` 的 entities 里拿，不要自己编。
