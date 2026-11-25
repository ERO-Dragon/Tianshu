# tianshu:block.inspect

读一个方块，不打开它：id、状态，以及它装的物品、流体、能量。箱子类容器也能读，不需要先打开界面。

## 调用

```json
{"id":"tianshu:block.inspect","args":{"x":120,"y":64,"z":-30}}
```

## 返回

```json
{"ok":true,"result":{"id":"minecraft:chest","state":{"facing":"north"},"items":[{"slot":0,"item":"minecraft:diamond","count":5}]},"durationMs":3}
```

普通箱子不会谎报能量：`energy` 只在真有能量存储时出现。
