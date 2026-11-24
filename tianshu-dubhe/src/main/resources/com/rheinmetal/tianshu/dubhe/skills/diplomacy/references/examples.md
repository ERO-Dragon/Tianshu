# 一次完整往返

## 场景

玩家："让女仆把箱子里的原木搬到仓库。"

## 1. 发现

```
relay {"action":"list"}
→ {
    "outcome":"OK",
    "targets":[{"id":"maid:guide","kind":"skill","description":"女仆使用说明"}],
    "taskEntries":[{"id":"maid:task","kind":"connector","description":"女仆任务入口"}]
  }
```

判断：`maid:guide` 是说明书；`maid:task` 才是投递口。

## 2. 看参数

```
relay {"action":"describe","target":"maid:task"}
→ {"outcome":"OK","id":"maid:task","kind":"connector",
   "attributes":{"taskEntry":true,"accepts":"task","returns":"taskId"}}
```

## 3. 投递（只收受理）

```
relay {"action":"submit","target":"maid:task","task":"把主屋箱子里的原木搬到仓库"}
→ {"outcome":"OK","taskId":"t-9","progress":"RUNNING"}
```

回答玩家："我跟女仆说了，让她搬。"（此时只能说"去办了"，不能说"搬完了"）

## 4. 玩家问进度

```
relay {"action":"progress","target":"maid:reader","taskId":"t-9"}
→ {"outcome":"OK","taskId":"t-9","progress":"DONE"}
```

回答："搬完了。"

## 4'. 对方没说进展

```
→ {"outcome":"OK","taskId":"t-9","progress":"UNKNOWN"}
```

回答："女仆没说进展，要不我再去问问？"（不猜"办完了"）

## 4''. 还在办

```
→ {"outcome":"OK","taskId":"t-9","progress":"RUNNING"}
```

回答："还在搬。"

## 记住的三件事

- `taskId`：`t-9`
- 读工具：`maid:reader`
- 玩家原话："让女仆把箱子里的原木搬到仓库"

## 常见错误

| 错误 | 后果 |
|---|---|
| 把 `maid:guide` 当投递口 | 任务变成一段文字，没人执行 |
| 投完说"搬完了" | 对玩家撒谎 |
| 每轮都查进度 | 打扰对方，变成第三个触发源 |
| `UNKNOWN` 说成完成 | 谎报 |