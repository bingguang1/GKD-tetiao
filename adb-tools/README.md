# 一键 ADB 配置工具

把安装后需要手动点的授权一次性配好，让 App 开箱可用。**只需要一根数据线。**

## 快速开始

1. 手机开启开发者选项与 **USB 调试**
   （设置 → 关于手机 → 连点「版本号」7 次 → 返回 → 系统 → 开发者选项 → USB 调试）
2. 把这两个文件与 APK 放进**同一个目录**：

   ```
   gkd-tejiao-v1.12.2-fok0014.apk
   adb-setup.bat
   adb-setup.ps1
   ```
3. 数据线连电脑，手机弹出「允许 USB 调试吗？」→ 勾选「始终允许」→ 允许
4. **双击 `adb-setup.bat`**

脚本会自动找到同目录的 APK 安装，然后逐项配置，最后打印一份成功/警告/失败汇总。

## 它做了什么

| 步骤 | 内容 |
|---|---|
| 1 | 查找 adb（脚本目录 → 上一级 → PATH → 常见 SDK 路径）；找不到就从 Google 官方地址下载 platform-tools |
| 2 | 等待设备连接；未授权/offline 会给出具体提示 |
| 3 | 安装同目录的 APK（`-r -d` 覆盖安装）；签名冲突时给出卸载重装的指引 |
| 4 | `pm grant`：`WRITE_SECURE_SETTINGS`、`GET_APP_OPS_STATS`、`POST_NOTIFICATIONS` |
| 5 | `appops set ... allow`：`POST_NOTIFICATION`、`SYSTEM_ALERT_WINDOW`、`ACCESS_ACCESSIBILITY`、`ACCESS_RESTRICTED_SETTINGS`、`FOREGROUND_SERVICE_SPECIAL_USE`、`CREATE_ACCESSIBILITY_OVERLAY`（按系统版本自动取舍） |
| 6 | **自动开启无障碍服务**：读取现有列表→追加本应用→写回，**不会关掉你已启用的其它无障碍服务** |
| 7 | 加入电池优化白名单，触发一次状态同步并打开 App |

> 权限清单与 App 内置授权页完全一致，源码依据：
> `app/src/main/kotlin/li/songe/gkd/ui/AuthA11yPage.kt` 的 `gkdStartCommandText`。

## 命令参数

```powershell
# 指定 APK 路径
.\adb-setup.bat -ApkPath "D:\download\gkd-tejiao.apk"

# 只配置，不安装
.\adb-setup.bat -NoInstall

# 不自动开启无障碍（只想手动开）
.\adb-setup.bat -SkipA11y

# 延长等待设备的时间（默认 120 秒）
.\adb-setup.bat -DeviceTimeoutSec 300
```

## 常见问题

**Q: 提示「未检测到已授权设备」**
按脚本打印的排查清单逐条检查。最常见的是手机没弹授权窗（请在手机上重新插拔数据线，
或在开发者选项里点「撤销 USB 调试授权」后重试）。

**Q: 提示「adb 写入无障碍设置未生效」**
小米/红米、华为、OPPO、vivo 等 ROM 会拦截 adb 修改无障碍设置，属系统限制。
请手动开启：**设置 → 无障碍（辅助功能）→ 已安装的服务 → 「GKD特调版」→ 打开**。
脚本的其余步骤已生效。

**Q: 提示「签名不一致, 无法覆盖安装」**
手机上已装的是用别的密钥签名的版本。先在 App 内 **设置 → 导出备份**，
然后 `adb uninstall li.songe.gkd`（或手动卸载），再运行本脚本，最后导入备份。

**Q: 为什么下载 platform-tools？**
adb 不属于系统自带组件。脚本优先用你已有的 adb；没有才从 Google 官方地址下载，
下载目标就是本脚本所在目录的 `platform-tools\`，不会写入系统目录、不需要管理员权限。

## 关于文件编码（给维护者）

- `adb-setup.bat` **必须保持纯 ASCII**：cmd.exe 按系统 OEM 代码页（简体中文是 GBK）解析批处理，
  非 ASCII 字符会让解析出错。
- `adb-setup.ps1` **必须保存为 UTF-8 带 BOM**：Windows PowerShell 5.1 对无 BOM 的 `.ps1`
  会按 GBK 解码，中文会全部乱码。
