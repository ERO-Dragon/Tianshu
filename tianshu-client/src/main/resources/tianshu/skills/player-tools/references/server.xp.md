# minecraft:server.xp

改玩家经验等级。这是作弊级动作：需要 `FULL` 档与服务端开关，默认关闭。

## 调用

```json
{"id":"minecraft:server.xp","args":{"levels":5}}
```

## 返回

```json
{"ok":true,"result":{"levels":12},"durationMs":3}
```

被拒时看 `code`：`DISABLED`（服务端没开）与 `NO_PERMISSION`（权限不够）修法不同。
