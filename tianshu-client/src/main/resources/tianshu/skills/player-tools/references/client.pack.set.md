# minecraft:client.pack.set

按 id 启用或禁用一个材质包，然后应用（客户端会重载资源，需要一会儿）。

## 调用

```json
{"id":"minecraft:client.pack.set","args":{"pack":"file/mypack.zip","enabled":false}}
```

## 返回

```json
{"ok":true,"result":{"pack":"file/mypack.zip","enabled":false},"durationMs":4}
```

必选的材质包不能关：会被拒绝，不要靠重试绕过。
