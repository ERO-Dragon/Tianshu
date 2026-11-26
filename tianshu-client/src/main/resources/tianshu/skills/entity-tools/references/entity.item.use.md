# minecraft:entity.item.use

让一具身体用主手格里的物品：对空气用或对方块面用，只支持即时生效的用法；`x`/`y`/`z` 要么三个都给、要么都不给（都不给＝对空气用），`holdTicks` 最多 100（5 秒），吃东西、拉弓、举盾这类要一直按住的用法会被拒——它不是万能右键。`body` 省略时由调用者指定的那具身体执行；由身体自己调用时可省略。

## 调用

```json
{"id":"minecraft:entity.item.use","args":{"body":"changsui","x":120,"y":64,"z":-30,"face":"up","holdTicks":0}}
```

## 返回

```json
{"ok":true,"result":{"outcome":"DONE","detail":"used minecraft:snowball"},"durationMs":5}
```

## 失败与下一步

- `outcome` 是 `REJECTED` 也仍是成功信封，`detail` 说明原因（`nothing is equipped in the active slot`、`the block is out of reach`、`the target is in another dimension`、`this item has no usable duration`）→ 先 `entity.equip` 换主手或靠近目标，别重发。
- `INVALID_ARGS` / 三个坐标只给了一半、`holdTicks` 大于 100、`face` 拼错 → 补齐或改值；`UNSUPPORTED_ACTOR` 表示身体缺 `USE`/`INVENTORY`。
