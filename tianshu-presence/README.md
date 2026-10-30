# 映迹（tianshu-presence）

可选 universal 功能模组：把世界状态整理成模块可用的事实，聚合成产品主状态，并绘制游戏内 HUD。模块本身不含平台代码；Minecraft 落笔在 `tianshu-adapter`，通过 api 的 HUD 端口接入。

## 文档

- [映迹文档索引](../doc/function/presence/Presence_文档索引.md)
- [HUD 浏览器预览](tools/hud-preview/README.md)

## 验证

```powershell
.\gradlew.bat :tianshu-presence:test
.\gradlew.bat :tianshu-presence:check
```

修改 HUD 后重新生成预览模型与着色器块：

```powershell
.\gradlew.bat :tianshu-presence:exportHudPreviewAssets
```
