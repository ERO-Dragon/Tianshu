# minecraft:server.say

以玩家身份发送普通聊天消息。

## 调用示例

```json
{"id":"minecraft:server.say","args":{"text":"我在矿洞口，需要火把"}}
```

## 返回示例

```json
{"ok":true,"result":{"ok":true}}
```

text 为要发送的一行文字。连接不可用时，消息无法送达。
