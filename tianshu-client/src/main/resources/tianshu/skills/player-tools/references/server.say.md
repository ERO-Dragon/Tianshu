# minecraft:server.say

以玩家身份在普通聊天里说一句话。任何玩家都能聊天，所以不需要操作员权限。

## 调用

```json
{"id":"minecraft:server.say","args":{"text":"我在矿洞口，需要火把"}}
```

## 返回

```json
{"ok":true,"result":{"said":"我在矿洞口，需要火把"},"durationMs":2}
```

想广播给所有人用 `server.broadcast`（那是作弊级动作，默认关闭）。
