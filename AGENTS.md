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

pi 侧 extension 源码是资源文件 `src/main/resources/bridge/pi-launcher-bridge.ts`，
由 `PiBridgeInstaller` 替换 `@EXTENSION_VERSION@` 后安装到
`~/.pi/agent/extensions/pi-launcher-bridge.ts`（用户信任目录，只写自己那一个文件，勿动其他文件）。

## 核心设计（详见 docs/tasks/）

- 会话 id 模型：conversation 有稳定 `id`（tabKey / 主键）+ `piSessionId`（当前绑定的 pi 会话，`/new` 等操作会换绑它；换绑时旧会话归档为 closed 条目，可复活）
- 启动注入：`--session <jsonl 完整路径>`（resume，扫描命中时）/ `--session-id <uuid>`（新会话或扫描未命中，pi 对同 id 是 resume-if-exists）+ `--name`；**不注入 `--session-dir`**，落盘目录交给 pi 自身配置（`PI_CODING_AGENT_DIR`/`sessionDir`/默认 `--<cwd>--`），避免插件复刻 pi 目录约定；env 前缀注入 `PI_LAUNCHER_PORT/TOKEN/TAB_KEY`（Windows PowerShell 与 POSIX `env` 两种语法，按 OS 分支；Windows 推荐 pwsh 7；cmd.exe 跳过注入并警告；Extra arguments 里的 `--session`/`--resume` 等冲突旗标会丢掉并警告）
- 通道协议：`{v, seq, type, tabKey, token, data}` 通用信封；主版本号 + 未知 type 丢弃 + seq 按 `(tabKey, type)` 分域去重；token 校验
- 兜底：resume 判定收敛在 launch 时刻（jsonl 必已落盘；先扫默认项目目录，再跨 `sessions/` 子目录按 `*_<id>.jsonl` 搜，扫不到退回 `--session-id` 让 pi 自己解析）；不做启动期清空 / mtime 重绑（terminal 进程随 IDE 关闭终止，理由见设计文档 §七）
- rename 禁止重名（含归档标题查重）；删除会话不删 pi 侧 jsonl

## 构建与验证

- **不要主动在本地构建**（`./gradlew build` / `compileKotlin` 等）：IDEA 插件构建成本高；日常改动提交到 `dev`，需要编译验证时按下一节手动触发 `dev` 的 CI 构建
- 本地最多做静态检查（读代码、对拍 pi 文档），编译问题交给 CI

## 分支、CI 与发布

- 日常开发在 `dev` 分支进行；`main` 保持可发布状态。
- `dev` 是用户的测试分支：**不改插件版本号、不走 publish**。`build.gradle.kts` 的版本与 tag 只在发版流程里动。
- 向 `dev` push **不会自动触发 GitHub Actions**（workflow 只监听 `main` push、`v*` tag push，以及指向 `main` 的 PR）。
- 需要验证 `dev` 能否编译时，用 `workflow_dispatch` 手动触发一次构建（只编译 + 跑 bridge 单测 + 上传 artifact，不出 Release、不发 Marketplace，因为 `build.yml` 里建 Release 的步骤只在 `refs/tags/v*` 下执行，`publish.yml` 只监听 `release: created`）：

  ```bash
  # 仓库已存 GitHub 凭据，可直接复用（scope: repo, workflow）
  export GH_TOKEN=$(grep '@github.com' ~/.git-credentials | sed -E 's#https://[^:]*:([^@]*)@github.com.*#\1#')

  gh workflow run build.yml --ref dev --repo JsonSong89/pi-jetbrains-terminal-bridge
  gh run list --repo JsonSong89/pi-jetbrains-terminal-bridge --limit 1
  gh run view --log-failed --repo JsonSong89/pi-jetbrains-terminal-bridge   # 失败时看编译错
  ```

  也可以用 GitHub 页面：Actions → Build and Release → Run workflow → 分支选 `dev`。注意 `gh auth status` 会因 token 缺 `read:org` 报错，但 `gh workflow run` 可用。
- 需要正式 CI/发布验证时：
  - 把 `dev` 合并到 `main` 并 push `main`，触发 Build and Release；
  - 或由用户明确授权后 push `v*` tag，触发构建、GitHub Release 与 Marketplace 发布。
- 未经用户明确要求，不自行：
  - 合并 `dev` → `main`
  - 创建/移动 tag
  - 修改 `build.gradle.kts` 的插件版本号
- 发版前由用户安排严格内测；内测期间插件版本与所有 tag 保持在 `0.3.5`。

## 文档同步约定

每个功能迭代（P0/P1/P2）合入时同步更新 `README.md` / `README.zh-CN.md`；设计变更先改 `docs/tasks/` 里的设计文档再写代码。
