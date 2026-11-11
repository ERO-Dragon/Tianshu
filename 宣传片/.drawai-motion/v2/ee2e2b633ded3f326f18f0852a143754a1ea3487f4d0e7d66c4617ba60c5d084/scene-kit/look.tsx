// 画面质感三件：SSAA、软阴影、色调映射，外加一层确定性颗粒。
// 放在 <ThreeCanvas> 里面（Grain 放外面，覆盖在整幅画面上）。
//
// 关键：等轴场景满屏都是 30°/60° 斜边，1× 渲染出来的阶梯肉眼可见。
// 把绘制缓冲放大到 2× 再让浏览器缩小，就是最便宜的 SSAA —— 比任何 CSS 滤镜都真实。

import * as THREE from 'three'
import { useThree } from '@react-three/fiber'
import { useLayoutEffect } from 'react'
import { AbsoluteFill, useCurrentFrame } from 'remotion'

type LookProps = {
  ssaa?: number          // 1 = 关；2 = 推荐；>2 收益递减
  shadow?: 'soft' | 'pcf' | 'basic'
  exposure?: number
  toneMapping?: 'aces' | 'neutral' | 'none'
}

export function Look({ ssaa = 2, shadow = 'soft', exposure = 1, toneMapping = 'aces' }: LookProps) {
  const { gl } = useThree()
  useLayoutEffect(() => {
    const ratio = gl.getPixelRatio()
    const type = gl.shadowMap.type
    const mode = gl.toneMapping
    const previousExposure = gl.toneMappingExposure
    gl.setPixelRatio(Math.min(2, Math.max(1, ssaa)))
    gl.shadowMap.type = shadow === 'basic' ? THREE.BasicShadowMap : shadow === 'pcf' ? THREE.PCFShadowMap : THREE.PCFSoftShadowMap
    gl.shadowMap.needsUpdate = true
    gl.toneMapping = toneMapping === 'none' ? THREE.NoToneMapping : toneMapping === 'neutral' ? THREE.NeutralToneMapping : THREE.ACESFilmicToneMapping
    gl.toneMappingExposure = exposure
    return () => {
      gl.setPixelRatio(ratio)
      gl.shadowMap.type = type
      gl.toneMapping = mode
      gl.toneMappingExposure = previousExposure
    }
  }, [gl, ssaa, shadow, exposure, toneMapping])
  return null
}

// 颗粒：feTurbulence 是确定性的（同 seed 同图），不消耗逐帧随机数，
// 用来打散 8bit 渐变的色带——色带是被「平」出来的，颗粒就是把它重新打碎。
export function Grain({ opacity = 0.05, seed = 7 }: { opacity?: number; seed?: number }) {
  const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="160" height="160"><filter id="n" x="0" y="0"><feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="3" seed="' + seed + '"/><feColorMatrix type="saturate" values="0"/></filter><rect width="160" height="160" filter="url(#n)" opacity="1"/></svg>'
  return (
    <AbsoluteFill style={{ pointerEvents: 'none', opacity, mixBlendMode: 'multiply',
      backgroundImage: 'url("data:image/svg+xml;utf8,' + encodeURIComponent(svg) + '")',
      backgroundSize: '160px 160px' }} />
  )
}

// 暗角：只压四角，中间不动。等轴画面四角是空的，压下去反而更聚。
export function Vignette({ strength = 0.18 }: { strength?: number }) {
  return (
    <AbsoluteFill style={{ pointerEvents: 'none',
      background: 'radial-gradient(ellipse at 50% 48%, rgba(0,0,0,0) 52%, rgba(20,16,48,' + strength + ') 100%)' }} />
  )
}
