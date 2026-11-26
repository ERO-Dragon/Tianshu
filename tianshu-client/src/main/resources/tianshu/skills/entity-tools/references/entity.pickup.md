# minecraft:entity.pickup

拾取身体周围的掉落物。

## 调用示例

```json
{"id":"minecraft:entity.pickup","args":{"body":"worker-1","radius":3}}
```

## 返回示例

```json
{"ok":true,"result":{"items":[{"itemId":"minecraft:raw_iron","count":3}]}}
```

按 items 统计实际拾取量。为空时检查掉落物距离及背包空间。
