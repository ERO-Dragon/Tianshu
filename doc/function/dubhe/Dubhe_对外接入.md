# 天枢对外接入（DUBHE.RESEARCH / DUBHE.RELAY）

> 面向其他模组：如何调用天枢、能调什么、返回什么、失败怎么读。
> 状态：施工基准（P8）。代码位置 `com.rheinmetal.tianshu.dubhe.external`。

## 1. 能调什么

天枢对外只有**两个**能力，没有"提交任务给我规划"的通用入口：

| 对外名 | 注册 id | 性质 |
|---|---|---|
| `DUBHE.RESEARCH` | `dubhe:research` | **只读**查证：入 `intent`，出结论 + 来源 |
| `DUBHE.RELAY` | `dubhe:relay` | **只转发**：把一句话交给指定模组，或查询已投递任务的状态 |

两者都是 `CapabilityKind.SKILL`、`CapabilityBinding.LOCAL`、`ToolSide.CLIENT`、`ToolPermission.EVERYONE`，
由 `dubhe` 提供。执行层（代理循环、技能目录、记忆）不对外。

## 2. DUBHE.RESEARCH

### 入参

```json
{ "intent": "如何进入暮色森林" }
```

- `intent` 必填、非空；不需要会话上下文，也不接受多步规划指令。
- 固定预算：512 token 输出上限、30 秒超时。

### 返回

```json
{ "conclusion": "先做暮色森林传送门。", "sources": [] }
```

- `conclusion`：一句（或两句）结论；模型失败时为空串。
- `sources`：来源列表。**没有检索模块时为空数组，绝不编造来源。**

## 3. DUBHE.RELAY

### 投递

```json
{ "target": "maid:task", "task": "把木头搬进箱子" }
```

- `target` 必须是目标模组注册的 capability，形式 `namespace:name`。
- 返回：`{ "taskId": "t-9" }`；目标没有返回 `taskId` 时，天枢把这次投递判为失败（无法查询进度）。

### 查进度

```json
{ "target": "maid:reader", "taskId": "t-9" }
```

- `target` 必须是目标模组注册的**读工具**。
- 返回：`{ "state": "RUNNING" | "DONE" | "UNKNOWN" }`；不认识的状态一律 `UNKNOWN`。

天枢**不轮询**：只在被调用时查询，或接收目标模组主动回投的报告。

## 4. 失败与配额

| 情况 | 返回 |
|---|---|
| 参数不合法 | `ok=false`，`code=FAILED`，`message` 说明缺什么 |
| 目标拒绝 / 未注册 | `ok=false`，`code=FAILED`，`message` 来自目标 |
| 超过配额 | `ok=false`，`code=QUOTA_EXCEEDED`，`message` 含当前 running/queued |
| 研究调用失败 | 仍返回 `{conclusion:"", sources:[]}` |

**配额**：按"调用方 × 能力"限**并发 1 + 排队 4**；第 6 个调用立即拒绝，不无限排队。不同调用方、不同能力各自独立。
玩家侧入口不设频率上限——配额防的是第三方代码里的循环调用。

## 5. 最小调用示例

```java
CapabilityId research = CapabilityId.of("dubhe", "research");
JsonObject args = new JsonObject();
args.addProperty("intent", "萤石怎么获得");

CapabilityCatalog.invoke(research, args, ToolCaller.mod("your.mod.id"), ToolSide.CLIENT)
        .thenAccept(result -> {
            if (result.has("ok") && !result.get("ok").getAsBoolean()) {
                // 结构化失败：读 code / message
                return;
            }
            String conclusion = result.get("conclusion").getAsString();
        });
```

## 6. 不要做的事

- 不要用天枢驱动实体动作（放置、破坏、攻击、跟随）：天枢没有实体，这些能力不存在。
- 不要寻找 `AGENT_TASK` 一类的通用入口：已永久取消，契约测试会守住这条边界。
- 不要在循环里无节制调用：配额会在第 6 个请求上直接拒绝。