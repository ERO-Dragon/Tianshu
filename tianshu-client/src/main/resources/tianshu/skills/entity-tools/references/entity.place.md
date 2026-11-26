# minecraft:entity.place

让一具身体用**它自己的背包**放一个方块到指定位置，朝向与消耗按原版规则；`face` 可选（`up`/`down`/`north`/`south`/`east`/`west`），省略时由身体按自己的站位决定朝向。`body` 省略时由调用者指定的那具身体执行；由身体自己调用时可省略。

## 调用

```json
{"id":"minecraft:entity.place","args":{"body":"changsui","item":"minecraft:torch","x":120,"y":64,"z":-30,"face":"up"}}
```

## 返回

```json
{"ok":true,"result":{"blockId":"minecraft:torch","x":120,"y":64,"z":-30,"placed":true,"message":""},"durationMs":6}
```

## 失败与下一步

- `FAILED` / 消息来自游戏本身：`the actor has no minecraft:xxx`（它背包里没有）、`the block is out of reach`（够不着）、`that chunk is not loaded`、`that agent body has no inventory` → 分别确认携带、靠近、等区块加载、换一具有背包的身体；重发同一个坐标不会变好。
- `UNSUPPORTED_ACTOR` / `agent body … cannot place`：这具身体没有 `PLACE` 能力位 → 换一具身体；`body.target.required` 则说明是模块在调用却没点名身体。
