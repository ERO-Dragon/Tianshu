# tianshu:client.audio.list

列出客户端音量通道（master、music、weather、block……）与各自百分比。

## 调用

```json
{"id":"tianshu:client.audio.list","args":{}}
```

## 返回

```json
{"ok":true,"result":{"channels":[{"id":"master","percent":100},{"id":"music","percent":40}]},"durationMs":2}
```

通道 id 用这里的原字符串去调 `client.audio.set`，不要自己改大小写。
