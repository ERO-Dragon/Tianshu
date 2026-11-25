# tianshu:item.inspect

读一个槽位里的物品：id、数量、剩余耐久、附魔、自定义名等。

## 调用

```json
{"id":"tianshu:item.inspect","args":{"slot":36}}
```

## 返回

```json
{"ok":true,"result":{"item":"minecraft:iron_pickaxe","count":1,"damage":3,"remainingDurability":248,"enchantments":{"minecraft:efficiency":2}},"durationMs":2}
```

空槽位是"带原因的拒绝"，不是空对象：先看 `message` 再决定是不是槽位给错了。
