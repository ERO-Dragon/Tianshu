# tianshu:container.inspect

读玩家当前打开的容器内容，槽位索引与 `container.transfer` 完全一致。

## 调用

```json
{"id":"tianshu:container.inspect","args":{}}
```

## 返回

```json
{"ok":true,"result":{"slots":[{"slot":0,"item":"minecraft:diamond","count":5},{"slot":3,"item":"minecraft:oak_log","count":12}]},"durationMs":2}
```

没有打开任何容器时返回空列表，而不是报错：先确认玩家真的开着箱子。
