# 工作约定（重要）

本项目由 AI 助手协作开发，遵循以下固定规则。

## 1. 对手机只做无害操作

- **默认只读**：`getprop` / `dumpsys` / `pm list` / `ls` / `cat` 等，不改变设备状态。
- **禁止擅自**安装 APK、写文件、改设置、改动或重启设备、卸载/启用模块。
- 需要任何**会改变手机状态**的操作时，必须先说明并获得明确同意。
- 临时文件（手机或本机）用完立即清理，不在 `/sdcard`、`/data/local/tmp` 留残留。
- 不触碰用户已有的模块与数据（例如 LSPosed 配置、其他 Xposed 模块）。

## 2. 每次改动自动同步 Git

- 每完成一处改动（代码 / 文档 / 脚本），运行：
  ```bash
  scripts/commit.sh "简短描述"
  ```
  该脚本会 `git add -A` → commit → `git push origin HEAD:main`。
- 仓库：https://github.com/ning198243753-svg/unreal
- 目标：本地 `HEAD` 与远端 `main` 始终保持一致。

## 3. 构建

见 [README](../README.md)：WSL 纯命令行工具链，`scripts/build.sh`，无需 Android Studio。

## 4. 凭据

- GitHub PAT 存于 `~/.config/mcp/github-token`（**不在仓库内**），git 通过 credential helper 读取。
- 仓库中不得出现任何 token / 密钥 / 签名文件。
