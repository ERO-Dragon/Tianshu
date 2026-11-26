# minecraft:server.gamerule

设置服务端支持的游戏规则。

## 调用示例

```json
{"id":"minecraft:server.gamerule","args":{"rule":"keepInventory","value":"true"}}
```

## 返回示例

```json
{"ok":true,"result":{"ok":true,"detail":"keepInventory is now true"}}
```

规则名区分大小写；使用服务端接受的布尔值。
