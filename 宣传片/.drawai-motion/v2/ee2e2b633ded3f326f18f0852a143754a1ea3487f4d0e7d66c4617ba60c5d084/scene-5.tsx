import React from 'react'
import { AbsoluteFill, interpolate, useCurrentFrame, useVideoConfig } from 'remotion'
import { PAPER, INK, SERIF } from './theme'

// shot-6: 5040–5879 帧
// 占位：换成这一镜真正的版面。颜色/字体从 theme.ts 取，不要手写十六进制。
// 时长一律用 useVideoConfig()：旁白重排或改时间轴之后，收尾动作会自动跟着挪；
// 写死一个 DUR 常量的话，镜头一变长，最后那段就是静止的（旁白接上就会踩到）。
// anchors.<动作名> 是音乐绑定后的本镜局部帧，用作动效关键帧。
export default function Scene({ anchors = {} }) {
  const frame = useCurrentFrame()
  const { durationInFrames } = useVideoConfig()
  const opacity = interpolate(frame, [0, 12], [0, 1], { extrapolateRight: 'clamp' })
  const settle = interpolate(frame, [durationInFrames - 10, durationInFrames], [1, 0.97], { extrapolateLeft: 'clamp' })
  return (
    <AbsoluteFill style={{ background: PAPER, opacity: opacity * settle }}>
      <AbsoluteFill style={{ alignItems: 'center', justifyContent: 'center' }}>
        <div style={{ color: INK, fontSize: 40, fontFamily: SERIF }}>shot-6</div>
      </AbsoluteFill>
    </AbsoluteFill>
  )
}
