# DSH（DeepSeek Harness）运行逻辑架构技术手册

> 读者假设：要在 Minecraft 模组 / 服务端里**复刻或借鉴** DSH 的 agent 运行架构。
> 因此本文按"能不能搬、搬哪一段、搬了会在哪里炸"来组织，而不是按 API 文档顺序。

---

## 0. 取证方法与阅读约定

### 0.1 源码位置与读取方式

- 源码归档：`E:\DSH\DSH Desktop\resources\app.asar`，单文件 301,633,615 字节，**是 asar 归档不是目录**。
- 本次全部结论来自**内存内按偏移解析**，未解压到磁盘、未写入任何临时文件。
- asar 头布局（实测）：`u32@0=4` 为 pickle 长度域；`u32@4` = header pickle 载荷长度；`u32@12` = JSON 字节长度，JSON 实际起始于**偏移 16**；文件数据基址 `BASE = 8 + u32@4`（本次 `BASE = 5577604`）。
- 本机 `node` 是 Electron 的 Node（`C:\Users\...\harness\.desktop-bin\node.cmd`），`fs` 被 Electron 的 asar 包装层劫持，直接 `fs.openSync('*.asar')` 会被拆成"asar 内部路径"而 `ENOENT`。必须先 `process.noAsar = true`。
- 包分布：`node_modules/@deepseek-ai/` 下 **289 个包**；DSH 自身版本统一为 `0.2.0-rc.2`（`dsh-agent-loop/package.json:4`）。
- 每个包同时提供：可读的编译产物 `lib/index.js`（tsc 输出，**非压缩**）、`lib/types/*.js`（部分是可被外部 import 的运行时模块，如 `surface`）、以及一份高质量的 `README.zh.md`（含设计理念、源码地图、KV Cache 影响、已知限制）。

### 0.2 引用格式

所有证据写成 `包名/文件:行号`，省略 `@deepseek-ai/` 前缀：

- `dsh-agent-loop/lib/index.js:1052` —— 编译产物行号（本手册主要证据形态）。
- `dsh-agent-loop/README.zh.md:119` —— 官方说明行号（用于"设计意图"，不作行为唯一依据）。

**注意**：README 里的"源码地图"指向 `src/*.ts`，与 `lib/index.js` 的实现是一一对应的语义关系，但**行号不同**。本手册的行号一律指 `lib/**` 产物。凡只有 README 支撑、我没在代码里定位到的，标注「未验证」。

### 0.3 核心包清单（本手册涉及）

| 包 | 职责 | 关键文件 |
|---|---|---|
| `dsh-session` | 事件溯源会话日志 + surface 投影 + 派生历史 | `lib/index.js`、`lib/types/surface.js` |
| `dsh-agent` | `Agent` 句柄 / 注册表 / `agent/*` 事件词汇（seam，不含循环） | `lib/index.js` |
| `dsh-agent-loop` | **唯一具体循环实现**：收件箱、轮次/步骤状态机、工具调度、请求构建 | `lib/index.js` |
| `dsh-llm` | 提供方无关的消息/分片词汇、适配器注册、`prepareCall`/`stream` | `lib/index.js`、`lib/types/assembler.js` |
| `dsh-llm-deepseek` | DeepSeek 协议适配（含缓存用量字段解析） | `lib/index.js` |
| `dsh-system-prompt` | 系统提示词分区注册表 + 组装/渲染 | `lib/index.js` |
| `dsh-tools` | 工具注册表 + 执行流水线 + 并发安全判定 | `lib/index.js` |
| `dsh-token-meter` | 回放式 token 计量与上下文压力 | `lib/index.js`、`lib/types/estimate.js` |
| `dsh-compaction` | 压缩 seam（抽象引擎 / 锁 / tool 配对） | `lib/types/tool-pairing.js` |
| `dsh-compaction-basic` | 压缩后端（阈值、选段、摘要调用、检查点） | `lib/index.js` |
| `dsh-compaction-tool-result-pruner` | 压缩前修剪超大工具结果（不发模型调用） | `lib/index.js` |
| `dsh-jobs` | 后台任务注册表约定 | — |
| `dsh-subagent` | 子 agent 委派 seam | — |

---

## 1. 总体架构

### 1.1 一句话结构

**一个只追加的事件日志是唯一真相；模型历史、transcript、token 计量、压缩、持久化全部是这条日志的投影（fold）。** 循环只做三件事：把待办输入领进日志、从日志派生请求、把模型与工具的结果写回日志。

`dsh-session/README.zh.md:88` 的表述是权威版本：

> 该包建立在事件溯源之上：`Session` 是类型化 `SessionEvent` 的仅追加日志，其他一切——模型历史、transcript、遥测、标题、持久化——都从这条流派生。

### 1.2 分层与数据流

```
            (UI / API / CLI / SDK)
                     │  ctx.agents.create() / resume()
                     ▼
        ┌────────────────────────────┐
        │ dsh-agent-loop  AgentLoop  │  ← 唯一具体实现（工厂注册在 ctx.agents）
        │  turn → step → 工具调度     │
        └───────┬─────────┬──────────┘
   systemPrompt │         │ llm.stream / prepareCall
        ┌───────▼──┐   ┌──▼─────────┐
        │system-   │   │ dsh-llm    │ → 适配器（deepseek / pi-ai）→ 网络
        │prompt    │   └──┬─────────┘
        └──────────┘      │ StreamChunk（token 增量）
                          ▼
                   BlockAssembler + AssistantStreamAttempt
                          │ 一次 settlement
                          ▼
   ┌──────────────────────────────────────────────┐
   │ dsh-session：append-only SessionEvent 日志     │ ← 唯一写入点
   │  surface 投影（nodes + projectedMessages）     │
   └───────┬───────────────┬──────────────┬────────┘
           │ deriveMessages │ fold         │ fold
           ▼                ▼              ▼
      模型 messages     token-meter    compaction / title / telemetry
```

### 1.3 依赖方向：不是 import，是服务注入

这是最容易误判的一点。看 `package.json`：`dsh-agent-loop` 的 `dependencies` 里**只有** `dsh-brand`、`dsh-util-values`、`schemastery`、`zod`；而 `dsh-session`、`dsh-llm`、`dsh-tools`、`dsh-system-prompt` 全部列在 `peerDependencies`（`dsh-agent-loop/package.json:33-44`）。

真正把循环与服务连起来的是 Cordis 的服务注入列表：

```js
// dsh-agent-loop/lib/index.js:1524
static inject = ["agents", "sessions", "llm", "tools", "systemPrompt", "sessionProjections"];
```

推论（对复刻很关键）：

1. **循环不认识 token-meter，也不认识 compaction。** 这两个包是在运行时以事件监听器挂进循环的：`dsh-compaction-basic/lib/index.js:839` 注册 `agent/pre-step`，`:861` 注册 `agent/request-error`。
2. 因此"压缩"不是循环的一个阶段，而是**步骤边界上的一个监听器**。这决定了你可以在不碰循环的前提下插拔策略。
3. 反向依赖只有一处显式例外：`dsh-session` 的 `dependencies` 里有 `dsh-llm`（`dsh-session/package.json:59`），因为它要用 llm 的 `Message`/`ContentBlock` 词汇。`dsh-compaction/README.zh.md:72` 明确承认这是"有意的偏离"。

### 1.4 两条贯穿全局的不变式

| 不变式 | 出处 |
|---|---|
| **模型可见 ⟺ 已记录**：请求里每一个模型可见输入都必须能从会话日志重建 | `dsh-llm/README.zh.md:114`；`dsh-session/README.zh.md:88` |
| **请求是日志的纯函数**：循环构建的请求深冻结后到达适配器，监听器只能读不能改 | `dsh-llm/README.zh.md:88`；`dsh-agent-loop/lib/index.js:1261-1276` |

第二条不是洁癖。它带来的直接收益是：请求可重放、缓存前缀可证明一致、压缩决策可独立重算。复刻时如果允许插件改写请求，前面所有关于 token 计量与缓存命中率的推理全部失效。

---

## 2. Agent Loop 运行逻辑

### 2.1 三层状态机

- **Turn（轮次）**：`turn()` `dsh-agent-loop/lib/index.js:936`。一个轮次由一次用户输入开启，到循环认为可以停为止。
- **Step（步骤）**：`step()` `dsh-agent-loop/lib/index.js:1041`。一次步骤 = 一轮模型尝试（含内部重试）+ 其工具执行。
- **Attempt（尝试）**：步骤内的单次模型调用，可在 `agent/request-error` 后 `continue` 重试。

### 2.2 一次用户请求的完整时序（带行号）

```
ctx.agents.create/resume → ReactLoopAgent → turn()
```

**T1. 开轮次**（`dsh-agent-loop/lib/index.js:943`）

```js
this.session.append("turn/start", { turn });
```

**T2. 步骤前：领取输入 + 组装提示词 + 运行 pre-step waterfall**（`:902-918`）

```js
const claimed = this.inbox.claim(target, position.turn);                       // :906
const assembly = await this.loopCtx.systemPrompt.assemble(assembleContextFor(this, signal)); // :907
const sections = renderContextSections(assembly);                              // :909
const context = this.runtimeContext.project(joinContextSections(sections), sections); // :910
const decision = await this.dispatch.waterfall("agent/pre-step", { messages: claimed, ...position, signal }, ...); // :911
```

注意顺序：**先领输入，再组装提示词，最后才跑 `agent/pre-step`**。压缩就挂在这个 waterfall 上——它在"模型请求派生之前"拿到控制权（`dsh-compaction-basic/lib/index.js:839`）。

`target` 在轮次边界是 `"next-turn"`，步骤之间是 `"next-step"`（`:949`）；投递语义由 `dsh-agent-loop/README.zh.md:119` 描述为：轮次边界原子领取 next-step 输入与一条排队提示词，步骤之间只领取 next-step 输入。

**T3. 是否真的开步骤**（`:958-966`）

- `decision.kind === "reject"` → 本轮 `turnEnds = blocked`，不开步骤。
- 首个步骤且 `decision.messages.length === 0` → `completed`，不开步骤。

即：**空输入不产生空步骤，也就不会污染日志**。

**T4. 记录步骤边界**（`:968`）

```js
this.session.append("step/start", { turn, step });
```

随后挂一个临时的 `session/event` 观察者把事件喂给 `ToolCallRecovery`（`:973-976`）——这是"步骤失败时补工具结果"的依据。

**T5. 解析路由与预准备调用**（`prepareRequest` `:1163-1199`）

```js
const proposedConfig = await this.dispatch.waterfall("agent/request", {...}, () => seedConfig); // :1179
preparedCall = await this.loopCtx.llm.prepareCall(proposedConfig, signal);                      // :1189
config = preparedCall.config;                                                                   // :1190
```

`prepareCall()` 把"适配器代次 + 能力（contextWindow / maxTokens 默认 / reasoningEffort / systemPromptUpdate / toolUpdate / 图片定价 / 重试策略）"绑成一个**一次性**对象（`dsh-llm/README.zh.md:108`）。`NO_ADAPTER` 被吞掉只是为了后续给出更好的错误（`:1192-1193`），不代表能继续流式调用。

**T6. 协调系统提示词并落日志**（`:1052-1060`）

```js
const commits = this.systemPrompt.project(renderedPrompt, {
  inHistory: preparedCall?.systemPromptUpdate === "in-history",
  startsSeries: startsRequestSeries
    || this.requestSurfaceGeneration !== this.session.surface.contentGeneration
    || preparedCall?.toolUpdate === void 0 && this.toolsChanged(assembly.tools),   // :1054
});
for (const { message, intent } of commits) this.session.append("system/message", { turn, step, message }, intent);
```

三个"开启新请求序列"的条件写在同一行：显式声明、surface 内容代号变化（替换/投影/图片省略）、工具集变化且路由不支持工具增量更新。

**T7. 只在首次尝试追加用户消息**（`:1061`）

```js
if (firstAttempt) for (const message of decision.messages) this.session.append("user/message", message, { surfaceOp: "append" });
```

重试走 `continue`（`:1134`）回到 `:1050`，`firstAttempt` 已为 `false`，所以**重试不会重复插入用户消息**，也不会重跑 `agent/pre-step` 与提示词组装（`renderedPrompt` 在循环外算好，`:1047`）。

**T8. 构建并冻结请求**（`:1063` → `buildRequest` `:1202-1276`，详见 §4）。

**T9. 流式调用**（`:1072-1080`）

```js
const stream = preparedCall?.stream(request) ?? this.loopCtx.llm.stream(request);
live.start();
for await (const chunk of stream) { signal.throwIfAborted(); live.push(chunk); }
```

每个分片都检查中止信号——这是"协作式取消保留已交付文本"的实现点。

**T10. 结算**（`:1084-1155`）

| 结果 | 行为 | 行号 |
|---|---|---|
| 用户取消 | `live.interruptedBlocks()` → 追加 `assistant/message` 带 `interrupted: true` + 已交付前缀 | `:1084-1100` |
| 异常（已 start） | 追加 `assistant/attempt`（**不产生模型历史**） | `:1101-1110` |
| terminal `finish.kind = error/aborted` | 先落 `assistant/attempt`，再 waterfall `agent/request-error`；返回 `{kind:'retry'}` 则 `continue` | `:1118-1134` |
| 正常结束 | 追加 `assistant/message`（携 usage + 紧凑 stream） | `:1144-1150` |
| `finish.kind === 'max-tokens'` | 返回 `{kind:'max-tokens'}`，轮次可继续 | `:1151` |
| 有 tool-call 块 | `executeToolCalls(...)` 调度工具 | `:1152-1155` |

**T11. 工具执行完成后回到循环**（`:1154-1155`）

```js
const { concluded } = await executeToolCalls(..., (context) => this.inbox.splice("next-step", this.inbox.nextStep.length, 0, [context]));
return concluded ? { kind: "completed" } : null;
```

返回 `null` 表示"本步骤还没完，但步骤结果不算终结"，外层 `turn()` 继续开下一个 step。**`concluded` 为 true 才终结**——这是"工具调用是否还需要下一步"的唯一判据。

**T12. 步骤收尾与轮次收尾**（`:990-1000`）

```js
finally { stopRecovery(); this.session.append("step/end", { turn, step }); }   // :992
if (turnEnds && this.inbox.nextStep.length === 0) await this.dispatch.serial("agent/turn-stopping", {...}); // :998-999
```

步骤失败时先补工具结果再抛：`:980-989` 遍历 `toolRecovery.results()` 逐条 append `tool/result`，失败则抛 `AggregateError`。补的文案在 `dsh-session/lib/types/repair.js:11-18`：

- `TOOL_OUTCOME_UNKNOWN`（有 `tool/call` 记录但无结果）：`The tool call was interrupted after it was recorded, but no result was durably recorded. Its outcome is unknown…`
- `TOOL_NOT_STARTED`（连调用记录都没有）：`The tool call was interrupted before the Harness recorded it as started. Retry it if it is still needed.`

**这条设计非常值得照搬**：失败不是"丢掉这一步"，而是"给每个悬空调用补一条可判读的错误结果"，让下一次请求的工具历史仍然配对完整。

### 2.3 工具调度：独占屏障 + 有界并行池

```js
// dsh-agent-loop/lib/index.js:551 runGroup(...)
const maxParallelToolCalls = ctx.agentLoop.config.maxParallelToolCalls.get(); // :553
while (!aborted && nextToStart < group.length && inFlight.size < maxParallelToolCalls) { ... } // :619
```

- 默认并发 10：`dsh-agent-loop/lib/index.js:1285` `DEFAULT_MAX_PARALLEL_TOOL_CALLS = 10`；配置 schema 默认也是 10 且标记 `volatile()`（`:1534`，可在下一次工具组开始时重读）。
- **独占调用构成排序屏障**，并行安全调用才进池（`:543-544` 注释、`:619`）。
- "是否并行安全"由工具自己声明：`dsh-tools/lib/index.js:3052-3063` —— 只有 `tool.isConcurrencySafe(args) === true` 才判为 `parallel`，**未知/隐藏/未声明/抛异常一律按 exclusive 处理**（fail-safe 方向正确）。
- 结果顺序：注释明确"策略、持久结果与结果上下文保持模型顺序"（`dsh-agent-loop/README.zh.md:119`）。

---

## 3. Session / Surface / Event 模型

### 3.1 事件日志

`Session.append(type, data, opts?)`：`dsh-session/lib/index.js:1441`。每次追加：

1. 用共享的迭代式 `snapshotJsonValue()` 逐层读取-校验-复制一次（防止有状态 getter 在"校验"与"存储"之间给出不同值）；
2. 拒绝一切非无损 JSON（BigInt、循环引用、稀疏数组、`-0`、特殊原型），且**在写后端之前**拒绝（`dsh-session/README.zh.md:108`）；
3. 分配 `SessionSeq`；
4. 通知观察者。

事件类型是**封闭集合**：`dsh-session/lib/types/known-event-types.js:21-81` 列出本构建认识的 **59** 种事件；读路径拒绝解释集合外的类型，除非信封带 `ignorable: true`（同文件 `:7-20` 的注释解释了为什么用"可忽略标记"而不是"事件名注册"）。

### 3.2 surface 是什么

surface 不是另一份存储，而是"事件日志的一个有序投影"：`dsh-session/lib/types/surface.js:1-8`

> Surface layer on top of the session event log: an ordered view of events that produce LLM messages. The append-only log remains the source of truth.

它只包含 **5 类** 事件（`surface.js:13-19`）：`system/message`、`developer/message`、`user/message`、`assistant/message`、`tool/result`。

折叠状态（`createFoldState` `surface.js:185` 起）至少含：

- `nodes: number[]` —— 按**模型可见顺序**排列的 seq 数组（注意：不是按 seq 升序，替换会把新 seq 放到旧位置）；
- `projectedMessages: Map<seq, Message>` —— 插件投影后的不可变消息；
- `replaceGeneration` —— 位置替换次数（单调）；
- `contentGeneration` —— "已提交的模型可见内容发生变化"次数（单调，含替换与投影）。

`applySurfacePlan`（`surface.js:437-460`）是全部语义所在：

```js
if (plan?.kind === 'append')      state.nodes.push(plan.seq);
else if (plan?.kind === 'replace') {
    state.nodes.splice(plan.startIdx, plan.endIdx - plan.startIdx + 1, plan.seq);
    state.replaceGeneration += 1;
    state.contentGeneration += 1;
}
else if (plan?.kind === 'project') { /* 写 projectedMessages */ state.contentGeneration += 1; }
```

### 3.3 surfaceOp 的全部语义

`surfaceOp` 只有两种合法形态，从校验函数读最准（`surface.js:196-205`、`:207-234`）：

| 形态 | 语义 | 校验点 |
|---|---|---|
| `'append'` | 追加到 surface 尾部；event 自己的 seq 即其位置 | `:225-226` |
| `{ op:'replace', startSeq, endSeq }` | 遮蔽一个**闭区间**的地面范围（端点都含），在**原位置**插入新节点 | `isReplaceOp` `:196-205` 要求**恰好 3 个键** |

关键规则（都在 `surfaceOpOf` / `planSurfaceEvent` 里）：

1. **类型与标记强绑定**：非 surface 类型**禁止**携带 `surfaceOp` 或 `sourceEventSeqs`（`:213-218`，违者抛错）；surface 类型**必须**携带（`:222-224`）。所以压缩的 `compaction/*` 事件永远不可能混进 surface。
2. **节点 0 保护**（`assertSystemHeadRewrite` `:388-397`）：
   ```js
   if (event.type !== 'system/message' || shadowedSeqs.length !== 1)
       throw new Error('surface replace: node 0 holds the system prompt and may be rewritten only by a system/message over exactly that node');
   ```
3. **tool/result 只能改写自己那一个结果**（`assertToolResultRewrite` `:356-386`）。
4. seq 必须连续（`planSurfaceEvent` `:400-402`）。

### 3.4 两条 generation 的分工（这是全系统最精巧的一处）

| 计数器 | 谁在消费 | 作用 |
|---|---|---|
| `replaceGeneration` | `dsh-compaction/lib/types/tool-pairing.js:48-50` | 位置被替换 → 工具配对缓存必须整表重建 |
| `contentGeneration` | `dsh-agent-loop/lib/index.js:1054`、`:1211` | 已有内容的模型可见值变了 → 必须开启新的请求序列（缓存前缀不再可信） |

`contentGeneration` 在**投影**时也自增（`surface.js:450`），因为投影会改变已发送过的消息内容——这直接影响缓存与"新序列"判定。复刻时若只统计"结构性替换"而漏掉投影，就会在插件改内容后继续假装前缀没变。

### 3.5 为什么不用"直接维护 messages 数组"

代码与文档给出的理由，按可靠性排序：

1. **可重建性（有代码证据）**：请求由 `header.config + deriveMessages() + header.tools + toolHistory` 构成（`dsh-agent-loop/lib/index.js:1269-1276`），其中 `deriveMessages()` 与 `toolHistory()` 都是日志 fold（`dsh-session/lib/index.js:1554`、`:1525`）。没有 messages 数组可维护，所以不存在"内存与磁盘不一致"。
2. **transcript ≠ 模型视图（有代码证据）**：`surface.js:39-52` 的注释说得很直接——被替换的范围在 surface 上是"遮蔽"，所以 surface 是**错误的人类 transcript 来源**（已落地的替换会抹掉用户已经看过的对话）。因此另设 `isAppendSurfaceEvent()` 作为 transcript 的持久素材。
3. **压缩必须可逆 / 可回放（有代码证据）**：压缩只做一次 surface replace，原始事件全部留在日志里（`dsh-compaction/README.zh.md:97`）。若直接改 messages 数组，摘要就是破坏性写入。
4. **fork 需要精确前缀**：`ctx.sessions.fork(source, boundary)` 复制到切点的**精确事件前缀**（`dsh-session/README.zh.md:68`）。数组只能复制"当前值"，无法复制"某个时刻的值"。
5. **投影可插拔**：插件可注册纯处理器改动消息内容而不改消息身份（`dsh-session/README.zh.md:54`、`surface.js:405-413`）。数组方案里这是"随便改"。

「推断」第 6 条（无直接注释，但为代码结构所迫）：增量折叠的成本可控。`deriveMessages()` 的注释写明了代价模型：

```js
// dsh-session/lib/index.js:1546-1551
* CACHED: pure tail growth costs O(new nodes); a replacement or message projection
* (SessionSurface.contentGeneration) rebuilds. The returned array is
* a fresh snapshot per call (later appends never grow an array a caller
* already holds); the `Message` objects in it are SHARED and **deep-frozen**.
```

实现见 `dsh-session/lib/index.js:1554-1569`：`derivedNodes` 水位 + `derivedGeneration` 失效 + 每次返回 `[...this.derived]`（新数组、共享冻结消息）。

---

## 4. 消息装配（assembler）

### 4.1 先纠正一个术语陷阱

DSH 里有两个完全不同的 "assembler"：

| 名字 | 干什么 | 位置 |
|---|---|---|
| `BlockAssembler` | **流分片 → 内容块**（`block-start`/`text-delta`/`usage`/`finish` 增量拼装） | `dsh-llm/lib/types/assembler.js`，用法 `dsh-llm/README.zh.md:47` |
| `Session.deriveMessages()` | **surface → 模型 messages 数组** | `dsh-session/lib/index.js:1554` |

"最终发给模型的 messages 怎么来的" 的答案是后者。前者只负责把一次流式响应还原成块。

### 4.2 surface → messages 的投影规则

`deriveEventMessage`（`dsh-session/lib/types/surface.js:75-112`）是唯一的投影函数：

```js
case 'user/message':     return event.data;                       // :91-93 原样直通
case 'system/message':
case 'developer/message':
case 'assistant/message':
    if (event.data.message.content.length === 0) return null;     // :100-101 空内容不投影
    return event.data.message;
case 'tool/result':      return event.data.message;               // :104-106
default:                 return null;                             // :107-111 边界/attempt/仅日志事件
```

三个要点：

1. **`user/message` 是逐字节直通**。注释 `:83-90` 明确禁止在这里加 `<context>` 之类的包装：框架文本由生产者烘进 `content`（例如 `dsh-agent-instructions` 用 `<system-reminder>`），否则会同时破坏"投影是纯函数"和缓存稳定性。
2. **空 system/developer/assistant 不产生消息，但保留 surface 位置**（`:94-102` 注释）。空 assistant 是用来承载 max-tokens 步骤的 usage 的。
3. **非表面事件一律 `null`**，且故意写成非穷尽 switch（`:79-81`、`:108-110`），因为 `SessionEventMap` 是可合并扩展的。

### 4.3 system 如何占位

**请求里没有 `system` 字段。** 看请求对象（`dsh-agent-loop/lib/index.js:1269-1276`）：

```js
return markAgentLoopRequest(Object.freeze({
  ...header.config,                 // provider / model / reasoningEffort / maxTokens …
  messages: boundaryMessages,       // 含 system/message 派生出的 system 角色消息
  toolHistory: session.toolHistory(),
  ...header.tools !== void 0 ? { tools: header.tools } : {},
  sessionId: this.session.id,
  signal,
}));
```

系统提示词是**历史里的第 0 号 surface 节点**，靠 `deriveMessages()` 变成 `messages[0]`。`dsh-session/README.zh.md:92` 与 `dsh-agent-loop/README.zh.md:119` 都强调这点（"请求不携带 `system` 字段"）。

这个选择带来两个后果：

- 好：系统提示词可以被 surface 机制统一处理（替换、压缩遮蔽、清空），不需要单独的同步逻辑。
- 险：它天然是"最容易让缓存全灭"的节点——改它等于从第 1 个 token 起全部失效。

**空提示词的表示**：`rendered === ''` 时不是"没有 system 消息"，而是**给每个生效的 system 节点各写一条空内容替换**（`dsh-agent-loop/lib/index.js:272-275`）。理由在 `dsh-session/README.zh.md:58`：空节点不投影为消息，所以只替换最新节点不足以清掉旧指令。

### 4.4 工具 schema 放在哪里

- **权威位置**：`request/header.tools`，由 `canonicalHeader({config, adapterDefaults, tools})` 生成（`dsh-agent-loop/lib/index.js:1205-1209`）。
- **记录时机**（`:1213-1228`）：首次 `reason:"initial"`；恢复 `"resume"`；内容变了 `"change"`；仅序列起点变了 `"series"`；否则继承最新快照（不重复写）。
- **变更的模型可见形式**：不重写 header，而是追加一条 `developer/message`，内容是 `tool-addition` / `tool-removal` 块（`:1229-1247`）；添加块携带 `headerSeq` 指回那份 header（`:1246`）。
- **运行时**：`request.toolHistory = session.toolHistory()`（`:1272`），由 `ToolHistoryProjection` 增量折叠（`dsh-session/lib/index.js:1517-1528`）。适配器据此在 `in-history` 模式下做延迟声明与保留声明（`dsh-llm/README.zh.md:162`）。

**这就是"工具集可动态变化而不破坏前缀"的完整机制**：新增工具写在追加的 developer 消息里，且只对支持该能力的路由启用；不支持的路由则整体换成新声明 + 开新序列。

### 4.5 工具调用与结果的配对

配对信息**只存在于 surface 内容里**——`assistant/message` 的 `tool-call` 块与后续 `tool/result` 节点，不靠 step 标记。这一点在压缩里被写成显式注释（`dsh-compaction/lib/types/tool-pairing.js:1-6`）：

> Compaction changes surface positions, so safe cuts are derived from tool-call/result content in current surface order rather than step markers.

增量平衡算法（同文件）：

```js
case 'assistant/message': return content.filter(b => b.type === 'tool-call').length;  // :11-12
case 'tool/result':       return -1;                                                  // :13-14
...
inProgressToolCalls += eventDelta(event);
if (inProgressToolCalls < 0) throw ... 'tool/result … has no matching tool-call (corrupt surface)'  // :34-36
pendingCuts.push(inProgressToolCalls === 0);                                          // :37
```

即：`cutBalanced[i] === true` 表示"在第 i 个节点之前切一刀，没有未应答的工具调用跨越它"。缓存随 `replaceGeneration` 重建（`:48-50`），且先校验尾部再提交（`:23-25`）。

### 4.6 流式中断 / 截断时如何收尾

三种收尾路径，都会**恰好落一个持久事件**，不会留下悬空流：

| 情形 | 落的持久事件 | surface 影响 | 行号 |
|---|---|---|---|
| 用户取消（`signal.aborted`） | `assistant/message` + `interrupted: true` + 已交付前缀 | append（模型下次能看到自己已说的话） | `:1084-1100` |
| 传输/适配器异常且已 start | `assistant/attempt`（只有紧凑 stream） | **无**（`deriveEventMessage` 返回 null） | `:1101-1110` |
| 适配器终态 error/aborted | 先 `assistant/attempt`，再 waterfall 决定是否 retry | 无 | `:1118-1134` |
| 正常但被 max-tokens 截断 | `assistant/message`（可能空内容）+ usage | append（空内容则不投影） | `:1144-1151` |
| 步骤失败（工具/settle 异常） | 逐条合成 `tool/result` 错误结果 | append | `:980-989` + `repair.js:11-18` |

注释 `dsh-session/README.zh.md:88` 解释了为什么 attempt 事件不进历史：**"如果进程在 settlement 前硬中断，则不会留下持久 attempt stream"**——attempt 是诊断/回放数据，不是模型历史。

---

## 5. 系统提示词组装

### 5.1 两阶段：求值 → 渲染

`dsh-system-prompt/README.zh.md:107` 定义了拆分：

- `assemble()`：返回**文本已求值但尚未插值**的段、有序工具 schema、每个变量按当前上下文的取值。
- `renderPrompt()`：对未 `interpolate:false` 的段插值 `{{var}}`、**删除空段**、用空行连接（`dsh-system-prompt/lib/index.js:113-115`）。

严格性要求：未知引用、已注册但无值的引用、畸形完整组都会抛错（`lib/index.js:153-160`）。README 的理由是"格式错误的提示词比明确失败更糟"（`README.zh.md:107`）。

### 5.2 固定槽位表（这是可以照抄的部分）

`dsh-system-prompt/lib/index.js:10-43` 是一张硬编码的顺序表：

| order | 槽位 | 说明 |
|---|---|---|
| `-1000` | `HARNESS_IDENTITY` | 固定开场白 `You are an AI agent powered by DeepSeek Harness.` |
| `0` | `DEPLOYMENT_PERSONA_PREFIX` | 部署 persona 前缀（含模型名介绍） |
| `500` | `PLAN_POLICY` | |
| `600` | `TEAM_POLICY` | |
| `800` | `PTC_ONLY` | |
| `900` | `FILE_REFERENCE` | |
| `1000`–`1700` | `TOOL_BASH` … `TOOL_PTY` | 逐工具的使用指南段（间隔 100，留插槽） |
| `2000`–`3000` | `TOOL_WEB_SEARCH` … `TOOL_COMPUTER_USE` | |
| `3100` | `MCP_SERVERS` | |
| `5000` | `TOOLS_SDK` | 生成的工具 SDK 文档 |
| `9000` | `DELIVERABLE_FILE_REFERENCES` | |
| `9900` | `STRUCTURED_OUTPUT` | 结构化输出指导 |
| `10000` | `HARNESS_SOURCE` | harness 源码环境信息 |
| `10100` | `WEB_SURFACE` | Web 表层环境信息 |
| `10200` | `DEPLOYMENT_PERSONA_SUFFIX` | 部署 persona 后缀 |

动态运行时上下文另有独立表 `CONTEXT_ORDERS`（`:44-48`）：`SANDBOX_POLICY:110`、`APPROVAL_POLICY:115`、`SUBAGENT_DELEGATION:120`。

设计要点：**稀疏具名常量 + 间隔 100**，让插件可以在不重排既有槽位的前提下插到自己想要的位置，而不需要中心协调。

### 5.3 确定性序列化

排序函数（`lib/index.js:96-99`）：

```js
/** Order prompt sections by their explicit placement, then deterministically by name. */
return a.order - b.order || compareNames(a.name, b.name);
/** Code-unit name comparison — locale-independent, so the order is identical on every machine. */  // :92-93
```

用 **code-unit 比较而非 `localeCompare`**：跨机器、跨 locale 结果一致。这是"确定性"的必要条件，因为顺序抖动会逐字节改变前缀。

工具顺序（`orderTools` `:83-90`）同理：未配置时按 `compareToolNames` 字典序；配置 `toolOrder` 时强制要求**恰好一个** `"<unlisted-tools>"` 占位（`:68-76`），未列出的工具在占位处按字典序插入（`:89-90`）。重复项、未注册名都抛错。

### 5.4 system prompt 变更时的处理策略（核心）

决策全在 `dsh-agent-loop/lib/index.js:264-282`：

```js
project(rendered, input) {
  const nodes = this.systemNodes();
  const head = nodes[0];
  if (head === void 0) return [{ message: createSystemMessage(rendered), intent: { surfaceOp: "append" } }];   // :267-270
  const latest = nodes.findLast((node) => node.text !== "") ?? head;                                            // :271
  if (!input.inHistory || input.startsSeries || rendered.length === 0) {                                        // :272
    const updates = nodes.slice(1).filter((node) => node.text !== "").map((node) => this.replace(node.seq, "")); // :273
    if (head.text !== rendered) updates.push(this.replace(head.seq, rendered));                                 // :274
    return updates;
  }
  if (latest.text === rendered) return [];                                                                      // :277 无变化 → 零事件
  return [{ message: createSystemMessage(rendered), intent: { surfaceOp: "append" } }];                          // :278-281 in-history 追加
}
```

翻译成策略矩阵：

| 路由能力 | 序列状态 | 提示词变化 | 行为 | 缓存后果 |
|---|---|---|---|---|
| 任意 | 新序列 | 有变化 | 清空后续非空节点 + **回写 node 0** | 从 node 0 起（即整段前缀）失效 |
| 任意 | 任意 | 变空 | 逐节点写空替换（含 node 0） | 同上 |
| `in-history` | 延续 | 无变化 | **不产生任何事件** | 完全不动 |
| `in-history` | 延续 | 非空变化 | **追加**一条新 system/message | 已缓存历史到末尾仍可复用，只有新增部分未命中 |
| 无 `in-history` | 延续 | 非空变化 | 回写 node 0 | 从第 0 节点起失效 |

替换操作的 intent 携带 `sourceEventSeqs: [seq]`（`:283-295`），使"这个节点替换了谁"可追溯。

`in-history` 能力从哪来：`preparedCall.systemPromptUpdate`（`:1053`），由适配器声明——"当模型把任意位置最新的 `system` 消息读作有效系统提示词时为 `'in-history'`，只读开头 system 消息时该字段缺失"（`dsh-llm/README.zh.md:68`）。

**注意 `latest` 那一行的语义**（`:271`）：有效提示词是"最后一个非空 system 节点"，不是"node 0"。所以 in-history 模式下 node 0 可能是陈旧文本，模型读到的是最新的那条。这也是为什么压缩范围里遮蔽后续 system 节点是安全的（`dsh-compaction-basic/README.zh.md:113`）。

---

## 6. Token 计量与预算

### 6.1 估算算法（逐字对应代码）

`dsh-token-meter/lib/types/estimate.js`：

```js
const CHARS_PER_TOKEN = 4;   // :9  "Fixed text-density estimate used until exact tokenization is needed."
const BLOCK_OVERHEAD  = 4;   // :11 每个块的 JSON 框架/类型标签开销
export const ROLE_OVERHEAD = 4; // :13 每条消息的 role 字段开销
```

计价规则：

| 对象 | 公式 | 行号 |
|---|---|---|
| text / reasoning 块 | `ceil(len/4) + 4` | `:38-40` |
| tool-call 块 | `ceil(nameLen/4) + ceil(argsLen/4) + 4` | `:42-46` |
| 其他块（含图片引用、未知块） | `4 + ceil(JSON.stringify(block).length/4)` | `:22-28` |
| system 消息 | `ceil(总字符/4) + ROLE_OVERHEAD`；空内容为 0 | `:64-72` |
| 普通消息 | `estimateContent(content) + ROLE_OVERHEAD` | `:79-83` |
| 工具 schema（**envelope 里唯一计价的字段**） | `ceil(JSON.stringify(header.tools).length/4) + 4` | `:90-94` |

注意最后一行注释（`:85-87`）：**系统提示词不进 envelope 计价，因为它是 surface 节点**。这句话把"surface 是唯一真相"贯彻到了计量层。

### 6.2 两种口径必须分开

- `heuristicTokens`（**路由无关**）：无状态启发式，供替换影子价、`contextBreakdown` 使用。
- **路由定价总量**：图片按适配器声明的 `imageRequestPricing` 计价（`dsh-token-meter/README.zh.md:43`），文件按模型可见句柄文本计价。

两者**按构造**不等于彼此（`README.zh.md:159`），所以文档反复强调"构成行是近似，不要当总量"（`:154`）。

### 6.3 provider usage 锚定机制

这是全系统最值得照搬的一处工程技巧。`dsh-token-meter/lib/index.js:641-676`：

```js
const anchor = state.anchor;
if (anchor !== void 0 && optionalHeaderEquals(anchor.header, header)) {            // :650 只比 envelope
  const anchorSurfaceTokens = priceSurface(anchor.nodes, pricing, fileText).surfaceTokens + anchor.assistantTokens;
  const estimatedAnchorTokens = estimateToolsTokens(header) + anchorSurfaceTokens;
  const usage = anchor.usage;
  baseline = usage !== void 0 && usageTokens(usage) >= estimatedAnchorTokens
    ? { kind: "usage", tokens: usageTokens(usage), usage }                          // :654-657 用真值
    : { kind: "estimated", tokens: estimatedAnchorTokens };                          // :658-661 用估算
  surfaceDeltaTokens = surface.surfaceTokens - anchorSurfaceTokens;                  // :662 之后按带符号 delta
} else if (header === void 0 && surface.surfaceTokens === 0) { … }
else { baseline = { kind: "estimated", tokens: estimateToolsTokens(header) + surface.surfaceTokens }; surfaceDeltaTokens = 0; }
```

规则：

1. **锚定条件 = 规范 envelope 完全相等**（provider/model/reasoningEffort/maxTokens/adapterDefaults/tools）。工具、模型、调用配置任一变，立刻回退到全量估算。
2. **只接受"不低于估算"的 provider 数**（`:654`）。这条防御避免了"provider 少报导致预算被低估、进而溢出"。
3. 表面变化**不做全量重估，而是相对锚点取带符号增量**（`:662`），包括缩减替换后的负 delta（`README.zh.md:43`）。
4. 锚点包含"成功 assistant 消息之前的全部已计价 surface，含 step/start 之后接纳的系统与用户消息"（`:630-631` 注释）。

为什么这个设计重要：**估算器可以很粗糙，只要每隔几步能用 provider 的真值把误差归零**。这是把"4 chars/token 不可靠"降级为"局部误差"的关键。

### 6.4 三个投影字段

`contextPressure`（`dsh-token-meter/lib/types/usage-projection.js:163-186`）：

- `pressureTokens` = 最新一次 provider 报告的提示词规模（**prompt 侧专属**，流式期间保持不动，`:129-133`）；
- `projectedTokens = max(0, pressureTokens + surfaceTokens − sampledSurfaceTokens)`（`:185`）；
- `contextWindow` = 来自最新 `request/context` 记录。

`tokenUsage` 另给 `uncachedInputTokens` / `outputTokens` / `cacheReadTokens` / `cacheWriteTokens`（`dsh-token-meter/README.zh.md:49`）。重试会贡献另一次计费用量（同处）。

### 6.5 contextWindow / maxTokens / headroom 的分配公式

**全部在 `dsh-compaction-basic/lib/index.js:124-146` 一个函数里**，没有第二处实现：

```js
const messageBudgetTokens  = contextWindow - reservedCompletionTokens;              // :128  W − O
const pressureBudgetTokens = messageBudgetTokens - policy.headroomTokens;           // :130  W − O − B
const thresholdTokens = Math.floor(Math.min(contextWindow * policy.thresholdRatio, pressureBudgetTokens)); // :132
const retainTokens = policy.retainTokens === void 0
    ? Math.floor(messageBudgetTokens * policy.retainRatio)                          // :133  (W − O) × r
    : policy.retainTokens;
if (retainTokens >= thresholdTokens) throw ...                                       // :134
```

其中：

| 符号 | 取值 | 出处 |
|---|---|---|
| `W` contextWindow | 适配器声明、按**已路由**模型解析；不咨询 `listModels()` | `README.zh.md:129` |
| `O` reservedCompletionTokens | `header.config.maxTokens ?? 适配器默认 ?? 0` | `:757-759` |
| `B` headroomTokens | 默认 `65536` | `:63` |
| `thresholdRatio` | 默认 `0.8` | `README.zh.md:66` |
| `retainRatio` | 默认 `0.16`（以 `W−O` 为基数） | `README.zh.md:68` |
| 摘要 `maxTokens` | 默认 = `headroomTokens` | `:64` |

即默认触发阈值 `floor(min(W×0.8, W−O−65536))`，逐字保留 `floor((W−O)×0.16)`。两条硬校验：`W−O−B > 0`（`:131` 抛 `TargetPressureConfigError`）、`retainTokens < thresholdTokens`（`:134`）。

**复刻警示**：`B=65536` 是给 128k 级窗口调的。64k 窗口模型下 `W−O−B` 直接 ≤ 0，插件会拒绝加载（不是静默失效）。

---

## 7. 压缩（compaction）

### 7.1 触发条件

| 触发 | 挂点 | 条件 | 行号 |
|---|---|---|---|
| `pressure`（压力） | `agent/pre-step` 串行监听器 | 用 meter 给"最新持久路由 envelope"定价，越过路由模型阈值 | `dsh-compaction-basic/lib/index.js:839-841` |
| `context-overflow`（溢出恢复） | `agent/request-error` | `failure.code === CONTEXT_WINDOW_EXCEEDED` 且未 abort | `:861-872` |
| 手动 | `compactNow()` | `/compact` 命令，无论是否到阈值 | `:985` |
| 显式范围 | `compactRegion(start,end)` | 调用方指定 surface 范围，要求存在开放轮次 | `:971`、`:460` |

压力触发的完整动作序列（`compactIfNeeded` `:914-934`）：`auto` 开启 → 定价 → 越阈值 → **先修剪（若挂了 pruner）** → 选段 → 摘要。

溢出恢复的特权（`dsh-compaction-basic/README.zh.md:113`）：**绕过常规阈值与保留策略**，尝试一次"最大平衡头部缩减"，并且只在 `replace` 生成代号前进后才授权重试。

### 7.2 选段算法（含 tool 配对保护）

`selectCompactableRange`（`dsh-compaction-basic/lib/index.js:410-433`），四步：

```js
// 1) 计量与 surface 必须逐 seq 一致，否则拒绝（防止用陈旧计量切当前 surface）
if (surfaceNodes.length !== pricedNodes.length || surfaceNodes.some((seq,i) => seq !== pricedNodes[i]?.seq))
    throw new Error("compaction: token-meter surface does not match the current session surface");   // :414

// 2) 起点：node 0 若是 system/message 则从 1 开始（系统提示词永不被遮蔽）
const firstIdx = systemHead(session, surfaceNodes[0]) === void 0 ? 0 : 1;                            // :415

// 3) 从尾部向前累加到 retainTokens，得到保留边界
for (let index = pricedNodes.length - 1; index >= 0; index -= 1) {
    accumulated += pricedNodes[index].tokens;
    keepFromIdx = index;
    if (accumulated >= retainTokens) break;                                                          // :418-422
}
if (keepFromIdx <= firstIdx) return null;                                                            // :423 无可压缩

// 4) 边界必须工具配对平衡，不平衡就继续往前退
while (keepFromIdx > firstIdx) {
    if (toolPairingBalancedBefore(session, surfaceNodes[keepFromIdx])) break;
    keepFromIdx -= 1;                                                                                // :424-427
}
```

返回 `{ start: surfaceNodes[firstIdx], end: surfaceNodes[keepFromIdx-1] }`——**闭区间的 seq 范围**。

关键点：

- **选段用的是"路由定价"的每节点 token，不是启发式**（`README.zh.md:106`）。
- 缩进策略是"平衡头部缩减"：从最老开始、到保留边界为止，而不是随便挑一段。
- 因为 `system/message` 在 node 0，`firstIdx=1` 保证了压缩**永不缩小系统提示词**（`README.zh.md:162`：「压缩缩减派生历史，绝不缩减系统提示词、工具或会话前缀」）。
- 内存中还有一个对 `retainTokens` 的"不可分单元"边界：若无平衡切点可用，返回 `null`，**一个字节都不写**（`README.zh.md:82`）。

### 7.3 重建方式：一条 checkpoint user 消息

surface 只变更一次：一条 `user/message` 携带 `surfaceOp:{op:'replace',startSeq,endSeq}`（`dsh-compaction/README.zh.md:95`）。框定内容（`dsh-compaction-basic/lib/index.js:344-356`、常量 `:234-235`、`:280`）：

```
＜CHECKPOINT_PREAMBLE＞
（空行）
<compacted-summary>
……模型返回的摘要……
</compacted-summary>
```

`CHECKPOINT_PREAMBLE` 原文（`:280`）：

> This is an automatically generated checkpoint condensing an earlier span of the conversation to free up context. Treat the captured context as established background and build on it without restating it. Continue the task directly from the messages that follow, without acknowledging this checkpoint.

三个设计点值得照搬：

1. **明确的"不要复述、不要确认"指令**——否则模型会在下一轮把摘要重复一遍，白白花 token。
2. **原始摘要另存**在 `compaction/summary` 事件上（`README.zh.md:119`），框定的只是模型可见视图。
3. **拒绝不缩小源内容的摘要**（`README.zh.md:125`："拒绝不缩小源内容的摘要"），防止"压缩反而变长"。

### 7.4 事务与锁（崩溃可检测）

标记对就是锁（`dsh-compaction/README.zh.md:73`、`dsh-compaction-basic/lib/index.js:536-550`）：

```
compaction/start      ← 仅日志，先写；写成功即持锁
compaction/summary    ← 仅日志，摘要原文
user/message replace  ← 唯一的 surface 变更，位于锁区间【内】
compaction/end        ← 仅日志，恰好尝试一次
```

- `inspectCompactionEntryState`（`:706-733`）反向扫描日志，同时判定"是否有未匹配的 `compaction/start`""是否有开放轮次""最新 `session/end-seed` 在哪"。
- 未匹配 start 若在最新的 `session/end-seed` **之后** → 活动锁，报 `busy`；在之前 → 上个进程生命周期的陈旧证据，不阻塞（`dsh-compaction/README.zh.md:112`）。
- 闭合失败故意留下未匹配 start，作为"busy 信号"而不是假装成功（`:73`、`:125`）。
- 每次失败**恰好一次** `compaction/end` 尝试（`:125`）。

**把 start 放在 surface 替换之前、end 放在之后**，使得"在两者之间崩溃"留下可检测的遗留锁。这个顺序如果反了，崩溃就会变成"数据已改但没人知道"。

### 7.5 摘要调用如何复用 KV cache

这是 §8 里最硬的一条证据。`buildSummarizationInput`（`dsh-compaction-basic/lib/index.js:695-704`）：

```js
function buildSummarizationInput(session, shadowedSeqs) {
  const header = session.requestHeader();
  const head = systemHead(session, session.surface.nodes[0]);
  const system = head === void 0 ? null : session.deriveEventMessage(head);
  const regionMessages = shadowedSeqs.map((seq) => session.deriveEventMessage(session.eventAt(seq))).filter(m => m !== null);
  return {
    ...header?.tools === void 0 ? {} : { tools: header.tools },     // :701 沿用最后一次生效的 tools
    messages: system === null ? regionMessages : [system, ...regionMessages],  // :702 系统提示词原样打头
  };
}
```

配合 `summarizeWithLlm`（`:292-338`）：

```js
const messages = [...input.messages, deepFreeze({ role:"user", content:[{ type:"text", text: COMPACTION_INSTRUCTION }] })]; // :305-311
const options = { provider, model, messages, toolHistory: agent.session.toolHistory(), ... input.tools === void 0 ? {} : { tools: [...input.tools] }, maxTokens, sessionId, purpose: "compaction", signal }; // :312-322
```

于是摘要请求 = **与会话最后一个真实请求逐字节相同的前缀（system head + 被遮蔽区域消息 + tools）** + 尾部一条私有指令。`dsh-compaction-basic/README.zh.md:234` 的表述：

> 已回放系统提示词、工具与已遮蔽区域消息与会话最后一个已路由请求逐字匹配，因此提供方的热前缀 cache 可复用至尾随指令之前；只有该指令与摘要输出未缓存。

**放弃复用的两种情形**（同处）：把摘要器路由到不同 provider/model，或压缩非头部范围。

另外：`purpose: "compaction"`（`:320`）是显式标记，只取返回文本、排除推理与工具调用（`:327-328`），图片输出会以 `UNSUPPORTED_CONTENT` 失败而不是静默丢弃（`README.zh.md:119`）。

### 7.6 失败与重试策略

| 机制 | 值/行为 | 出处 |
|---|---|---|
| `compactionRetries` | 默认 `1`（仍超阈值时再试几次） | `:83`、`README.zh.md:73` |
| `maxOverflowRetries` | 默认 `1`；`0` 禁用溢出恢复 | `:83`、`README.zh.md:74` |
| 摘要失败 | 保留最新持久 surface，超预算继续跑（自动路径记警告） | `README.zh.md:247` |
| 手动失败 | 抛 `ManualCompactionError`，稳定 `code`；只有 start 之后的失败才被记录 | `dsh-compaction/README.zh.md:86` |
| 恢复钩子 | 同步 `compaction/summary-error` waterfall，监听器必须先记录持久输入变更才能请求重试 | `README.zh.md:84`、`:123` |
| 选区稳定性 | 自动调用要求**整个 surface** 不变；手动只要求**所选 span** 不变 | `:609-627` |
| 取消 | 检查取消与选区稳定性**在**触发 waterfall **之前**；取消始终有最终决定权 | `README.zh.md:123`、`:125` |
| pruner 前置 | 修剪不发模型调用，修剪后已达标则**完全跳过摘要** | `dsh-compaction-basic/README.zh.md:90` |

---

## 8. 缓存友好性：把所有"为了前缀命中"的设计集中看

> 本节是全手册对复刻最有价值的一节。**先给结论：DSH 里没有任何 cache breakpoint / `cache_control` 标记。缓存命中完全靠"前缀逐字节不变"这个结构性质。**

### 8.1 先钉死事实：没有显式缓存标记

我对 `@deepseek-ai` 全树做了扫描（跳过 `dsh-client-ui-*` 与 `dsh-web-frontend`，共扫描 **1281 个** `.js/.json` 文件，逐行匹配 `cache_control|cacheControl|cacheBreakpoint|cache_breakpoint|prompt_cache|promptCacheKey|ephemeral`），命中 **7 处**，其中与本议题相关的只有 5 处，且全部在**第三方多提供方适配器**里：

```
dsh-llm-pi-ai/lib/index.js:331  const CACHE_CONTROL_FORMATS = Object.keys({ anthropic: true });
dsh-llm-pi-ai/lib/index.js:397  cacheControlFormat: "offer",
dsh-llm-pi-ai/lib/index.js:439  supportsCacheControlOnTools: "offer",
dsh-llm-pi-ai/lib/index.js:982  cacheControlFormat: z.union(CACHE_CONTROL_FORMATS),
dsh-llm-pi-ai/lib/index.js:985  supportsCacheControlOnTools: z.boolean(),
```

这些是 **compat gate**：一张"某个第三方模型档案**允许**配置哪些字段"的白名单（同文件 `:388-407` 的 `COMPLETIONS_COMPAT_GATE`、`:409-420` 的 `RESPONSES_COMPAT_GATE` 都是同一张模式的表，取值只有 `"offer"`/`"withhold"`）。也就是说：**DSH 自己从不写缓存标记，它只是不阻止上游 pi-ai 库按它自己的协议写。**

第一方 DeepSeek 适配器里，`cache` 只出现在**读取**方向（`dsh-llm-deepseek/lib/index.js`）：

```
:1814  cache_read_input_tokens: "cacheReadTokens",
:1815  cache_creation_input_tokens: "cacheWriteTokens"
:1994  usage.totalTokens = usage.inputTokens + usage.outputTokens + (usage.cacheReadTokens ?? 0) + (usage.cacheWriteTokens ?? 0);
```

即：**只解析提供方回报的缓存命中/写入用量并计入总量，从不发送任何缓存意图。**

文档也承认词汇是空的：`dsh-llm/README.zh.md:159` —— "`prefill`、逐工具 `strict`、**内容块 `cache` 提示**和 `agent` 消息来源变体**都没有产生方**"。

### 8.2 那么命中靠什么？六条设计

**① 只追加（append-only）**

surface 的 `append` 只 `push` seq（`dsh-session/lib/types/surface.js:438-440`），不触碰已有节点。所以普通历史增长时，请求前缀的旧部分是**同一个对象、同一串字节**。

每处都明确标注了 KV Cache 影响：

- 派生消息历史：`dsh-session/README.zh.md:154` —— "追加的 surface 条目会保留可复用前缀。即使底层事件日志保持仅追加，`replace` 操作也会从首条被遮蔽消息起使缓存复用失效。"
- 取消后的合成结果：`dsh-agent-loop/README.zh.md:192` —— "仅追加；每个合成结果都位于可复用请求前缀之后，不会使现有 KV Cache 条目失效。"
- 工具结果恢复：`dsh-session/README.zh.md:168` —— 同上。

**② 易变内容置尾**

| 内容 | 位置策略 | 证据 |
|---|---|---|
| 动态运行时上下文（沙箱策略/审批策略/委派） | **不进 system**，而是作为带 source 的 **user 角色快照**追加到历史尾部 | `dsh-agent-loop/lib/index.js:334-349`；`dsh-system-prompt/README.zh.md:96` |
| 上下文的抖动抑制 | 只有渲染值真的变了才产生新快照：`if (this.retained?.text === snapshot) return;` | `dsh-agent-loop/lib/index.js:337` |
| 时间上下文 | `refreshIntervalMs` 冷却，未到期不注入新时间戳 | `dsh-time-context/lib/index.js:221-222` |
| 压缩指令 | 作为**最后一条 user 消息**追加 | `dsh-compaction-basic/lib/index.js:305-311` |
| 工具增删通知 | 追加 `developer/message`，不改 header 前缀 | `dsh-agent-loop/lib/index.js:1240-1247` |

这一组是"把易变的东西全部推到尾巴，让不动的部分尽量长"的系统性实践。

**③ 确定性排序**

- 段排序：`order` 升序 → code-unit 名称序（`dsh-system-prompt/lib/index.js:96-99`，`:92-93` 明确"locale-independent, so the order is identical on every machine"）。
- 工具排序：未配置按字典序；配置了必须带唯一 `"<unlisted-tools>"` 占位，未列出者在占位处按字典序（`:83-90`）。
- envelope 规范化：`canonicalHeader(...)`（`dsh-agent-loop/lib/index.js:1205`）用于**逐字段比较**，`headerEquals` 决定是否值得写新 header。

**④ 真前缀复用（最有说服力的一个）**

摘要请求打头重放的是 `deriveEventMessage(surface node 0)` 的**同一份消息对象**，而不是重新渲染一份"看起来一样"的系统提示词（`dsh-compaction-basic/lib/index.js:695-704`）。README 直接把它称作"摘要复用提供方的热前缀"（`README.zh.md:108`、`:234`）。

**⑤ 变更定位策略：能追加就不改写**

`in-history` 路由上是**追加**新 system 节点而不是回写 node 0（`dsh-agent-loop/lib/index.js:278-281`）。缓存后果被文档精确描述（`dsh-system-prompt/README.zh.md:151`）：

> 没有 `systemPromptUpdate` 时…头节点重写会从首个变化的 token 起失去前缀复用；当已准备调用声明 `systemPromptUpdate: 'in-history'` 时…直到该历史末尾的前缀仍可复用。

**⑥ 用"代号"而不是"内容比较"来决定是否可复用**

`contentGeneration`（`surface.js:443-450`）让循环用一次整数比较就知道"已有内容变过没有"（`dsh-agent-loop/lib/index.js:1054`、`:1211`），避免深度比较，也避免漏判（投影也算变更）。

### 8.3 一个诚实的边界

`dsh-system-prompt/README.zh.md:151` 结尾：

> 任何变更都可能从第一个变化的 token 起使复用失效；**不保证提供方共享缓存或实际命中率**。

也就是说：DSH 保证的是"我这边前缀逐字节一致"，不保证 provider 真的缓存了。**复刻时不要把"架构友好"当成"命中率有保证"。**

---

## 9. 可迁移到 Minecraft 模组的要点

### 9.1 依赖 DSH 进程模型、**不可**直接照搬

| 设计 | 为什么依赖进程模型 | 证据 |
|---|---|---|
| 多会话长期驻留 + 每会话独立 fold 状态 | 每个 `Session` 持有自己的 surface fold 缓存、派生消息缓存、tool 配对缓存（`WeakMap` 键是 session 对象） | `dsh-session/lib/index.js:1248`、`dsh-compaction/lib/types/tool-pairing.js:7` |
| Agent 生命周期与资源级联释放 | 依赖 Cordis fiber / `AgentHandle.dispose()` 的精确拆除与记忆化 teardown | `dsh-agent-loop/lib/index.js:1655-1780`、`dsh-agent-loop/README.zh.md:111` |
| 协作式取消 | 依赖 `AbortSignal` 贯穿 fetch/流/工具 | `dsh-agent-loop/lib/index.js:1045`、`:1073`、`:1077` |
| 后台任务 | 需要"控制器 + 有界输出环 + 结算事件通知"三件套；任务按 agent 会话隔离 | `dsh-jobs/README.zh.md:12`、`:32-38` |
| 子 agent | 需要独立的 session + agent + 提供方后端路由 | `dsh-subagent/README.zh.md:12`、`:28-39` |
| 事件循环保证 | `append` 是同步调用，同一 agent 的步骤天然串行；没有跨线程互斥问题 | 「推断」，依据 `dsh-session/lib/index.js:1441` 的同步签名 + 单进程事件循环 |

**Minecraft 侧的对应风险**（工程建议，非 DSH 结论）：

- 服务端主线程是 tick 线程，**不能在里面 `await` 模型调用**。必须把 agent 循环放到独立 executor，产生的 append 通过队列投回主线程或由一个串行队列统一落盘。
- DSH 靠"单进程 + 同步 append"免费得到的互斥，在 MC 里没有。同一 session 的 `append` 必须自己加串行化（例如 per-session 队列）。
- Folia 的多区域线程模型会让"全局单例 session store"变成竞争点，需要按 region/玩家分片。

### 9.2 **可以照抄**的纯策略

1. **事件日志 + surface 投影**：只追加日志（`surfaceOp:'append'`）+ 一个"遮蔽式替换"操作，就能同时得到模型视图与用户 transcript 两个视图。
2. **两条 generation 计数**：`replaceGeneration`（位置变了）+ `contentGeneration`（内容变了）。前者驱动缓存重建，后者驱动"必须开新请求序列"。
3. **system 提示词分区 + 固定 order 槽位 + code-unit 确定性排序**：整张 `SECTION_ORDERS` 表可以直接搬（`dsh-system-prompt/lib/index.js:10-48`）。
4. **in-history vs 回写 node 0** 的二选一策略（§5.4 矩阵）。
5. **4 chars/token + provider usage 锚定**（§6.3）：估算器可以粗糙，靠锚点纠偏。
6. **`floor(min(W×ratio, W−O−B))` 阈值 + `(W−O)×retainRatio` 保留** 公式（§6.5）。
7. **tool 配对平衡切点**：`+1 / −1 / ===0` 三行算法（§4.5）。
8. **检查点框定文案**（§7.3）：包含"不要复述、不要确认"这条指令。
9. **失败补悬空工具结果**（`TOOL_OUTCOME_UNKNOWN` / `TOOL_NOT_STARTED`），让下一次请求历史仍然配对完整（§2.2 T12）。
10. **压缩标记对当锁**，start 在写之前、end 在写之后（§7.4）。

### 9.3 将来加 **function calling（工具）**：现成机制在哪

| 需求 | DSH 的现成件 | 证据 |
|---|---|---|
| 工具声明 → 模型可见 schema | 注册表自动把 schema 送进 `systemPrompt.assemble()`，无需手动接线 | `dsh-tools/README.zh.md:28`；`dsh-system-prompt/README.zh.md:96` |
| 参数校验与错误 | `defineTool` 声明类型化参数；无效输入在执行前变成**普通错误结果**，不中止轮次 | `dsh-tools/README.zh.md:32` |
| 并发安全 | 工具声明 `isConcurrencySafe(args)`；只有**精确 `true`** 才并行，其余一律独占 | `dsh-tools/lib/index.js:3052-3063` |
| 有界并行 + 独占屏障 | `runGroup` 滚动池 + exclusive 屏障 | `dsh-agent-loop/lib/index.js:551`、`:619` |
| 工具集动态变化 | `developer/message` 的 `tool-addition`/`tool-removal` + `headerSeq` 回指 | `dsh-agent-loop/lib/index.js:1229-1247` |
| 结果配对 | surface 内容级配对，不依赖 step 标记 | `dsh-compaction/lib/types/tool-pairing.js:1-18` |
| 两种调用形态 | `mode` 选原生 Function Calling / PTC / 两者 | `dsh-tools/README.zh.md:12` |

**照搬建议**：MC 里"工具"= 服务器能力（方块操作、实体查询、GUI 打开）。参数 schema 用 JSON Schema 而不是 Java 注解反射，因为确定性序列化是缓存的前提。

### 9.4 将来加 **后台任务**：现成机制

`ctx.jobs` 的契约（`dsh-jobs/README.zh.md:12`、`:32-38`）：

- 稳定 id `<kind>-N`（如 `bash-1`）；
- **拥有者 = 启动它的 agent 会话**，其他 agent 无法读取或停止；id 可预测，所以"这是授权而非保密"；
- 两种输出源：spec 上的**拉取式**偏移读数器（注册表按自己节奏泵），或 `JobHandle` **推送**块；两者都落入**有界环**；
- 通道分级：`stdout`/`stderr` 块到达模型，`log` 块只到达观察者；
- 结算事件 → `dsh-tool-jobs` 转成**会话内通知**，无需轮询；
- 观察者按绝对字节偏移读取保留块，`readAt` **不消耗**任何东西、也不触碰通知状态。

**照搬建议**：MC 的"后台任务"极自然——区块扫描、世界备份、批量方块操作。照抄这四点即可：有界环 + `stdout/log` 分级 + 结算通知（不轮询）+ 拥有者隔离。

### 9.5 将来加 **子代理**：现成机制

`dsh-subagent`（`dsh-subagent/README.zh.md:12`、`:28-39`）：

- 一个 seam + N 个提供方后端（进程内 spawn / ACP / SDK / Codex / Claude Code），可并排；
- **一次性子级**（要单个结果）vs **可继续子级**（要后续消息与中断能力）；
- 每个请求按名字路由到具名提供方；
- 可检查可用子级、在线活动、最近结束轮次状态与谱系，**而无需加载或恢复它们**；
- 子 agent 创建时设 `options.parentAgent`，调用方 Context 拥有事务与句柄（`dsh-agent-loop/README.zh.md:111`）。

**照搬建议**：MC 里子代理最合适的用途是"脏活隔离"——大范围结构扫描、配方树搜索、日志分析。复用同一套 surface/compaction 即可，因为子代理就是一个独立 session。

---

## 10. 风险与反模式

### R1. `4 chars/token` 对中文严重低估 —— 这是官方承认的

三处独立证据：

- `dsh-token-meter/README.zh.md:68`："`contextBreakdown` 数字是估算值…后者的提供方锚点恰好携带启发式误差——**CJK 文本与 JSON schema 在每 token 四字符下严重低估**。"
- `dsh-token-meter/README.zh.md:154`（开发备注，同一句再说一遍）。
- `dsh-compaction-basic/README.zh.md:258`："token meter 每 token 四字符的启发式对 **CJK 文本与 JSON Schema 定价偏低**；精确 token 化仍是测量服务的开放方向。"

**后果**：中文 MC 场景下，`pressureTokens` 的启发式成分会低估 2–4 倍（「未验证」精确倍数，取决于文本构成）。若你的阈值直接用启发式值，会发生"以为还有空间，实际已经溢出"。

**DSH 自己的解法**：不靠估算器兜底，靠 §6.3 的 provider usage 锚点把误差归零；且锚点只接受"不低于估算"的值（`dsh-token-meter/lib/index.js:654`）。

**给你的做法**：中文内容要么用真实 tokenizer，要么把阈值乘以实测系数，要么保留 DSH 的锚定机制并确保它常被刷新。

### R2. 把 surface 当 messages 数组用

绕过 `surfaceOp` 直接改消息内容，会被 validator 就地拦死（`surface.js:394-396`、`:356-386`、`:213-224`）。**不要因为这些校验"太严"就拆掉它们**——它们保的是"模型看到的历史永远是配对的、合法的"。

### R3. 频繁改写系统提示词的头节点

每次回写 node 0，缓存从第 1 个 token 起全部失效（`dsh-agent-loop/README.zh.md:164`）。正确做法是由适配器声明 `systemPromptUpdate: 'in-history'`，走追加（§5.4）。

### R4. 工具 schema 抖动

注册顺序、可见集合、动态开关只要有抖动，`canonicalHeader` 就不同 → `request/header` 写新快照 → 开新序列 → 缓存全灭（`dsh-session/README.zh.md:182`："后续请求头若更改配置或 schema，可能从第一处差异开始使复用失效"）。**必须**做确定性排序（§5.3）。

### R5. 每次 append 都全量重算派生历史

`deriveMessages()` 是 O(新增节点) 的（`dsh-session/lib/index.js:1546`），靠 `derivedNodes` 水位 + `contentGeneration` 失效。若写成每次全量 fold，长会话会变成 O(n²)。同理 `measure()` 每次克隆当前表面（`dsh-token-meter/README.zh.md:141`），不要在 tick 循环里高频调用。

### R6. 压缩参数配错（会直接拒绝加载，不会静默降级）

- `W − O − B ≤ 0` → `TargetPressureConfigError`（`dsh-compaction-basic/lib/index.js:131`）；
- `retainTokens ≥ thresholdTokens` → 抛错（`:134`）；
- `retainRatio` 与 `retainTokens` 同时出现 → 抛错（`:192`）；
- 小窗口模型必须显式配 `headroomTokens` / `maxTokens`，不能吃 `65536` 的默认值（`README.zh.md:78`）。

### R7. 压缩切开 tool 对

必须用 `toolPairingBalancedBefore/After`，否则模型会看到孤立的 `tool/result`。校验函数在 `dsh-compaction-basic/lib/index.js:557-558` 就是拿这个做硬拒绝的。

### R8. 假设"压缩后 token 一定下降"

拿不到时**什么都不写**（`README.zh.md:82`），且后端会拒绝不缩小源内容的摘要（`:125`）。以下情形无法压缩（`dsh-compaction/README.zh.md:161`）：

- 单个不可分单元（例如一次超大工具调用）把窗口撑爆；
- 承载文本的主体是工具结果、且该对不可剪枝；
- 溢出发生在 envelope 侧（系统提示词/工具/会话前缀）——压缩**从不**碰这些。

### R9. 拿估算值或 provider usage 做账 / 做硬决策

- `dsh-token-meter/README.zh.md:68`："占用是参考数字，不是计费记录：harness 中没有任何机制依据它做决定，压缩读取的是 `measure()`。"
- provider usage 只在 **envelope 完全相等**时可复用（`lib/index.js:650`），工具/模型/配置任一变化都回退估算。
- 同一轮内的重试会贡献**另一次**计费用量（`README.zh.md:49`），所以"轮次用量 ≠ 单次请求用量"。

### R10. 以为"照搬这套就有缓存命中"

没有 cache breakpoint（§8.1）。命中来自于"前缀稳定 + provider 侧自动缓存"，而 README 明确不保证命中率（`dsh-system-prompt/README.zh.md:151`）。**复刻时不要伪造一个 `cache_control` 就以为问题解决了**——真正的收益来自只追加 + 易变置尾 + 确定性排序。

### R11. 往事件里塞非无损 JSON

`-0`、`BigInt`、稀疏数组、循环引用、特殊原型全部在 `append` 处被拒（`dsh-session/README.zh.md:108`）。MC 里的 `ItemStack`、`CompoundTag`、`BlockPos`、`ResourceLocation` 都要显式转成纯 JSON 才能进日志。

### R12. 想给模型一个"压缩工具"

当前**没有**面向模型的压缩工具，只有 `/compact` 命令与自动触发（`dsh-compaction/README.zh.md:160`、`:172` 明确列为开放问题）。要加就是新 schema + 与命令路径协调，别以为接上就能用。

### R13. 想靠"轮次预算"防跑飞

DSH **没有内置轮次预算**（`dsh-agent-loop/README.zh.md:219`）：工具调用或 steering 会让当前轮次继续。限制失控轮次必须从 `agent/turn-stopping` 之类的扩展点主动取消。MC 里这尤其危险（一个 AI 循环挖矿能吃满服务器），**必须自己加**。

---

## 11. 「未找到」与「未验证」清单

| 项目 | 状态 |
|---|---|
| 任何显式 cache breakpoint / `cache_control` / `prompt_cache_key` 标记 | **未找到**（全树 1281 文件扫描，仅 pi-ai 的 compat gate 5 处，非标记发送） |
| 精确 tokenizer | **未找到**（`dsh-token-meter/README.zh.md:155` 明说"按提供方的精确分词器尚未决定"） |
| 内置轮次 / 工具调用预算 | **未找到**（`dsh-agent-loop/README.zh.md:219` 明说没有） |
| 面向模型的压缩工具 | **未找到**（`dsh-compaction/README.zh.md:160`） |
| 会话树（branch 树） | **未找到**（`dsh-session/README.zh.md:194`：fork 之外没有会话树） |
| provider 侧真实缓存命中率 | **未验证 / 不可验证**（`dsh-system-prompt/README.zh.md:151` 明确不保证） |
| 4 chars/token 对中文的具体低估倍数 | **未验证**（只能说"严重低估"，无法从本归档得出系数） |
| `assembleContextFor()` 的实现位置 | **未定位**（`dsh-agent-loop/lib/index.js:907` 调用，定义未在本文件内） |
| MC 侧多线程下 append 的互斥方案 | **无证据**（DSH 靠单进程同步 append，未提供跨线程语义） |

---

## 12. 一页速查（复刻时的最小可行子集）

如果你只想在 MC 模组里拿到 80% 的收益，按这个顺序实现：

1. **日志**：`SessionEvent{seq,type,data}` 只追加；5 类 surface 事件必须带 `surfaceOp`，其余禁止带。
2. **surface**：`nodes[]` + `replaceGeneration` + `contentGeneration`；替换用闭区间 `{op:'replace',startSeq,endSeq}`；node 0 只允许 `system/message` 替换。
3. **派生**：`deriveMessages()` 带水位缓存，返回新数组 + 共享冻结消息；空 system/developer/assistant 不投影。
4. **请求**：`{...config, messages, tools, toolHistory}`，**不要** `system` 字段。
5. **提示词**：分区 + 固定 order 表 + `order→code-unit name` 排序；空段删除；变量未解析就抛错。
6. **提示词变更**：能追加就追加（in-history），否则回写 node 0；清空提示词要给每个生效节点写空替换。
7. **计量**：`ceil(chars/4)+4`；provider usage 只在 envelope 全等且不低于估算时采用。
8. **压缩**：`threshold=floor(min(W×0.8, W−O−B))`、`retain=floor((W−O)×0.16)`、选段从尾累加 + tool 配对平衡、写一条 `<compacted-summary>` replace、标记对当锁。
9. **缓存**：只追加、易变置尾、确定性排序、**不发 cache_control**。
10. **失败**：悬空工具调用补 `TOOL_OUTCOME_UNKNOWN` / `TOOL_NOT_STARTED`；步骤失败先补结果再抛。
