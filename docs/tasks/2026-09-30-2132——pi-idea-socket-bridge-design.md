# Pi ↔ IDEA 桥接实现与开发约定（0.3.5 内测）

> 本文描述双向操作重构后的源码；编译、真实 TUI 和 IDE 端验收尚待执行。
> 插件版本仍为 0.3.5；托管 extension VERSION=9。正式 wire 契约见 [bridge-protocol.md](../bridge-protocol.md)，实施决策见 [双向操作通道与回退确认](../plans/2026-11-04-2114——双向操作通道与回退确认.md)。
> 公共通道、Rewind 和 ForkWorkflow 的 wire 契约统一使用 v2；v1 仅保留状态/模型/写文件通知。本轮 Fork 代码仍待编译与真实 TUI / IDE 验证。

## 1. 产品结构

IDEA Terminal 继续运行完整 pi TUI，托管 extension 与该 TUI 在同一进程执行。没有 RPC mode、第二个操作会话的进程，或直接修改 SessionManager/jsonl 的替代实现。

```text
PiConversationPanel
  → PiConversationService / ConversationOperations
    → BridgeOperations → OperationStore（IDE system 目录）
      → PiBridgeServer → BridgeConnection ⇄ extension Transport
                                         → OperationRuntime / OperationJournal
                                         → PiAdapter → 新鲜 CommandContext → 公开 pi API
```

- pi 决定会话身份、持久化树、当前分支及运行状态。
- IDEA 决定标题、草稿和本地业务提交；消息列表只是原生分支缓存，完整快照整体替换。
- 面板显示用户消息；传输保留原生 AI/tool/其他 entries，不能将它宣称为精确模型 prompt。
- 网络超时表示观察未知，不能改写远端执行状态，也不能自动重放修改请求。

## 2. 组件与边界

| 组件 | 职责 |
|---|---|
| `BridgeProtocol` / `SessionSnapshot` | 通用 JSON DTO、LF framing、原生分支校验与用户投影读取；未知 entry 字段保留 |
| `PiBridgeServer` | loopback 监听、token/tab 校验、v1/v2 分流、单一活动连接 |
| `BridgeConnection` | 认证连接绑定、后台读写、心跳、发送预算、断联 |
| `BridgeOperations` / `OperationHandle` | 本地 preparation reservation、请求关联、耐久收件、原 ID 查询、稳定 tab 锁、只读结果订阅 |
| `OperationStore` | 本地请求与业务 checkpoint 原子写入、容量保护 |
| `ConversationOperations` | rewind 意图、业务提交、草稿保护、显式对账 |
| `ForkWorkflow` / `ForkPolicy` | 独立 Terminal 准备、原生 turn / 副本身份核验、结果提交策略 |
| `ConversationProjection` / `ConversationCommitPolicy` | 无 IDE 依赖的原生历史投影和提交前置条件 |
| extension `Transport` | JSONL 长连接、重连与背压，无请求重放队列 |
| extension `OperationRuntime` | descriptor registry、intent/终态、去重、查询、handoff 协调 |
| extension `PiAdapter` | 无副作用 probe、内部命令派发、快照、公开 API 和 rewind 后置核验 |
| extension `OperationJournal` | 独立日志、writer lease、执行结束证据与不可变 handoff ticket/terminal |

extension 仍是单文件资源 `src/main/resources/bridge/pi-launcher-bridge.ts`。安装器只替换 `@EXTENSION_VERSION@`，按完整内容比较后写入 `~/.pi/agent/extensions/pi-launcher-bridge.ts`；不触碰用户的其他扩展文件。更新在新 Terminal 或 pi `/reload` 后加载。

## 3. 连接与兼容

启动注入 `PI_LAUNCHER_PORT/TOKEN/TAB_KEY`，新增 `PI_LAUNCHER_JOURNAL_DIR`。缺少前三个变量的 pi 不注册命令或启动后台资源。缺少日志目录时不能开放修改能力。

extension 在 `session_start` 之后派发唯一内部命令完成 probe，再发 v2 hello。修改基线锁定 pi **1.5.0** / session v3；不支持的版本不声明 rewind。内部保留前缀有 input 拦截，过期的 `/bridge-rewind` 也被消费并提示，不进入模型。

- hello/welcome 认证后身份固定在连接上。
- 握手 5s；心跳 5s；15s 无响应断开；重连退避 0.5–10s 并带抖动。
- 每帧严格按 LF 分隔，接受 CRLF；UTF-8 分块及 U+2028/U+2029 保持原样。
- 默认帧 32 MiB、发送队列 64 MiB / 128 帧。资源不足明确 CONTEXT_TOO_LARGE，不传“完整”的截断快照。
- 网络解析与日志在后台；IDE reader 等待后台消费，快照/UI 通知合并，避免 EDT 队列无限堆积。
- 同时出现第二个活动 runtime 不直接接管；旧 socket 关闭后可重连。未知操作仍保留原请求身份与锁。

v1 短连接事件暂保留，用于 working/idle、模型、写文件通知。按 `(tabKey,type)` 的 seq 去重；每个事件短暂失败时重试一次。状态类事件（`agent_state`、`model_changed`、`session_changed`、`session_stopped`）在 EDT 上只处理同类型最新一条；`file_modified` 每条对应不同路径，必须逐条处理，不能被后来的 seq 覆盖。v2 接管过的 tab，即使断联，旧 v1 `session_changed/stopped` 也不能抢先换绑或归档。

v2 快照是该 tab 会话绑定与分支投影的单一入口。agent 完成通知另用 v1 状态转换记录，避免快照同步吞掉 error/abort 通知。

## 4. 快照和本地卡片

`SessionSnapshot` 包含公开 session header/file/id、完整活跃分支的原生 entries、leaf、idle/model/pending 状态、采集时间及 source。分支校验只要求 `user`/`assistant`/`toolResult` 角色带 `content`；其他角色（如 `!cmd` 写入的 `bashExecution`、`custom`、`branchSummary`、`compactionSummary`）没有 `content`，只校验 `role`。采集前后对拍；snapshotId 覆盖 runtimeId、事件代数和完整原生内容，不只是 leaf。adapter 只缓存最多 16 个观察凭据。

完整稳定快照才可驱动历史更新；首连、重连、树变化、消息结束（合并）、模型变化、操作终态与主动查询都可同步。心跳不携带历史。

历史卡片必须由 pi 的完整、稳定的当前活跃分支快照直接生成，每张卡携带原生 `(sessionId, entryId)`；禁止按文本、时间戳、顺序或 slash 命令展开结果推断身份。extension 使用公开 `ctx.sessionManager.getBranch()/getSessionId()/getSessionFile()/getLeafId()`，经现有 `session.snapshot/1` 和快照事件提供数据；IDEA 不实现 jsonl 会话树解析，也不新增 WS 服务。保留分支顺序，不按时间戳重排；重复文本仍是不同 entry。

面板发送不创建历史卡片，也不做 pending 匹配或文本规范化匹配；命令没有产生原生 user entry 就不显示。第一份完整有效快照替换旧卡片，旧隐藏记录失效；旧 session 卡片不会混入当前历史。消息保留原生顺序和文本，时间戳仅用于显示。空分支会清空历史；不完整、变化中或其他 session 的快照不能覆盖缓存。

本地 XML 消息仅作离线缓存；无在线有效快照不能执行 Rewind/Fork。草稿、recoveryText、revision 和幂等标记继续保存。checkpoint 只恢复草稿/恢复文本/幂等信息，不能覆盖在线历史。未决操作仍冻结视图，不能用当前快照推断原操作成功；结果核验或显式对账后采用原生分支。

## 5. Rewind

1. 取得 READY capability、完整快照和精确 target entryId；记录草稿/视图 revision。
2. 本地 intent 落盘，锁住同 tab 的冲突操作，只发送一次；历史和草稿保持原样。
3. extension 耐久登记请求后返回 accepted。相同 ID/相同摘要只查询；相同 ID/不同摘要返回 ID_CONFLICT。
4. 内部命令取得新鲜 ctx；执行前重新核对 session、snapshot、busy、开始期限。不自动 abort，也不等待未来 idle。
5. 首期只导航到安全的 parent：无 parent、parent 是 user/custom_message 时执行前 UNSUPPORTED，避免公开 API 再多退一条或改变编辑器。安全 parent 的未回复消息可处理。
6. 检查 cancelled、真实分支必须等于原 branch 到目标前的完整切片，且 TUI 编辑器内容不变。
7. 先落终态，再发送结果与 after/current 快照。日志失败只保留未知，查询也不能提前泄露未耐久成功。
8. IDEA 先耐久保存结果再 ack；只有结果关联、快照证据、当前 session 和本地 view revision 全部满足时提交投影。
9. 草稿 revision 未变则回填文本；已变则保留新输入，把旧文本放到 `Restore text`，点击追加到草稿。

accepted/running 都不是成功。超时或断联只更新观察状态，并每 5s 按原 ID 查询。查询 NOT_FOUND 不代表未执行。UNKNOWN 时允许浏览、复制、编辑草稿和操作其他会话；同 tab 的发送、rewind、fork、删除会话受保护。

## 6. 显式对账

面板状态由 bridge 自动同步，不再提供常驻 Sync 或状态行文字按钮。顶部统一 toolbar 仅在未决执行均已结束、当前完整快照与对账 capability 可用时显示 `Sync current session` 图标，提交耐久的 `operation.reconcile/1` 元数据操作。`Restore text` 仅有待恢复文本时显示为顶部图标；输入区 Workspace / Copy 使用统一无边框小图标。它能同步已确认当前状态，但不会执行原请求，也不将原结果改成成功。

允许对账的结束证据：

- 原请求有耐久终态；
- 原 executor 返回后写入的 `.ended` 证据（绑定 id/digest/runtime）；
- 原进程已确认不存在。PID 重用时保守地保留未知。

新 hello、runtimeId 改变或释放 writer lease 本身都不足以证明旧异步执行结束；同进程 reload 可能还在展开旧命令。handoff 未完成也不能以对账抢走所有权。结果和当前分支不同、视图变化等进入本地 conflict，由用户显式对账。对账本身的结果未知时同样只查询；后续显式对账可覆盖多个已经确认结束的请求记录。

## 7. 持久化与交接

IDE system 目录：

```text
pi-terminal-bridge/<project-hash>/
  local/<operation-id>.json
  remote/<tab-hash>/
    writer.lease
    <operation-id>.json
    <operation-id>.ended
    <operation-id>.ticket
    <operation-id>.terminal
```

- 日志不写 pi session tree，不持久化 bridge token。
- 同目录临时文件 + 原子 rename + fsync；Unix 目录权限 0700、文件 0600；Windows 依赖用户 profile 下 IDE system 目录自带的 ACL（token 不落盘，日志只含脱敏标识）。
- writer lease 拒绝仍存活的进程接管；stale lease 回收串行化。损坏/不可证明安全时关闭修改能力。
- 两端默认日志预算各 512 MiB；未决不淘汰。ack 后远端可去掉历史快照；本地成功提交后保留最近 checkpoint 与所有终态 tombstone。
- 预算满时拒绝新操作，不通过清空日志自动解锁。损坏日志与失败的 stale-lease 回收可能需要人工检查。
- 导航、终态日志、本地提交不在一个事务中，不承诺跨进程 exactly-once。

通用 handoff 在执行前写不可变 ticket，绑定原 id/digest/稳定 tab/预期替换证据；公开 `withSession` 回调仅用新 ctx 核验，再以独立的排他终态文件完成单写者交接。新 runtime 查询原请求并验证 ticket/nonce/digest 后报告结果，旧 ctx/socket/pending map 不复用。新 hello 可以早于终态，不能作为成功证明。

ForkWorkflow 已使用通用交接：只捕获 ticket 配置和原生核验证据，回调中使用新 ctx。旧运行时仍存活的取消/执行前失败也写 ticket terminal；替换后异常记录 failed/effect unknown，不能冒充取消或重放。ticket/terminal 先 fsync 临时文件，再以排他硬链接原子发布；读取 terminal 时同步目录，防止读到半写文件或未经同步的结果。

## 8. ForkWorkflow

1. 源 tab 必须具备 fork capability、完整稳定快照、准确 user entryId，且没有未决操作或忙碌。
2. 新目标暂用源原生卡片缓存，草稿为空，不继承隐藏状态；没有提前 subList 裁剪。
3. 在普通操作日志中先保存 `preparing` reservation（还没有 request），内容包括目标身份/初始视图、源原生文件路径、目标 entryId 与完整 turn branch。
4. 耐久发布后只启动一次目标 Terminal：`--fork <原生文件完整路径> --session-id <目标id>`。等待快照/能力事件，不用延迟或轮询 session id。准备期点击顶部 Stop fork preparation 图标可停止并保留副本；点击/恢复不会另起目标进程。
5. 目标快照核对原生 turn entries 前缀与源完全一致；只允许其后附带启动 session_info/model/thinking 元数据。复制身份变化、内容变化、busy 或不支持 capability 时不发修改请求。
6. 同一 reservation id 一次性转成 `conversation.forkAtTurn/1`，参数 `{targetEntryId,turnLeafId}`，复用耐久 intent / 结果 / query。
7. PiAdapter 在新鲜命令上下文重新核对观察/截止时间/完整 turn leaf。目标必须持久、无 label、TUI editor 为空；公开 API 的 label 重建与 callback 后清空 editor 行为决定这些前置限制。
8. durable handoff 后调 `ctx.fork(leaf,{position:"at",withSession})`，新 ctx 验证新 session/file、parentSession、完整 branch 和空 editor，记录终态。新 hello 本身不释放锁。
9. IDEA 取得耐久成功结果及兼容当前快照后，采用新 session 身份，直接投影确认分支；只更新目标，不归档中间全量副本。等待时编辑的新草稿保持原样。
10. 已发送或可能已发送只查询原 id。IDE 重开后的未发送 preparation 标为 not_sent，不重新启动或执行；缺 XML 的未决目标可由操作记录重建。checkpoint 支持在 XML 尚未持久化时恢复已确认替换身份与视图。

旧 `/bridge-fork` handler、base64/文本回退定位、固定 3s 启动延迟、1.5s/6 次结果轮询、forkingIds/session_changed 特判全部删除。过期命令只有 input 拦截，不会进入模型。失败或未知保留全量副本/当前树供用户检查；带 label 的会话需后续适配。

## 9. 启动与其他功能

以下规则保持：

- conversation.id 是稳定 tabKey；piSessionId 可换绑。已确认换绑时旧会话归档为独立 closed 条目，首次绑定不伪造归档。
- launch 时先找默认项目目录，再跨 sessions 子目录找 `*_<id>.jsonl`；命中用 `--session <完整路径>`，否则 `--session-id` 由 pi 自己解析。
- 不注入 `--session-dir`；不做启动期清空/mtime 重绑；Terminal 按需恢复。
- Windows 注入 PowerShell `$env:` 或 POSIX `env`，cmd.exe 跳过并警告；推荐 pwsh 7。
- session 冲突 Extra arguments 在启动时清洗；插件版本与 tag 只在发布流程中改。
- rename 禁止重名；删除会话不删除 jsonl。
- 普通草稿仍走 bracketed paste + 80ms 后 Enter；Rewind / Fork 控制操作均走 bridge。
- tool_result 上报 edit/write/patch/apply 的路径，后台刷新 VFS，可选在 EDT 打开文件；agent 完成通知默认关闭。
- 面板历史的折叠、滚动锚点、消息轨道和快捷键保持。

## 10. 验证入口与未决项

- `node --test tests/bridge-extension.test.mjs`：执行打包资源，覆盖 registry、快照、busy/取消/歧义、重复 ID、日志/断联/重启、执行结束证据、handoff、背压/framing 和保留的 v1 行为。
- `./gradlew test`：JVM framing、原生快照、旧缓存替换、草稿/视图提交前置条件，以及完整 turn / 副本身份 / fork 提交策略。
- `PI_BINARY=/path/to/pi python3 tests/bridge-tui-smoke.py`：隔离目录和配置，用 PTY 启动真实 pi 1.5.0，核验 probe、rewind、CLI 副本内 fork / 新实例交接、TUI 编辑器及零 prompt/model 事件。只操作临时 fixture，不使用 RPC。
- GitHub CI 默认执行 Node 契约测试与 `test buildPlugin`；workflow_dispatch 可勾选 `tui_smoke` 安装固定 pi 版本执行真实 TUI smoke。手动 dev 构建不发布。

本地依仓库约定只做静态检查，不运行 Gradle、单测或真实 TUI smoke；这些测试的存在不代表通过。发布前仍需真实长会话资源预算、IDE 断联/重开与本地提交崩溃窗口、用户实际发行版，以及 fork 的真实 withSession 生命周期与 label 适配验收。
