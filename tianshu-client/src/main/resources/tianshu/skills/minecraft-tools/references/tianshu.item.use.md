# tianshu:item.use

用主手物品：对空气用（扔雪球、吃东西）或对一个方块面用。只支持即时生效的用法。

## 调用

```json
{"id":"tianshu:item.use","args":{"x":120,"y":64,"z":-30,"face":"up","holdTicks":0}}
```

## 返回

```json
{"ok":true,"result":{"used":"minecraft:snowball","consumed":1},"durationMs":5}
```

需要蓄力的用法（拉弓、吃东西）不在这个工具里：它不是"万能右键"。
