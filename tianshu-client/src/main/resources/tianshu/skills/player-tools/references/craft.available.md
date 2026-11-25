# minecraft:craft.available

列出这具身体现在能做的配方，并标出哪些需要工作台。

## 调用

```json
{"id":"minecraft:craft.available","args":{"limit":10}}
```

## 返回

```json
{"ok":true,"result":{"recipes":[{"id":"minecraft:stick","needsTable":false}],"truncated":true},"durationMs":9}
```

`truncated:true` 说明结果被 `limit` 截断：要更多就加大 `limit`，不要以为"只有这些"。
