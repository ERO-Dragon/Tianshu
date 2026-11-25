# minecraft:tool.catalog

取工具表与参数 schema。**参数不确定时先读它，不要猜字段名。**

## 调用

```json
{"id":"minecraft:tool.catalog","args":{}}
```

## 返回

```json
{"ok":true,"result":{"tools":[{"id":"minecraft:entity.mine","description":"...","enabled":true,"argsSchema":{}}]},"durationMs":2}
```

表里只出现你能用的工具：看不见的不会出现，出现了也调不通——不要凭记忆调用。
