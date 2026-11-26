# minecraft:entity.command.run

以身体身份申请执行命令。

此工具需要 NPC 作弊档，并由操作员逐次批准命令。

## 调用

```json
{"id":"minecraft:entity.command.run","args":{"body":"worker-1","line":"gamemode creative"}}
```

## 返回

```json
{"ok":true,"result":{"status":"PENDING_APPROVAL","output":"","message":"this command needs an operator's approval: /tianshu approve 7F3A9C21","approvalToken":"7F3A9C21"},"durationMs":3}
```

收到 `PENDING_APPROVAL` 时，将 `approvalToken` 交给操作员执行 `/tianshu approve <token>`。批准后用原 `body`、原 `line` 和 `approval` 令牌再调用一次。令牌五分钟内有效，仅可执行一次。操作员拒绝时结束请求。
