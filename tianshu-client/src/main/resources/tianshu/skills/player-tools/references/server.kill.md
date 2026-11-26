# minecraft:server.kill

移除玩家所在维度的一具指定实体。

## 调用示例

```json
{"id":"minecraft:server.kill","args":{"id":"a13bd57b-223e-4000-a694-e17ebec9672e"}}
```

## 返回示例

```json
{"ok":true,"result":{"ok":true,"detail":"removed a13bd57b-223e-4000-a694-e17ebec9672e"}}
```

使用目标实体的真实 ID；目标已离开维度时重新确认。
