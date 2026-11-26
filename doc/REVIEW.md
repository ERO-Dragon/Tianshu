# 舟楫审查

2026-11-26。范围包括智能体能力中心、两本内建技能、天枢助手订阅、远端工具目录同步及内容源的生命周期。本次按工作区当前源码核对；以下为待修复项。

## P1　工作流有两条执行路径，关闭后仍可能启动

只有 TOOL 经 TianshuTools 检查开关和档位。WorkflowInstaller 同时安装 WORKFLOW handler 和工具，前者直接运行步骤。调用者订阅了工作流、且有权执行各步骤时，关闭整个工作流仍挡不住能力入口。步骤自己的限制仍然有效。工具路径还有另一种结果形状，失败的 WorkflowRun 被包装成外层成功，消费方只看标准 ok 会误判。

修复与验收建议：让已安装工作流的两个公开入口复用执行策略和结果转换。对同一个关闭／执行失败的计划，分别断言两个入口给出的状态及步骤执行次数。

[CapabilityCatalog.java 第 268 行](../../../../Tianshu-ferry/tianshu-api/src/main/java/com/rheinmetal/tianshu/api/capability/CapabilityCatalog.java#L268)

## P1　工作流超时后还会执行后续步骤

能力入口使用 CancellationSignal.NEVER。CapabilityCatalog 超时取消的是派生 future，取消不会向已组装的 thenCompose 步骤链反向传播。工具入口的 orTimeout 也未触发执行器检查的信号。某个步骤稍后成功，执行器仍可能启动下一项写操作，调用者则已经收到了 TIMEOUT。

修复与验收建议：让两条入口共享运行级取消信号与截止时间，所有步骤开始前检查。用可控的慢步骤和记录调用次数的末步验证超时后的副作用。

[WorkflowInstaller.java 第 63 行](../../../../Tianshu-ferry/tianshu-api/src/main/java/com/rheinmetal/tianshu/api/capability/WorkflowInstaller.java#L63)

## P1　读取工具说明会在调用线程做同步 I/O，文件大小限制生效太晚

工具 ID 的读取直接调用 SkillReferences.of，逐个提供方查找，SkillFileProvider.reference 每次复制并排序技能集合，随后同步读取文件。工具的超时在 invoke 返回 future 后才设置。从游戏线程发起读取时，慢盘或大文件会阻塞该线程。磁盘和 classpath 都先读完整内容，再按字符串长度截断；48,000 的常量无法限制输入字节数或峰值内存。普通技能和 TextCapabilityProvider 也有全读后截断。

修复与验收建议：将文档 I/O 放在有界执行器上，在输入流解码时实施字节预算。用超大 UTF-8 文件和受控慢读取验证峰值与调用线程的返回时间。

[CapabilityReadTool.java 第 118 行](../../../../Tianshu-ferry/tianshu-client/src/main/java/com/rheinmetal/tianshu/client/tool/CapabilityReadTool.java#L118)

## P1　第三方内容源无法随模块完整卸载

静态 REGISTERED 持有 provider::unload。调用 registerSkills 返回对象的 unload 可移除能力行，却不会释放 REGISTERED 的键和值；同一模组再次启动会被拒绝。只有全局 closeAll 清表，当前生产销毁路径仅清理运行时直接持有的六个内容源。requireNamespace 还会阻止同一模组分别注册技能、资源和提示词，即使三类使用不同 ID。

修复与验收建议：让宿主或模组持有可单独释放的注册句柄，以提供方及内容种类为键。验证两个模组并存、同一模组发布三类内容和连续启停两次。

[CapabilitySources.java 第 30 行](../../../../Tianshu-ferry/tianshu-client/src/main/java/com/rheinmetal/tianshu/client/capability/CapabilitySources.java#L30)

## P1　远端工具激活失败会污染后续目录刷新

ACTIVATED.add 先于真正注册。已有同 ID 的非工具能力时注册抛错，标记却留下；冲突解除后下一次刷新跳过注册，直接对不存在的工具 setEnabled，又一次失败。同批后续项也无法同步。另一个边界是已经存在同 ID 工具时直接修改它的开关，断开时仅按 ID 移除 REGISTERED 中的项，缺少对实际注册实例的核对。

修复与验收建议：在成功取得注册后再发布激活状态，卸载时核对本次持有的实例。验证冲突解除后刷新自愈，其余目录项同步成功。

[ServerToolDeclarations.java 第 77 行](../../../../Tianshu-ferry/tianshu-client/src/main/java/com/rheinmetal/tianshu/client/tool/ServerToolDeclarations.java#L77)

## P2　非法超时属性会在任务投递后同步抛出

timeoutMillis 是开放 JSON 属性。字符串、布尔值均是 primitive，getAsLong 对不可解析值会抛异常。执行任务先入队，再求超时；调用者可能拿不到 future，处理者却已经开始执行。handler 正常完成但返回 null 时，这条路径也直接交出 null，违反统一结果信封契约。

修复与验收建议：在注册或调度前校验超时配置，并在同一调用边界校验结果信封。用非法超时属性断言处理者未启动，用空结果断言稳定失败码。

[CapabilityCatalog.java 第 445 行](../../../../Tianshu-ferry/tianshu-api/src/main/java/com/rheinmetal/tianshu/api/capability/CapabilityCatalog.java#L445)

## P2　会话描述会混合两个目录版本，订阅变化也不会触发刷新

describe 先取能力快照和 revision，再用 tools() 读取当前表，最后从旧快照生成 capabilities。一次并发注册就可能造成同一文档两份清单不一致。CapabilitySubscriptions.declare／withdraw 只改 RULES，不发变更事件或更新目录版本；仅依赖 revision 或 onChange 的消费方会保留旧可见目录，直至另一次目录变化或主动重读。

修复与验收建议：从同一注册快照和订阅规则生成整份会话视图，订阅变化使对应消费方缓存失效。验证只修改订阅时目录通知和版本也正确。

[AgentSession.java 第 89 行](../../../../Tianshu-ferry/tianshu-api/src/main/java/com/rheinmetal/tianshu/api/agent/AgentSession.java#L89)

## P2　带句柄调用的异常契约和普通调用不一致

对于 schema 放行但无法反序列化成工具 Java 参数的输入，executeJson 中的 Gson 会抛 JsonParseException。callJson 转换为 INVALID_ARGS；callHandled 只捕获 ToolException，所以同一输入可能直接抛出，调用方连句柄都拿不到。

修复与验收建议：复用参数准备与失败转换。用一个宽松 schema 和严格 Java 参数类型，让两种入口接收相同的无效输入。

[TianshuTools.java 第 296 行](../../../../Tianshu-ferry/tianshu-api/src/main/java/com/rheinmetal/tianshu/api/tool/TianshuTools.java#L296)

## P2　目录推送合并了发送次数，却没有限制排队任务数

监听器对每条能力变更都调用单线程执行器的 execute。PENDING 只在消费时合并全表扫描；当推送慢或技能批量重载时，执行器无界队列仍按变更次数累积 drain 任务。技能、提示词等无关变动也触发服务端工具目录广播。

修复与验收建议：过滤影响服务端工具目录的变更，同时保证最多一个待执行的 drain。以慢推送和批量注册验证队列峰值及最后一份目录。

[ToolCatalogPushRegistry.java 第 81 行](../../../../Tianshu-ferry/tianshu-api/src/main/java/com/rheinmetal/tianshu/api/tool/ToolCatalogPushRegistry.java#L81)

## P2　技能目录文件只检查了字面路径边界

目录读取用 normalize 和 startsWith 检查路径，Files.isRegularFile 与 readString 默认跟随符号链接。玩家或模组的技能目录存在指向目录外的文件链接时，实际读取可以越过技能目录。exists 与 read 均使用这套判定。

修复与验收建议：按真实路径或禁止链接穿越来统一限制文件访问；验证目录内合法链接和指向外部的链接都得到预期结果。

[SkillFileProvider.java 第 343 行](../../../../Tianshu-ferry/tianshu-api/src/main/java/com/rheinmetal/tianshu/api/capability/skill/SkillFileProvider.java#L343)

## P2　附件读取错误会持续累积诊断对象

reference 在运行期每遇到一次 IOException 就往实例的 ArrayList problems 追加字符串；同一损坏或不可读附件被轮询时，相同诊断会积累到下次 reload，且多个读取线程可能同时写这个列表。

修复与验收建议：对运行时读取错误采用有界、线程安全的记录方式。并发反复读取同一不可读附件，确认诊断数量有界。

[SkillFileProvider.java 第 208 行](../../../../Tianshu-ferry/tianshu-api/src/main/java/com/rheinmetal/tianshu/api/capability/skill/SkillFileProvider.java#L208)

## P2　工作流失败说明会暴露未向调用者开放的提供方

某一步被能力中心按 UNKNOWN_CAPABILITY 拒绝后，错误格式化又从全局表按 ID 查询 provider。模型能够根据错误内容区分未注册与已注册但不可见的步骤，和“不确认隐藏能力存在”的契约冲突。

修复与验收建议：归因时使用调用者的可见视图。分别使用未注册和未开放的步骤，断言外部错误不透露提供方。

[WorkflowExecutor.java 第 91 行](../../../../Tianshu-ferry/tianshu-api/src/main/java/com/rheinmetal/tianshu/api/capability/WorkflowExecutor.java#L91)

## 验证范围

Gradle 离线执行 tianshu-api、tianshu-client、tianshu-dubhe、tianshu-lab 的 test 任务，以及 verifyEveryToolHasADocument、verifyServerToolRegistration，构建成功，部分任务复用已有结果。以上问题按源码控制流判定。此次未启动游戏，未做堆转储、JFR 或实服负载测量。
