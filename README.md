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

## 构建（WSL / 无 Android Studio）

本项目**不需要 Android Studio**。WSL 里用纯命令行工具链即可构建，全部装在
`~/android-toolchain`，不需要 sudo、不污染系统。

```bash
# 1) 一次性安装 JDK21 + Android SDK(platform-36/build-tools-36) + Gradle 8.9
scripts/setup-toolchain.sh

# 2) 构建 debug APK
scripts/build.sh                    # 产物 app/build/outputs/apk/debug/app-debug.apk
scripts/build.sh :app:assembleRelease
scripts/build.sh clean

# 3) 安装到已连接设备（可选）
scripts/install.sh
```

当前 shell 手动激活环境：`source scripts/env.sh`

> 也可用 Android Studio 直接 Open 本目录；SDK 路径写在 `local.properties`（已 gitignore）。

## 提交

```bash
scripts/commit.sh "M2: 描述"
```

## 当前进度（M1）

- [x] 工程骨架（Kotlin + Gradle KTS + libxposed 101）
- [x] `system_server` 核心 hook：`LocationProviderManager#onReportLocation` 改写坐标
- [x] `LocationManagerService#getLastLocation` 兜底改写
- [x] 去掉 mock 标记（`setIsFromMockProvider(false)` / `setMock(false)`）
- [x] 跨进程配置通道（LSPosed remote preferences）
- [x] 极简控制界面（输入经纬度 / 开始 / 停止）
- [x] **M2**：系统侧“直推泵”——室内无 GPS 时按 1s 节奏向所有 location 注册持续注入伪造坐标
- [ ] M2.5：快照自检 / 下发计数回读
- [ ] M3：WebView + 高德地图选点
- [ ] M4：环境伪装（WiFi / 基站 / GNSS 屏蔽）
- [ ] M5：路线模拟 + 摇杆

详见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 已知待验证点（M1 → M2）

- `onReportLocation` 在个别 OEM 上可能有多重重载或经 `OplusLocationManagerService`
  分流；已做多类名/多重重载兜底，需真机日志确认命中。
- 系统侧直推泵依赖 `LocationProviderManager#deliverToListeners(Function)` 与
  `LocationResult.wrap(Location[])`（@SystemApi，反射调用）；若某 ROM 改名会降级为仅
  被动改写。就绪状态会打印到 LSPosed 日志（`pump ready=...`）。
- 若目标应用绕过系统定位（自绘 GNSS/第三方 SDK），需在 M4 补 App 级 hook。

## 免责声明

作者不对任何滥用负责。使用前请确认你拥有目标设备与账号的合法授权，并自行承担后果。
