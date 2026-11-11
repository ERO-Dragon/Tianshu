// HUD 画布原语：这一整套片子的 HUD 都从这儿取，不要在场景里各写一份。
// 全部是纯函数：接 (ctx, ...参数)，输出只取决于入参，方便逐帧重画。

export const E = {
  outC: (x: number) => 1 - Math.pow(1 - x, 3),
  outE: (x: number) => (x >= 1 ? 1 : 1 - Math.pow(2, -10 * x)),
  inOutC: (x: number) => (x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2),
  inQ: (x: number) => x * x,
  outBack: (x: number, s = 1.70158) => 1 + (s + 1) * Math.pow(x - 1, 3) + s * Math.pow(x - 1, 2),
}

export const clamp01 = (x: number) => Math.max(0, Math.min(1, x))

/** 帧区间归一：frame 在 [a,b] 内映射到 0..1，区间外夹住。 */
export const ramp = (frame: number, a: number, b: number) => clamp01((frame - a) / Math.max(1, b - a))

/** 确定性伪随机：同一 (a,b,c) 每帧同一个数，不用 Math.random。 */
export function hash(a: number, b = 0, c = 0): number {
  const s = Math.sin(a * 127.1 + b * 311.7 + c * 74.7) * 43758.5453
  return s - Math.floor(s)
}

const GLYPHS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/#%&*+-<>'
/** 数字/文字先乱码后定值：前 5 帧跳字形，之后按字符逐个落定。 */
export function scramble(text: string, frame: number, settleFrom: number, settleSpan: number): string {
  if (frame < settleFrom) {
    return text
      .split('')
      .map((ch, i) => (ch === ' ' ? ' ' : GLYPHS[Math.floor(hash(i, frame) * GLYPHS.length)]))
      .join('')
  }
  const p = clamp01((frame - settleFrom) / Math.max(1, settleSpan))
  const locked = Math.floor(p * text.length)
  return text
    .split('')
    .map((ch, i) => (i < locked || ch === ' ' ? ch : GLYPHS[Math.floor(hash(i, frame) * GLYPHS.length)]))
    .join('')
}

export type RingStyle = { color: string; width: number; alpha: number }

/** 扫描环：按 40 段长短弧画，segments 数与上游一致，视觉上"环是拼出来的"。 */
export function scanRing(
  ctx: CanvasRenderingContext2D,
  cx: number,
  cy: number,
  radius: number,
  progress: number,
  style: RingStyle,
) {
  const total = Math.PI * 2 * progress
  const segs = 40
  let angle = -Math.PI / 2
  let drawn = 0
  ctx.save()
  ctx.lineCap = 'butt'
  for (let i = 0; i < segs && drawn < total; i += 1) {
    const span = (4 + hash(i, 3) * 30) * (Math.PI / 180)
    const gap = (3 + hash(i, 7) * 10) * (Math.PI / 180)
    const end = Math.min(angle + span, -Math.PI / 2 + total)
    if (end > angle) {
      ctx.strokeStyle = style.color
      ctx.globalAlpha = style.alpha
      ctx.lineWidth = style.width
      ctx.beginPath()
      ctx.arc(cx + 0.5, cy + 0.5, radius, angle, end)
      ctx.stroke()
      drawn += end - angle
    }
    angle += span + gap
  }
  ctx.restore()
}

/** 四角直角框：尺寸按 1.35 → 1 收进来，收完跳两帧再定住。 */
export function frameCorners(
  ctx: CanvasRenderingContext2D,
  cx: number,
  cy: number,
  half: number,
  m: number,
  style: RingStyle,
) {
  ctx.save()
  ctx.strokeStyle = style.color
  ctx.globalAlpha = style.alpha
  ctx.lineWidth = style.width
  const x0 = cx - half
  const y0 = cy - half
  const x1 = cx + half
  const y1 = cy + half
  ctx.beginPath()
  // 左上
  ctx.moveTo(x0, y0 + m); ctx.lineTo(x0, y0); ctx.lineTo(x0 + m, y0)
  // 右上
  ctx.moveTo(x1 - m, y0); ctx.lineTo(x1, y0); ctx.lineTo(x1, y0 + m)
  // 右下
  ctx.moveTo(x1, y1 - m); ctx.lineTo(x1, y1); ctx.lineTo(x1 - m, y1)
  // 左下
  ctx.moveTo(x0 + m, y1); ctx.lineTo(x0, y1); ctx.lineTo(x0, y1 - m)
  ctx.stroke()
  ctx.restore()
}

/** 六边形网格：从中心推开的波前，波前之外不画。 */
export function hexWave(
  ctx: CanvasRenderingContext2D,
  cx: number,
  cy: number,
  wavefront: number,
  color: string,
  alpha: number,
  step = 46,
) {
  const r = step * 0.62
  ctx.save()
  ctx.strokeStyle = color
  ctx.lineWidth = 1
  const rows = 26
  const cols = 34
  for (let j = -rows; j <= rows; j += 1) {
    for (let i = -cols; i <= cols; i += 1) {
      const x = cx + i * step * 1.5 + (j % 2 ? step * 0.75 : 0)
      const y = cy + j * step * 0.87
      const d = Math.hypot(x - cx, y - cy)
      if (d > wavefront || d < wavefront - 260) continue
      const fade = clamp01(1 - Math.abs(wavefront - 160 - d) / 320)
      ctx.globalAlpha = alpha * fade
      ctx.beginPath()
      for (let k = 0; k < 6; k += 1) {
        const a = (Math.PI / 3) * k + Math.PI / 6
        const px = x + Math.cos(a) * r
        const py = y + Math.sin(a) * r
        if (k === 0) ctx.moveTo(px, py)
        else ctx.lineTo(px, py)
      }
      ctx.closePath()
      ctx.stroke()
    }
  }
  ctx.restore()
}

/** 等宽小字：按 progress 逐字打出来，末尾留一个闪烁光标。 */
export function typeLine(
  ctx: CanvasRenderingContext2D,
  text: string,
  x: number,
  y: number,
  progress: number,
  frame: number,
  color: string,
  font: string,
  cursor = true,
) {
  const n = Math.floor(clamp01(progress) * text.length)
  ctx.save()
  ctx.font = font
  ctx.fillStyle = color
  ctx.textBaseline = 'alphabetic'
  ctx.fillText(text.slice(0, n), x, y)
  if (cursor && Math.floor(frame / 8) % 2 === 0) {
    const w = ctx.measureText(text.slice(0, n)).width
    ctx.globalAlpha = 0.8
    ctx.fillRect(x + w + 3, y - 9, 7, 10)
  }
  ctx.restore()
}

/** 3px 扫描线，幅度 5%：周期整除 1080，避免爬行条纹。 */
export function scanlines(ctx: CanvasRenderingContext2D, w: number, h: number, frame: number) {
  ctx.save()
  const amp = 0.05 * (0.9 + 0.1 * Math.cos(frame / 9))
  ctx.fillStyle = 'rgba(0,0,0,' + amp.toFixed(3) + ')'
  for (let y = 0; y < h; y += 3) ctx.fillRect(0, y, w, 1)
  ctx.restore()
}

/** 逐像素颗粒：每帧换位置，落在 1/3 分辨率上再放大，干净但看得见。 */
export function grain(ctx: CanvasRenderingContext2D, w: number, h: number, frame: number, amount = 0.028) {
  const gw = Math.floor(w / 3)
  const gh = Math.floor(h / 3)
  const img = ctx.createImageData(gw, gh)
  const data = img.data
  for (let i = 0; i < data.length; i += 4) {
    const p = Math.floor(i / 4)
    const v = hash(p, frame, 11) * 255
    data[i] = v
    data[i + 1] = v
    data[i + 2] = v
    data[i + 3] = Math.floor(amount * 255 * hash(p, frame, 29))
  }
  const off = document.createElement('canvas')
  off.width = gw
  off.height = gh
  off.getContext('2d')!.putImageData(img, 0, 0)
  ctx.save()
  ctx.globalCompositeOperation = 'overlay'
  ctx.drawImage(off, 0, 0, w, h)
  ctx.restore()
}

/** 四角压暗，让中间那束光更像一束光。 */
export function vignette(ctx: CanvasRenderingContext2D, w: number, h: number, strength = 0.55) {
  const g = ctx.createRadialGradient(w / 2, h / 2, Math.min(w, h) * 0.28, w / 2, h / 2, Math.max(w, h) * 0.72)
  g.addColorStop(0, 'rgba(0,0,0,0)')
  g.addColorStop(1, 'rgba(0,0,0,' + strength + ')')
  ctx.save()
  ctx.fillStyle = g
  ctx.fillRect(0, 0, w, h)
  ctx.restore()
}

/** 软边色块擦除：从右往左推过整幅，边缘 120px 渐变。 */
export function softWipe(ctx: CanvasRenderingContext2D, w: number, h: number, progress: number, color: string) {
  if (progress <= 0) return
  const x = -w * 0.15 + progress * (w * 1.3)
  const g = ctx.createLinearGradient(x - 120, 0, x + 120, 0)
  g.addColorStop(0, 'rgba(0,0,0,0)')
  g.addColorStop(0.5, color)
  g.addColorStop(1, color)
  ctx.save()
  ctx.fillStyle = g
  ctx.fillRect(0, 0, w, h)
  ctx.restore()
}