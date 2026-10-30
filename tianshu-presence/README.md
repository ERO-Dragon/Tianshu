# 映迹（tianshu-presence）

可选 universal 功能模组：把世界状态整理成模块可用的事实，聚合成产品主状态，并绘制游戏内 HUD。模块本身不含平台代码；Minecraft 落笔在 `tianshu-adapter`，通过 api 的 HUD 端口接入。

## 文档

- [映迹文档索引](../doc/function/presence/Presence_文档索引.md)
- [HUD 浏览器预览](tools/hud-preview/README.md)

## 发布后独立更新

shader 资产、`PresenceHudProgram` 的 uniform 绑定、`PresenceHudShaderParams` 参数以及预设枚举都在映迹 jar 内。
北辰只提供 `HudProgram` / `HudUniformSink` 端口，编译映迹声明的资产并调用 binder；它不认识 uniform 名，也没有固定参数表。
因此新增/改名 uniform、增加参数或预设都只需要更新映迹，不需要更新北辰。当前 sink 支持 float、int、vec2、vec3、vec4；
如果将来需要矩阵等新类型，才需要扩展 api。

## 验证

```powershell
.\gradlew.bat :tianshu-presence:test
.\gradlew.bat :tianshu-presence:check
```

修改 HUD 后重新生成预览模型与着色器块：

```powershell
.\gradlew.bat :tianshu-presence:exportHudPreviewAssets
```
