# minecraft:entity.command.run

让一具身体申请执行一条命令：第一次调用不执行，只返回一次性令牌；操作员执行 `/tianshu approve <token>` 后，带上令牌用同一条 `line` 重发才会真正执行。`body` 省略时由调用者指定的那具身体执行；由身体自己调用时可省略。

## 调用

```json
{"id":"minecraft:entity.command.run","args":{"body":"changsui","line":"gamemode creative"}}
```

## 返回

```json
{"ok":true,"result":{"status":"PENDING_APPROVAL","output":"","message":"this command needs an operator's approval: /tianshu approve 7F3A9C21","approvalToken":"7F3A9C21"},"durationMs":3}
```

## 失败与下一步

- `PENDING_APPROVAL` 不是失败：把 `approvalToken` 交给操作员执行 `/tianshu approve …`，再加 `"approval":"7F3A9C21"` 重发一次；令牌绑定"这具身体 + 这条命令"，单次有效、5 分钟过期，`line` 差一个字都对不上。
- `FAILED` / 状态 `REJECTED`（令牌未知、过期、已用过、与这具身体或这条命令不匹配）；`NO_PERMISSION` / 服务器只让身体做生存动作，而本工具要求 `PRIVILEGED` → 重新申请，权限不够就由操作员自己跑这条命令。
