# 虚拟定位模块 · 架构设计（v1）

> 目标平台：**Android 16（API 36）** · 仅 **系统级 Hook** 模式
> 定位：个人开发 / 学习 / 调试工具，仅用于自有设备。禁止考勤作弊、刷单、外挂、欺诈等用途。
> 参考项目：AnyDoor、GlobalTraveling(Shadow)、XposedFakeLocation、HideMockLocation、LocusMimic

---

## 0. 已敲定的决策

| 项 | 决策 |
|---|---|
| 系统版本 | Android 16（API 36），`compileSdk/targetSdk = 36`，`minSdk = 34`（留余量，可改） |
| 模式 | 系统级 Hook（system_server），**不做**免 root Mock Provider |
| 反检测 | 参考成熟项目：隐藏 mock 标记 + WiFi/基站/GNSS/蓝牙环境伪装（可选标识符伪造） |
| 地图 | WebView + 高德瓦片（免 Key）+ 高德 REST 搜索（需免费 Web 服务 Key） |
| UI | Android View + WebView（不使用 Compose，降低复杂度） |
| 配置通道 | 双通道：控制面文件/SharedPreferences + 状态回读伪 Provider；数据面在系统侧 |
| 技术栈 | Kotlin + Gradle(KTS) + libxposed API 101（首选，备选 legacy） |

---

## 1. 总体架构

```
┌─────────────────────────── App 进程 ───────────────────────────┐
│ UI 层      MainActivity / WebView(高德) / JsBridge / 悬浮摇杆    │
│ 服务层     SpoofService(前台服务) / SimulationController         │
│ 配置层     Config / ConfigSnapshot / ConfigWriter / RootShell   │
│ 引擎层(共享) GeoMath / RouteSimulator / Jitter / CoordTransform  │
└───────────────┬───────────────────────────────▲────────────────┘
     控制面(低频)  │ 写快照(prefs + root 原子文件)   │ 状态回读(伪 Provider)
                ▼                               │
┌─────────────────────── system_server 进程 ──────────────────────┐
│ Xposed 入口  ModuleEntry → HookEntry                            │
│ 状态层       SpoofState(读快照, revision+lease) / EngineRuntime  │
│ Hook 层      SystemHooks   → LocationProviderManager.onReportLocation ★核心
│             AcceptHooks   → *Registration.acceptLocationChange │
│             LastLocationHook → LocationManagerService.getLastLocation
│             MockOpGrant   → AppOpsService(mock_location)        │
│             WifiHooks / ConnectivityHooks / CellHooks / GnssHooks│
│             BluetoothHooks(com.android.bluetooth)               │
│             IdentityHooks(可选)                                 │
│ 反检测层     MockFlagHider(跨进程: Location/AppOps/Settings)     │
└─────────────────────────────────────────────────────────────────┘
```

**核心思想**
1. **一个稳定咽喉点**：Android 12+ 所有 provider 上报都经过
   `LocationProviderManager#onReportLocation`。在此统一改写坐标并抹掉 mock 标记，
   覆盖所有使用系统定位接口的 App（含 GMS Fused）。
2. **高频数据面留在系统侧**：位置插值 / 路线推进 / 摇杆位移在 system_server 内计算，
   避免高频 IPC；App 只下发紧凑控制参数（起点、路线折线、速度、时间戳、速度向量）。
3. **控制面低频**：用户操作（开始/停止/选点/切路线）才写快照，带 `revision` + `lease`。

---

## 2. 技术栈（推荐并敲定）

| 分类 | 选型 | 说明 |
|---|---|---|
| 语言 | **Kotlin 2.x** | 现代、简洁，工程生态一致 |
| 构建 | **Gradle (Kotlin DSL) + AGP 8.7+** | `compileSdk=36 targetSdk=36 minSdk=34`，Java 21 |
| Hook 框架 | **libxposed API 101**（`io.github.libxposed:api:101`，compileOnly） | 当前官方方向；XposedFakeLocation 已在 Android 16 验证。**要求 LSPosed 支持 API 101**（较新 LSPosed / JingMatrix Vector）。若设备管理器不支持，退回 legacy `de.robv.android.xposed:api:82`（用 `HookAdapter` 隔离，切换成本低） |
| UI | Android View + **WebView** | 地图用高德 Web 瓦片/JS；`androidx.appcompat` + Material |
| 地图 | **高德**（Web 服务 REST 搜索 + 瓦片） | 需申请「Web 服务」Key；支持 WGS84/GCJ02/BD09 纠偏 |
| 网络 | **OkHttp** | 调高德 REST |
| 序列化 | **org.json** | Android 内置，零额外依赖，配置结构简单 |
| 配置存储 | **SharedPreferences (+ XSharedPreferences) + 原子文件** | 见 §4 |
| 根能力 | `su` 命令封装（RootShell） | 权限授予、原子写镜像文件、重启目标 App |

> 不用 Compose / DI 框架 / 数据库：目标是**低复杂度 + 高性能**，保持依赖最小。

---

## 3. Hook 点清单（Android 12–16，system_server）

| # | 目标 | 方法/类 | 作用 |
|---|---|---|---|
| 1 ★ | 全局定位改写 | `com.android.server.location.provider.LocationProviderManager#onReportLocation` | 所有 provider 上报的唯一咽喉点：改写坐标、去 mock 标记、覆盖真实定位回灌 |
| 2 | 分流/豁免 | `LocationProviderManager$*Registration#acceptLocationChange` | 按调用方（包名/UID）分流：目标 App 返回伪造、豁免 App 放行真实 |
| 3 | 最后位置 | `LocationManagerService#getLastLocation`（含 `com.android.server.location.LocationManagerService`、OEM `HwLocationManagerService`） | `getLastKnownLocation` 返回伪造值 |
| 4 | 测试源授权 | `AppOpsService#checkOperationImpl/checkOperation` 对 `OP_MOCK_LOCATION` 放行 | 部分 ROM（ColorOS/OxygenOS）需内部放行，保证内部测试源可用 |
| 5 | GNSS 原始数据屏蔽 | `LocationManagerService#addGnssMeasurementsListener / addGnssNavigationMessageListener / addNmeaListener / addGnssBatchingCallback / …` | 拒绝/置空，防止用卫星原始数据反推 |
| 6 | WiFi 扫描屏蔽 | `com.android.server.wifi.WifiServiceImpl#getScanResults`（→空）、`getConnectionInfo`（→BSSID 伪造/SSID 置空） | 防网络定位反查真实环境 |
| 7 | 已连 WiFi BSSID | `ConnectivityService` 经 `NetworkCapabilities` 下发的 BSSID（Android 12+） | 与 6 配套 |
| 8 | 基站屏蔽 | `com.android.server.TelephonyRegistry#listen/listenWithEventList`、`checkFineLocationAccess/checkCoarseLocationAccess` | 清掉 CellInfo / 小区标识 |
| 9 | 蓝牙屏蔽 | `com.android.bluetooth.*`（作用域 `com.android.bluetooth`） | 屏蔽蓝牙扫描环境 |
| 10 | 标识符（可选） | `ContentProvider$Transport#call`（Android ID）、`DeviceIdentifiersPolicyService`（序列号） | 隐私加固，默认关 |

**反检测（跨进程，按作用域加载）**
- `android.location.Location`：`isFromMockProvider / isMock / setIsFromMockProvider / setMock / getExtras / setExtras / set`
- `android.app.AppOpsManager`：`checkOp / checkOpNoThrow / unsafeCheckOp / unsafeCheckOpNoThrow`（`android:mock_location`）
- `android.provider.Settings`：`Secure.getStringForUser`
- `com.android.providers.settings.SettingsProvider#call`、`com.android.server.appop.AppOpsService`
> Root 环境隐藏（Shamiko / HMA）由用户自行配置，非模块职责，文档中说明。

---

## 4. 配置通道设计（高性能 · 低复杂度）

| 通道 | 方向 | 机制 | 频率 | 说明 |
|---|---|---|---|---|
| **控制面** | App → system_server | ① `SharedPreferences`（系统侧用 `XSharedPreferences` 读）② **root 原子镜像文件**：`base64 → tmp → chmod 600 → chcon system_data_file:s0 → mv` 原子替换 | 低频（用户操作） | 双读 + `revision` 取新；带 **15s lease 心跳**，防止陈旧写入生效 |
| **状态回读** | system_server → App | 伪 Location Provider：App 调 `getLastKnownLocation("xxx.state")`，`extras` 携带状态 JSON（协议号/命中计数/错误） | 低频 | 零额外权限，用于环境自检页 |
| **数据面** | 系统侧内部 | 位置插值/路线/摇杆在 system_server 内按时间推进，**不过 IPC** | 高频（定位刷新） | 保证响应快、无 `su` 抖动 |

**快照字段（示例）**：`_schema, _revision, _wall, _elapsed, started, lat, lng, alt, acc, speed, bearing, mode, routeJson, routeSpeed, routeLoop, jitter, privacy{wifi,cell,gnss,bt}, exemptPkgs[]`
**校验**：`_schema` 必须匹配、`revision` 单调、坐标有限且在范围内、`lease` 未过期。

---

## 5. 模块与包结构

```
app/src/main/java/<pkg>/
├── ui/            MainActivity, MapWebView, JsBridge, JoystickOverlay, StatusFragment
├── service/       SpoofService(前台), SimulationController, Notifications
├── engine/        (App/系统共享) GeoMath, CoordinateTransform(WGS84/GCJ02/BD09),
│                  RouteSimulator, Jitter, SensorMock
├── config/        Config, Keys, ConfigSnapshot, ConfigWriter, RootShell, MirrorCommand
└── xposed/        ModuleEntry(入口), HookEntry,
                   SystemHooks, LastLocationHook, AcceptHooks, MockOpGrant,
                   WifiHooks, ConnectivityHooks, CellHooks, GnssHooks,
                   BluetoothHooks, IdentityHooks,
                   MockFlagHider, SpoofState, EngineRuntime, HookUtil, HookAdapter
app/src/main/resources/META-INF/xposed/
├── module.prop        (minApiVersion=101, targetApiVersion=101, staticScope=false)
└── java_init.list     (<pkg>.xposed.ModuleEntry)
```

`hook` 类与 `engine`/`config` 类编译进同一 APK，由 LSPosed 在 system_server 内加载；
`engine`/`config` 同时被 App 复用，避免逻辑重复。

---

## 6. 构建配置要点

- `app/build.gradle.kts`：`compileSdk=36, targetSdk=36, minSdk=34`，Java 21，Kotlin 2.x，AGP 8.7+。
- 依赖：`compileOnly(io.github.libxposed:api:101)` + `implementation(io.github.libxposed:service:101)`；OkHttp；AppCompat/Material。
- `packaging.resources.merges += "META-INF/xposed/*"`。
- `AndroidManifest`：`INTERNET`、定位权限、`QUERY_ALL_PACKAGES`（列目标 App）；外部控制 `BroadcastReceiver` 默认 `enabled=false`，用户显式开启。
- Release 开 `minifyEnabled`，Xposed 入口类加 keep 规则。

---

## 7. MVP 路线图

| 阶段 | 交付 | 验收 |
|---|---|---|
| M1 | 工程骨架 + libxposed 入口 + `onReportLocation` 改写固定坐标 + 去 mock 标记 | ✅ 已完成（编译通过） |
| M2 | 系统侧“直推泵”：1s 节奏向所有 registration 注入伪造固定位固定坐标 + 去 mock 标记 | ✅ 已完成（编译通过） |
| M2.5 | 状态自检/回读：探针 Provider 把 system_server 侧状态 JSON 回传 App（仅本模块 UID 可读） | ✅ 已完成（编译通过） |
| M3 | WebView + Leaflet + 高德瓦片选点 + Web服务 REST 搜索 | ✅ 已完成（编译通过） |
| M4 | 环境伪装：WiFi/基站/GNSS 屏蔽（+ 蓝牙，可选） | 待做 |
| M5 | 路线模拟 + 摇杆（系统侧插值）+ 传感器/计步（可选） | 待做 |
| M6 | 反检测补全（AppOps/Settings/标识符，可选）+ 兼容性打磨 | 待做 |

### M2 实现要点（系统侧直推）

被动改写只能处理“真实 provider 上报”的时刻；室内/屏蔽网络定位后可能无上报。
直推泵（`Pump.kt`）在 system_server 内主动投递：

```
LocationProviderManager (= ListenerMultiplexer)
  .deliverToListeners(Function<Registration, ?>)
       → registration.acceptLocationChange(LocationResult.wrap([fake]))
```

- `LocationResult` 为 @SystemApi（公开 SDK 无），用 `LocationResult.wrap(Location[])` 反射构造。
- `deliverToListeners` 在 `ListenerMultiplexer` 父类上，遍历父类按名查找。
- 只注入“伪造坐标”，真实 provider 不受影响；真实定位只被被动改写。
- 频率 1Hz，位置变化或 2s 心跳时重投；用 `HandlerThread` 定时。

### M2.5 状态回读（探针）

App 调 `getLastKnownLocation("moon.location.probe")` → 模块在 `getLastLocation` hook 中
识别到该 provider，直接返回一个携带 JSON 的 `Location.extras`（不查真实 provider）：

```json
{ "sdk":36, "configReadable":true,
  "snapshot":{"revision":123,"started":true,"lat":..,"lng":..},
  "pumpReady":true, "pumpDelivery":"deliverToListeners", "pumpManagers":4,
  "injected":42, "error":null }
```

- 仅当调用 UID = 本模块 UID 时返回，防止其他应用探测模块运行状态。
- 用途：真机验证时 App 首页即可看到系统侧真实状态，无需翻日志。

---

## 8. 风险与注意

- **libxposed API 101 依赖较新 LSPosed**：需确认设备管理器支持；否则切 legacy API（HookAdapter 隔离）。
- **OEM 差异**：`LocationManagerService` 包名/子类在不同 ROM 可能不同，需 `findClassIfExists` 多路径兜底（华为 `HwLocationManagerService` 等）。
- **SELinux**：系统侧读 App 私有 prefs 可能被拒，故保留 root 原子镜像文件（`system_data_file` 上下文）兜底。
- **Android 15/16 行为变化**：WiFi 列表改为 `ParceledListSlice`、`WifiSsid` API 变更、已连 BSSID 走 `NetworkCapabilities`，需按版本分支。
- **合规**：仅限自有设备学习/调试，文档与 UI 内均写明用途边界。

---

## 9. 实测设备情报（realme GT7 Pro / Android 16）

> 通过 adb **只读**探测获得，未对手机做任何写入/安装/改设置。

| 项 | 值 |
|---|---|
| 设备 | realme RMX5010（GT7 Pro） |
| 系统 | Android **16** / API **36** / 安全补丁 2026-06-01 / `user` `release-keys` |
| 架构 | **arm64-v8a**（无 32 位 abilist 主用） |
| Root | **KernelSU（SukiSU Ultra）**，`ksud 4.2.0`，`su` 上下文 `u:r:ksu:s0` |
| SELinux | **Permissive** |
| Xposed | LSPosed **v2.1.1 (7790)**，以 KernelSU 模块 `zygisk_lsposed` 运行，`lspd` 在跑 |
| libxposed API | **101**（`framework.dex` 含 `io.github.libxposed.api.*`，LSPosed 配置库 user_version=101）→ **建议直接用 libxposed 101** |
| 冲突模块 | 曾装 `com.yinshibai.location`（已卸载，仅 LSPosed 残留记录），其作用域名单可直接参考 |

**关键系统包 / 类名（已在 `services.jar` 中核对）**

- AOSP：`com.android.server.location.LocationManagerService`、`com.android.server.location.provider.LocationProviderManager`（含 `onReportLocation / onReportLocations / getLastLocation / getLastLocationUnsafe / getCurrentLocation / addTestProvider / setTestProviderLocation`、`acceptLocationChange`）
- OPLUS 扩展：`com.android.server.location.OplusLocationManagerService`、`com.android.server.location.ILocationManagerServiceExt`、`IOplusLBSMainClass`
- 厂商定位包（需覆盖）：`com.oplus.location`（system_ext，v16.51）、`com.oplus.locationproxy`（product，v12.0.20）、`com.qualcomm.location`（system_ext priv-app）
- 已有第三方定位类应用：高德 `com.autonavi.minimap`、GMS `com.google.android.gms`

**由此确定的最终决策（覆盖 §9 原问题）**

1. minSdk = **34**，compile/target = **36**
2. Hook 框架 = **libxposed API 101**（设备已支持，无需 legacy）
3. 反检测 v1 = WiFi / 基站 / GNSS；蓝牙 + 标识符放后续
4. 路线/摇杆放 M5
5. 包名 = **`com.moon.location`**，工程名 **MoonLocation**

**默认作用域（LSPosed 勾选）**：`系统框架(system)`（必须）、`android`、`com.android.phone`、`com.oplus.location`、`com.oplus.locationproxy`、`com.qualcomm.location`。

> 注意：realme 上网络定位链路经过 OPLUS 服务，但最终仍通过 AOSP `LocationProviderManager.onReportLocation` 汇聚，核心 hook 点不变；若个别 OPLUS 应用绕过，再补 App 级 hook。

