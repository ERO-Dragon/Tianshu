# minecraft:capability.read

列目录，或读一份文档。不带 `id` 列出可读的东西；带 **工具 id** 读这个工具自己的文档；带技能 id 读技能正文。

## 调用

```json
{"id":"minecraft:capability.read","args":{"id":"minecraft:block.break"}}
```

## 返回

```json
{"ok":true,"result":{"text":"# minecraft:entity.mine\n..."},"durationMs":3}
```

## 读法

- 长文档分页：带 `page`（从 1 开始），返回里会说明还有没有下一页。
- 技能的参考文件：带 `file`（相对技能根目录，例如 `references/x.md`）。
- **参数不确定先读工具文档，不要猜字段名**：schema 在同一份文档里。
