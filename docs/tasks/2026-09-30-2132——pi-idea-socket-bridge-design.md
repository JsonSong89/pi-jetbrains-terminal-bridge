# Pi ↔ IDEA 桥接设计（对齐 0.3.5 内测实现）

> 本文档按 2026-10-24 的 `src/main` 代码重写，取代 2026-09-30 首版。
> 适用基线：插件 0.3.5（内测线，未发版；`sinceBuild=243`，目标 2026.1）、pi 1.4.0、pi 扩展 `VERSION = 7`、通道协议 `v = 1`。
> 事件语义按 pi 1.4.0 的 `docs/json.md` 与 `dist/core/extensions/types.d.ts` 核对。
>
> 首版里「计划做 / 已交付」的条目，以本文的「现状」描述为准；设计意图与当前实现的偏差统一收进 §11，不再把未落地能力写成已完成。

---

## 0. 设计定位

这个插件**不是 AI IDE**，也不代理 pi 的模型调用。它只做四类事：

1. 在 IDE 的 Terminal 工具窗里创建/恢复 Pi 终端；
2. 维护一份会话索引（列表、标题、草稿、用户发送记录），让会话跨 IDE 重启可恢复；
3. 通过 loopback TCP 接收 pi 进程内 extension 的运行期事件（换绑、工作状态、模型、写文件）；
4. 把面板里的草稿、文件引用、Fork/Rewind 指令送进 pi 的 TUI。

由此派生出的几条硬约束：

- **pi TUI 是唯一交互面**。插件不嵌入聊天 UI，不绕过 TUI 直接调 pi。
- **会话内容以 pi 的 jsonl 为权威**。插件只存索引与用户消息发送日志，不镜像 AI 回复全文。
- **事件流会丢，状态不会丢**。TCP 通道负责实时性；会话恢复在 launch 时刻用 jsonl 扫描兜底，不做运行期文件 watch。
- **全链路 best-effort**。桥接失效时会话列表、手动恢复、发送草稿仍可用；Fork/Rewind 依赖桥接，会明确拒绝并提示。

---

## 1. 架构总览

```
┌──────────────────────────────── IDEA 进程 ────────────────────────────────┐
│                                                                          │
│  PiConversationPanel ──► PiConversationService ──► PiConversationStore   │
│        │  (tool window)         │  (内存中的会话状态)      (XML 持久化)   │
│        │                        │                                        │
│        │                        ├──► PiStatusWidget（状态栏 working 计数）│
│        │                        │                                        │
│        │                        └──► PiTerminalService ──► Terminal tab ─┼──► pi TUI
│        │                                     │  (反射调用 Terminal API)   │
│        │                                     │                           │
│        └──────────────────────────────► PiBridgeServer                    │
│                                          127.0.0.1:<随机端口> + token    │
└───────────────────────────────────────────────▲──────────────────────────┘
                                                │ 每条消息独立 TCP 连接
                                                │ 一行一个 JSON（协议 v1）
                     ~/.pi/agent/extensions/pi-launcher-bridge.ts
                     （由 PiBridgeInstaller 从插件资源安装/覆盖）
```

| 组件 | 源码 | 职责 |
|---|---|---|
| `PiConversationPanel` | `ui/PiConversationPanel.kt` | 右侧工具窗：会话下拉、用户消息卡片、草稿输入、工具栏 |
| `PiConversationService` | `conversations/PiConversationService.kt` | 会话状态机；桥接事件处理器；Fork/Rewind 编排；文件刷新 |
| `PiConversationStore` | `conversations/PiConversationStore.kt` | project 级 `PersistentStateComponent`，索引落 XML |
| `PiTerminalService` | `services/PiTerminalService.kt` | 每会话一个 Terminal tab；启动命令构造；进程/标签生命周期 |
| `PiBridgeServer` | `bridge/PiBridgeServer.kt` | project 级 TCP server；信封校验、去重、EDT 路由 |
| `PiBridgeInstaller` | `bridge/PiBridgeInstaller.kt` | 把插件资源里的 extension 写到用户目录；内容比对升级 |
| `pi-launcher-bridge.ts` | `resources/bridge/pi-launcher-bridge.ts` | 跑在 pi 进程内；上报事件；注册 `/bridge-fork`、`/bridge-rewind` |
| `PiSettings` / `PiSettingsConfigurable` | `settings/` | 应用级设置、模型列表、桥接诊断 |
| `PiStatusWidget` | `services/PiStatusWidget.kt` | 状态栏显示当前 project 的 working 会话数 |
| actions | `actions/` | 打开面板、发送选区/文件、聚焦终端/输入框 |

---

## 2. 数据模型与持久化

### 2.1 Conversation

```kotlin
class PiConversation(
    val id: String,                 // UUID，插件侧稳定主键，同时是 bridge tabKey
    var title: String,              // 面板显示名，项目内唯一
    val tabName: String,            // 创建 Terminal tab 时的标签名，之后不再变化
    var piSessionId: String = id,   // 当前绑定的 pi session id；换绑只改这个字段
    val createdAt: Long,
    val messages: MutableList<PiUserMessage>, // 仅用户发出的消息
    var draft: String
)
```

关键语义：

- **一条 live conversation = 一个 Terminal tab = 当前一个 pi session**；归档条目只是历史索引，没有自己的 Terminal。
- `messages` **不是完整 transcript**：只记录用户从面板发出的文本，用于消息卡片、Fork/Rewind 定位，以及 Rewind 后回填草稿；AI 回复和工具调用只在 pi TUI/jsonl 中。
- 新会话：`id` 随机 UUID，`piSessionId` 初始等于 `id`；启动时 `--session-id <id>`。pi 首次上报 `session_changed` 时采纳 pi 的真实 id（pi 可能对 id 做规范化）。
- 换绑（`/new`、`/fork`、`/resume` 等）：Tab 的 `id` 不变，只更新 `piSessionId`；旧会话被归档成一条独立 closed 条目（新 `id`、旧 `piSessionId`、复制旧 `messages`、`draft` 为空），插入到 live 条目之前。旧 jsonl 保留在磁盘上，随时可恢复。
- 归档条目的 `tabName` 是归档时的旧标题；恢复时会尝试去掉 `" (archived)"` 后缀（边界见 §11）。
- 删除会话只删索引与 Terminal，不删除 pi 侧 jsonl。

### 2.2 标题与唯一性

- `rename` 会 trim；空名或与原名相同直接返回 false；与**任何**条目（含归档）重名时拒绝并弹警告。
- Fork 标题：`<源标题>-fork`，重复时 `<源标题>-fork (2)`、`(3)`…
- 归档标题：`<原标题> (archived)`，重复时追加 ` (2)`、`(3)`…
- `tabName` 是 `val`：重命名只改面板显示名，Terminal 标签名和 pi 侧 `--name` 都不会立即变（下次 launch 才会把新 `title` 传给 `--name`）。

### 2.3 持久化

`PiTerminalBridgeConversations.xml`（project 级）：

```
State:
  entries: [ Entry{ id, title, tabName, piSessionId, createdAt, draft, messages[] } ]
  activeId: String?
  titleSeq: Int        // 新建会话的标题序号 Pi-<n>-<MM-dd HH:mm>
```

- 每次状态变更（新建、换绑、归档、发消息、改草稿、重命名、删除）都会调用 `save()` 写回内存 state；实际落盘时机由平台决定。
- 加载时丢弃 `id`/`title` 为空的损坏条目；`activeId` 失效时回退到最后一个条目。
- IDE 启动时**不自动复活终端**：只加载索引，用户点击会话时才 launch。
- 不做启动期清空或 mtime 重绑：resume-vs-new 在 launch 时刻判断（见 §3），避免误杀刚创建、jsonl 尚未落盘的会话。

---

## 3. 启动、恢复与命令构造

### 3.1 启动入口

| 入口 | 行为 |
|---|---|
| 工具栏 `Open Pi Bridge` / `Ctrl+Shift+P` | 显示面板，`ensureActiveConversation()`；没有会话就创建一个 |
| 下拉框选择会话 | `setActive()`；Terminal 活着就切标签，不活着就 `restoreTerminal()` |
| 点击（closed）会话 | `restoreTerminal()`：去掉 archived 后缀、持久化、launch |
| `Restore Terminal` 工具栏动作 | 已运行则提示；未运行则 launch，并提示正在恢复 |
| 草稿 Send 时 Terminal 已死 | 先 launch，提示「Send again after Pi is ready」，保留草稿、不发送 |
| 进程异常退出 | 关闭标签 + 重置 session + 气泡通知，带 `Restart Pi` 动作 |

`restoreTerminal()` 只恢复 Terminal 和会话绑定，不会自动批量恢复所有 closed 会话。

### 3.2 会话文件定位（launch 期兜底）

```text
sessionDir() = (PI_CODING_AGENT_DIR ?: ~/.pi/agent)/sessions

findSessionFile(piSessionId, cwd):
  快路径目录 = sessionDir() / ("--" + encode(cwd) + "--")
    encode(cwd) = cwd 去掉一个前导分隔符，再把 / \ : 全部换成 -
  1. 快路径：快路径目录/*_<piSessionId>.jsonl
  2. 兜底：在 sessionDir() 下最多递归 3 层，找 *_<piSessionId>.jsonl
  3. 都没有：null
```

- 支持 `PI_CODING_AGENT_DIR`（含 `~` / `~/` 展开）。
- **不注入 `--session-dir`**：落盘目录交给 pi 自身配置（`sessionDir` / `PI_CODING_AGENT_SESSION_DIR` / 默认 per-project 目录）。
- 快路径的目录编码与 pi 的 `getDefaultSessionDirPath` 保持一致：只剥掉一个前导分隔符，再把 `/`、`\`、`:` 换成 `-`。
- 若用户配置了自定义 `sessionDir` 而插件扫描不到，仍可由下一步的 `--session-id` 交给 pi 自己按 id 解析，不会重复建会话。

### 3.3 启动参数

`startPiWithDelay()` 构造的命令：

```text
[env 前缀] <piCommand>
  ├─ fork:          --fork <源 jsonl 完整路径 | 源 id> --session-id <新 id>
  ├─ 扫到文件:      --session <jsonl 完整路径>
  ├─ 扫不到:        --session-id <piSessionId>
  ├─ 总是:          --name <title>
  ├─ 可选:          --model <customModelId 或下拉模型>
  ├─ 可选:          --thinking <level>
  └─ 可选:          <sanitized extraArgs>
```

pi 语义（当前 CLI 文档）：

- `--session <path|id>`：按文件路径、精确 id 或部分 id 打开；`--session-id <id>`：打开该 project 的精确 id，不存在则创建。
- `--fork` 不能与 `--session` / `--continue` / `--resume` / `--no-session` 同时使用；`--session-id` 不能与 `--session` / `--continue` / `--resume` 同时使用，但可以和 `--fork` 组合（用来指定新会话 id）。
- 因此 fork 分支用 `--fork ... --session-id ...`，resume 分支用完整路径或 `--session-id`，三者互斥。

**Extra arguments 清洗**：

- 丢弃 `--continue`、`--resume`、`--session`、`--session-id`、`--fork`；其中 `--session` / `--session-id` / `--fork` 的带值形式会连同下一个 token 一起丢弃。
- 有丢弃时弹警告，列出被丢弃的 flag。
- 模型优先级：`customModelId`（非空） > `model` 下拉（非 `Default`） > 不传。
- Thinking level 非 `Default` 才传。
- 参数用单引号包裹；Windows 下把 `'` 转成 `''`，POSIX 下转成 `'\''`。

### 3.4 env 注入与 shell 判定

extension 只在 `PI_LAUNCHER_PORT` / `PI_LAUNCHER_TOKEN` / `PI_LAUNCHER_TAB_KEY` 三个 env 都存在时启用。

| 平台 / shell | 注入形式 | 说明 |
|---|---|---|
| Windows PowerShell | `$env:PI_LAUNCHER_PORT='..'; $env:...='..'; ` | 默认假设；推荐 `pwsh.exe` |
| Windows POSIX（Git Bash 等） | `env PI_LAUNCHER_PORT=.. PI_LAUNCHER_TOKEN='..' PI_LAUNCHER_TAB_KEY='..' ` | 覆盖 bash/zsh/fish |
| Windows cmd.exe | **不注入** | 无法解析 env 前缀；照常启动 pi，但弹警告，面板不会实时同步 |
| 非 Windows | `env VAR=..` 前缀 | 同上 |

Windows shell 探测：

1. `SystemInfo.isWindows` 为 false → `POSIX`；
2. 在**非 EDT** 线程通过反射读 `TerminalProjectOptionsProvider.getShellPath/getDefaultShellPath`，再尝试 `TerminalOptionsProvider`；`getShellPath()` 在 EDT 上会 runBlocking 抛异常，因此结果缓存；
3. 都拿不到时用设置里的 `shellPath`；
4. 仍为空 → 按 PowerShell 处理（JetBrains 默认）。

通过可执行文件名判定：`cmd(.exe)` → CMD；`powershell/pwsh` → POWERSHELL；`bash/zsh/fish/sh` → POSIX；其他未知 → POWERSHELL。

### 3.5 启动时序

1. 创建 Terminal 标签：反射调用 `TerminalToolWindowManager.createLocalShellWidget(workingDir, tabName)`，并在组件上打 `pi-terminal` client property；
2. 注册进程监控；
3. `PiBridgeInstaller.ensureInstalled()`；
4. `PiBridgeServer.ensureStarted()` 拿 endpoint（失败则命令不带 env，桥接降级）；
5. 300ms 定时器触发 `executeCommand(command)`（一次性写入整条命令行并回车）。`sendTextFn` 在创建标签时就已经设置，所以通常首次触发即发送；Timer 的 20 次上限只是防御空 fn；
6. 更新状态栏、通知监听者。

启动命令走 `executeCommand`；用户消息与 `/bridge-*` 指令走 `sendText`（bracketed paste + 80ms 后单独发 `\r`），因此多行草稿不会被拆成多条消息。

### 3.6 Terminal 存活判定与退出

`isAlive(conversationId)` 同时要求：

- `component.parent != null`；
- 对应的 `Content` 仍在 Terminal 的 `ContentManager` 中；
- `initialized == true`。

用户手动关闭 Terminal 标签时，`ContentManagerListener` 会移除 session 并更新状态栏。进程退出由每 2 秒（首次延迟 5 秒）检查 `TtyConnector.isConnected()` 发现：关闭标签、重置 session、通知并提供 `Restart Pi` 动作。

`session_stopped` 只清理 working 状态，**不**改变 Terminal 存活判定。

---

## 4. 通道协议

### 4.1 信封

```jsonc
// pi → IDEA
{
  "v": 1,
  "seq": 1690000000000001,
  "type": "session_changed",
  "tabKey": "<conversationId>",
  "token": "<每 IDE 实例随机 token>",
  "data": { "sessionId": "...", "extensionVersion": "6" }
}
```

规则：

| 规则 | 行为 |
|---|---|
| `v` | 必须等于 `1`；不匹配 → warn + 丢弃整条消息 |
| `token` | 必须与当前 project 的 token 完全一致；否则 warn + 丢弃 |
| `tabKey` / `type` | 必须是 JSON primitive 字符串；否则丢弃 |
| `seq` | 可选；`> 0` 时按 `(tabKey, type)` 分域记录 last seq，`seq <= last` 丢弃 |
| `data` | 非对象或缺失时按 `{}` 处理 |
| 未知 `type` | info 日志 + 丢弃，不断连 |
| JSON 解析失败 | warn + 丢弃该行 |
| 单行长度 | 超过 64K 字符（`String.length`）丢弃 |
| 处理线程 | 校验通过后 `invokeLater` 到 EDT 路由；project 已释放则丢弃 |

`seq` 按 `(tabKey, type)` 分域而不是按 tabKey 全局：每条消息是独立短连接，不同类型的到达顺序不保证；若共用一个 seq 时间线，早到的 `agent_state` 可能把后到的 `session_changed` 误判为旧消息。状态类消息本身幂等，按 type 做 last-write-wins 足够。

### 4.2 传输层

- IDEA：`ServerSocket` 绑定 `127.0.0.1:0`（随机端口），每个 project 一个 `PiBridgeServer`；token 为 16 字节随机数的 hex。
- accept 循环为每个连接起 daemon 线程；连接 `soTimeout = 10s`；逐行读取。
- pi 侧：**每条消息独立 connect**，不维护长连接。首次尝试 500ms 超时；1500ms 后再重试一次（1500ms 超时）；定时器 `unref()`，不阻塞 pi 退出。
- 重试会发送相同 `seq`，服务端去重后天然幂等；失败静默降级，绝不影响 pi 主流程。
- **没有 server → pi 的 socket 命令通道**。IDEA 侧要让 pi 执行动作时，走的是「往 TUI 输入 `/bridge-*` 命令」这条路（见 §7）。

### 4.3 安全边界

- loopback + 每实例 token，能拒绝非本插件启动的进程和误连；但同一用户下的本机进程仍可能读取 pi 的环境变量或探测端口，因此这不是强隔离。
- extension 位于 `~/.pi/agent/extensions/`，拥有 pi 进程同等权限；插件只写自己那一个文件，文件头声明由插件管理，不碰用户其他 extension。
- 协议无 TLS、无握手、无消息签名；适用范围是本机单用户。

### 4.4 消息类型

| type | 方向 | data | IDEA 侧处理 |
|---|---|---|---|
| `session_changed` | pi → IDEA | `sessionId`, `extensionVersion?` | 换绑/归档/采纳首次绑定，见 §6.1 |
| `session_stopped` | pi → IDEA | `{}` | 清 working 状态，刷新状态栏 |
| `agent_state` | pi → IDEA | `state: "working" \| "idle"`, `stopReason?` | 维护 working 集合；working→idle 时按设置发通知 |
| `model_changed` | pi → IDEA | `modelId` | 记录当前模型，面板状态行显示 |
| `file_modified` | pi → IDEA | `path` | 立即刷新 VFS，可选打开编辑器 |

---

## 5. pi 侧 extension

### 5.1 安装与版本

- 路径硬编码为 `~/.pi/agent/extensions/pi-launcher-bridge.ts`，**不跟随** `PI_CODING_AGENT_DIR`；设置页诊断也读这个固定路径。
- 源码：插件资源 `src/main/resources/bridge/pi-launcher-bridge.ts`；`@EXTENSION_VERSION@` 在 `PiBridgeInstaller` 加载时替换为 `VERSION = 7`。
- 每次启动 Terminal 前调用 `ensureInstalled()`：
  - 文件不存在 → 写入；
  - 文件存在但**完整内容**与资源不一致 → 覆盖；
  - 内容一致 → 跳过。
- 用内容比对而不是只看版本号：`VERSION` 只用于诊断展示；任何源码改动都必须让已安装文件被重写。
- 设置页诊断显示：未安装 / 已安装且最新 / 已过期（下次启动重写）/ 路径。
- 覆盖后需要新开 Pi Terminal 才生效（已运行的 pi 进程不会热加载）。

### 5.2 生命周期与事件

factory 在 env 不完整时**直接 return**，不注册任何 handler/command；非插件启动的 pi 完全零副作用。

| pi 事件 | extension 行为 | 上报 data |
|---|---|---|
| `session_start` | 刷新 `ctx.sessionManager.getSessionId()` 缓存；同时上报 `ctx.model`（`model_select` 只在模型变化时触发） | `session_changed { sessionId, extensionVersion }` + 可选 `model_changed { modelId }` |
| `session_shutdown` | 刷新缓存；有 id 才发 | `session_stopped {}` |
| `agent_start` | 清空上一轮 stopReason 缓存；发 working | `agent_state { state: "working" }` |
| `agent_end` | 从本轮最后一条 assistant message 缓存 `stopReason`（仅缓存，不上报） | — |
| `agent_settled` | 发 idle；`event.aborted` → `aborted`，否则缓存为 `error` 时 → `error` | `agent_state { state: "idle", stopReason? }` |
| `model_select` | 从 `event.model`（`Model` 对象）取 `provider` + `id`，格式 `provider/id` | `model_changed { modelId }` |
| `tool_result` | 仅 `edit` / `write` / `patch` / `apply`，从 `input`/`args`、`details`/`result`、`input.edits[]` 提取路径 | 每个路径一条 `file_modified { path }` |

说明：

- `tool_result` 而不是 `tool_call`：只有工具执行完，文件才真正写盘；在 `tool_result` 发刷新才不会与写入竞态。
- `agent_settled` 的 `AgentSettledEvent` 只有 `aborted: boolean`，没有错误字段；`error` 从 `agent_end` 本轮最后一条 assistant message 的 `stopReason` 取得，`agent_start` 会重置缓存，避免自动重试成功后被误报为 error。1.4 另有 `agent_before_settle.outcome`，但注册该 handler 会触发 pi 的 boundary 路径，当前不需要额外副作用。
- `model_select` 的 `event.model` 是 `Model` 对象（含 `provider`/`id`/`name`）；`session_start` 额外上报 `ctx.model`，覆盖「恢复会话但模型没变、因此没有 model_select」的情况。
- 只处理「修改类」工具；`read`/`grep`/`find` 等不触发文件刷新。
- 路径提取覆盖 `path`、`filePath`、`file_path`、`file`，以及 `edits` 数组中的字符串或 `{path}`。
- 没有实现 `usage`/成本上报，也没有 `tool_call` 危险命令确认。

### 5.3 命令

extension 在 env 启用时注册两个内部命令。IDEA 通过 `sendText` 把它们打进 TUI：

```text
/bridge-fork   <messageIndex> <base64(用户消息文本)>
/bridge-rewind <messageIndex> <base64(用户消息文本)>
```

参数解析：

- 严格两段：非负安全整数 index + 合法 base64；
- base64 解码后必须是合法 UTF-8，且重新编码后与原文一致（防止损坏/拼接参数被静默解码成另一条消息）；
- 不合法 → `ctx.ui.notify(..., "warn")`，不执行任何操作。

消息定位：

1. 从 `ctx.sessionManager.getBranch()` 取当前活跃分支上的 user 消息，按时间从旧到新；
2. 优先用「index 命中且文本完全相等」；
3. 若面板 index 漂移（例如用户在 TUI 直接输入过消息），从新到旧按文本精确匹配；
4. 都找不到 → warn，不执行。

`bridge-fork`：

- 找到目标 user entry 后，计算该轮 leaf：从该 entry 往后，直到下一个 user message 之前的最后一个 entry（包含工具调用/结果和最终回复；若没有回复则就是该 user entry 本身）；
- `ctx.fork(leaf.id, { position: "at" })`：保留到该 leaf 为止的完整历史，之后全部丢弃；
- 不向 TUI 编辑器写入任何文本。

`bridge-rewind`：

- 目标 entry 必须有 `parentId`；首条消息没有 parent，直接 warn；
- `ctx.navigateTree(entry.parentId)`：会话文件不变，活跃分支退到该消息**之前**，该消息及其后内容退出模型上下文；
- 不向 TUI 编辑器预填文本；文本只回到 IDEA 面板草稿。

---

## 6. IDEA 侧 server 与状态机

### 6.1 `session_changed` 状态机

```
收到 session_changed(tabKey, sessionId)
  ├─ tabKey 未知 → 日志 + 丢弃（不复活条目）
  ├─ conversation.piSessionId == sessionId
  │    ├─ 有 pending fork → 触发 fork 指令派发
  │    └─ 否则 return
  ├─ firstBind（boundTabs 中没有 tabKey）或旧 piSessionId 为空
  │    └─ 直接采纳 pi 的 id，persist，派发 pending fork
  ├─ forkingIds 中有 tabKey
  │    └─ 移除标记，采纳新 id（不归档；中间的全量副本只是 fork 传输层）
  └─ 其他
       └─ 归档旧 piSessionId → 更新 conversation.piSessionId → persist
```

- `boundTabs` 只存在内存里，IDE 重启后为空，所以重启后的第一次 `session_changed` 总是「首次绑定」，直接采纳 pi 的 id，不产生 spurious archive。
- 首次绑定采纳 pi 返回的 id，而不是坚持插件生成 id：pi 可能规范化 id，强行以插件 id 为准会造成每次启动都误归档。
- 归档条目：新 UUID、`title = uniqueArchiveTitle(旧 title)`、`tabName = 旧 title`、`piSessionId = 旧 id`、复制旧 `messages`、`draft = ""`；插入在 live 条目之前，保持列表顺序稳定。
- 归档只发生在插件索引层：Terminal 仍是同一个 tab（pi 已切到新 session），只是面板上多出一条 closed 历史；旧 jsonl 不删。

### 6.2 其他事件

- **`agent_state`**：
  - `working` → 加入 `workingIds`；`idle` → 移除。
  - 仅在「之前 working、现在 idle」时考虑通知；还需满足设置 `notifyOnAgentEnd` 且 `PiBridgeUi.isIdeActive(project)` 为 false（IDE 在后台）。
  - 通知正文根据 `stopReason` 拼 ` (stopped with an error)` / ` (aborted)`；`aborted` 来自 `agent_settled`，`error` 来自 `agent_end` 缓存的最后一条 assistant `stopReason`。
  - 通知带 `Open terminal` 动作与超链接，点击 `revealTerminal()`。
  - 状态栏 working 数随之更新。
- **`session_stopped`**：从 `workingIds` 移除，刷新面板和状态栏；不关闭 Terminal 条目。
- **`model_changed`**：按 tabKey 记到 `models` map；面板状态行显示当前会话模型。`session_start` 会先上报一次初始模型，因此不需要等用户切模型。孤儿 tabKey 也会记录，但只有存在会话时才展示。
- **`file_modified`**：见 §8；不要求 tabKey 对应会话存在。

### 6.3 孤儿消息

- `session_changed`：未知 tabKey → 日志丢弃，不复活条目。
- `agent_state`：未知 tabKey → 直接 return。
- `session_stopped`：会清理 working 集合并刷新 UI，不报错。
- `model_changed` / `file_modified`：不校验会话存在；前者只写 map，后者只刷新文件。

---

## 7. Fork / Rewind

### 7.1 语义对比

| 动作 | 会话 | 历史 | 输入 | 依赖 |
|---|---|---|---|---|
| **Fork**（面板按钮） | 新建 conversation + 新 pi session | pi 侧保留到该消息**及该轮完整回复**；之后丢弃；面板 `messages` 保留到该 user 消息 | 新会话草稿为空 | 桥接 endpoint；源会话至少有一个有效 session |
| **Rewind**（面板按钮） | 同一 conversation、同一 pi session | 活跃分支退到该消息**之前**；该消息及之后退出模型上下文；jsonl 中树节点仍保留 | 面板草稿预填该消息文本；TUI 编辑器不预填 | 桥接 endpoint + Terminal 存活 |
| pi 原生 `/fork` | 新 session | 默认 edit-before（可先编辑再 fork） | 由 TUI 决定 | 无插件依赖 |
| pi 原生 `/tree` | 同一 session | 选中的 user 消息会回到 TUI 编辑器 | TUI 预填 | 无插件依赖 |

面板 Fork/Rewind 是刻意与 pi 原生默认行为区分开的：Fork 不把选中消息放回编辑器，Rewind 不把文本塞进 TUI 编辑器。

### 7.2 Fork 实现时序

1. 面板点击 Fork：
   - 校验 message index；
   - `PiBridgeServer.endpoint() == null` → 警告「bridge channel unavailable」，拒绝；
   - 源会话无有效 pi session（`piSessionId` 为空，或等于 `id` 且扫描不到 jsonl）→ 警告，拒绝。
2. 创建新 conversation：
   - `id = UUID`；
   - `title = uniqueForkTitle(源 title)` → `<源>-fork`；
   - `tabName = 源 tabName`（Terminal 标签沿用源标签名）；
   - `piSessionId = 新 UUID`；
   - `messages = 源 messages[0..index]`；
   - `draft = ""`。
3. 写入 `forkingIds`（表示「本次换绑不要归档」），并把 `(index, base64(text))` 放进 `pendingForkCommands`。
4. 启动 Terminal：
   ```text
   --fork <源 jsonl 完整路径 | 源 id> --session-id <新 id> --name <新 title>
   ```
5. 首次 `session_changed` 绑定后派发 fork 指令：
   - 后台线程先等 3000ms，让 TUI 完成启动；
   - 检查 project 未释放、Terminal 仍存活、session id 未被改动；
   - **只发送一次** `/bridge-fork <index> <base64>`（`sendText`：bracketed paste + Enter）；
   - 不重试、不重复写 TUI 输入。
6. 结果确认：
   - 每 1500ms 只读检查一次绑定的 session id，最多 6 次（约 9 秒）；
   - id 变化 → 认为 fork 成功；服务端收到新 `session_changed` 时因 `forkingIds` 命中而直接采纳，不归档；
   - 超时 → 日志 warn，清除 in-flight 标记。
7. 失败后的实际状态：新 conversation 的 `messages` 已在面板裁剪，但 pi 侧仍停留在 `--fork` 的全量副本；不会自动回滚，也不会自动重试。见 §11。

为什么 one-shot：反复把 slash command 打进 TUI 会污染用户正在输入的草稿，而且 `--fork` 已经保证历史副本存在；即使 trim 失败，至少会话仍可用。

### 7.3 Rewind 实现时序

1. 校验 index、bridge endpoint、Terminal 存活。
2. **立即**在面板裁剪 `messages`（从该 index 到最后全部移除），把该消息文本写入 `draft`，persist。
3. `sendText("/bridge-rewind <index> <base64>")`。
4. extension 定位消息 → 要求 `parentId` → `ctx.navigateTree(parentId)`。
5. 不产生新的 session id，所以没有 `session_changed`；面板状态已经由步骤 2 更新。

Rewind 是乐观更新：命令发送前面板历史已裁剪，如果 extension 侧定位失败、命令被 TUI 忙碌状态打断或用户正在输入导致拼接，面板不会自动恢复。见 §11。

---

## 8. 文件同步

数据来源：extension 的 `tool_result`，只覆盖 `edit` / `write` / `patch` / `apply`。

`PiConversationService.onBridgeFileModified()`：

1. 读取设置 `openModifiedFiles`（默认 false）。
2. 在**后台线程**执行：
   - 相对路径按 `project.basePath` 解析；绝对路径直接用；
   - `VfsUtil.markDirtyAndRefresh(false, false, false, ioFile)`；
   - `LocalFileSystem.findFileByIoFile` 找不到时，刷新父目录后 `refreshAndFindFileByIoFile`；找到时 `file.refresh(false, false)`；
   - 刷新失败：找不到文件记 debug，异常记 warn。
3. `invokeLater` 到 EDT：
   - project 已释放或 VFS 失效则返回；
   - 如果该文件有缓存的 Document 且磁盘上没有未保存修改，`reloadFromDisk(doc)`；
   - `openModifiedFiles` 为 true 时 `FileEditorManager.openFile(vf, false)`（不抢焦点）。
4. 刷新始终发生；打开编辑器是 opt-in。

不要在 EDT 上做 VFS refresh 或 `openFile` 的写动作组合，否则可能卡 IDE；当前实现把刷新放后台、打开放 EDT，且不包在 `runWriteAction` 里。

---

## 9. UI、动作与通知

### 9.1 面板

- 右侧 ToolWindow `Pi Bridge`，标题栏工具条：New Conversation、Rename、Delete、Restore Terminal、Focus Terminal。
- 顶部下拉框：
  - working 会话显示 `●`；
  - 非 working 且 Terminal 不在 → 显示 `(closed)`；
  - closed 条目右侧显示 `×` 删除热区；点击删除不会选中/重启该会话，弹窗保持打开，方便批量删除。
  - 会话多到出现弹窗滚动条时（IntelliJ 的弹窗滚动条浮在列表上），渲染和命中测试都按滚动条宽度留出右侧内缩，避免 `×` 被遮住或点不到。
- 中部：用户消息卡片（时间 + 文本 + Rewind/Fork/Copy/Delete）。卡片只显示用户消息，不显示 AI 回复。
- 底部：草稿输入 + Copy input / Append open workspace files / Send。
  - 每次键入都写回 `draft`；
  - Enter / Ctrl+Enter 发送行为由设置决定；Shift+Enter 换行；
  - 输入框右键菜单：Cut/Copy/Paste、Insert current file、Insert selection、Insert open files、Send、Clear draft；
  - 接受 Project 视图拖入的文件，转成 `@path` 引用；
  - `Insert open files`/`OpenDisk` 写入 `<workspace-open-files>...</workspace-open-files>` 块；重复操作会**替换**旧块，而不是追加。
- 发送：`trim` 后为空不发；Terminal 已死则先 launch 并提示重发；否则 bracketed paste + 80ms 后 Enter，追加一条 `PiUserMessage`，清空草稿，切到 Terminal 标签。
- 字体：跟随 Editor 字体家族，只覆盖字号；设置变化或编辑器配色变化时刷新。

### 9.2 文件引用

- 编辑器选区：`@relative/path#L10-25`（单行 `#L10`）。
- Project 视图/编辑器标签：`@relative/path`；同时选中文件和父目录时只发文件。
- 相对路径基于 `project.basePath`；项目外文件保留绝对路径。
- `Send to Pi` 是**追加**到草稿，不覆盖用户已输入内容。

### 9.3 状态栏

- `π Idle` / `π Working` / `π N Working`；
- 点击：显示面板并 `ensureActiveConversation()`。
- 数据源是 `PiConversationService.workingCount()`，由 `agent_state` 推送维护。

### 9.4 通知

- agent 结束通知：仅 `working → idle`、设置开启、IDE 在后台时发；正文含标题与错误/中止提示（字段现状见 §11），点击聚焦 Terminal。
- 进程异常退出：警告气泡 + `Restart Pi` 动作。
- 启动失败、Extra args 冲突、cmd.exe、bridge 不可用等：警告气泡。
- 普通通知组：`Pi Bridge`（BALLOON，绑定工具窗）。

### 9.5 快捷键

| 快捷键 | 动作 | 生效条件 |
|---|---|---|
| `Ctrl+Shift+P` | Open Pi Bridge | 任意 |
| `Alt+Shift+3` | Send selection / file to Pi | 编辑器 / Project 视图 / 编辑器标签 |
| `Ctrl+Alt+Shift+T` | Focus Pi Terminal | 仅 prompt 输入框有焦点时启用，避免抢 Refactor This |
| `Ctrl+Alt+Shift+E` | Focus Pi Prompt | 仅 Pi Terminal 标签有焦点时启用 |

均可在 `Settings → Keymap → Pi Terminal Bridge` 重绑。

---

## 10. 设置与诊断

`PiSettings` 是 application 级 `PersistentStateComponent`，存储 `PiTerminalBridgeSettings.xml`。

| 设置 | 默认 | 作用 |
|---|---|---|
| `piCommand` | `pi` | 启动命令（可指向包装脚本） |
| `model` | `Default` | 下拉模型，格式 `provider/id`，来自 `~/.pi/agent/models.json` |
| `customModelId` | 空 | 非空时覆盖下拉模型 |
| `thinkingLevel` | `Default` | `none/low/medium/high/max` |
| `extraArgs` | 空 | 透传；冲突 session flag 会被丢弃 |
| `shellPath` | 空 | Windows shell 探测失败时的兜底 |
| `conversationFontSize` | 14（8–32） | 面板消息与输入框字号；字体家族跟随 Editor |
| `sendWithCtrlEnter` | 首次运行 `true`，State 默认 `false` | true = Ctrl+Enter 发送、Enter 换行；false = Enter 发送 |
| `notifyOnAgentEnd` | `false` | agent 结束气泡（仅 IDE 后台） |
| `openModifiedFiles` | `false` | Pi 写文件后额外打开编辑器；刷新始终执行 |

设置页诊断：

- extension 状态（未安装 / 最新 / 过期）、版本与路径；
- 第一个已打开 project 的 bridge server 端口（没启动则显示 `server not started`）；
- Windows 下附带 `pwsh.exe` 建议。

`PiModelLoader` 固定只读 `~/.pi/agent/models.json` 的 `providers.*.models[]`（不跟随 `PI_CODING_AGENT_DIR`），取 `id`/`name`，显示为 `provider/id`；文件缺失或解析失败时只保留 `Default`。

---

## 11. 已知缺口与风险

> 这一节记录「当前代码实际会怎样」，不是待办承诺。修复前不要按理想语义假设。

| # | 现状 / 风险 | 影响 | 建议 |
|---|---|---|---|
| 1 | **Fork 失败不回滚**：`/bridge-fork` 未确认时，新会话停在 `--fork` 全量副本，但面板 `messages` 已裁剪；若超时后命令才完成，下一次换绑还会把中间副本当普通旧会话归档 | 面板显示与 pi 实际历史不一致 | 失败时提示并提供「保留全量 / 重建会话」；或确认成功后再裁剪面板 |
| 2 | **Rewind 乐观更新**：面板先裁剪再发命令；命令失败/被 TUI 忙碌打断时不回滚 | 面板历史丢失，pi 分支可能未变 | 等 extension 确认后再裁剪，或失败时恢复快照 |
| 3 | **Fork/Rewind 指令通过 TUI 输入发送**：3 秒 settle 窗口内用户若正在输入，slash command 可能拼接进草稿 | 命令解析失败或污染用户输入 | 派发前检测 TUI 输入状态，或改为真正的 server→pi 命令通道 |
| 4 | **归档复活可能重名/残留后缀**：`restoreTerminal` 只 `removeSuffix(" (archived)")`，且不查重 | 复活后可能与 live 标题重名；`(archived) (2)` 不会被去掉 | 复活时走统一的唯一标题分配 |
| 5 | **`tabName` 不可变**：重命名只改面板标题 | Terminal 标签名、pi 会话名（`--name`）保持旧值，直到下次 launch | 重命名时同步 terminal 标签；需要时通过命令同步 pi name |
| 6 | **`session_stopped` 不清 closed 状态**：只清 working | 面板的「closed」由 Terminal 存活决定，不是由 pi 事件决定 | 明确 closed 的判定来源，避免两套状态 |
| 7 | **没有运行期对账/mtime 重绑** | 桥接长时间失联期间的会话切换只能等下次 launch 扫描 | 保持现状（terminal 随 IDE 关闭终止，风险有限）；确需时再加 |
| 8 | **Extra args 未过滤 `--no-session`** | 与插件注入的 session 参数可能冲突 | 加入冲突列表 |
| 9 | **只部分跟随 pi 的目录配置**：会话扫描支持 `PI_CODING_AGENT_DIR`，但不支持 `PI_CODING_AGENT_SESSION_DIR`；extension 安装与 `models.json` 又固定读 `~/.pi/agent` | 自定义 agent/session 目录时，extension 可能不被加载、模型列表为空、fork 源检查误判 | 统一抽一个 `agentDir()` / `sessionDir()`，安装、扫描、模型读取共用 |
| 10 | **cmd.exe 不支持实时桥接** | 面板不同步，fork/rewind 不可用 | 保持警告；文档明确 PowerShell 7 |
| 11 | **桥接安全是「本机单用户」级**：loopback + token，无 TLS/握手/签名 | 同用户本机进程可探测；不适合多用户主机 | 保持现状并在文档声明 |
| 12 | **面板只记用户消息** | 无法在面板查看 AI 回复/工具输出 | 定位就是 TUI 看结果；不要把它做成聊天镜像 |
| 13 | **extension 升级需新开 Terminal** | 已运行 pi 不会热加载 | 诊断页提示；必要时提示用户重开 |
| 14 | **测试只覆盖 extension 命令与事件** | server、状态机、启动参数、文件刷新无自动测试 | 补协议/状态机单测；CI 现有 extension 测试见 §12 |
| 15 | **旧数据空 `piSessionId`**：`loadState` 保留空值，注释称「下次启动重新生成」，但当前 launch 路径没有重建逻辑 | 极端遗留 XML 可能导致 `--session-id ''` | 加载时或 launch 前补一次 id |

---

## 12. 验证与测试

- `node --test tests/bridge-extension.test.mjs`：17 个用例，直接执行**打包资源**里的 extension 源码（`@EXTENSION_VERSION@` 替换为 7，`export default` 改写为 `install`），覆盖：
  - fork 保留完整工具调用轮次、排除后续消息；
  - rewind 导航到目标消息的 parent、不 fork；
  - fork/rewind 连续调用互不污染；
  - 未回复消息的 fork leaf；
  - UTF-8/多行/text-part 内容；
  - index 漂移时按文本回退；
  - 重复文本优先 index；
  - 非法/拼接参数不改会话；
  - 找不到消息只 warn；
  - 首条消息 rewind 明确失败；
  - API 失败可见、不误报成功；
  - `session_start` 同时上报 session 与初始 `Model`；
  - `model_select` 从 `event.model` 取 `provider/id`；
  - `agent_settled` 读取 `aborted`；
  - `error` 取自最后一条 assistant，成功重试会清掉旧 error；
  - 未启用 env 的 pi 不注册命令。
- GitHub Actions：`node --test` 通过后 `./gradlew buildPlugin`；tag 触发 release；release 触发 sign/verify/publish。
- 本地按仓库约定不主动跑 Gradle 构建；编译与打包交给 CI。

---

## 13. 决策记录

已实现并保持：

- ✅ 存储：project 级 `PersistentStateComponent`；pi jsonl 是会话内容权威，插件只存索引。
- ✅ 删除：只删插件条目与 Terminal，不动 pi jsonl。
- ✅ id 模型：conversation 稳定 `id`（tabKey）+ 可换绑 `piSessionId`；`/new` 只改后者。
- ✅ 换绑：旧会话归档为独立 closed 条目，可恢复；首次绑定直接采纳 pi 返回的 id。
- ✅ 通道：loopback TCP + 每实例 token；per-request connect；socket 为主、launch 期目录扫描兜底。
- ✅ 协议：`v` 主版本 + 未知 type 丢弃 + `(tabKey, type)` 分域 seq 去重。
- ✅ 启动：不注入 `--session-dir`；命中 jsonl 用 `--session <完整路径>`，否则 `--session-id`。
- ✅ rename 禁止重名；fork/archive 自动生成唯一标题。
- ✅ 恢复语义：不自动复活 Terminal，点击按需恢复。
- ✅ 通知默认关闭；文件刷新始终开启，打开文件 opt-in。
- ✅ Fork：保留到消息及完整回复；Rewind：同会话退到消息之前，面板预填文本。
- ✅ `model_select` 按 pi 1.4 的 `Model` 对象上报 `provider/id`；`session_start` 补报初始模型。
- ✅ `agent_settled` 用 `aborted` + 最后一条 assistant 的 `stopReason` 还原 error/abort 详情。
- ✅ IDEA → pi 的 Fork/Rewind 指令走 TUI slash command：Fork 一发送一确认，Rewind 只发送一次；都不反复写输入。

明确不做 / 推迟：

- ⏸ 运行期文件 watch 与 mtime 重绑（§11 #9）。
- ⏸ server → pi 的真双向命令通道（rename/compact/切模型）；当前只有 TUI slash command 这条路。
- ⏸ `usage`/成本/上下文用量推送；状态栏仍只显示 working 数。
- ⏸ `tool_call` 危险命令确认对话框。
- ⏸ 面板镜像 AI 回复全文。

---

## 14. 关键常量

| 常量 | 值 | 位置 |
|---|---|---|
| 协议版本 | `1` | `PiBridgeServer.PROTOCOL_VERSION` |
| extension 版本 | `7` | `PiBridgeInstaller.VERSION` |
| 单行上限 | 64K 字符 | `PiBridgeServer.MAX_LINE_LENGTH` |
| 客户端连接读超时 | 10s | `PiBridgeServer.handle()` |
| 首次发送超时 / 重试延迟 / 重试超时 | 500ms / 1500ms / 1500ms | `pi-launcher-bridge.ts` |
| 启动命令触发 | 300ms 后一次（Timer 上限 20 次，防御空 fn） | `PiTerminalService.startPiWithDelay()` |
| 启动后发送回车延迟 | 80ms | `PiTerminalService.sendText()` |
| Fork settle | 3000ms | `PiConversationService.FORK_STARTUP_SETTLE_MS` |
| Fork 结果检查 | 1500ms × 6 次 | `FORK_RESULT_CHECK_INTERVAL_MS` / `FORK_RESULT_CHECKS` |
| 进程监控 | 5s 后开始，每 2s 检查 | `PiTerminalService.monitorProcess()` |
| 归档后缀 | `" (archived)"` | `PiConversationService.ARCHIVED_SUFFIX` |
| 字体范围 | 8–32，默认 14 | `PiSettings` |
| workspace 块标签 | `<workspace-open-files>` / `</workspace-open-files>` | `PiConversationService` |
