// 确定性随机：同一个 (frame, salt, index) 永远得到同一个值。
// 禁 Math.random()：帧渲染可能被并行、被重跑、被预取，随机数会让同一 t 出两种画面。
// 用法：const dx = detBetween(frame, 7, 0, -0.5, 0.5)

export function hash32(seed: number): number {
  let x = seed | 0
  x = (x ^ 61) ^ (x >>> 16)
  x = x + (x << 3)
  x = x ^ (x >>> 4)
  x = Math.imul(x, 0x27d4eb2d)
  x = x ^ (x >>> 15)
  return (x >>> 0) / 4294967296
}

export const det = (frame: number, salt = 0, index = 0): number =>
  hash32(Math.imul(frame + 1, 73856093) ^ Math.imul(salt + 1, 19349663) ^ Math.imul(index + 1, 83492791))

export const detBetween = (frame: number, salt: number, index: number, min: number, max: number): number =>
  min + (max - min) * det(frame, salt, index)

// 低差异序列：累积采样要用 Halton(2,3) 而不是均匀随机，
// 否则 8 个样本会扎堆，边缘反而更脏。
export function halton(index: number, base: number): number {
  let result = 0
  let fraction = 1 / base
  let i = index + 1
  while (i > 0) {
    result += (i % base) * fraction
    i = Math.floor(i / base)
    fraction /= base
  }
  return result
}

// 亚像素抖动偏移（单位：像素），居中在 [-0.5, 0.5]。
export const jitter = (sample: number, index: number): [number, number] =>
  [halton(index, 2) - 0.5, halton(index, 3) - 0.5]
