# minecraft:client.pack.set

设置当前客户端资源包。

## 调用示例

```json
{"id":"minecraft:client.pack.set","args":{"pack":"file/mypack.zip","enabled":false}}
```

## 返回示例

```json
{"ok":true,"result":{"id":"file/mypack.zip","label":"My Pack","enabled":false,"required":false}}
```

使用列表返回的 id；required 为 true 的包需要保留。
