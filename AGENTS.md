# Pi Terminal Bridge 开发约定

给在本仓库工作的 agent / 协作者。

## 项目身份

- pluginId：`com.piterminal.bridge`（marketplace 名 "Pi Terminal Bridge"）
- 仓库：https://github.com/JsonSong89/pi-jetbrains-terminal-bridge
- 版本从 0.3.1 起算；0.2.4 及更早属于原 pi-agent-launcher 项目，与本插件无继承关系

## 构建

- `./gradlew build` / `runIde`（沙箱）/ `buildPlugin`（打包）
- JDK 21 toolchain；IntelliJ Platform Gradle Plugin 2.x，目标 2026.1，`sinceBuild=243`
- 依赖 terminal 插件（反射调用 `TerminalToolWindowManager.createLocalShellWidget`，保持零废弃 API 姿势）

## 包结构约定

```
actions/        编辑器/工具栏动作
bridge/         IDEA ↔ pi 的运行期通道（TCP server + extension 安装器）
conversations/  会话模型、持久化 store、会话服务
services/       terminal 生命周期、状态栏 widget
settings/       设置存储与 UI
ui/             右侧 ToolWindow 面板
```

pi 侧 extension 源码以 Kotlin 常量内联在 `PiBridgeInstaller` 中，安装目标是
`~/.pi/agent/extensions/pi-launcher-bridge.ts`（用户信任目录，只写自己那一个文件，勿动其他文件）。

## 核心设计（详见 docs/tasks/）

- 会话 id 模型：conversation 有稳定 `id`（tabKey / 主键）+ `piSessionId`（当前绑定的 pi 会话，`/new` 等操作会换绑它；换绑时旧会话归档为 closed 条目，可复活）
- 启动注入：`--session-id`（新会话）/ `--session`（resume，launch 时按 session 文件存在性判定）+ `--name` + `--session-dir`；env 前缀注入 `PI_LAUNCHER_PORT/TOKEN/TAB_KEY`（Windows PowerShell 与 POSIX `env` 两种语法，按 OS 分支；Windows 已知限制：cmd 不支持）
- 通道协议：`{v, seq, type, tabKey, token, data}` 通用信封；主版本号 + 未知 type 丢弃 + seq 按 `(tabKey, type)` 分域去重；token 校验
- 兜底：resume 判定收敛在 launch 时刻（jsonl 必已落盘）；不做启动期清空 / mtime 重绑（terminal 进程随 IDE 关闭终止，理由见设计文档 §七）
- rename 禁止重名（含归档标题查重）；删除会话不删 pi 侧 jsonl

## 文档同步约定

每个功能迭代（P0/P1/P2）合入时同步更新 `README.md` / `README.zh-CN.md`；设计变更先改 `docs/tasks/` 里的设计文档再写代码。
