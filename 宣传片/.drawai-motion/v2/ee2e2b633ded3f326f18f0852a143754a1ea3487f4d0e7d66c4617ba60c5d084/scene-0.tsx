import React, { useEffect, useRef, useState } from 'react'
import { AbsoluteFill, cancelRender, continueRender, delayRender, staticFile, useCurrentFrame, useVideoConfig } from 'remotion'
import * as THREE from 'three'
import { EffectComposer } from 'three/addons/postprocessing/EffectComposer.js'
import { RenderPass } from 'three/addons/postprocessing/RenderPass.js'
import { UnrealBloomPass } from 'three/addons/postprocessing/UnrealBloomPass.js'
import { PAPER, STAMP, INK, DUST, FIELD, MONO } from './theme'
import {
  E, ramp, clamp01, hash, scramble, scanRing, frameCorners,
  hexWave, typeLine, scanlines, grain, vignette, softWipe,
} from './components/hudkit'

// shot-1 · 0–359f（12s）：HUD 自检 → 六边形波前 → 模组身份方块落位 → 字标。
// 标识不做动效：它是一个圆角方形方块，只负责"这是天枢"这一个语义。
const W = 1920
const H = 1080
const CX = 960
const CY = 450
const R = 401
const CHIP = 196
const CHIP_Y = CY - 96

type ThreeState = {
  renderer: THREE.WebGLRenderer
  scene: THREE.Scene
  camera: THREE.PerspectiveCamera
  stars: THREE.Points
  orb: THREE.LineSegments
  composer: EffectComposer
}

export default function Scene() {
  const frame = useCurrentFrame()
  const { durationInFrames } = useVideoConfig()
  const glRef = useRef<HTMLCanvasElement>(null)
  const hudRef = useRef<HTMLCanvasElement>(null)
  const threeRef = useRef<ThreeState | null>(null)
  const [chip, setChip] = useState<HTMLImageElement | null>(null)
  const [handle] = useState(() => delayRender('加载标识方块'))

  // 标识必须等到位再拍：不等的话第一帧就是空括号（静帧好看、动起来穿帮）
  useEffect(() => {
    const el = new Image()
    el.onload = () => { setChip(el); continueRender(handle) }
    el.onerror = () => cancelRender(new Error('标识方块加载失败：tianshu-chip-2.png'))
    el.src = staticFile('tianshu-chip-2.png')
  }, [handle])

  // ── three 层：星尘 + 线框球（22-hud 的全息体，只给纵深，不参与叙事）
  useEffect(() => {
    const canvas = glRef.current
    if (!canvas) return
    if (!threeRef.current) {
      const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true })
      renderer.setPixelRatio(1)
      renderer.setSize(W, H, false)
      const scene = new THREE.Scene()
      const camera = new THREE.PerspectiveCamera(46, W / H, 1, 6000)
      camera.position.set(0, 0, 1500)

      const count = 760
      const pos = new Float32Array(count * 3)
      for (let i = 0; i < count; i += 1) {
        pos[i * 3] = (hash(i, 1) - 0.5) * 2600
        pos[i * 3 + 1] = (hash(i, 2) - 0.5) * 1600
        pos[i * 3 + 2] = (hash(i, 3) - 0.5) * 2600
      }
      const geo = new THREE.BufferGeometry()
      geo.setAttribute('position', new THREE.BufferAttribute(pos, 3))
      const stars = new THREE.Points(geo, new THREE.PointsMaterial({
        color: 0x4fd2ff, size: 2.4, transparent: true, opacity: 0.62,
        blending: THREE.AdditiveBlending, depthWrite: false, sizeAttenuation: true,
      }))
      scene.add(stars)

      const orb = new THREE.LineSegments(
        new THREE.WireframeGeometry(new THREE.SphereGeometry(760, 28, 18)),
        new THREE.LineBasicMaterial({ color: 0x0e8fa3, transparent: true, opacity: 0.1, depthWrite: false }),
      )
      scene.add(orb)
      // 上游这套 HUD 的光是 bloom 溢出来的：不加这一层，青线只是细灰线
      const composer = new EffectComposer(renderer)
      composer.addPass(new RenderPass(scene, camera))
      composer.addPass(new UnrealBloomPass(new THREE.Vector2(W, H), 0.75, 0.45, 0.32))
      threeRef.current = { renderer, scene, camera, stars, orb, composer }
    }
    const s = threeRef.current
    const t = frame / 30
    const dive = E.inOutC(clamp01(frame / 300))
    const shake = frame > 186 && frame < 194 ? (hash(frame, 5) - 0.5) * 9 * (1 - (frame - 186) / 8) : 0
    s.camera.position.z = 1500 - dive * 250
    s.camera.position.y = shake
    s.camera.position.x = Math.sin(t * 0.35) * 12
    s.camera.lookAt(0, 0, 0)
    s.stars.rotation.z = t * 0.012
    s.stars.position.z = (t * 26) % 400
    s.orb.rotation.y = t * 0.09
    s.orb.rotation.x = Math.sin(t * 0.2) * 0.12
    s.composer.render()
  }, [frame])

  // ── HUD 层：canvas 2D 逐帧绘制
  useEffect(() => {
    const canvas = hudRef.current
    const ctx = canvas?.getContext('2d')
    if (!canvas || !ctx) return
    ctx.clearRect(0, 0, W, H)

    const boot = ramp(frame, 0, 26)
    const ringP = E.outC(ramp(frame, 24, 64))
    const hexP = ramp(frame, 58, 168)
    const chipP = ramp(frame, 150, 192)
    const lockP = ramp(frame, 188, 204)
    const nameP = ramp(frame, 208, 236)
    const subP = ramp(frame, 226, 252)
    const wipe = E.inOutC(ramp(frame, durationInFrames - 26, durationInFrames - 2))

    const glow = ctx.createRadialGradient(CX, CHIP_Y, 0, CX, CHIP_Y, 640)
    glow.addColorStop(0, 'rgba(0,229,255,' + (0.13 * boot + 0.09 * lockP).toFixed(3) + ')')
    glow.addColorStop(0.4, 'rgba(11,124,140,0.05)')
    glow.addColorStop(1, 'rgba(0,0,0,0)')
    ctx.fillStyle = glow
    ctx.fillRect(0, 0, W, H)

    if (hexP > 0 && hexP < 1) hexWave(ctx, CX, CHIP_Y, hexP * 1500, '#0b7c8c', 0.22)
    if (ringP > 0) {
      // 一束光：扫描环先描一道带辉光的底，再叠 40 段短弧
      ctx.save()
      ctx.shadowColor = '#00E5FF'
      ctx.shadowBlur = 18
      ctx.strokeStyle = 'rgba(0,229,255,' + (0.5 * ringP).toFixed(3) + ')'
      ctx.lineWidth = 1.2
      ctx.beginPath()
      ctx.arc(CX + 0.5, CY + 0.5, R, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * ringP)
      ctx.stroke()
      ctx.restore()
      scanRing(ctx, CX, CY, R, ringP, { color: STAMP, width: 1.7, alpha: 0.92 })
      scanRing(ctx, CX, CY, R * 1.245, ringP * 0.92, { color: FIELD, width: 1.1, alpha: 0.5 })
    }
    frameCorners(ctx, CX, CY, 520, 40, { color: STAMP, width: 1.5, alpha: 0.62 * ringP })
    frameCorners(ctx, CX, CY, 250 - 40 * E.outC(ramp(frame, 48, 62)), 34, { color: STAMP, width: 1.3, alpha: 0.85 * ringP })

    if (boot > 0) {
      const arm = 74 * E.outE(boot)
      ctx.save()
      ctx.strokeStyle = STAMP
      ctx.globalAlpha = 0.95
      ctx.lineWidth = 1.6
      ctx.beginPath()
      ctx.moveTo(CX - arm, CY + 0.5); ctx.lineTo(CX - 8, CY + 0.5)
      ctx.moveTo(CX + 8, CY + 0.5); ctx.lineTo(CX + arm, CY + 0.5)
      ctx.moveTo(CX + 0.5, CY - arm); ctx.lineTo(CX + 0.5, CY - 8)
      ctx.moveTo(CX + 0.5, CY + 8); ctx.lineTo(CX + 0.5, CY + arm)
      ctx.stroke()
      ctx.restore()
    }
    for (let i = 0; i < 4; i += 1) {
      const p = ramp(frame, 8 + i * 6, 34 + i * 6)
      if (p <= 0 || p >= 1) continue
      ctx.save()
      ctx.strokeStyle = STAMP
      ctx.globalAlpha = 0.34 * (1 - p)
      ctx.lineWidth = 1.2
      ctx.beginPath()
      ctx.arc(CX + 0.5, CY + 0.5, 18 + 300 * E.outC(p), 0, Math.PI * 2)
      ctx.stroke()
      ctx.restore()
    }

    const mono = '13px "JetBrains Mono", monospace'
    const lines = [
      'BOOT // TIANSHU CORE',
      'PROTOCOL BUS    ONLINE',
      'MODULES MOUNTED  04',
      'ASR · TTS · LLM · HUD',
    ]
    lines.forEach((text, i) => {
      const p = ramp(frame, 30 + i * 16, 56 + i * 16)
      if (p <= 0) return
      const settled = frame >= 90 + i * 16
      const shown = settled ? text : scramble(text, frame, 60 + i * 16, 18)
      typeLine(ctx, shown, 96, 168 + i * 24, p, frame, i === 0 ? INK : DUST, mono, frame < 120)
    })
    const right = ['LOCAL AUDIO IN', '127.0.0.1 · NO UPLOAD', 'ENDPOINT  YOUR KEY']
    right.forEach((text, i) => {
      const p = ramp(frame, 96 + i * 14, 118 + i * 14)
      if (p <= 0) return
      ctx.save()
      ctx.font = mono
      ctx.fillStyle = i === 1 ? STAMP : DUST
      ctx.textAlign = 'right'
      ctx.globalAlpha = 0.8
      ctx.fillText(scramble(text, frame, 110 + i * 14, 14), 1824, 172 + i * 22)
      ctx.restore()
    })

    // 模组身份方块：落下 → 挤压 → 冲击环 → 锁框
    const dropY = CHIP_Y - 620 * (1 - E.inQ(chipP))
    const squash = frame > 186 && frame < 191 ? 1 - 0.14 * (1 - (frame - 186) / 5) : 1
    if (chip && frame >= 150) {
      for (let g = 3; g >= 1; g -= 1) {
        if (chipP >= 1) break
        ctx.save()
        ctx.globalAlpha = 0.18 / g
        ctx.drawImage(chip, CX - CHIP / 2 / squash, dropY - CHIP / 2 + g * 24, CHIP / squash, CHIP * squash)
        ctx.restore()
      }
      ctx.save()
      ctx.globalAlpha = clamp01(chipP * 3)
      ctx.drawImage(chip, CX - CHIP / 2 / squash, dropY - CHIP / 2, CHIP / squash, CHIP * squash)
      // 1px 青色包边 + 外辉光：方块自己那块深底和页面底几乎同色，没有这道边会像浮着的截图
      ctx.beginPath()
      ctx.roundRect(CX - CHIP / 2 / squash, dropY - CHIP / 2, CHIP / squash, CHIP * squash, CHIP * 0.22)
      ctx.strokeStyle = 'rgba(0,229,255,0.55)'
      ctx.lineWidth = 1.5
      ctx.stroke()
      ctx.restore()
      ctx.save()
      ctx.globalAlpha = 0.22 * clamp01(chipP * 3)
      ctx.shadowColor = '#00E5FF'
      ctx.shadowBlur = 46
      ctx.strokeStyle = '#00E5FF'
      ctx.lineWidth = 2
      ctx.beginPath()
      ctx.roundRect(CX - CHIP / 2 - 2, dropY - CHIP / 2 - 2, CHIP + 4, CHIP + 4, CHIP * 0.22)
      ctx.stroke()
      ctx.restore()
    }
    if (frame >= 186) {
      const p = ramp(frame, 186, 206)
      ctx.save()
      ctx.strokeStyle = '#ff2a8a'
      ctx.globalAlpha = 0.7 * (1 - p)
      ctx.lineWidth = 2
      ctx.beginPath()
      ctx.arc(CX + 0.5, CHIP_Y + 0.5, 72 + 150 * E.outC(p), 0, Math.PI * 2)
      ctx.stroke()
      ctx.restore()
    }
    if (lockP > 0) {
      const half = 172 - 56 * E.outC(lockP)
      frameCorners(ctx, CX, CHIP_Y, half, 30, { color: frame < 204 ? '#FF2A8A' : STAMP, width: 1.7, alpha: 0.95 * lockP })
      ctx.save()
      ctx.font = '11px "JetBrains Mono", monospace'
      ctx.fillStyle = DUST
      ctx.textAlign = 'center'
      ctx.fillText('TIANSHU CORE · 1.0.0', CX, CHIP_Y + 126)
      ctx.restore()
    }

    if (nameP > 0) {
      const s = 1.06 - 0.06 * E.outC(nameP)
      ctx.save()
      ctx.translate(CX, CY + 150)
      ctx.scale(s, s)
      ctx.textAlign = 'center'
      ctx.font = '700 108px "HarmonyOS Sans SC", "Noto Sans SC", sans-serif'
      ctx.fillStyle = INK
      ctx.globalAlpha = nameP
      ctx.fillText('天枢', 0, 0)
      ctx.restore()
    }
    if (subP > 0) {
      const text = 'MINECRAFT AI COMPANION'
      ctx.save()
      ctx.font = '600 22px "Chakra Petch", "Rajdhani", sans-serif'
      ctx.fillStyle = STAMP
      ctx.globalAlpha = subP
      const spacing = 9
      let x = CX - ((text.length - 1) * spacing + ctx.measureText(text).width) / 2
      for (const ch of text) {
        ctx.fillText(ch, x, CY + 208)
        x += ctx.measureText(ch).width + spacing
      }
      ctx.restore()
      ctx.save()
      ctx.font = '12px "JetBrains Mono", monospace'
      ctx.fillStyle = DUST
      ctx.textAlign = 'center'
      ctx.globalAlpha = subP * 0.85
      ctx.fillText('NeoForge 1.20.1–26.3  /  Fabric 1.20–26.3  /  语音在本机', CX, H - 86)
      ctx.restore()
    }

    scanlines(ctx, W, H, frame)
    grain(ctx, W, H, frame, 0.022)
    vignette(ctx, W, H, 0.52)
    if (wipe > 0) softWipe(ctx, W, H, wipe, PAPER)
  }, [frame, durationInFrames, chip])

  return (
    <AbsoluteFill style={{ background: PAPER }}>
      <canvas ref={glRef} width={W} height={H} style={{ position: 'absolute', inset: 0 }} />
      <canvas ref={hudRef} width={W} height={H} style={{ position: 'absolute', inset: 0 }} />
    </AbsoluteFill>
  )
}