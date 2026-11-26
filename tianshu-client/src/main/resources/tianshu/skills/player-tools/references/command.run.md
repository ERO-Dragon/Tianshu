# minecraft:command.run

以**你自己的玩家身份**执行一条命令。**第一次调用不会执行**：它返回一次性令牌，由操作员批准。

## 调用

```json
{"id":"minecraft:command.run","args":{"line":"gamemode creative"}}
```

## 返回（申请）

```json
{"ok":true,"result":{"status":"PENDING_APPROVAL","output":"","approvalToken":"7F3A9C21","message":"/tianshu approve 7F3A9C21"}}
```

操作员批准后，带令牌重试一次：

```json
{"id":"minecraft:command.run","args":{"line":"gamemode creative","approval":"7F3A9C21"}}
```

```json
{"ok":true,"result":{"status":"EXECUTED","output":"Set own game mode to Creative Mode","message":"","approvalToken":""}}
```

## 失败与下一步

- `FAILED`：令牌未知、过期、已用过，或不是为这条命令批的——**不要拿旧令牌换一条命令**。
- 令牌绑定"谁"和"哪一行"，同一个人对同一行重复申请会拿回同一个令牌。
- 权限不够的命令会看起来像"未知命令"：消息里会说明当前权限级别与该改哪个设置。
- 一条令牌只跑一次；要再跑就重新申请。让**身体**跑命令用 `minecraft:entity.command.run`。
