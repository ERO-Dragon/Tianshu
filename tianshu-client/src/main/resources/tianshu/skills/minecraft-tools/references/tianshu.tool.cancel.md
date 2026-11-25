# tianshu:tool.cancel

取消一次还在飞的调用（比如一次很远的搜索）。

## 调用

```json
{"id":"tianshu:tool.cancel","args":{"toolId":"tianshu:world.locate"}}
```

## 返回

```json
{"ok":true,"result":{"cancelled":"tianshu:world.locate"},"durationMs":1}
```

只能取消还没结束的调用；已经结束的会说明它已经结束了，这不是错误。
