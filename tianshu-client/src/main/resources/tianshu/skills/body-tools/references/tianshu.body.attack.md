# 攻击

`tianshu:body.attack` 对目标实体造成伤害。

- 这会真的掉血，也真的会拉仇恨：不确定时先 `body.scan` 看周围有多少同类。
- 手上没有武器时伤害很低——先 `body.equip`。
- 打到不该打的东西（村民、宠物）是真实后果，没有撤销。

## 范例

```json
{"id":"tianshu:body.attack","args":{"entity":"entity-42"}}
```

```json
{"ok":true,"result":{"target":"entity-42","damage":6.0,"remainingHealth":14.0},"durationMs":5}
```
