# minecraft:block.place

从背包放一个方块到指定位置，朝向与消耗按原版规则。

## 调用

```json
{"id":"minecraft:block.place","args":{"item":"minecraft:torch","x":120,"y":64,"z":-30,"face":"up"}}
```

## 返回

```json
{"ok":true,"result":{"placed":"minecraft:torch","remaining":11},"durationMs":6}
```

`face` 决定贴着哪个面放：地面用 `up`，挂墙用 `north/south/east/west`。
