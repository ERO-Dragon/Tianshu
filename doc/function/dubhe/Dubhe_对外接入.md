# 天枢对外接入（DUBHE.SEARCH）

> 面向其他模组：天枢对外只发布**一个可读技能**——联网技能族的入口。
> 状态：施工基准（P8）；代码位置 `com.rheinmetal.tianshu.dubhe.external`。

## 1. 对外发布什么

| 对外名 | 注册 id | kind | 性质 |
|---|---|---|---|
| `DUBHE.SEARCH` | `dubhe:search` | `SKILL` | **可读技能**：联网技能族的入口；`read` 返回一段给模型看的正文 |

- 它**不是工具**、不是函数、不是执行入口：调用方 `read` 它，把正文交给自己的模型；
- 正文是族入口（"遇到什么类问题去查哪里"的路由表）；更细的用法在 `references` 里按需读（渐进式披露）；
- `relay` **不对外**：它是天枢外交技能旗下的一类 reference（怎么发现目标、投递、查进度），只在天枢内部被代理阅读；
- 没有"提交任务给我规划"的通用入口，也没有 `AGENT_TASK` 一类能力。

固定预算：512 token 输出上限、30 秒超时；只读，不改世界、不读会话上下文。

## 2. 怎么调用

入参：

```json
{ "intent": "怎么进入暮色森林" }
```

读取（`AgentSession.read` 或 `CapabilityCatalog.invoke`）：

```java
CapabilityId search = CapabilityId.of("dubhe", "search");
JsonObject args = new JsonObject();
args.addProperty("intent", "萤石怎么获得");

CapabilityCatalog.invoke(search, args, ToolCaller.mod("your.mod.id"), ToolSide.CLIENT)
        .thenAccept(result -> {
            if (result.has("ok") && !result.get("ok").getAsBoolean()) {
                return; // 结构化失败：读 code / message
            }
            String text = result.get("text").getAsString();
        });
```

返回：

```json
{ "text": "先做暮色森林传送门。", "sources": [] }
```

- `text`：给模型读的正文；
- `sources`：来源列表。**没有检索模块时为空数组，绝不编造来源。**

## 3. 失败与配额

| 情况 | 返回 |
|---|---|
| 缺少 `intent` | `ok=false`，`code=FAILED`，`message` 说明缺什么 |
| 超过配额 | `ok=false`，`code=QUOTA_EXCEEDED`，`message` 含 running/queued |
| 模型调用失败 | 仍返回 `{ "text": "", "sources": [] }` |

**配额**：按"调用方 × 技能"限**并发 1 + 排队 4**；第 6 个调用立即拒绝，不无限排队。不同调用方各自独立。
玩家侧入口不设频率上限——配额防的是第三方代码里的循环调用。

## 4. 渐进式披露

外部只看到技能的描述与 `attributes`（`entry` / `references`）；更细的检索源与族内技能在 `references` 中按需读。
天枢内部代理同样遵守这条：先拿摘要，选中后才取参考。

## 5. 不要做的事

- 不要用天枢驱动实体动作（放置、破坏、攻击、跟随）：天枢没有实体，这些能力不存在。
- 不要寻找 `AGENT_TASK` 一类的通用入口：已永久取消，契约测试会守住这条边界。
- 不要在循环里无节制调用：配额会在第 6 个请求上直接拒绝。