# Pi ↔ IDEA Socket 通道设计

## 背景

插件当前在 IDEA 右侧临时维护会话列表，重启即丢。持久化方案已对齐（见决策记录）：

- 会话 id 由插件生成，启动 pi 时通过 `--session-id` 注入，`PiConversation.id` 即 pi sessionId
- 启动时附带 `--name <title>`；title 以插件侧存储为权威，单向同步（插件 → pi）
- IDEA 重启后按需 `pi --session <id>` 恢复
- 索引存 PersistentStateComponent（project 级），pi 的 jsonl 是会话数据权威
- 插件侧删除条目不联动删除 pi 会话文件

但"只管好启动参数"有固有盲区：用户在 terminal 里 `/new`、`/fork`、`/clone`、`/resume` 会在 pi 进程内部产生新的 sessionId，tab 与插件条目脱钩，且 socket 失联/IDEA 重启后会接回旧会话。因此需要一条**运行期回报通道**。

## 通道选型结论

**socket 为主通道，session 目录扫描只做重启/重连后的对账兜底**（不做运行期 watch）。

传输层实现决策（实现期细化）：**loopback TCP（`127.0.0.1` 随机端口 + 每实例随机 token）**，而非 unix socket / Windows named pipe 双实现。理由：JetBrains 侧 named pipe 需要额外传输层依赖、AF_UNIX 走相对少用的 Netty API，双实现成本远超收益；TCP 一套代码同时覆盖 Windows 与 Linux（**Windows 优先验证，Linux 同步支持**）。安全对冲：token 校验拒绝非本插件启动的连接/消息。

| 能力 | socket | 文件 watch |
|---|---|---|
| session 变更实时上报 | ✅ | ⚠️ 凑合（靠落盘副作用） |
| 运行期状态（working/idle）推送 | ✅ | ❌ 不落盘 |
| 双向命令（IDEA → pi） | ✅ | ❌ |
| 失联自愈 | ❌ 需兜底 | ✅ 状态在磁盘上 |

定位：socket 传的是**事件**（会丢），文件系统是**状态**（不会丢）。事件流负责实时性，对账负责正确性。

## 一、借鉴 herdr 的点

参考实现：`~/.pi/agent/extensions/herdr-agent-state.ts`（herdr 集成 pi 的方式，模式成熟）。

1. **env 注入约定**：启动 pi 前注入 env（`PI_LAUNCHER_PORT`、`PI_LAUNCHER_TOKEN`、`PI_LAUNCHER_TAB_KEY`），extension 在 pi 进程内读取。实现方式为启动命令前缀：Windows（默认 PowerShell）`$env:VAR='..'; ` 逐个赋值；POSIX `VAR=.. VAR=.. pi ..` 内联前缀。启动时约定，无需运行期发现机制。
2. **per-request connect，无长连接**：每条消息独立 `net.createConnection` → write → destroy，带超时降级。绕开"IDEA 重启后旧 socket 失效导致 pi 侧死连接"的生命周期坑。herdr 一次失败后 1500ms 重试一次再放弃。
3. **env 门控**：非本插件启动的 pi 进程（用户手跑的）env 不存在，extension 全部行为短路，零副作用。
4. **sessionId 获取方式**：`ctx.sessionManager.getSessionId()` / `getSessionFile()`，在 `session_start` 等事件里刷新缓存。
5. **跨平台写法**：Linux unix socket，Windows named pipe（`\\\\.\\pipe\\<name>`），`node:net` 两者统一覆盖。

**不抄的点**：herdr 的协议是为其 pane 管理特化的（`pane.report_agent_session` 等）。我们做通用信封。

## 二、协议：通用信封

```jsonc
// pi → IDEA（上报）
{ "v": 1, "seq": 1024, "type": "session_changed", "tabKey": "<conversationId>", "token": "...", "data": { "sessionId": "..." } }

// IDEA → pi（命令，请求-响应，后期）
{ "v": 1, "seq": 88, "type": "cmd_set_session_name", "tabKey": "<conversationId>", "data": { "name": "..." } }
```

兼容性语义（关键约定，不是形式主义）：

- **`v` 是主版本号**（v1/v2），仅协议结构变更（字段类型变、语义反转）才 bump；pi/插件自身的 patch 版本不参与比对
- **未知 `type` 直接丢弃**，不断联不报错——老端收到新功能消息只是忽略
- **已知 type 解析失败**：丢弃 + 日志告警，核心功能（session 上报）不受影响
- **`seq` 单调递增，且去重按 `tabKey` 分域**（每 tabKey 独立 seq 基线）：消息来自多个 pi 进程，各进程 seq 独立计数，全局去重会误杀；用于乱序/重复丢弃（防旧状态闪回）。未知 type 直接丢弃的规则同前
- 降级底线：通道完全失联时，退化为现状行为（空 terminal，用户手动 resume），不影响 pi 本身

首期只实现一个 type：`session_changed`。后续加功能只是加 type。

## 三、pi 侧：extension

**分发**：插件安装/更新时向 `~/.pi/agent/extensions/` 写入一个 extension 文件（如 `pi-launcher-bridge.ts`），文件头注明由插件管理、勿手改。注意与 herdr 等同目录管理者共存（只覆盖自己那个文件）。`~/.pi/agent/extensions/` 是用户信任目录，extension 拥有 pi 进程同权限，这是必要的安全知会。

**实现约束**（来自 pi 文档）：

- factory 里**不**启动 socket/watcher/timer（部分加载不伴随 session）；从 `session_start` 起资源，`session_shutdown` 幂等清理
- extension 跑在 pi 的 Node/Bun 运行时内，直接用 `node:net`

**事件 → 上报映射**：

| pi 事件 | 上报 type | 说明 |
|---|---|---|
| `session_start` | `session_changed` | 换绑 tab ↔ sessionId（`/new`、`/fork`、`/clone`、`/resume`、重启后恢复都会走到这） |
| `session_shutdown` | `session_stopped` | tab 标记 closed |
| `model_select` | `model_changed` | 侧栏显示当前模型 |
| `agent_start` | `agent_state: working` | 会话条目状态 |
| `agent_settled` | `agent_state: idle` + 结束状态 | **`agent_settled` 是"本轮彻底跑完、不会再自动续"的信号**，语义比 `agent_end` 准，通知用它 |
| `tool_call` / `tool_result` | `tool_activity` | bash 命令、读写文件（用于通知与文件同步，见 §六） |
| `message_end` | `usage` | token 用量/成本（升级 PiStatusWidget 数据源为推送） |

请求-响应类命令（IDEA → pi）首期不做，协议预留。

## 四、IDEA 侧：listener

- **传输层**：`ServerSocket` 绑定 `127.0.0.1` 随机端口，per-project 实例；accept 循环逐行读 JSON → 信封解析（v/token 校验、按 tabKey 分域 seq 去重、type 分发）→ 按 `tabKey` 路由到 `PiConversationService`；handler 无状态路由，不复用 `active()` 隐式假设
- **孤儿消息**：`tabKey` 对应 conversation 已被删除 → 丢弃 + 日志，不复活条目
- **安全**：loopback TCP 本机任何进程可连；每实例随机 token 校验拒绝非法来源，后续敏感命令需升级握手

## 五、对账兜底（socket 的第二条腿）

触发时机：IDEA 启动、socket 重连成功、以及手动 reconcile。

逻辑：先按 pi 默认目录约定扫 `~/.pi/agent/sessions/--<projectPath>--/`，再兜底在 `sessions/` 下跨子目录按 `*_<id>.jsonl` 递归搜一层。pi 的 `--session-dir` **按给定目录扁平查找**（不再套一层 cwd 编码），且 `--session <完整路径>` 走 path 分支、不需要 `--session-dir`。因此插件**不再注入 `--session-dir`**（让 pi 用自身配置决定落盘目录），恢复时优先用 `--session <jsonl 完整路径>`；扫不到则退回同 id 的 `--session-id`——pi 对该 id 是 resume-if-exists / create-if-not，即便扫描没覆盖到用户自定义 `sessionDir` 也能正确恢复而非重复建会话。若给 `--session` 传裸 id 又配错 `--session-dir`，才会报 `No session found matching`。

- v1（已实现，launch 时判定）：**不在启动时对账**——resume vs 新建在 launch 时用 `findSessionFile` 判定（此刻 jsonl 必已落盘，避免刚建会话就重启时误杀 id）；命中给 `--session <完整路径>`，未命中给同 id 的 `--session-id`（pi 侧文件被删也会用该 id 重建）
- v2（不做，理由见 §七）：mtime 重绑失联窗口的会话切换还原

## 六、socket 解锁的功能（迭代顺序）

### P0 —— 本次交付
1. **会话持久化 + `session_changed` 换绑**：解决 `/new` 脱钩。换绑策略：同 tab 换绑新 id，原 id 条目保留为 closed（可查可复活），符合 pi "分支不删"语义。
2. **agent 结束通知**（曾被砍掉，现回归）：`agent_settled` → IDEA notification，含正常/错误/中止状态。设置项默认关闭（避免打扰），在 Settings 增加 toggle。附带会话条目上 🔄/✅ 状态徽标。

### P1
3. **pi 修改的文件在 IDEA 中打开**（曾被砍掉，现回归）：`tool_result` 中提取 file-tracking（read/modified files）→ 对 modified 文件触发 `VirtualFileManager.refreshAndFindFileByNio` + 可选打开编辑器。**默认关闭**（Settings toggle），因为会抢焦点/打乱当前编辑布局，由用户显式开启。

### P2
4. 工作状态/用量推送：`agent_start`/`usage` 升级 `PiStatusWidget` 数据源（轮询 → 推送），token 用量、成本、当前模型。
5. 双向命令：重命名会话（补齐 name 双向同步）、`/compact`、切模型。
6. 危险命令确认：`tool_call` bash → IDEA 原生确认对话框 → 回传决定（对齐 pi examples 的 confirm-destructive 模式，UI 移到 IDEA 侧）。

### 已排除
- 读取"pi 全局有哪些插件"：静态配置，IDEA 侧直接读 `~/.pi/agent/extensions/` 目录即可，不走通道。
- `pi --mode rpc`：双向 JSONL 更"官方"，但要求放弃 TUI、以 RPC 模式启动，与"terminal 里交互式跑 pi"冲突。

## 七、主要风险清单

| 风险 | 缓解 |
|---|---|
| `--session-id` 与用户 extraArgs（`--continue`/`--resume` 等）冲突导致启动失败 | 启动前校验/过滤冲突参数 |
| `/fork`、`/clone` 产生新 id 造成脱钩 | `session_changed` 换绑（旧会话归档为 closed 条目）+ 存在性对账 |
| 消息乱序导致换绑丢失 | 去重按 (tabKey, type) 分域，不同 type 不共用时间线 |
| 偶发 connect 失败丢消息 | extension 侧超时 + 一次重试；服务端 seq 去重天然幂等 |
| bridge 消息线程安全 | 服务端 EDT 分发，处理前校验 project 已释放；socket Disposable |
| socket 生命周期错位（IDEA 重启后 pi 侧端口/token 失效） | per-request connect + 失败静默降级 + 对账自愈 |
| 全局 extensions 目录与其他管理者冲突/被用户手改 | 只写自己文件 + 文件头声明 + 启动时校验内容版本 |
| 协议版本漂移 | v 主版本 + 未知 type 丢弃 + 解析失败告警 |
| 非默认 shell 下 env 前缀失效 | 已知限制：Windows 假定 PowerShell（JetBrains 默认，cmd 不支持）；POSIX 用 `env` 前缀覆盖 bash/zsh/fish |
| `--session <id>` 语义依赖 pi 版本 | 文档声明最低 pi 版本要求 |

对账范围说明（审查后收敛）：resume 判定收敛到 launch 时刻（jsonl 必已落盘，启动期清空 id 会误杀刚建会话）；v2 mtime 重绑**不做**——JetBrains terminal 的 shell 进程随 IDE 关闭而终止，“IDE 关闭期间用户在 terminal 里 /new”不成立，残余风险仅剩运行期 socket 短暂失联（已由 extension 重试覆盖）。

`/new` 换绑补充（二次审查后）：pi 返回的 sessionId 首次确认时直接采纳（不归档，防 pi 侧 id 规范化差异误归档）；后续变更才归档，归档标题查重（` (archived)`、` (archived) (2)`…）。归档条目的复活：下拉选中未存活会话即按需 `pi --session` 重启（同设计§恢复语义），复活时去掉 archived 后缀。

## 八、决策记录（已对齐）

- ✅ 存储：PersistentStateComponent（方案 A），pi jsonl 为数据权威
- ✅ 删除：只删插件条目，不动 pi 侧会话文件
- ✅ id 对齐：`--session-id` 注入，`PiConversation.id` == pi sessionId
- ✅ 通道：socket 主 + 目录对账兜底，不二选一；传输层 loopback TCP + token（Windows 优先，Linux 同步支持）
- ✅ id 模型：conversation 存稳定 `id`（tabKey）+ `piSessionId` 两字段，`/new` 换绑只更新后者
- ✅ rename：**禁止重名**（同项目内 title 唯一）
- ✅ title 同步：插件 → pi 单向（`--name`），name 双向同步留待 P2 双向命令顺带解决
- ✅ 恢复语义：不自动复活 terminal，点击时按需 `pi --session <id>`；列表区分 running/closed
- ✅ `/new` 换绑策略：同 tab 换绑新 id，旧条目保留为 closed
- ✅ 回归两个被砍功能：agent 结束通知（默认关）、pi 改动文件在 IDEA 打开（默认关），均进 Settings
