# minecraft:block.machine

操作炉子一类的机器：`insert_fuel`（放燃料）、`insert_input`（放原料）、`take_output`（取产物）。

## 调用

```json
{"id":"minecraft:block.machine","args":{"x":120,"y":64,"z":-30,"operation":"insert_input","playerSlot":36}}
```

## 返回

```json
{"ok":true,"result":{"operation":"insert_input","moved":1,"slot":0},"durationMs":9}
```

## 失败与下一步

- `take_output` 空手回来：产物还没出来，等一会儿或先看 `block.inspect` 的进度。
- 槽位不对：`playerSlot` 用 `entity.inventory` / `inventory.inspect` 的索引，别自己另算。
