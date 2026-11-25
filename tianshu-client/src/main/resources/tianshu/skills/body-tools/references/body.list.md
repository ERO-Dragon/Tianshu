# 身体清单

`tianshu:body.list` 列出所有身体及其 id。

- 工具不带 `body` 参数时作用于调用者默认的那具；要指定别的一具时，把 id 填进 `body`。
- 多具身体同时存在时，先确认 id 再下指令——指令下错身体是真实发生过的错误。

## 范例

```json
{"id":"tianshu:body.list","args":{}}
```

```json
{"ok":true,"result":{"bodies":[{"id":"body-1","position":{"x":118,"y":52,"z":-30},"canMove":true}]},"durationMs":2}
```
