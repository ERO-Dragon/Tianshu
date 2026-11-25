# tianshu:craft.recipe

按配方 id 合成一次，消耗背包里的材料。

## 调用

```json
{"id":"tianshu:craft.recipe","args":{"recipeId":"minecraft:crafting_table"}}
```

## 返回

```json
{"ok":true,"result":{"crafted":"minecraft:crafting_table","consumed":[{"item":"minecraft:oak_planks","count":4}]},"durationMs":12}
```

## 失败与下一步

- 材料不够 → 先 `craft.available` 看能做哪些，不要硬试同一个配方。
- 需要工作台 → 先走到工作台旁边再合成。
