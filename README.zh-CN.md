# Pi Terminal Bridge（pi 终端桥）

[English](README.md) · [简体中文](README.zh-CN.md)

[JetBrains 插件] 为 JetBrains IDE 提供 [Pi coding agent](https://pi.dev) 的持久会话 —— 启动、跟踪与恢复会话，并在 IDE 与 pi CLI 终端之间维持一条实时通道。

> **前置条件：** 需单独安装 Pi CLI。
> `npm i -g @earendil-works/pi-coding-agent` 或访问 [pi.dev](https://pi.dev)
>
> **Windows：** 建议将 IDE 终端 Shell 设为 **PowerShell 7**（`pwsh.exe`）（Settings → Tools → Terminal）。cmd.exe 无法用于实时桥接。

## 功能

- **持久会话** —— Pi 会话跨 IDE 重启保留；面板中列出全部会话，点击即用 `pi --session <id>` 重启恢复。重启后终端不自动复活，由你决定何时恢复。
- **IDEA ↔ pi 实时桥接** —— 一条 loopback TCP 通道（每实例独立 token）让面板与终端实时同步：
  - 每个会话的 working ● / idle 徽标
  - 当前模型显示
  - `/new`、`/fork`、`/resume` 触发换绑 —— 旧会话归档为独立 closed 条目，仍可恢复
  - 面板中重命名会话（禁止重名）
- **一键启动** —— 工具栏按钮在 Terminal 工具窗中打开专属 Pi 标签页，按配置的模型 / thinking 级别 / 额外参数运行 `pi`。
- **发送到 Pi** —— 编辑器选中代码，右键 → *Send to Pi*，文件引用（`@path/file.go#L10-25`）直达 Pi 输入框。
- **通知（默认关闭，需在设置中开启）**
  - agent 结束时气泡通知（含出错 / 中止原因）——仅 IDE 在后台时弹出；点击可定位到对应终端
  - 自动刷新并在编辑器中打开 Pi 修改过的文件
- **诊断** —— 设置页展示桥接 extension 状态与服务端口。

## 桥接原理

```
IDEA（PiBridgeServer，loopback TCP + token）
   ▲                                   │
   │ env PI_LAUNCHER_PORT/TOKEN/TAB_KEY│  pi --session <path> | --session-id <uuid> --name <tab>
   │                                   ▼
Terminal 标签页 ── ~/.pi/agent/extensions/pi-launcher-bridge.ts（懒安装，内容 hash 版本化）
```

- extension 在首次终端启动时安装，上报 `session_changed`、`agent_state`、`model_changed`、`file_modified`（取自 `tool_result`，文件实际写完之后）。
- 插件在启动时注入 session id；运行期 `/new` 类换绑通过 `session_changed` 事件归档旧会话并绑定新会话。
- 协议：`{v, seq, type, tabKey, token, data}` —— 每行一个 JSON 对象，按 `(tabKey, type)` 去重，未知 type 丢弃，版本不匹配显式告警。
- 全链路尽力而为：桥接失效时会话功能不受影响，退化为手动恢复。

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

一句话：IDE 面板负责结构（持久列表、运行徽标、归档、点击恢复），TUI 负责 pi 的完整交互能力，两者随意组合。

## 配置

**Settings → Tools → Pi Terminal Bridge**

| 配置项 | 说明 |
|---|---|
| 模型 / thinking 级别 / 额外参数 | 启动时传给 `pi` CLI |
| Agent 结束通知 | 会话 agent 落定时气泡提醒（默认关；仅 IDE 在后台时弹出；点击可定位到对应终端） |
| 打开 Pi 修改的文件 | 写入后刷新并在编辑器打开（默认关） |
| 桥接诊断 | 只读：extension 状态 + 服务端口 |

## 快捷键

默认值 —— 均可在 **Settings → Keymap → "Pi Terminal Bridge"** 自行修改：

- `Ctrl+Shift+P` —— 打开 Pi 会话窗口
- `Alt+Shift+3` —— 发送选中内容 / 文件到 Pi

## 构建

```bash
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

本项目部分灵感来源于 [pi-agent-launcher](https://github.com/haokanjiang/pi-agent-launcher)，表示感谢！
