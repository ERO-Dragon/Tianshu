# 整片风格：赛博 HUD·FUI（22-hud）

96% 的黑底上一束青，401px 扫描环配乱码定值的等宽数字，全程轻微 glitch。

来源：mg-styles-15（MIT，commit 49052d80）。上游是「HTML 页 + renderAt(t) + 截帧」，
这里不是逐行搬运（整份 HTML 搬进场景会跟 Remotion 的帧模型打架），但动效、转场、冲击、
相机与质感必须按下面的「效果配方」对齐——对不上就写偏离行，别静默省掉。

## 判据（做不出这些就不算这个风格）

- 96% 画面是 #020A0D，只剩 1–1.5px 青线、11–21px 全大写等宽字与一束辉光
- 中央 401px 扫描环：40 段 4–34° 长短弧 + 四角 34px 直角框
- 数字先乱码后定值：scramble 5 帧、roll 4 帧、flick 4 帧才稳
- 一切都不太干净：3px 扫描线 ±5%、径向色差、54 条 glitch 切片、0.028 颗粒
- 锁定瞬间整屏压成橙调 vec3(1.0, 0.40, 0.0)

## 引擎契约（硬规定）

- 上游引擎：`three.js（WebGL）` + `Canvas 2D`（都要在，缺一个就不像）
- three.js 全息线框地球（holo.js）+ Canvas2D HUD 面板与数据流，3D 与 2D 两层叠加。

上游证据：
  - `demos/22-hud/index.html`
  - `demos/22-hud/js/holo.js`
  - `demos/22-hud/js/hud.js`
  - `demos/22-hud/js/v5.js`

- three.js（WebGL）：在 <canvas> 里用 three 或 @remotion/three 真渲染：自建 WebGLRenderer、场景与相机，按 useCurrentFrame() 驱动 renderAt，后处理用 three/addons（UnrealBloomPass 等）。
- Canvas 2D：用 canvas 2D 逐帧绘制（路径 / 贴图 / 像素读写），不要用 DOM 拼贴替代逐帧绘制。

**不要用更省事的方式替代**（CSS 3D、div 层叠、纯 Canvas2D 渐变、静态图位移都不算）。`motion_render start` 会扫工程源码：重引擎（three / webgl2 / webgl）缺了直接拒绝渲染，不会静默降级。确实要换实现，先在这里写一行「偏离：引擎 three 改为 …，因为 …」，渲染会带 warning 通过。

## 效果配方（必做，逐项对照参考帧）

| 项 | 上游怎么做 | 这里必须做到 |
| --- | --- | --- |
| 转场 | 色块 field wipe（按解析软边擦入/擦出，不是硬切） | 至少一次带软边的擦除转场；没有 wipe 的段落不许用淡入淡出冒充 |
| 冲击 | 接触瞬间 impact：形变挤压 + 地面环/波纹 + 机位抖 | 每一次落地/接触都要有：挤压、环、抖动三件里至少两件 |
| 相机 | world drift / hero push / whip / 冲击 shake，机位一直在动 | 整片至少三处镜头运动（漂移/推近/甩/抖），不能全片静止机位 |
| 运动模糊 | motionBlur samples 1 / shutter 0.5 | 快速位移的帧要有拖影（MotionBlurTrail / shutter 采样），不能是干净单帧 |
| 颗粒/纹理 | 逐像素颗粒 （随帧位移 + 柔化），不是几百个点 | 颗粒要"看得见但不起脏"：逐像素生成、每帧换位置；几百个 1px 点不算 |
| 二次运动 | 上游还有：pulse / smear / trail | 这些"让画面活着"的小动作至少保留两样，别只留主形变 |

取得参考帧：`mg_style {action:"frames", slug:"22-hud"}` → 返回 8 张 PNG，用 `read_image` 逐张看。
**上游未检测到 ≠ 可省**：配方是下限，不是上限；判据（signature）与参考帧才是验收标准。
做完每个镜头，把参考帧和 `motion_preview` 的成片帧并排放一次，逐行对上面的表——对不上的项要么改，要么在下面写一行「偏离：X 改为 Y，因为 Z」。

## 色板

| 角色 | 值 |
| --- | --- |
| page | `#020A0D` |
| surface | `#0E3A42` |
| field | `#0B7C8C` |
| text | `#E8FEFF` |
| muted | `#6B8C93` |
| accent | `#00E5FF` |
| border | `#FF2A1F` |

片子里还会用到：`#020A0D` / `#0E3A42` / `#0B7C8C` / `#00E5FF` / `#E8FEFF` / `#FF2A1F` / `#FF2A8A`（这些不是 theme 常量，场景里直接写）。

## 字体

- display：`"Rajdhani","HarmonyOS Sans SC","Noto Sans SC",sans-serif`
- text：`"Chakra Petch","HarmonyOS Sans SC","Noto Sans SC",sans-serif`
- mono：`"JetBrains Mono",monospace`

## 节奏

- bpm：120
- fps：30
- duration：10
- aspect：16:9
- cutFrames：5

## 移植说明

# 22-hud · 赛博朋克 HUD / FUI

上游：`demos/22-hud/`（`index.html` 9 805 B、`js/hud.js` 65 650 B / 855 行、`js/holo.js` 17 026 B、`js/post.js` 8 773 B、`cues.json` 1 411 B）。three.js 线框地球 → `UnrealBloomPass` → 文字层 → CRT。1920×1080 / 30fps / 10.000s；120 BPM = 15 帧/拍，LOCK 6.400s = 第 192 帧。提示词原文用 `mg_style {action:"read",slug:"22-hud"}` 取。

## 1. 一眼认出

- **96% 是黑**：底 `#020A0D`，只剩 1–1.5px 青线、11–21px 全大写等宽字和一束辉光（`UnrealBloomPass(res, 0.75, 0.45, 0.32)`）。
- **中央 401px 扫描环**：`318 * SS`（`SS = 1.26`），`SEGS` 生成 40 段长短弧（4–34°，间隔 3–13°），四角 34px 直角框（`frameCorners`，`m = 40`）。
- **数字先乱码后定值**：`scramble` 5 帧跳 `GL` 字符、`roll` 4 帧跳数字、`flick` 4 帧 `[0.7,0.1,1.0,0.45]` 抖完才稳。
- **一切都不太干净**：3px 扫描线 ±5%、径向色差、54 条 glitch 切片、0.028 颗粒。

## 2. 色板

| 角色 | hex | 用在哪 |
| --- | --- | --- |
| 墨底 | `#020A0D` | 全屏背景，`INK` 也是文字挖洞色 |
| 深青 | `#0E3A42` | 面板底、`DEEP` |
| 暗青 | `#0B7C8C` | 次级刻度、坐标行，`DIM` |
| 主青 | `#00E5FF` | 90% 的线、括号、状态框，`CYA` |
| 高光白青 | `#E8FEFF` | 主数字、锁定标题，`HOT` |
| 警报红 | `#FF2A1F` | REC 点、失败状态，`RED` |
| 锁定/信标 | `#FF2A8A` / `#FF5A4E` | 锁定环与四段弧 / 信号点与脉冲环 |

色板只有这 8 个（`MKC` 是色盲替换表）。**禁止色**：纯白 `#FFFFFF`、绿色、任何暖色——唯一例外是 lock 时 `COMP_FS` 的 `reink()`，整屏压成 `vec3(1.0,0.40,0.0)` 橙调。

## 3. 字体与字排

**当前优先使用插件内置字体：** 中文用 HarmonyOS Sans SC，拉丁标题用 Rajdhani 或 Chakra Petch，数据用 JetBrains Mono。

下方保留原片排版参数及旧版系统替代方案；只有内置字体不适合或不可用时才参考旧系统栈及其损失说明。

原片加载 4 族：`"JetBrains Mono"`、`Rajdhani`、`"Chakra Petch"`、`"HarmonyOS Sans SC"`，等 `fonts.ready` 后才出首帧。拉丁一律大写 + `letterSpacing 1–9`。

旧版没有内置字体时使用的系统替代栈：等宽 `"Consolas","JetBrains Mono",monospace`；拉丁窄体 `"Bahnschrift SemiCondensed","Arial Narrow",sans-serif`；中文 `"Microsoft YaHei UI","Microsoft YaHei",sans-serif`。

**损失**：Rajdhani 窄体换 Bahnschrift 宽 8–12%，同一行 `ls:3` 会撑破 300px 栏，字距降到 1.5–2；JetBrains Mono 0.6em vs Consolas 0.55em，滚动数字左右跳，需 `tabular-nums` + 固定宽盒；HarmonyOS 900 在 Windows 无此字重（YaHei 仅 400/700），58px 标题会细一档。

## 4. 动效语法

- **120 BPM = 15 帧/拍**。0.4 / 4.4 / 6.4 / 7.1 四个冲击帧用闭快门单独渲染（`HARD` 集合），别让运动模糊糊掉。
- 缓动全在 `util.js`：`E.outC = 1-(1-x)³`、`E.outE = 1-2^(-10x)`、`E.inOutC`、`E.inQ = x²`、`E.outQ`、`E.outBack(1.70158)`、`sstep`。所有位移 3–10 帧到位后彻底静止。
- **禁用**：`spring()`、超过 0.35s 的位移、`rotate` 动画（HUD 是平面图，转起来就假）、opacity 渐变长于 4 帧。
- 签名动作：
  1. **开机 0–1.2s**：十字准星 0.04–0.22（`outE`），4 次 ping 每帧错峰、`318*E.outC` 落环；外环 0.40–0.90 画出；六边形网格 1.2 起 1500px 波前推开（`uHex`）。
  2. **雷达 2.4s 起**：`radar_period 2.0`、`theta0 52°`，4 个 blip 按 `(brg-52)/180` 秒依次亮。
  3. **捕获 5.9–6.33**：括号 8 帧 `outCubic` 从 (872,476) 收到 (176,176)，再 2 帧 `(F>>1)&1` 在 176/204 间跳，`snap 6.2333` 起 3 帧 `[1.35,1.08,0.96,1.0]` 过冲；锁环 36 段按 `E.inQ` 填满。
  4. **LOCK 6.400**：标题 1.15→1（3 帧），首帧纯 `HOT`；扫描环换 `#FF2A8A` 并 `+0.62*DIVE` 推近；侧栏 `x ±120px` 外推 + `blur(4px)`。
  5. **关机 9.767**：0.2s 内 `csy = pow(1-cz/0.6, 2.2)` 压成一条线，再横向收成点。
- **网格**：安全边距 96px，角标 40px，顶栏 y=76，分隔线 y=100.5，左栏 `CW=300`。字号 11/13/14/15/17/21/26/58/60；线宽 1/1.5/2/3/4/6。

## 5. Remotion 落地配方

必须换掉：

1. `window.renderAt(t)` / `requestAnimationFrame` → `useCurrentFrame()`；`holo.update(t)` 的 `h`、`zAt(t)`、`SURGE(t)` 全改成 `frame` 纯函数。
2. `Date.now()` / `document.fonts.ready` → 删；字体同步给。
3. `Math.random()` → `util.js` 的 `hash(...a)` 与 `mulberry(seed)`。
4. **Canvas 2D 逐帧**：保留，但合成两遍——几何一层、文字一层（上游 `drawHUD(t,S,'geo')` / `'txt'` 就这么分），文字层不参与 blur/bloom，否则滚动数字双曝光。
5. CSS `transition`/`animation` → 删。`ctx.filter='blur()'` 可用，但只在整层用一次。
6. 运动模糊：`samples:1`，**不加** `<CameraMotionBlur>`，动感靠 dive 推镜和 glitch。

**1px 刻线在 1080p**：上游按 `dpr`（通常 2）画 `W*dpr` 画布，1.25 逻辑 px 实为 2.5 device px；Remotion 输出就是 1920×1080，1 逻辑 px = 1 像素，所以坐标 `+0.5`、`lineWidth: 1.25`，否则线要么消失要么发灰。**不要**画 3840 再缩，线变 0.6px 灰线。

**后期近似**：bloom 用副本 `brightness(1.6) contrast(2.4) blur(10px)` + `screen`，靠提对比度当阈值；扫描线 3px `repeating-linear-gradient`、幅度 5%（对应 `0.90+0.10*cos`）；色差用两份通道副本 `translate ±1.5px`；glitch 54 条切片按 `hash(slice,frame)` 挑 22%（≤67px）；白闪只抬亮部。**损失**：单次高斯 ≠ 5 级 mip bloom，1px 线光晕半径不对；平移色差无径向权重；无 `TEXT_FS` 双环采样，暗底字偏薄。

```tsx
import { AbsoluteFill, Easing, interpolate, useCurrentFrame } from 'remotion';
import { useEffect, useRef } from 'react';

const CX = 960, CY = 540, R = 401;              // 318 * SS(1.26)

export const HudScene: React.FC = () => {
  const frame = useCurrentFrame();
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const ctx = ref.current!.getContext('2d')!;
    // 0.40–0.90s 外环；192 帧起括号 3 帧收 130px
    const d = interpolate(frame, [12, 27], [0, 1],
      { extrapolateRight: 'clamp', easing: Easing.out(Easing.exp) });
    const k = interpolate(frame, [192, 195], [1.4, 1],
      { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
    ctx.lineWidth = 1.25; ctx.globalAlpha = 0.9; ctx.strokeStyle = '#00E5FF';
    ctx.beginPath(); ctx.arc(CX + 0.5, CY + 0.5, R, -Math.PI / 2, -Math.PI / 2 + d * 6.2832);
    ctx.stroke();
    ctx.strokeStyle = frame >= 192 ? '#FF2A8A' : '#00E5FF';
    ctx.strokeRect(CX - 130 * k, CY - 130 * k, 260 * k, 260 * k);
  }, [frame]);
  return (
    <AbsoluteFill style={{ background: '#020A0D' }}>
      <canvas ref={ref} width={1920} height={1080} />
      <AbsoluteFill style={{ background:
        'repeating-linear-gradient(to bottom, rgba(0,0,0,.10) 0 2px, transparent 2px 3px)' }} />
    </AbsoluteFill>
  );
};
```

## 6. 镜头结构模板

| 时间 | 意图 | 画面 |
| --- | --- | --- |
| 0.0–1.4 | 开机自检 | 十字 → ping 落环 → 侧栏 0.9/1.15 左右开 → 六边形波前 |
| 1.4–2.4 | 地球出现 | 线框球 1.35 起长到 1，扫描面上下走，日志 4 行依次打 |
| 2.4–4.4 | 数据活起来 | 雷达扫描、6 组数字 7.5 帧一滚、sparkline 2.9 |
| 4.4–6.4 | 捕获信号 | 状态框转 `SIGNAL DETECTED` 闪烁、地球转向、候选 `NO MATCH`、括号飞入 |
| 6.4–7.1 | 锁定冲击 | 白闪、橙调 re-ink 0.45s 扫过、标题砸落、dive 推近、侧栏失焦 |
| 7.1–10 | 展开与收尾 | 暗带打开、坐标打字、`alert_pulses` 脉冲、outro 淡出、9.767 压线收点 |

## 7. 坑

1. 文字层必须在 bloom 之上单独合成，和几何一起过 blur 就会糊成两行字。
2. 1px 线整数坐标会闪：`.5` 偏移 + `lineWidth ≥ 1.25`；扫描线 3px 周期要整除 1080。
3. `knock()`（`destination-out` 挖洞）是文字可读性的关键，漏掉就有线穿字。
4. 别把 `drawHUD` 返回的 `scopeR` 写死：dive 时它要涨 62%，锁定环靠这个才"飞过镜头"。

## 8. 版权与素材

上游 mg-styles-15 为 MIT。`KESTREL-9`、`隼眼-9` 与城市代号都是虚构；`land-50m.json`（Natural Earth 衍生）随仓库使用。刻线、括号、锁定环是通用 FUI 语汇，可自由重画。


## 上游源码

- `demos/22-hud/cues.json`
- `demos/22-hud/index.html`
- `demos/22-hud/js/holo.js`
- `demos/22-hud/js/hud.js`
- `demos/22-hud/js/post.js`
- `demos/22-hud/js/util.js`
- `demos/22-hud/js/v5.js`

## 版权

上游 MIT。品牌名必须虚构；通用几何与三原色可自由使用。
