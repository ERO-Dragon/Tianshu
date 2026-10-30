# 北辰 API 兼容策略（RFC）

> 状态：草案，待拍板。适用起点：`tianshu 1.0.0`。
> 关联：审计报告 A5；`doc/architecture/模块架构.md`；`EXTERNAL_INTEGRATION_GUIDE.md`。

## 1. 目标与非目标

**目标**

- 1.x 系列内，`tianshu-api` 对第三方/功能模块**向后兼容**：旧 jar 不需要重编译即可继续工作。
- `2.0.0` 是唯一的破坏窗口：集中删除 1.x 的弃用别名、整理契约；之后从 2.0.0 重新起算兼容。
- 模块可以用与普通 Minecraft 模组一致的方式声明依赖范围（`tianshu >= 1.2.0` 等）。

**非目标**

- 不承诺"行为完全不变"：默认值、调度策略、日志文案可以在 minor 内调整；承诺的是**契约、路由与解析**不破。
- 不承诺跨大版本（1.x → 2.0.0）的二进制兼容。

## 2. API 面：兼容承诺覆盖什么

| 属于 API 面 | 不属于 |
|---|---|
| `tianshu-api` 的公开包与公开类型 | 各功能模块的内部类、`internal`/`impl` 包 |
| topic id、capability id、payload type id、模块 id | 日志文案、诊断事件码（模块自有的聊天文案键除外，见 §5） |
| 信封（`TianshuEnvelope`）字段与 payload schema | 测试、构建脚本、`doc/` 内容 |
| 端口接口（`ClientPorts` 组件、`GameEventPort`、`HudHost` 等）的签名 | 模块的设置页布局、HUD 具体视觉 |

> 原则：**只要外部模块编译时可能引用到，就属于 API 面**。拿不准的按"属于"处理。

## 3. 版本号规则

- 采用 `MAJOR.MINOR.PATCH`：
  - `MAJOR`：API 破坏。只允许在 `2.0.0`、`3.0.0` 这样的窗口发生。
  - `MINOR`：新增（方法、capability、payload 字段、端口组件），不删不改。
  - `PATCH`：修复，API 面不变。
- `apiVersion` 由构建从 `MAJOR` 推导（1.x → `1`），写进 `tianshu-build.properties`，`CoreCapabilityProbe` 读它；**不手工维护**，避免与 mod 版本漂移。
- `apiVersion` 只用于探针展示与诊断，不作为模块加载门槛；加载门槛由 manifest 的 `versionRange` 决定。

## 4. 1.x 内的变更规则

| 变更 | 1.x 内 | 做法 |
|---|---|---|
| 新增公开方法/字段/常量 | 允许 | 直接加；接口新增方法必须是 `default`，或新建接口由调用方注入 |
| 新增 capability / topic / payload type | 允许 | 由**生产者模块**声明（本次清洁后都在模块内），消费者用字符串 id 或模块常量 |
| 重命名 id（payload type / capability / topic） | 允许，但旧名必须仍可用 | 保留旧名别名 + 记一条弃用诊断；旧名到 2.0.0 才删 |
| 删除公开类型/方法/常量 | 禁止 | 只能标 `@Deprecated` |
| payload 增加字段 | 允许 | 旧解码器缺省；新字段必须有默认语义 |
| payload 改字段类型/含义 | 禁止 | 新建 payload type（或 schema v2），旧类型保留 |
| 改信封必填字段 | 禁止 | 新增可选字段；必填化留到 2.0.0 |
| 改默认投递策略/重试语义 | 视为行为破坏 | 新 topic 或 2.0.0；1.x 内只允许修 bug 级偏差 |

## 5. 弃用与移除

- 弃用必须同时做三件事：`@Deprecated`、运行时一条 `WARN`/诊断（写明替代项）、在 `doc/api-deprecations.md` 登记"替代项 + 计划移除版本"。
- 2.0.0 的移除清单从该文档生成；2.0.0 发布说明逐条给迁移方法。
- 聊天/界面文案：键由**模块**提供（如 `tianshu.gui.ax.text_input.*`），宿主只保留中性兜底；改名时模块自己改键，不触发 API 兼容问题。

## 6. 模块侧的版本声明

NeoForge（`neoforge.mods.toml`）：

```toml
[[dependencies.example_mod]]
modId = "tianshu"
type = "required"
versionRange = "[1.0.0,)"      # 只要 ≥1.0.0
ordering = "NONE"
side = "CLIENT"
```

| 需求 | `versionRange` |
|---|---|
| 任意 1.x 及以上 | `[1.0.0,)` |
| 只兼容 1.x | `[1.0.0,2.0.0)` |
| 需要 1.2.0 引入的能力 | `[1.2.0,)` |

跨模块依赖同样声明（本次清洁后 capability/topic/id 属于生产者模块）：

```toml
[[dependencies.example_mod]]
modId = "tianshu_echo"        # 辅星依赖回响的 TTS 契约
versionRange = "[1.0.0,)"
```

**加载器只校验收声明的范围，不校验真实兼容。** 2.0.0 发布时必须要求生态把上界收窄为 `<2.0.0`，或由宿主在启动期探测旧模块并给出明确报错（优于运行时崩溃）。

## 7. 把承诺变成机制

1. **API 快照测试**：导出公开签名 + 全部 id（topic/capability/payload type/module id）+ 信封字段，提交时 diff。规则：
   - 删除/改签名且未 bump `MAJOR` → 测试失败；
   - 新增 → 通过并更新快照；
   - 重命名 → 必须同时出现旧名别名，否则失败。
2. **弃用诊断**：别名命中时记 `WARN`，让"还在用旧名"可见。
3. **发布检查**：2.0.0 前跑一次快照 diff，确认删除项与迁移文档一一对应。
4. **文档**：本 RFC + `EXTERNAL_INTEGRATION_GUIDE.md` 的"版本与兼容"一节 + `doc/api-deprecations.md`。

## 8. 实施顺序

1. 本 RFC 定稿（确认 §9 的待定项）。
2. **1.0.0 发布前**：`PayloadType` 别名/弃用 + payload schema 版本字段（现在不做：无外部消费者，且所有模块同仓同构建，属过度设计）。
3. API 快照测试（`apiVersion` 已由 `MAJOR` 推导，见 §3）。
4. 各模块 `neoforge.mods.toml` 写范围；外部指南给模板。
5. 2.0.0 窗口：执行移除清单、发布迁移指南、生态收窄上界。

## 9. 待定问题

1. 1.x 内"行为变化"的边界：默认投递策略、重试次数这类算不算破坏？建议：凡会影响消息是否送达/顺序的，一律按破坏处理。
2. 别名保留策略：仅到下一个大版本，还是长期保留（内存与话题表成本很低）？
3. 预发布/开发构建的版本表达（`1.2.0-dev`）与范围比较规则。
4. 是否需要在宿主启动时强制校验模块声明的 `tianshu` 范围（FML 已做，但只对 `neoforge.mods.toml` 生效；Fabric 侧要另写）。
