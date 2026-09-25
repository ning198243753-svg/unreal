# 真机部署与验证（realme GT7 Pro / Android 16）

## 1. 安装 APK

```bash
scripts/build.sh
# Windows 端 adb（streamed install 在本机 Windows adb 上会失败，用 push install）
adb install --no-streaming app-debug.apk
```

## 2. 在 LSPosed 中启用模块并勾选作用域

本机 LSPosed 管理器（`org.lsposed.manager`）未常驻安装。用 root 运行模块的
`action.sh` 可临时唤起管理器：

```bash
adb shell su -c 'sh /data/adb/modules/zygisk_lsposed/action.sh'
```

管理器中进入「位置模拟 → 启用模块」，并勾选以下作用域：

| 作用域 | 说明 |
|---|---|
| `system`（系统框架） | **必须**，系统级定位改写的关键 |
| `com.android.phone` | 基站相关（M4） |
| `com.oplus.location` | realme/OPLUS 定位服务 |
| `com.oplus.locationproxy` | realme/OPLUS 运营商定位 |
| `com.qualcomm.location` | 高通定位 |

> 可用只读方式核对（避免手改数据库）：
> ```bash
> adb shell su -c 'cp /data/adb/lspd/config/modules_config.db /sdcard/Download/_mc.db'
> adb pull /sdcard/Download/_mc.db
> # sqlite3 查询 modules_state / scope 表
> ```

## 3. 生效方式（重要）

- **`system_server` 作用域**：仅在**开机时**注入。
  - 首次启用、或模块代码更新后 → **需要重启手机**（或软重启框架）。
  - 仅仅“关掉应用再打开”**不够**。
- **应用进程作用域**：关掉目标应用再打开即可。

## 4. 验证

打开 App：
- 顶部状态栏出现 `✅ 模块已激活`、`泵=on`、`注入` 数字增长 → system_server hook 生效。
- 地图点选/搜索选择坐标 → 「开始模拟」。
- 用任意地图 App 或 `dumpsys location` 观察下发坐标。

只读调试：
```bash
adb logcat -d | grep -i MoonLocation
adb shell dumpsys location | head -60
```

## 5. 回滚

```bash
adb uninstall com.moon.location
# 或在 LSPosed 里关闭模块，然后重启
```
