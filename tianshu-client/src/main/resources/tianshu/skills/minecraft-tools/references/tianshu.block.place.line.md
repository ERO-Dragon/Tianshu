# tianshu:block.place.line

沿一个方向连续放同一种方块，中途失败即停并报告已放数量。

## 调用

```json
{"id":"tianshu:block.place.line","args":{"item":"minecraft:cobblestone","x":120,"y":64,"z":-30,"direction":"east","count":8}}
```

## 返回

```json
{"ok":true,"result":{"placed":8,"stoppedAt":null},"durationMs":51}
```

`stoppedAt` 不为空时说明中途停了：看它是被什么挡住的（实体、边界、没材料），补好再继续。
