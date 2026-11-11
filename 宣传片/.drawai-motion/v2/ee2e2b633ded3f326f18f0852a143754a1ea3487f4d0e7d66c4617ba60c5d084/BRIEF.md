# 视频工程

规格：1920×1080 / 30fps / 7200 帧（240.00 秒）

## 分镜

- shot-1：第 0–359 帧，文件 scene-0.tsx
- shot-2：第 360–1799 帧，文件 scene-1.tsx
- shot-3：第 1800–2999 帧，文件 scene-2.tsx
- shot-4：第 3000–3839 帧，文件 scene-3.tsx
- shot-5：第 3840–5039 帧，文件 scene-4.tsx
- shot-6：第 5040–5879 帧，文件 scene-5.tsx
- shot-7：第 5880–7199 帧，文件 scene-6.tsx

## 分镜表

- shot-1：冷开场：把观众丢进一个正在自检的界面，先立名号；画面 黑场 → 十字准星 → ping 落环 → 401px 扫描环画出 → 六边形网格波前推过 → 标识由像素点聚合成星系 → 字标「天枢」；文案 BOOT // TIANSHU CORE → 天枢
- shot-2：讲清天枢是什么：一个基座 + 若干可拆的功能模组；画面 装配中心：基座居中，功能模组沿椭圆依次入位并点亮；拆掉一个，环仍在、界面仍在；末段版本矩阵逐行滚出；文案 一个基座，几颗可拆的星 / 装谁是谁
- shot-3：听澜：本机把一段空气变成一个句子；画面 按键按下 → HUD「正在听你说」大球 + 涟漪 → 波形进入本机 ASR → 文本逐字成形 → 物品名被修复 → 仲裁分叉；文案 听 · 麦克风到这里为止
- shot-4：回响：把回答变成声音，本机合成；画面 文本流入合成器 → 声波环扩散 → 玩家听到；说到一半被新指令打断，波形硬停再重起；文案 说 · 回答在本机合成
- shot-5：织言：大脑可替换，换模型只改设置；画面 设置页端点/Key/模型名三行 → 模型名槽位轮换 → 流式分片 25ms 合批 → 中途 CANCEL → 重连状态；文案 想 · 大脑换成你的
- shot-6：映迹：那颗球和它背后的世界状态；画面 真实 shader 球特写：空转 → 聆听 → 思考 → 回复；七状态轮转；球旁边的世界事件读数；文案 看 · 它在看着你的世界
- shot-7：收尾：实机蒙太奇 + 两个半球 + 安装；画面 夜间实机蒙太奇 → 本机/云端两半球，波形停在本机边界 → 安装三步 → 版本矩阵 → 字标收束；文案 语音留在这儿，大脑在云里

## 音频

（无音频）

## 约定

- 内置素材可用 motion_assets list 查看：builtin/paper.svg、builtin/grain.svg、builtin/grid.svg 可用 staticFile 引用；builtin/whoosh.wav、builtin/impact.wav 可通过 motion_audio add 加入时间轴。素材是 DrawAI 程序生成的通用素材，不是上游原片素材。
- 工程内置 Noto Sans SC、Noto Serif SC 及常用上游字体，场景可按字体名使用；个别字体与上游特定字重可能仍需补充。
- 需要纹理或乐器单音时，先看用户素材和 motion_assets {action:"list",nodeId}，再用 {action:"library",nodeId,query:"纸张"} 或 query:"钢琴" 搜索插件库；{action:"use",nodeId,assetId:id} 导入所选条目。图片用返回的 file 调 staticFile，声音须另用 motion_audio {action:"add",nodeId,file,role:"sfx",at:秒} 放入时间轴；先查看来源和许可，试听确认电平。单音不是完整配乐。
- 插件素材随安装包提供，素材 ID 不是工作区路径。不要猜路径、从其他工程拷贝素材或手写 manifest.json；使用 library 返回的 ID 调 use，失败时报告具体错误。
- 一场景一文件，单文件不超过 120 行；共享元素放 components/。
- 场景组件 default export，签名 ({ shot, anchors, assets, render }) => ReactNode；时间用 useCurrentFrame()，相对本段起点。
- scene-kit/ 是场景层脚手架：det.ts（确定性随机 + Halton 抖动，禁 Math.random）、grow.ts（弹簧/落体/生长曲线闭式解）、look.tsx（SSAA/软阴影/色调映射/颗粒/暗角）。要画质就先看这三件，别在场景里另写一套。
- 累积采样数与快门时长从 render.samples / render.shutter 读，不要写死数字。
- 改完先渲染 3 帧看图（motion_preview），确认后再跑全片（motion_render）。
- 配色主题：赛博 HUD·FUI（颜色/字体从 theme.ts 取，别手写十六进制）。
- 整片风格：赛博 HUD·FUI（22-hud）—— 施工说明见 STYLE.md，写每一镜之前先读它。
- 时间轴改动只改 timeline.json 的 startFrame / durationFrames，不要改场景文件里的绝对帧号。

## 描述

天枢（Tianshu）Minecraft AI 助手的模块化宣传片，4 分钟分 7 段：片头 / 天枢框架 / 听澜 / 回响 / 织言 / 映迹 / 收尾。视觉语言赛博 HUD（22-hud）：#020A0D 底、#00E5FF 主青、等宽数据字、扫描环与粒子星尘。真实素材：tianshu2.png 标识、映迹 HUD 的真实状态文案（正在听你说/正在思考/正在回复）、lang 里的按键文案。面向玩家与模组开发者，中文文案。

