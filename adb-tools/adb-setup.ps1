# =============================================================================
#  GKD 特调版 - 一键 ADB 配置脚本
#
#  用途: 用一根数据线 + adb, 把安装后需要手动点的授权一次性配好, 让 App 开箱可用。
#
#  用法: 双击同目录的 adb-setup.bat 即可(推荐), 或:
#        powershell -NoProfile -ExecutionPolicy Bypass -File adb-setup.ps1
#
#  可选参数:
#        -ApkPath "C:\path\to\gkd-tejiao.apk"   指定要安装的 APK(默认自动找同目录/上一级目录)
#        -NoInstall                              不安装 APK, 只做配置
#        -SkipA11y                               不自动开启无障碍服务
#
#  ⚠️ 本文件必须保存为 UTF-8 **带 BOM**。
#     Windows PowerShell 5.1 对无 BOM 的 .ps1 会按 GBK 解码, 中文会全部乱码。
#
#  权限来源说明(与 App 内置指引一致, 见源码 AuthA11yPage.kt:349 gkdStartCommandText):
#     pm grant   WRITE_SECURE_SETTINGS / GET_APP_OPS_STATS / POST_NOTIFICATIONS
#     appops set POST_NOTIFICATION / SYSTEM_ALERT_WINDOW / ACCESS_ACCESSIBILITY /
#                ACCESS_RESTRICTED_SETTINGS / FOREGROUND_SERVICE_SPECIAL_USE /
#                CREATE_ACCESSIBILITY_OVERLAY
# =============================================================================

[CmdletBinding()]
param(
    [string]$ApkPath = "",
    [switch]$NoInstall,
    [switch]$SkipA11y,
    [int]$DeviceTimeoutSec = 120,
    [string]$Serial = ""
)

# 不要用 Stop: adb 返回非 0 是常态, 我们靠输出文本判断
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

$ReleasePkg = 'li.songe.gkd'
$DebugPkg = 'li.songe.gkd.debug'
$A11yClass = 'com.google.android.accessibility.selecttospeak.SelectToSpeakService'
$ExposeSvc = 'li.songe.gkd.service.ExposeService'
$PlatformToolsUrl = 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip'

$script:Adb = $null
$script:Serial = $null
$script:Pkg = $ReleasePkg
$script:Results = New-Object System.Collections.ArrayList

function Head($t) { Write-Host ''; Write-Host ('=' * 62) -ForegroundColor DarkCyan; Write-Host ("  " + $t) -ForegroundColor Cyan; Write-Host ('=' * 62) -ForegroundColor DarkCyan }
function Ok($t) { Write-Host ('  [成功] ' + $t) -ForegroundColor Green; [void]$script:Results.Add(@('OK', $t)) }
function Warn($t) { Write-Host ('  [注意] ' + $t) -ForegroundColor Yellow; [void]$script:Results.Add(@('WARN', $t)) }
function Bad($t) { Write-Host ('  [失败] ' + $t) -ForegroundColor Red; [void]$script:Results.Add(@('FAIL', $t)) }
function Info($t) { Write-Host ('  ' + $t) -ForegroundColor Gray }

function Find-Adb {
    $cands = New-Object System.Collections.ArrayList
    [void]$cands.Add((Join-Path $PSScriptRoot 'platform-tools\adb.exe'))
    [void]$cands.Add((Join-Path $PSScriptRoot 'adb.exe'))
    $dir = Get-Item $PSScriptRoot
    for ($i = 0; $i -lt 2; $i++) {
        if ($dir.Parent) {
            $dir = $dir.Parent
            [void]$cands.Add((Join-Path $dir.FullName 'platform-tools\adb.exe'))
        }
    }
    $cmd = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($cmd) { [void]$cands.Add($cmd.Source) }
    [void]$cands.Add((Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'))
    [void]$cands.Add((Join-Path $env:ProgramFiles 'Android\Sdk\platform-tools\adb.exe'))
    [void]$cands.Add('C:\Program Files (x86)\Android\android-sdk\platform-tools\adb.exe')
    foreach ($p in $cands) {
        if ($p -and (Test-Path $p)) { return (Resolve-Path $p).Path }
    }
    return $null
}

function Install-PlatformTools {
    $zip = Join-Path $PSScriptRoot 'platform-tools.zip'
    Info '未找到 adb, 正在从 Google 官方地址下载 platform-tools ...'
    Info $PlatformToolsUrl
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $PlatformToolsUrl -OutFile $zip -UseBasicParsing -TimeoutSec 300
        Expand-Archive -Path $zip -DestinationPath $PSScriptRoot -Force
        Remove-Item $zip -Force -ErrorAction SilentlyContinue
        $adb = Join-Path $PSScriptRoot 'platform-tools\adb.exe'
        if (Test-Path $adb) { Ok 'platform-tools 下载完成'; return $adb }
        Bad '解压后未找到 adb.exe'
        return $null
    }
    catch {
        Bad ("下载失败: " + $_.Exception.Message)
        Info '请手动下载 platform-tools 并解压到本脚本所在目录:'
        Info $PlatformToolsUrl
        return $null
    }
}

function Sh([string]$Cmd) {
    # adb shell 执行, 返回合并后的输出文本
    $out = & $script:Adb -s $script:Serial shell $Cmd 2>&1 | Out-String
    return $out.Trim()
}

function Get-ReadyDevices {
    # 返回当前处于 device 状态的序列号列表(去重, 保持 adb 输出顺序)
    $raw = & $script:Adb devices 2>&1 | Out-String
    $rows = $raw -split "`r?`n" | Where-Object { $_ -match "`t" }
    $list = New-Object System.Collections.ArrayList
    foreach ($r in $rows) {
        $parts = ($r -replace "`t", ' ') -split '\s+' | Where-Object { $_ }
        if ($parts.Count -ge 2 -and $parts[1] -eq 'device') { [void]$list.Add($parts[0]) }
    }
    return ($list | Select-Object -Unique)
}

function Select-Device {
    # 一个就直用; 多个则选择(MuMu 之类的模拟器会同时暴露 emulator-5554 与 127.0.0.1:port,
    # 表现成"两台设备", 不带 -s 的 adb 命令会直接报 more than one device/emulator)
    param([string[]]$Devices, [string]$Prefer)
    if ($Prefer) {
        foreach ($d in $Devices) { if ($d -eq $Prefer) { return $d } }
        Warn ('指定的设备 ' + $Prefer + ' 不在当前列表中')
    }
    if ($Devices.Count -eq 1) { return $Devices[0] }
    if ($Devices.Count -eq 0) { return $null }

    Write-Host ''
    Info ('检测到 ' + $Devices.Count + ' 个可用设备:')
    for ($i = 0; $i -lt $Devices.Count; $i++) {
        Write-Host ('    [' + ($i + 1) + '] ' + $Devices[$i]) -ForegroundColor White
    }

    # 非交互场景(被管道/CI 调用)不阻塞, 直接用第一个
    if ([Console]::IsInputRedirected) {
        Info '当前为非交互运行, 自动使用第一个设备'
        return $Devices[0]
    }

    while ($true) {
        $ans = Read-Host ('  请输入序号(直接回车=1)')
        if (-not $ans) { return $Devices[0] }
        $n = 0
        if ([int]::TryParse($ans, [ref]$n) -and $n -ge 1 -and $n -le $Devices.Count) { return $Devices[$n - 1] }
        Write-Host '  输入无效, 请重新输入' -ForegroundColor Yellow
    }
}

function Wait-Device([int]$TimeoutSec = 120) {
    Info '等待设备连接 (请确认手机已插线, 且已在开发者选项里开启 USB 调试) ...'
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $lastMsg = ''
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
        $ready = @(Get-ReadyDevices)
        if ($ready.Count -ge 1) { return (Select-Device -Devices $ready -Prefer $Serial) }

        $raw = & $script:Adb devices 2>&1 | Out-String
        $rows = $raw -split "`r?`n" | Where-Object { $_ -match "`t" }
        foreach ($r in $rows) {
            $parts = ($r -replace "`t", ' ') -split '\s+' | Where-Object { $_ }
            if ($parts.Count -ge 2) {
                $serial = $parts[0]
                $state = $parts[1]
                if ($state -eq 'unauthorized') {
                    $msg = '设备 ' + $serial + ' 未授权: 请在手机屏幕上点「允许 USB 调试」'
                    if ($msg -ne $lastMsg) { Warn $msg; $lastMsg = $msg }
                }
                elseif ($state -eq 'offline') {
                    $msg = '设备 ' + $serial + ' 状态为 offline: 请重新插拔数据线, 或在手机上重新确认调试授权'
                    if ($msg -ne $lastMsg) { Warn $msg; $lastMsg = $msg }
                }
            }
        }
        Start-Sleep -Seconds 2
    }
    return $null
}

function Get-InstalledExact([string]$name) {
    # pm list packages 的前缀匹配会把 li.songe.gkd.debug 也算成 li.songe.gkd,
    # 这里必须逐行取 "package:xxx" 后做精确比较。
    $r = Sh ("pm list packages " + $name)
    foreach ($line in ($r -split "`r?`n")) {
        $l = $line.Trim()
        if ($l.StartsWith('package:')) {
            $n = $l.Substring(8).Trim()
            if ($n -ieq $name) { return $true }
        }
    }
    return $false
}

function Resolve-TargetPkg {
    # 优先级: 正式版 li.songe.gkd > 调试版 li.songe.gkd.debug
    # (两者可共存; 本工具发布的 APK 是正式版, 所以正式版优先)
    if (Get-InstalledExact $ReleasePkg) { return $ReleasePkg }
    if (Get-InstalledExact $DebugPkg) { return $DebugPkg }
    return $null
}

function AppOpsSet([string]$op) {
    $r = Sh ("appops set " + $script:Pkg + " " + $op + " allow")
    if ($r -match 'Unknown|Error|Exception|error') {
        # 回退到 android:xxx 形式(部分 ROM 只认这种)
        $alt = 'android:' + $op.ToLower()
        $r2 = Sh ("appops set " + $script:Pkg + " " + $alt + " allow")
        if ($r2 -match 'Unknown|Error|Exception|error') { return $false }
        return $true
    }
    return $true
}

# -----------------------------------------------------------------------------
Head 'GKD 特调版 - 一键 ADB 配置'
Write-Host '  本工具会把安装后需要手动点的授权一次性配好:' -ForegroundColor White
Write-Host '    · 授予写入安全设置 / 应用权限状态 / 通知 权限' -ForegroundColor White
Write-Host '    · 解除 6 项系统操作限制(appops)' -ForegroundColor White
Write-Host '    · 自动开启无障碍服务(省去手动找入口)' -ForegroundColor White
Write-Host '    · 加入电池优化白名单, 避免被后台清理' -ForegroundColor White

# --- 1. adb -----------------------------------------------------------------
Head '步骤 1/7  准备 adb'
$script:Adb = Find-Adb
if (-not $script:Adb) { $script:Adb = Install-PlatformTools }
if (-not $script:Adb) {
    Bad '没有可用的 adb, 无法继续'
    Write-Host ''
    Write-Host '  解决方式(任选其一):' -ForegroundColor White
    Write-Host '   1. 下载 platform-tools 解压到本脚本目录: https://developer.android.com/tools/releases/platform-tools'
    Write-Host '   2. 安装 Android SDK Platform-Tools 后重新运行本脚本'
    exit 2
}
Ok ('使用 adb: ' + $script:Adb)
$ver = (& $script:Adb version 2>&1 | Select-Object -First 1)
Info $ver

# --- 2. 设备 ---------------------------------------------------------------
Head '步骤 2/7  连接设备'
# 注意: 不能用 `& $adb start-server 2>&1 | Out-Null`。
# adb 会 fork 出一个常驻 daemon, 它继承调用方的 stdout 句柄, 于是父进程的管道永远等不到 EOF,
# 用户双击运行时表现为"窗口卡住不退出"。必须让 adb 完全脱离当前句柄。
$tmpOut = Join-Path $env:TEMP 'gkd-adb-start.out'
$tmpErr = Join-Path $env:TEMP 'gkd-adb-start.err'
try {
    Start-Process -FilePath $script:Adb -ArgumentList 'start-server' -NoNewWindow -Wait `
        -RedirectStandardOutput $tmpOut -RedirectStandardError $tmpErr | Out-Null
}
catch {
    Info ('启动 adb 服务端时出现提示(通常可忽略): ' + $_.Exception.Message)
}
$script:Serial = Wait-Device -TimeoutSec $DeviceTimeoutSec
if (-not $script:Serial) {
    Bad '未检测到已授权设备(超时)'
    Write-Host ''
    Write-Host '  排查清单:' -ForegroundColor White
    Write-Host '   1. 手机「设置 → 关于手机 → 连点 版本号 7 次」开启开发者选项'
    Write-Host '   2. 「开发者选项 → USB 调试」打开'
    Write-Host '   3. 插线后手机会弹「允许 USB 调试吗?」→ 勾选「始终允许」再点允许'
    Write-Host '   4. 数据线必须是数据线(部分线只能充电)'
    Write-Host '   5. 若用无线调试, 先执行: adb connect <手机IP>:<端口>'
    Write-Host ''
    Write-Host '  当前 adb devices 输出:' -ForegroundColor White
    & $script:Adb devices
    exit 3
}
$model = Sh 'getprop ro.product.model'
$brand = Sh 'getprop ro.product.brand'
$sdkTxt = Sh 'getprop ro.build.version.sdk'
$relTxt = Sh 'getprop ro.build.version.release'
$sdk = 0
[int]::TryParse(($sdkTxt -replace '\D', ''), [ref]$sdk) | Out-Null
Ok ('设备: ' + $brand + ' ' + $model + '  (Android ' + $relTxt + ' / API ' + $sdk + ')')
Info ('序列号: ' + $script:Serial)

# --- 3. 安装 APK ------------------------------------------------------------
Head '步骤 3/7  安装 APK'
$existing = Resolve-TargetPkg
if ($existing) { Info ('设备上已安装: ' + $existing) }
$installed = [bool]$existing

$apk = $ApkPath
if (-not $apk) {
    $searchDirs = @($PSScriptRoot, (Split-Path $PSScriptRoot -Parent))
    foreach ($d in $searchDirs) {
        if (-not $d) { continue }
        $hit = Get-ChildItem -Path $d -Filter 'gkd-tejiao-*.apk' -File -ErrorAction SilentlyContinue |
               Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (-not $hit) {
            $hit = Get-ChildItem -Path $d -Filter '*gkd*release*.apk' -File -ErrorAction SilentlyContinue |
                   Sort-Object LastWriteTime -Descending | Select-Object -First 1
        }
        if ($hit) { $apk = $hit.FullName; break }
    }
}

if ($NoInstall) {
    Info '按参数要求跳过安装'
}
elseif ($apk -and (Test-Path $apk)) {
    Info ('安装: ' + $apk)
    $ir = & $script:Adb -s $script:Serial install -r -d $apk 2>&1 | Out-String
    if ($ir -match 'Success') { Ok 'APK 安装/覆盖成功' }
    elseif ($ir -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match') {
        Bad '签名不一致, 无法覆盖安装'
        Info '原因: 手机上已装的是用别的密钥签名的版本。'
        Info '解决: 先在 App 内「导出备份」, 然后卸载旧版(adb uninstall ' + $script:Pkg + '), 再运行本脚本重新安装, 最后导入备份。'
    }
    else {
        Bad ('安装失败: ' + ($ir -replace '\s+', ' '))
    }
}
elseif ($installed) {
    Info '未找到 APK 文件, 但设备上已安装, 直接进入配置'
}
else {
    Bad '设备上未安装, 且本地找不到 APK'
    Info '请把 gkd-tejiao-<版本>.apk 放到本脚本同目录后重新运行, 或用 -ApkPath 指定路径'
    exit 4
}

# 安装后重新确认包名(正式版优先: 两者可共存, 而本工具发布的 APK 是正式版)
$resolved = Resolve-TargetPkg
if (-not $resolved) { Bad '安装后仍未找到应用, 中止'; exit 4 }
$script:Pkg = $resolved
if ($script:Pkg -eq $DebugPkg) {
    Info '注意: 设备上只有调试版(li.songe.gkd.debug), 将按调试版配置'
}
Ok ('目标包名: ' + $script:Pkg)

# --- 4. pm grant ------------------------------------------------------------
Head '步骤 4/7  授予权限'
$grants = @(
    'android.permission.WRITE_SECURE_SETTINGS',
    'android.permission.GET_APP_OPS_STATS'
)
if ($sdk -ge 33) { $grants += 'android.permission.POST_NOTIFICATIONS' }

foreach ($g in $grants) {
    $r = Sh ('pm grant ' + $script:Pkg + ' ' + $g)
    if ($r -match 'Exception|Error|error|not a changeable') { Warn ($g + ' : ' + ($r -replace '\s+', ' ')) }
    else { Ok ('已授予 ' + $g) }
}

# --- 5. appops --------------------------------------------------------------
Head '步骤 5/7  解除系统操作限制 (appops)'
$ops = @('POST_NOTIFICATION', 'SYSTEM_ALERT_WINDOW')
if ($sdk -ge 29) { $ops += 'ACCESS_ACCESSIBILITY' }
if ($sdk -ge 33) { $ops += 'ACCESS_RESTRICTED_SETTINGS' }
if ($sdk -ge 34) { $ops += 'FOREGROUND_SERVICE_SPECIAL_USE' }
if ($sdk -ge 34) { $ops += 'CREATE_ACCESSIBILITY_OVERLAY' }

foreach ($op in $ops) {
    if (AppOpsSet $op) { Ok ('appops ' + $op + ' = allow') }
    else { Warn ('appops ' + $op + ' 设置失败(该 ROM 可能不支持此项)') }
}

# --- 6. 无障碍 --------------------------------------------------------------
Head '步骤 6/7  开启无障碍服务'
if ($SkipA11y) {
    Info '按参数要求跳过'
}
else {
    $comp = $script:Pkg + '/' + $A11yClass
    $cur = Sh 'settings get secure enabled_accessibility_services'
    $list = New-Object System.Collections.ArrayList
    if ($cur -and $cur -ne 'null') {
        foreach ($x in ($cur -split ':')) { if ($x) { [void]$list.Add($x.Trim()) } }
    }
    $exists = $false
    foreach ($x in $list) { if ($x -ieq $comp) { $exists = $true } }

    if ($exists) {
        Ok '无障碍服务已处于开启状态'
    }
    else {
        [void]$list.Add($comp)
        $new = ($list -join ':')
        $r = Sh ('settings put secure enabled_accessibility_services ' + $new)
        Sh 'settings put secure accessibility_enabled 1' | Out-Null
        Start-Sleep -Milliseconds 800
        $back = Sh 'settings get secure enabled_accessibility_services'
        if ($back -and ($back -match [regex]::Escape($A11yClass))) {
            Ok '已自动开启无障碍服务(其余已启用的无障碍服务保持不变)'
        }
        else {
            Warn 'adb 写入无障碍设置未生效'
            Info '部分 ROM(小米/红米、华为、OPPO、vivo 等)会拦截 adb 修改无障碍, 属系统限制。'
            Info '请手动开启: 设置 → 无障碍(辅助功能) → 已安装的服务 → 「GKD特调版」→ 打开'
        }
    }
}

# --- 7. 电池白名单 + 暴露服务 ----------------------------------------------
Head '步骤 7/7  后台保活与收尾'
$wl = Sh ('dumpsys deviceidle whitelist +' + $script:Pkg)
$chk = Sh 'dumpsys deviceidle whitelist'
if ($chk -match [regex]::Escape($script:Pkg)) { Ok '已加入电池优化白名单' }
else { Warn '电池优化白名单添加未生效, 请手动设置: 设置 → 电池 → 应用 → GKD特调版 → 不限制' }

$ex = Sh ('am start-foreground-service -n ' + $script:Pkg + '/' + $ExposeSvc + ' --ei expose 1')
Start-Sleep -Milliseconds 600
$err = Sh ('dumpsys activity services ' + $script:Pkg)
if ($err -match 'ExposeService|SecurityException|Permission Denial') {
    if ($err -match 'Permission Denial') { Warn '暴露服务启动被拒(部分 ROM 限制)' }
    else { Ok '已触发状态同步(ExposeService)' }
}
else { Info '未确认暴露服务状态, 可在 App 内查看通知' }

# 拉起应用
Sh ('monkey -p ' + $script:Pkg + ' -c android.intent.category.LAUNCHER 1') | Out-Null
Ok '已尝试打开应用'

# --- 汇总 -------------------------------------------------------------------
Head '完成情况汇总'
foreach ($r in $script:Results) {
    $tag = $r[0]
    $color = 'Green'
    if ($tag -eq 'WARN') { $color = 'Yellow' }
    if ($tag -eq 'FAIL') { $color = 'Red' }
    Write-Host ('  [' + $tag + '] ' + $r[1]) -ForegroundColor $color
}

Write-Host ''
Write-Host '  接下来在手机上确认 3 件事:' -ForegroundColor White
Write-Host '   1. 打开 App, 首页应显示无障碍服务已开启,' -ForegroundColor White
Write-Host '      订阅列表里应已自动出现「梦念逍遥のGKD订阅」(首次启动会自动联网拉取规则, 需联网)' -ForegroundColor White
Write-Host '   2. 若订阅为空: 下拉刷新, 或进 订阅 → 右上角 + → 粘贴订阅链接' -ForegroundColor White
Write-Host '   3. 进 App 的「设置 → 授权」页, 可看到各项权限状态是否已就绪' -ForegroundColor White
Write-Host ''

$failCount = 0
foreach ($r in $script:Results) { if ($r[0] -eq 'FAIL') { $failCount++ } }
if ($failCount -gt 0) { exit 1 }
exit 0
