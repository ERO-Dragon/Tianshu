# tianshu:inventory.inspect

列出本地玩家背包里**非空**的槽位（不是全部槽位）。

## 调用

```json
{"id":"tianshu:inventory.inspect","args":{}}
```

## 返回

```json
{"ok":true,"result":{"slots":[{"slot":0,"item":"minecraft:bread","count":6}]},"durationMs":2}
```

要找空位（比如放东西）时它帮不上：空槽位不会出现在结果里。
