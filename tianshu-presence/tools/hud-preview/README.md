# 映迹 HUD 预览（浏览器版）

模块文档入口见 [Presence 文档索引](../../../doc/function/presence/Presence_文档索引.md)。

不用启动游戏，直接在网页里切换映迹的主状态、预设、图标尺寸与伙伴标记，观察图标、状态文字和整个 HUD 的变化。

双击 `index.html` 即可打开（`file://` 下也能运行，不需要起服务器）。

## 这个工具从哪里取数

- **模型块与着色器块是生成出来的**：`index.html` 里的 `<script id="presence-hud-model">` 和四个 `<script id="shader-…">`
  由 `HudPreviewAssetExporter` 从 `tianshu-presence` 的真实源码生成——
  状态参数（`PresenceHudVisualParameters`）、状态映射、布局常量、过渡时长、渲染器颜色/透明度/限流常量、
  七条状态文案与三份 `.fsh` 原文。重新生成：

  ```powershell
  .\gradlew.bat :tianshu-presence:exportHudPreviewAssets
  ```

- `PresenceHudPreviewAssetTest` 会比对页面里的生成块与源码；改了 HUD 却忘了重新生成时，`check` 会失败而不是让页面悄悄过期。

- **手写部分是渲染过程本身**：`renderer.js` 只保留示意背景、状态文字面板和伙伴标记（它本来就是贴图 + 租约环）；
  图标完全由 `.fsh` 绘制。`app.js` 复刻 `PresenceHudVisualTransition` 的 240 ms 状态过渡 / 700 ms loading 交接与
  `PresencePartnerIconRenderer` 的入退场、租约环。伙伴环的密度公式来自 `PresenceHudShapes`，改公式要同步这里。

## 对照关系

| 页面 | Java |
|---|---|
| 主状态（含正在听）/ 后台标记 | `PresenceActivitySnapshot`（`primaryState` / `backgroundTask`） |
| 状态参数 | `PresenceHudVisualParameters.forState` |
| 中心点与尺寸 | `PresenceHudLayout.resolve`（默认锚点在经验条上方） |
| 240 ms / 700 ms 过渡 | `PresenceHudVisualTransition` |
| 图标层 | `PresenceIconElementRenderer` 的 quad + `presence_preset_*.fsh`（预设、聆听环、后台小点同一个片元程序） |
| 状态文字 | `PresenceDisplayPolicy` + `PresenceStatusTextElementRenderer` |
| 伙伴标记 | `PresencePartnerSnapshot` 的租约 + `PresencePartnerIconRenderer` 的环/摆动/入退场 |
| 着色器 | `assets/tianshu_presence/shaders/core/*`（原文，页面只补 `#version 300 es` 与 precision） |

## 页面怎么用

- **主状态**：IDLE / LISTENING / LOADING / THINKING / RESPONDING / ERROR / RECONNECTING。普通切换 240 ms，涉及 loading 的交接 700 ms；
  ERROR 与 RECONNECTING 分别复用 TASK / LOADING 的视觉，只有文字不同。
- **正在听**是主状态（与空闲同级）；**后台任务**才是唯一并行标记。
  `PresencePrimaryState.LISTENING` 与 `PresenceHudVisualState.LISTENING` 直接对应，视觉上是 IDLE 参数加聆听脉冲，不再有单独的 `listening` 布尔。
- **预设一 / 预设二**：两套可选图案，分别对应 `libs/ShaderToy/preset1.glsl` 与 `preset2.glsl`；
  聆听扩散环和后台小点是两者共用的叠加层，加载态使用独立的 liquid loading 程序。
- **加载交接**：进入加载时，当前球体在前半段收成中心实心点，loading 在后半段从点展开成六点；
  退出时反过来，六点收成点，所选预设从点长成球体。同一时刻只画一个程序，中点两层画同一个点。
- **禁用来着色器层**：图标不绘制，等价于游戏内程序缺失/编译失败时的画面（没有 Java 回退层）。
- **暂停 / 倍率**：把过渡放慢或冻住，便于检查中间帧。
- **缩放**：围绕图标中心放大显示（100%–300%），逻辑像素与布局不变；放大是显示层缩放，着色器细节会略软，
  要看 1:1 就切回 100%。
- **伙伴标记**：勾选后看入退场与租约环；图标本身是占位图形，游戏内来自伙伴载荷的贴图 id。
- 右侧读数给出布局、参数、过渡进度、伙伴环、着色器编译状态；「复制当前状态」输出一段可直接贴进 issue 的 JSON。

## 深链

页面支持用查询参数复现一个画面，便于截图与对照：

```
index.html?primary=LISTENING&preset=PRESET_TWO&size=64&zoom=1.5&paused=1&t=1.5
```

可用参数：`primary`、`preset`、`size`、`posx`/`posy`（配合 `anchor=0` 自定义）、`lang`、`screen`、`zoom`、
`primary=LISTENING`（旧参数 `listening=1` 仍兼容）、`background`、`hud`/`icon`/`text`/`shader`（`0` 关闭）、`partner`、`partnericon`、`lease`、`paused`、`t`、`speed`、`cycle`。

## 不精确的地方（有意为之）

- 字体使用浏览器字体，不是 Minecraft 的位图字体，所以状态面板宽度会差几个像素；布局坐标不受影响。
- 背景、热键栏、经验条、血量/饥饿与聊天是示意画面，只为了让图标处在接近游戏的构图里。
- 伙伴图标是占位图形（星形/月牙/音符/气泡），没有加载真实资源包贴图。
- 这是开发工具，不参与 mod 打包；`tianshu-presence` 的 jar 里没有它。
