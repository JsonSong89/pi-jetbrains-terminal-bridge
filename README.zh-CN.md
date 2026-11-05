# Pi Terminal Bridge（pi 终端桥）

[English](README.md) · [简体中文](README.zh-CN.md)

这不是一个「AI IDE」。它真正做的事只有一件：

**在你已经熟悉的 JetBrains IDE 里，把带充分上下文线索的提示词编排好，发给 [Pi](https://pi.dev)，立刻看到结果。**

其余（会话列表、状态徽标、拖放、通知）都只是锦上添花。

不少人仍然在意架构层次和代码整洁，希望提示词能精确到类 / 方法，让 AI 改得更准、把修改约束在该改的范围里。JetBrains 的代码索引、方法跳转、引用查找、查看实现，正是资深程序员早就习惯用来组织这种上下文的方式。这个插件补上最后一公里：把这些线索收进 prompt，发给 Pi，在真实终端里看它干活。

它不适合完全 vibe 开发；更适合希望对 AI coding 过程有更多掌控力和参与感的场景。

> **前置条件：** 需单独安装 Pi CLI。
> `npm i -g @earendil-works/pi-coding-agent` 或访问 [pi.dev](https://pi.dev)
>
> **Windows：** 建议将 IDE 终端 Shell 设为 **PowerShell 7**（`pwsh.exe`）（Settings → Tools → Terminal）。cmd.exe 无法用于实时桥接。

## 还能做什么

- **持久会话** —— Pi 会话跨 IDE 重启保留；面板中列出全部会话，点击即用 `pi --session <id>` 重启恢复。重启后终端不自动复活，由你决定何时恢复。
- **IDEA ↔ pi 实时桥接** —— 一条 loopback TCP 通道（每实例独立 token）让面板与终端实时同步：
  - 每个会话的 working ● / idle 徽标
  - 当前模型显示
  - `/new`、`/fork`、`/resume` 触发换绑 —— 旧会话归档为独立 closed 条目，仍可恢复
  - 面板中重命名会话（禁止重名）
- **一键启动** —— 工具栏按钮在 Terminal 工具窗中打开专属 Pi 标签页，按配置的模型 / thinking 级别 / 额外参数运行 `pi`。
- **发送到 Pi** —— 编辑器选中代码，右键 → *Send to Pi*，文件引用（`@path/file.go#L10-25`）直达 Pi 输入框。
- **每条消息可 Fork / Rewind** —— 用户消息卡片自带两个动作，语义刻意不同：**Fork** 复制出新会话，保留到该消息（含该轮完整回复）为止，其后历史全部丢弃、输入框留空；**Rewind** 不换会话，把活跃分支退到该消息**之前**（pi `/tree` 语义——该消息及之后的对话退出模型上下文，消息文本回到输入框待改）。
- **长历史可导航** —— 超过约 480 字的消息默认折叠为 4 行，带「展开」链接；滚动条右侧一条细轨道按消息索引**等分**打点（一条巨长的消息不会把其余消息挤成几像素），点一下落到该消息头部；`Alt+↑` / `Alt+↓` 逐条上/下跳。
- **滚动位置不再被打断** —— agent 状态变化不再把历史列表拽回底部；只有发送、或本来就在底部时才跟随末尾。
- **Pi 写完立刻同步** —— 一收到 write/edit 工具结果就刷新对应文件，不用等 IDEA 大约 10 秒的磁盘轮询。也可选再打开该文件。
- **通知（默认关闭，需在设置中开启）**
  - agent 结束时气泡通知（含出错 / 中止原因）——仅 IDE 在后台时弹出；点击可定位到对应终端
  - 打开 Pi 改过的文件（仍需开启；刷新本身始终发生）
- **诊断** —— 设置页展示桥接 extension 状态与服务端口。

## 桥接原理

```
IDEA（PiBridgeServer，loopback TCP + token）
   ▲                                   │
   │ env PI_LAUNCHER_PORT/TOKEN/TAB_KEY│  pi --session <path> | --session-id <uuid> --name <tab>
   │                                   ▼
Terminal 标签页 ── ~/.pi/agent/extensions/pi-launcher-bridge.ts（懒安装，按完整内容比对更新）
```

- 托管 extension（VERSION **9**）仍运行在同一个交互式 TUI 进程里；v2 认证长连接传输请求、结果和完整原生活跃分支快照，不使用 RPC mode 或另起 pi 进程。
- Rewind / Fork 的 API 基线锁定 **pi 1.5.0 / session v3**，还需通过内部命令 probe 并声明 capability；不支持的版本关闭控制操作。本轮 Fork 迁移仍待 CI、真实 TUI 和 IDE 集成验收。
- 修改请求先落盘、只发送一次。断联和超时保留面板原状，只查询原 requestId；重连不重放修改。未决操作确认结束且可对账时，顶部才显示 **Sync current session** 图标。
- working/model/写文件通知暂时保留 v1；Fork / Rewind 都经 v2 操作。v2 接管过的 tab 不会再被迟到的 v1 会话事件换绑。开发契约见[通信协议](docs/bridge-protocol.md)。

## 使用方式

### 快速上手

1. 点击主工具栏的 **pi** 按钮（或按 `Ctrl+Shift+P`）。
2. Terminal 工具窗中打开专属 Pi 标签页，自动运行 `pi`。
3. 编辑器选中代码，右键 → **发送到 Pi** —— 文件引用（`@path/file.go#L10-25`）追加到 Pi 输入栏。面板输入栏也有右键菜单（插入当前文件 / 选区 / 已打开文件），并接受从 Project 视图拖入的文件。
4. 关闭并重开 IDE —— 面板中列出全部会话，点击即重新拉起终端（pi TUI 重新启动）并用 `pi --session <id>` 恢复会话。

> IDE 启动时不会批量复活终端，而是按需恢复：点击会话（或 Send）时才拉起对应 terminal，pi TUI 重启并接回原会话。

### 与 TUI 配合使用

Pi 标签页是**真实终端里跑着完整的 pi TUI** —— 所有斜杠命令与命令行完全一致：

- `/model`、`/resume`、`/fork`、`/new`、`/compact` … 都可直接在终端里输入
- 随时手动输入：插件**追加**内容到 Pi 输入栏，从不覆盖 —— 你手打的半句话原地保留
- 典型组合：
  - 先 Send 几条文件引用，自己补完问题再回车
  - `/fork` 一个进行中的会话去探索分支，原会话仍留在面板
  - 忙完一段 `/new` —— 旧会话归档在面板里，之后还能恢复

面板里每条用户消息卡片还带一个 **Fork** 按钮（分支图标）：新建会话 `<原会话>-fork`，
保留**到该消息（含其回复）为止**的历史、其后全部丢弃，输入框留空，从这个点在新会话里
继续追问，原会话不受影响。插件先以 CLI 创建独立的全量副本 Terminal，等待 bridge 能力和
完整快照就绪，再按原生 entry 身份核对选中的完整 turn。随后仅提交一次 v2 分叉请求，
通过公开 API 在该轮最后一个 entry 分叉，包含工具调用、工具结果和最终回复。
新会话经耐久 handoff 确认完整分支后，面板才裁剪；中间副本不会归档成额外会话。

Fork 会拒绝忙碌会话、有歧义的旧卡片、带 label 的会话，以及目标 TUI 输入框已有内容的情况。
源 Terminal 和 IDEA 草稿保留。准备期间可点顶部 **Stop fork preparation** 图标停止并保留全量副本；请求提交后，
断联只查询原操作 ID，不重复拉起 Terminal 或再次 fork。失败或未知时保留副本供检查。

**Rewind** 按钮（撤销图标，位于 Fork 左侧）经双向 bridge 在同一 session 内导航。
面板保留原状，收到 pi 确认结果并核验完整分支快照后才更新；选中的用户消息及后续对话
退出活跃分支，树上的 entry 仍保留在 jsonl。控制命令不再粘贴到 Terminal 编辑器。

pi 忙碌、首条消息无 parent，或 parent 是另一条 user/custom message 时，会在执行前拒绝，
避免多退或改变 TUI 编辑器。等待期间编辑 IDEA 草稿不会被覆盖，回退文本会保存在
**Restore text** 入口，点击追加。输入栏左下角会用最新 v2 分支快照显示低调的
`用户/AI+tool` 次数。状态自动同步；可恢复未决操作时，顶部才显示 **Sync current session**
图标，它不会重做操作。**Restore text** 也改为按需出现的顶部图标，悬停可查看用途。
输入框左下角的 Workspace 使用无边框小图标。未知期间可以查看、复制和编辑草稿，
同一会话的冲突修改会被锁住。

卡片使用原生 session/entry id 绑定，只保留 Rewind、Fork、Copy；通过 Rewind 调整活跃历史。
旧隐藏记录继续兼容，避免以前隐藏的卡片重新出现。
有歧义的旧卡片保留且禁用回退。首次迁移保留原面板可见性，因此迁移前仅在 TUI 输入的历史
也可能继续在面板隐藏；初始化后的新 TUI 用户消息会正常显示。

验证入口：`node --test tests/bridge-extension.test.mjs`、Gradle `test`，以及显式运行的
`PI_BINARY=/path/to/pi python3 tests/bridge-tui-smoke.py`（真实 pi 1.5.0 PTY smoke，覆盖 rewind 与 fork 的运行时替换）。
CI 手动触发可勾选 `tui_smoke`。本次重构仍需执行这些验证，不能把源码交付视为兼容性已通过。
安装按资源全文比较更新，新 Terminal 或 pi `/reload` 后加载。

一句话：IDE 面板负责结构（持久列表、运行徽标、归档、点击恢复），TUI 负责 pi 的完整交互能力，两者随意组合。

## 配置

**Settings → Tools → Pi Terminal Bridge**

| 配置项 | 说明 |
|---|---|
| 模型 / thinking 级别 / 额外参数 | 启动时传给 `pi` CLI |
| Agent 结束通知 | 会话 agent 落定时气泡提醒（默认关；仅 IDE 在后台时弹出；点击可定位到对应终端） |
| 打开 Pi 修改的文件 | 写入后再打开该文件（默认关）。VFS 刷新始终会发生。 |
| 桥接诊断 | 只读：extension 状态 + 服务端口 |

## 快捷键

默认值 —— 均可在 **Settings → Keymap → "Pi Terminal Bridge"** 自行修改：

- `Ctrl+Shift+P` —— 打开 Pi 会话窗口
- `Alt+Shift+3` —— 发送选中内容 / 文件到 Pi
- `Ctrl+Alt+Shift+T` —— 焦点到 Pi 终端（仅在 prompt 输入框内生效，不会在编辑器里抢走 Refactor This）
- `Ctrl+Alt+Shift+E` —— 焦点到 Pi prompt（仅在 Pi 终端内生效）
- `Alt+↑` / `Alt+↓` —— 在历史区跳到上/下一条消息头部（仅历史区有焦点时生效）

## 构建

```bash
node --test tests/bridge-extension.test.mjs   # 打包资源和故障契约
./gradlew test         # JVM 协议/投影/提交契约
PI_BINARY=/path/to/pi python3 tests/bridge-tui-smoke.py  # 显式执行真实 pi 1.5.0 PTY smoke
./gradlew build        # 验证
./gradlew runIde       # 沙箱 IDE
./gradlew buildPlugin  # 可分发 zip
```

JDK 21 toolchain，IntelliJ Platform Gradle Plugin 2.x，目标 2026.1，`sinceBuild=243`。

## 设计说明

架构与决策记录见 [`docs/tasks/`](docs/tasks/)。要点：

- 会话模型：稳定 `id`（tabKey / 主键）+ 可换绑的 `piSessionId` —— `/new` 换 pi 会话，tab 身份不变。
- pi 的 jsonl 文件是数据权威，插件只存索引。
- 已知限制：Windows 上实时桥接按 PowerShell（`$env:…`）或 Git Bash（`env`）注入。**推荐使用 PowerShell 7（`pwsh.exe`）** 作为 IDE 终端 Shell（Settings → Tools → Terminal）。若终端是 cmd.exe，会跳过注入并给出警告。Extra arguments 里的 `--session` / `--resume` 会在启动时丢掉——会话由面板接管。

## 致谢

- 感谢 [Linux.do](https://linux.do/) 社区的支持与帮助。
- 感谢 [pi-agent-launcher](https://github.com/haokanjiang/pi-agent-launcher) —— 本项目从其 Commit `72ea264`（v0.1.4）fork 而来，迭代到 0.2.x 之后，发现我们想要的上下文管理方式和原项目底层哲学完全不同，所以在 0.3.x 离开了 fork 网络、独立演进。感谢原项目的工作。

## 许可证

[MIT](LICENSE)
