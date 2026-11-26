# minecraft:entity.craft

让一具身体按配方 id 合成一次，材料从**它自己的背包**里扣，不做"自动凑料"。`body` 省略时由调用者指定的那具身体执行；由身体自己调用时可省略。

## 调用

```json
{"id":"minecraft:entity.craft","args":{"body":"changsui","recipeId":"minecraft:iron_pickaxe"}}
```

## 返回

```json
{"ok":true,"result":{"itemId":"minecraft:iron_pickaxe","count":1,"crafted":true,"message":"crafted"},"durationMs":12}
```

## 失败与下一步

- `FAILED` / 消息就是原因（`the actor is missing ingredients`、`a crafting table is required nearby`、`no such recipe`、`only crafting-table recipes are supported`）→ 先补材料或站到工作台旁；配方 id 不确定就先用 `entity.craft.available` 看它现在能做什么，别猜 id。
- `UNSUPPORTED_ACTOR` / `agent body … cannot inventory`：身体没有 `INVENTORY` 能力位 → 换一具身体；一次调用只做一次，要多个就重复调用。
