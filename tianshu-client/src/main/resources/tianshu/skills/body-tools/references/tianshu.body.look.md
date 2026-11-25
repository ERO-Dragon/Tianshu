# 转向

`tianshu:body.look` 给 `yaw`/`pitch`，或给一个目标坐标。

- `body.mine` 作用在视线与可触及距离内的方块上，所以挖之前先看是必要的，不是礼貌。
- 转向成功后，`body.scan` 的结果仍然按世界坐标给，不随朝向变化。

## 范例

```json
{"id":"tianshu:body.look","args":{"x":118,"y":52,"z":-30}}
```

```json
{"ok":true,"result":{"yaw":-135.0,"pitch":40.2},"durationMs":3}
```
