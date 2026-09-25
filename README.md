# MoonLocation · 安卓系统级虚拟定位（LSPosed 模块）

基于 **libxposed API 101** 的 Android 系统级虚拟定位模块。位置改写发生在
`system_server` 的 `LocationProviderManager#onReportLocation`（Android 12–16 所有
provider 上报的唯一汇聚点），因此对所有使用系统定位接口的应用全局生效，并抹掉
「模拟位置」标记。

> ⚠️ 仅用于**个人设备的学习、开发调试与安全测试**。禁止考勤作弊、网约车刷单、游戏外挂、
> 欺诈等用途。

## 目标设备（已实测）

| 项 | 值 |
|---|---|
| 设备 | realme GT7 Pro (RMX5010) |
| 系统 | Android 16 / API 36 / arm64-v8a |
| Root | KernelSU (SukiSU Ultra)，SELinux Permissive |
| 框架 | LSPosed v2.1.1 (7790)，支持 libxposed API 101 |

## 环境要求

- Root + Zygisk + **LSPosed**（支持 libxposed API 101 的版本，如 v2.1.x）
- 作用域（LSPosed 管理器中勾选）：
  - ✅ **系统框架**（`system` / `android`）—— 必须
  - ✅ `com.android.phone` —— 基站相关（M4）
  - ✅ `com.oplus.location`、`com.oplus.locationproxy`、`com.qualcomm.location` —— realme/OPLUS 定位链
  - ✅ 本模块自身（`com.moon.location`）—— 用于「模块已激活」自检
- 首次启用 / 升级后**完整重启一次**手机。

## 构建

本仓库不含 Gradle wrapper 的 `gradle-wrapper.jar`（二进制）。两种方式：

1. **Android Studio（推荐）**：直接 `Open` 项目目录，IDE 会补全 wrapper 并用本地 SDK 构建。
2. 命令行：本机装好 JDK 21 与 Android SDK (API 36) 后，在项目根执行
   `gradle wrapper` 生成 wrapper，再 `./gradlew :app:assembleDebug`。

产物：`app/build/outputs/apk/debug/app-debug.apk`。

## 当前进度（M1）

- [x] 工程骨架（Kotlin + Gradle KTS + libxposed 101）
- [x] `system_server` 核心 hook：`LocationProviderManager#onReportLocation` 改写坐标
- [x] `LocationManagerService#getLastLocation` 兜底改写
- [x] 去掉 mock 标记（`setIsFromMockProvider(false)` / `setMock(false)`）
- [x] 跨进程配置通道（LSPosed remote preferences）
- [x] 极简控制界面（输入经纬度 / 开始 / 停止）
- [ ] M2：测试源驱动（室内无 GPS 时的持续输出）+ 快照校验/自检
- [ ] M3：WebView + 高德地图选点
- [ ] M4：环境伪装（WiFi / 基站 / GNSS 屏蔽）
- [ ] M5：路线模拟 + 摇杆

详见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 已知待验证点（M1 → M2）

- `onReportLocation` 在个别 OEM 上可能有多重重载或经 `OplusLocationManagerService`
  分流；已做多类名/多重重载兜底，需真机日志确认命中。
- 若目标应用绕过系统定位（自绘 GNSS/第三方 SDK），需在 M4 补 App 级 hook。

## 免责声明

作者不对任何滥用负责。使用前请确认你拥有目标设备与账号的合法授权，并自行承担后果。
