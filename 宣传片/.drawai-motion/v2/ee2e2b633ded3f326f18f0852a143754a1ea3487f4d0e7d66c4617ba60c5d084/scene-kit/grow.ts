// 等轴生长曲线：底面落位 → 立面 scaleY 拉起 → 屋顶延迟盖上。
// 三种曲线都是闭式解：给定 t 就能算，不依赖历史帧，所以任意帧可独立渲染。

export const clamp01 = (value: number): number => (value < 0 ? 0 : value > 1 ? 1 : value)

export const easeOutCubic = (value: number): number => 1 - Math.pow(1 - clamp01(value), 3)
export const easeInOutCubic = (value: number): number => {
  const t = clamp01(value)
  return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2
}
export const easeOutBack = (value: number, overshoot = 1.70158): number => {
  const t = clamp01(value) - 1
  return 1 + (overshoot + 1) * t * t * t + overshoot * t * t
}

// 阻尼弹簧的闭式解。zeta=0.61 时过冲约 9%（e^(-pi*zeta/sqrt(1-zeta^2))≈0.089），
// 正好是「有生命、但不像卡通」的那一档；要更弹就降 zeta，要更硬就升。
export function spring(t: number, duration = 0.6, zeta = 0.61): number {
  if (t <= 0) return 0
  const wn = 10 / Math.max(duration, 0.01)
  const wd = wn * Math.sqrt(1 - zeta * zeta)
  return 1 - Math.exp(-zeta * wn * t) * (Math.cos(wd * t) + ((zeta * wn) / wd) * Math.sin(wd * t))
}

// 落体：加速下坠，触地一帧内挤压回弹（sy 从 0.7 弹回 1，40rad/s 的快振）。
export function drop(t: number, duration = 0.3): { y: number; sy: number; landed: boolean } {
  if (t <= 0) return { y: 1, sy: 1, landed: false }
  if (t < duration) {
    const x = clamp01(t / duration)
    return { y: 1 - x * x, sy: 1, landed: false }
  }
  const tau = t - duration
  return { y: 0, sy: 1 - 0.3 * Math.exp(-tau / 0.05) * Math.cos(40 * tau), landed: true }
}

// 立面：底座先落位，之后 scaleY 从 base 拉到 1。
export function wallScale(t: number, base = 0.1, duration = 0.6): number {
  if (t <= 0) return 0
  return base + (1 - base) * spring(t, duration)
}

// 屋顶：比墙晚 lag 秒盖上，用 3 帧的快速 pop。
export function roofAt(t: number, lag = 0.375, duration = 0.12): number {
  return clamp01((t - lag) / duration)
}

// 错峰：第 index 个物体晚 stagger 秒开始，整个梯队铺满 spread 秒。
export const stagger = (index: number, stagger = 0.04): number => index * stagger

// 冲击波：半径随 t 线性扩散（pulse_speed=15 是等轴岛上「刚好扫过半个岛」的速度）。
export const pulseRadius = (t: number, speed = 15, from = 1): number => from + speed * Math.max(0, t)
