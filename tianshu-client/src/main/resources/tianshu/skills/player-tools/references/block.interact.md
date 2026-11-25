# minecraft:block.interact

按下按钮、扳动拉杆、打开门/活板门/栅栏门。作用相当于右手点一下那个方块。

## 调用

```json
{"id":"minecraft:block.interact","args":{"x":120,"y":64,"z":-30}}
```

## 返回

```json
{"ok":true,"result":{"block":"minecraft:lever","powered":true},"durationMs":5}
```

## 失败与下一步

- 方块不可交互 → 换 `block.inspect` 先看它到底是什么。
- 够不着 → 先靠近；门在后面时先绕过去，别反复点。
