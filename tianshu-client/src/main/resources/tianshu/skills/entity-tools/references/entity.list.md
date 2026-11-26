# 身体清单

`minecraft:entity.list` 列出所有身体及其 id。

- 列的是**全部**身体，不需要 `body` 参数——它本来就是在问"有哪些身体"。
- 多具身体同时存在时，先确认 id 再下指令——指令下错身体是真实发生过的错误。

## 范例

```json
{"id":"minecraft:entity.list","args":{}}
```

```json
{"ok":true,"result":{"bodies":[{"id":"body-1","position":{"x":118,"y":52,"z":-30},"canMove":true}]},"durationMs":2}
```
