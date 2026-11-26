# minecraft:craft.available

列出你现在能做的配方，并标出哪些需要工作台。没有 `body` 参数：这问的是**你自己的**合成能力。

## 调用

```json
{"id":"minecraft:craft.available","args":{"limit":10}}
```

## 返回

```json
{"ok":true,"result":{"recipes":[{"id":"minecraft:stick","needsTable":false}],"limit":10},"durationMs":9}
```

返回值里没有 `truncated` 字段，只有 `limit`（你传的那个）：**`recipes.size() == limit` 就说明可能还有更多**，
加大 `limit`（省略按 20，最多 64）再问一次，不要以为"只有这些"。

## 失败与下一步

- `UNAVAILABLE`：这一端没有配方查询组件（客户端未连服务端时常见）——进世界或连服务端再用。
- 列表里没有你想要的配方：先确认材料在背包里（`minecraft:inventory.inspect`），再看是否需要工作台。