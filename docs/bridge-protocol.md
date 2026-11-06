# IDEA Bridge ↔ pi extension 通信协议

**状态：v2 首期代码已实现，尚待 CI / 真实 TUI / IDE 集成验收。** 公共通道、快照、rewind 与 ForkWorkflow 已接入。Fork 仍先创建独立 CLI 副本，再经 v2 request / 耐久 handoff 确认分叉；本轮 Fork 改动待验证。本文面向开发者；MUST/必须为契约要求，建议参数可以调节但不得改变语义。

关联方案：[双向操作通道与回退确认](plans/2026-11-04-2114——双向操作通道与回退确认.md)。

> 里程碑：bridge 协议开发完成（v0.4.0-dev 阶段）。此处只标记协议实现收尾，不代表已完成真实 TUI / IDE 集成验收，也不构成发布。

## 1. 架构与权威边界

- IDEA 启动并保留交互式 pi TUI；extension 在同一 pi 进程中运行。**不用 pi RPC mode，不启动第二个 RPC 进程操作同一 session。**
- 仅借鉴 pi RPC 的 JSONL、请求 id、response/event 分离；本文是 bridge 协议，不声称与 pi RPC wire-compatible。
- pi 是会话、分支和运行状态的权威来源。执行必须复用公开 pi API；禁止直接改 JSONL、私有 runner 或自行实现 SessionManager。
- IDEA 是本地草稿、标题和 UI 状态的权威来源；消息列表只是 pi 分支的展示缓存，不能作为第二份历史。完整有效快照整体替换缓存，旧隐藏记录不再过滤原生历史。
- bridge 负责可靠表达“请求、已知结果和未知”，不承诺两进程原子提交或跨崩溃 exactly-once。

## 2. 传输与握手

extension 主动连接 IDEA 的 loopback TCP server。首帧 hello 携带现有启动环境中的 token/tabKey，声明运行实例及能力；welcome 建立认证连接。

```json
{"v":2,"type":"hello","token":"<secret>","tabKey":"<conversation-id>","runtimeId":"<extension-instance-uuid>","piVersion":"<version>","extensionVersion":"<version>","capabilities":{"conversation.rewind":[1],"session.snapshot":[1]},"sessionId":"<pi-session-id>"}
{"v":2,"type":"welcome","connectionId":"<uuid>","protocolVersion":2}
```

- token 不写日志；只接受已知 tabKey。loopback/token 不是同用户进程之间的强安全隔离。
- runtimeId 在 extension 实例重建时改变；connectionId 每次连接改变。operation id 不随两者改变。
- 新实例不能仅凭同 tabKey 覆盖旧活动实例。预期 replacement 由运行时交接证据确认；其他冲突需要生命周期核对。
- capabilities 仅声明真实验证支持的方法及版本。未支持的方法明确拒绝；不能隐式退回 Terminal 文本输入。
- 建议握手 5s、heartbeat 5s、15s 无响应断联；重连退避 0.5–10s 并加抖动。重连不重放操作。

### 2.1 JSONL framing 与资源保护

每帧一个 JSON 对象，以 LF 结束，接受 CRLF。UTF-8 流式解码，不能把 U+2028/U+2029 当分隔符。两端必须持续读取、处理背压；网络解析和日志 I/O 不阻塞 EDT。

优先完整快照，不为节省本机带宽提前实现 delta、分页或压缩协议。但必须有可配置的帧/队列资源预算，不能无限分配内存。不能沿用 v1 的 64KiB 上限限制完整快照。上线前按真实长会话选择宽松默认值并验证。

超过预算明确报告 CONTEXT_TOO_LARGE，不静默截断并标为完整。大图片/工具输出需要额外策略时通过 capability 协商；未经协商不改变原生 content。心跳不携带全量历史。

## 3. 稳定消息族

认证后所有帧包含 `v:2,type`，身份由连接绑定；跨实例查询保留原请求归属信息。

| type | 方向 | 用途 |
|---|---|---|
| request | IDEA→extension | 首次提交操作意图 |
| response | extension→IDEA | 操作状态或终态；始终关联 id |
| query | IDEA→extension | 按原 id 只读查询结果，不执行 |
| ack | IDEA→extension | 已耐久接收结果，供日志回收 |
| event | extension→IDEA | 快照/状态观察，不冒充操作成功 |
| ping/pong | 双向 | 存活性 |

新增业务方法不能增加 `fork_result`、`rewind_request` 等帧族。业务方法版本独立于 v；不兼容 envelope 变更才升级协议主版本。

### 3.1 请求

```json
{
  "v":2,"type":"request","id":"<operation-uuid>",
  "method":"conversation.rewind","methodVersion":1,
  "params":{"targetEntryId":"<entry-id>"},
  "context":{"sessionId":"<session-id>","observedSnapshotId":"<snapshot-id>"}
}
```

- id 是操作身份，不是连接序号。日志将它与认证 project/tab、method/version、规范化参数和前置条件摘要绑定。
- 相同 id、相同摘要：返回已有状态，不重复执行；相同 id、不同内容：ID_CONFLICT。
- context 是客户端观察及前置条件，不是写入 pi 的权威会话。可以带更多定位上下文，但适配器必须从 pi 重新验证。
- 修改方法不得只依靠已过期 snapshotId。快照不可用时返回 STALE_CONTEXT 并附新快照，不能猜测前置条件已满足。
- 请求可声明开始截止时间，由适配器在执行前校验；客户端超时不表示远端已取消。

### 3.2 响应

```json
{
  "v":2,"type":"response","id":"<operation-uuid>",
  "method":"conversation.rewind","methodVersion":1,
  "status":"succeeded","effect":"applied",
  "data":{"targetEntryId":"<entry-id>"},
  "context":{
    "afterSnapshot":{"snapshotId":"<snapshot-id>","complete":true},
    "currentSnapshot":{"snapshotId":"<snapshot-id>","complete":true}
  }
}
```

示例省略 snapshot 其余字段，真实结构见 §4。首次终态 after/current 可以是同一个快照；后续查询时它们可能不同。

执行状态：`accepted | running | succeeded | failed | cancelled`。

- accepted 只证明登记；running 也不证明已发生副作用。
- succeeded 必须通过方法的后置核验；不能把 command dispatch 返回当操作成功。
- failed/cancelled 必须说明 effect：`none | applied | unknown`。只有已确认无副作用才用 none。
- 执行途中异常无法判定效果时，保留日志证据并说明 effect=unknown；不得伪造“失败所以什么都没变”。
- error 为 `{code,message}`，message 用于展示，不应被解析来决定逻辑。基础代码包括 UNSUPPORTED、INVALID_ARGUMENT、BUSY、STALE_CONTEXT、TARGET_NOT_FOUND、AMBIGUOUS_TARGET、ID_CONFLICT、INTERNAL_ERROR。
- 请求接收前的 schema 拒绝有合法 id 时用 failed/effect=none；无 id 的畸形帧是协议错误并记录/断连，不创造业务请求。

### 3.3 查询和观察状态

```json
{"v":2,"type":"query","id":"<original-operation-uuid>"}
```

查询有记录时返回 response。找不到记录返回 `type:response,id,lookup:"not_found"`，不编造执行状态；身份不匹配拒绝查询。

客户端单独维护 `reachable | disconnected | awaiting | unknown` 等观察状态。超时只改变观察状态，不能把远端 running 改成 failed。NOT_FOUND 不等于未执行。

**已发送或可能已发送的修改请求，必须只查询，绝不自动重放。** 自动重连、只读 snapshot 请求与结果查询允许重试，但不能混入 execute。没有泛化的 retryExecute 按钮/API。

当前快照即使恰好满足期望，也不能证明原 id 曾执行。操作记录回答“这次执行如何”，快照回答“现在是什么状态”，两者不得混淆。

## 4. SessionSnapshot：统一上下文

```text
snapshotId             本次观察标识，不是 operationId
capturedAt             诊断时间，不作为跨进程排序依据
complete               是否完整；false 不能用于破坏性视图提交
consistency            stable / changed_during_capture
session
  id                   pi 原生 session id
  header               可获取的原生 session header（含 version）
  file                 可获取的文件标识；不要假定默认目录
branch
  leafId               当前 leaf，可为 null
  entries              原生 SessionEntry[]，根到 leaf 的活跃分支
state                   经公开 API 可读取的状态，如 idle/model
source
  piVersion
  runtimeId
```

- entries 复用原生 id/parentId/type/message/content，保留未知字段。不要压平成自定义 role/text 后丢失工具和分支信息。
- active branch 不等于全部 session 文件，也不等于最终 LLM prompt；压缩、context edit 和运行时投影由 pi 管理。
- 优先从 extension 的公开 sessionManager 读取。拿不到字段时明确缺省，不自行猜路径、解析内部对象或伪造字段。
- 采集前后核对会话/分支一致性；changed_during_capture 快照只能用于诊断，需重新获取。snapshotId 的缓存及验证由 PiAdapter 封装，不能仅用 leafId 当完整 revision。
- context 默认完整活跃分支；无需在每个 heartbeat 重传。完整不意味着包含密钥、环境变量、UI 对象或不可序列化 ctx。
- 查询历史结果时 afterSnapshot 表示执行后的证据，currentSnapshot 是新观察；若历史快照未保留，明确缺失，不能用当前快照冒充。
- 大快照无需每次耐久保存多份。操作日志可保存足以核验的终态与分支证据；恢复时取当前快照。没有足够历史证据则不自动提交旧业务结果。
- 历史卡片必须由 pi 的完整、稳定的当前活跃分支快照直接生成，每张卡携带原生 `(sessionId, entryId)`；禁止按文本、时间戳、顺序或 slash 命令展开结果推断身份。extension 使用公开 `ctx.sessionManager.getBranch()/getSessionId()/getSessionFile()/getLeafId()`，经现有 `session.snapshot/1` 和快照事件提供数据；IDEA 不实现 jsonl 会话树解析，也不新增 WS 服务。保留分支顺序，不按时间戳重排；重复文本仍是不同 entry。
- IDEA 只展示用户消息，不要求展示传输中的 AI/tool entries。发送输入不立即添加历史卡片；`/model` 等未生成原生用户消息的输入不进入历史，skill/模板显示 pi 实际保存的文本。
- 升级后第一份有效快照整体替换旧卡片，包括旧 pending 和父 session 遗留项；取消旧隐藏策略，因此当前分支上的旧隐藏消息也会显示。无效、不完整或其他 session 的快照不能清空缓存。
- 断联/未启动时本地记录只作离线缓存，不可用来推断操作目标；重连后刷新。操作 checkpoint 不得覆盖新快照的历史，只恢复草稿、恢复文本和幂等信息。未决操作期间继续冻结视图，保持既有结果确认流程；解锁后采用当前原生分支。

建议完整快照发送节点：握手完成、重连、操作终态、session 变化、显式 `session.snapshot`。高频流事件先合并；第一版不设计增量一致性算法。

首期 method registry：

| method/version | 参数 | 结果与副作用 |
|---|---|---|
| `session.snapshot/1` | `{}` | 只读全量快照，可重新请求 |
| `conversation.rewind/1` | `{targetEntryId}` | pi 1.5.0 / session v3；非 busy、完整稳定观察、目标 parent 安全才执行；终态含目标 id 和快照 |
| `conversation.forkAtTurn/1` | `{targetEntryId,turnLeafId}` | 在独立副本内执行；完整 turn（含 tools）按原生身份匹配；`ctx.fork(position:"at",withSession)`；耐久 handoff 终态含目标 id、turn leaf、新 sessionId 与历史快照；取消/替换异常也用同一 ticket 记录 |
| `operation.reconcile/1` | `{operationIds:[...]}`（1–128 个唯一 id） | 耐久的日志元数据操作；只在这些执行已确认结束时解除运行时锁；返回相同 id 列表和当前快照；不执行原请求、不把其未知结果改写为成功 |

ForkWorkflow 的 Terminal 准备是本地业务阶段：通用 preparation reservation 先落盘，尚无 request 时不发 query；READY 后将同一 id 一次性转换为普通 request。IDE 重开后未发送 preparation 标为 not_sent，不重新启动或执行；已发送只查询原 id。目标面板在核验成功前保留完整副本。取消准备可保留副本；绑定最终会话时不归档中间副本。

首版 fork 只支持持久会话且没有 label 条目。公开 pi fork 会重建 label/rechain，未实现其核验适配前执行前 UNSUPPORTED。目标 TUI 编辑器必须为空：pi 1.5.0 wrapper 会在 withSession 返回后清空 editor，不能先确认“保留非空编辑器”。源 Terminal 输入和 IDEA 草稿不受影响。启动或替换 runtime 补写的 session_info/model_change/thinking_level_change 可跟在已核验 turn 后，其他新增内容不放行。withSession 的历史快照以 `handoff-<nonce>` 标记核验采集来源；它不是连接 runtime 身份，也不作为当前快照投递，当前状态仍由新认证连接独立采集。

对账响应可附 `executionEnded:true`。它不是执行成功状态，也不能凭新 hello / runtimeId 改变 / lease 释放推断：必须有原 executor 返回后持久化的 `.ended` 证据、耐久终态，或已确认原进程不再存在。仍在展开的同进程 reload 回调没有上述证据时不可对账；handoff ticket 未完成时也不可对账。原日志丢失仍返回 NOT_FOUND，不产生结束证据。


## 5. 操作运行时与 pi 适配器

公共 registry 的描述包括 method/version、schema、资源锁、前置核验、executor、后置核验；只读方法不申请修改锁。锁绑定稳定资源 tabKey，session 替换不能释放在途锁。

PiAdapter 通过唯一内部命令取得新鲜 CommandContext。使用公开程序化命令派发，内部命令只接受已登记 id；不写 Terminal 编辑器。能力开放前执行无副作用 probe 并验证不产生模型请求。内部保留前缀必须有 input 拦截兜底。

业务不得直接调用 socket、维护重连定时器或拼 response；executor 只返回数据/错误和可用上下文，公共 runtime 负责记录与发送。不同 pi 版本只改 PiAdapter，不散落版本分支到 IDEA UI。

### 5.1 跨实例执行交接

fork/switch 使用运行时的通用 handoff，不改变 wire 消息族或 operation id：

1. 执行前持久记录 ticket：请求 id/摘要、原身份、稳定 tab、预期替换及核验参数。
2. 在公开 withSession 回调中仅使用新 ctx 核验结果，以只捕获序列化数据的日志函数写终态。
3. 新实例读取允许的 ticket/日志并报告原请求结果；旧 socket/ctx/pending map 不再使用。
4. 新 hello 可先于终态到达，先作为候选实例，不凭 sessionId 改变认定成功。交接校验通过才提交会话绑定。
5. 日志必须单写者/条件原子更新，不能由旧新 runtime 同时覆盖。ticket/terminal 用已 fsync 的临时文件原子排他链接，读取者同步目录后才采信；取消、shutdown 清理与新实例恢复均须测试。
6. 意外 `/new`/reload 不可借在途 fork 任意认领结果。执行已完成但日志未落盘仍 UNKNOWN，不自动再次 fork。

Transport 只验证连接；OperationRuntime 验证交接；业务层决定新 session 如何展示，三者不得合并。

## 6. 持久化、安全与生命周期

发送前保存请求归属和摘要；pi 执行前保存 intent，终态先写日志再发送。导航与日志不是原子事务，崩溃窗口必须保留未知。

日志放插件管理的独立目录，不能 append 到 pi session tree 改变 leaf；权限、原子写入、容量和清理由 OperationJournal 统一管理。不持久保存 token/整份敏感上下文用于普通诊断。

ack 仅表示已耐久接收结果，不表示可以重做请求。终态可按策略清理，但未决不能静默淘汰。丢失/损坏记录返回未知，不自动解锁或重放。

UI 本地应用的幂等标记与业务状态一起持久化；LOCAL_CONFLICT/LOCAL_APPLIED 属于业务，不进入远端执行状态。关闭 IDE 不宣称远端取消；重开后只查询/对账。

日志输出允许 id、method、状态、耗时、脱敏 session 标识及错误码；默认禁止 token、全文 params、完整 snapshot。错误弹窗不是持久执行日志。

## 7. v1 兼容与演进

现有 v1 短连接事件可继续保留；v2 操作结果不使用 v1 的 `(tabKey,type)` last-seq 去重，必须按 id 独立关联。

v2 接管 tab 的 session 绑定与快照后，旧 v1 session_changed/stopped 不得重复提交或归档；明确设定单一状态所有者。旧 extension 无 capability 时禁用新操作并提示升级/重启，不自动 fallback 粘贴。

兼容字段增加保持 v2；未知可选字段忽略，未知原生 entry 保留。未知 method/version 明确拒绝。必需语义变化升级协议或 method 版本，不默默改变旧客户端行为。

## 8. 开发者接入清单

新增普通方法仅需：

1. 选 method 名及版本，定义 params/data 和副作用语义。
2. 注册 schema、稳定资源锁、前置/后置核验。
3. 通过 PiAdapter 复用公开 API；不重实现会话管理。
4. 返回统一 response/context；无需新增消息族。
5. 实现 IDEA 业务提交器，保护草稿和本地 revision；历史只投影原生分支。
6. 添加能力声明、真实 API smoke test 和故障契约测试。

新增 replacement 方法复用 handoff；多阶段流程（如新 Terminal fork）放业务 Workflow。若新增方法要求改 Transport/公共执行状态，先审查抽象边界，不直接添加特例。

必须测试：accepted 不是成功；超时只 query；重复 id 不执行两次；同 id 不同参数拒绝；丢包/断联/重启不重放；取消和部分副作用；当前/历史快照不同；未知字段；超大/不完整快照；旧 v1 乱序；withSession 新旧实例顺序；TUI 编辑内容保真且零模型请求污染。

## 9. 规范来源与实施前校准

参考本机 pi 1.5.0 的 `docs/rpc.md`、`docs/session-format.md`、`docs/extensions.md` 及对应分发/导航源码。未据此声称其他版本兼容。

实施基线锁定为 pi **1.5.0** 的交互式 TUI 与原生 session v3。其他 pi 版本不声明 `conversation.rewind` / `conversation.forkAtTurn`，probe 未完成/失败不声明操作能力。单元契约与真实 TUI smoke 是不同验证层；未执行 smoke 不能声称真实 TUI 兼容已通过。

首期参数：单帧默认 32 MiB、发送队列默认 64 MiB（最多 128 帧）、extension 待处理帧最多 128 个；IDE socket reader 等待后台消费，快照与 UI 通知分别合并。extension 以 `PI_BRIDGE_MAX_FRAME_BYTES` / `PI_BRIDGE_MAX_QUEUE_BYTES` / `PI_BRIDGE_MAX_JOURNAL_BYTES` 配置预算；IDE 以 `pi.bridge.maxFrameBytes` / `pi.bridge.maxQueueBytes` / `pi.bridge.maxJournalBytes` 系统属性配置。两端日志默认各 512 MiB。

IDE 日志位于 IDE system 目录的 `pi-terminal-bridge/<project-hash>/local`；extension 日志位于同一 project 目录的 `remote/<tab-hash>`，通过 `PI_LAUNCHER_JOURNAL_DIR` 注入。均不写 pi session tree，token 不落盘。快照证据经 ack 后可去掉，IDE 成功提交后保留最近业务 checkpoint 与所有终态 tombstone；未决记录不自动淘汰。预算耗尽、损坏/无法取得 lease 时拒绝修改；不自动删日志来解锁。

snapshotId 覆盖 runtimeId、事件代数及完整原生 session/header/file/branch/公开状态；缓存最多 16 个观察凭据。采集前后对拍，不能只比较 leafId。首期 rewind 在导航前拒绝无 parent、parent 是 user 或 custom_message 的目标，避免多退或改变 TUI 编辑器；安全 parent 的未回复消息可以处理。

验证入口：`node --test tests/bridge-extension.test.mjs`（真实资源的运行时/故障契约）、Gradle `test`（framing、快照、投影、业务提交前置条件）、`PI_BINARY=/path/to/pi python3 tests/bridge-tui-smoke.py`（隔离配置的 PTY + 真实 pi 1.5.0，覆盖 rewind 与 CLI 副本内 fork/withSession 交接）。CI 手动触发可勾选 `tui_smoke`；默认构建会执行 Node/JVM 契约测试。源码交付不等于这些验证已通过。
