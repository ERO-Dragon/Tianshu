# 读状态

`minecraft:entity.status` 给出位置、维度、血量、饥饿、手上物品、是否存活。

- 动手之前先读它：坐标、维度、手上有没有合适的工具，都能在这里看到。
- 血量或饥饿低到危险时先补给；死掉的身体会掉东西，这比"晚一步"贵得多。

## 范例

```json
{"id":"minecraft:entity.status","args":{}}
```

```json
{"ok":true,"result":{"position":{"x":118.5,"y":52,"z":-30.5},"dimension":"minecraft:overworld","health":20.0,"hunger":18},"durationMs":2}
```
