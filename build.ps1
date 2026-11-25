#requires -Version 5.1
<#
    PVZH Online — 一键构建（Android 工具 App）

    用法：
        .\build.ps1                  # 体检 + 构建 debug APK
        .\build.ps1 -CheckOnly       # 只体检环境，不构建
        .\build.ps1 -Clean           # 先 gradlew clean 再构建
        .\build.ps1 -Jdk "C:\jdk-21" -Sdk "C:\Android\Sdk"
        .\build.ps1 -Python "C:\Python313\python.exe"

    脚本会自己找 JDK / Android SDK / NDK / Python，生成 local.properties，
    按需下载 Frida devkit，最后输出 APK 路径、大小与 SHA256。
#>
[CmdletBinding()]
param(
    [switch]$CheckOnly,
    [switch]$Clean,
    [string]$Jdk,
    [string]$Sdk,
    [string]$Python,
    [string]$Gradle
)

# 用 Continue：本脚本大量调用 java/python/gradle 这些会往 stderr 写内容的原生程序，
# 在 Stop 模式下 2>&1 会被当成终止性错误。真正的失败由退出码判断。
$ErrorActionPreference = "Continue"
$Root     = Split-Path -Parent $MyInvocation.MyCommand.Path
$AppGradle = Join-Path $Root "app\build.gradle.kts"
$LocalProps = Join-Path $Root "local.properties"
$DevkitDir = Join-Path $Root "app\src\main\jni\frida-devkit"
$ApkPath   = Join-Path $Root "app\build\outputs\apk\debug\app-debug.apk"
$LogPath   = Join-Path $Root ("build-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".log")

$Script:Problems = New-Object System.Collections.ArrayList
$Script:Hints    = New-Object System.Collections.ArrayList

function Step($text) {
    Write-Host ""
    Write-Host ("== " + $text + " ==") -ForegroundColor Cyan
}
function Ok($text)   { Write-Host ("  [OK]   " + $text) -ForegroundColor Green }
function Note($text) { Write-Host ("  ----   " + $text) -ForegroundColor Gray }
function Bad($text)  { Write-Host ("  [FAIL] " + $text) -ForegroundColor Red; [void]$Script:Problems.Add($text) }
function Warn($text) { Write-Host ("  [WARN] " + $text) -ForegroundColor Yellow }
function Hint($text) { [void]$Script:Hints.Add($text) }

# ---------- JDK ----------
function Test-Jdk([string]$jdkHome) {
    if (-not $jdkHome) { return $null }
    $java = Join-Path $jdkHome "bin\java.exe"
    if (-not (Test-Path $java)) { return $null }
    $out = & $java -version 2>&1 | Out-String
    if ($out -notmatch 'version "(\d+)') { return $null }
    $major = [int]$Matches[1]
    # JDK 1.8 写法 → 8
    if ($major -eq 1 -and $out -match 'version "1\.(\d+)') { $major = [int]$Matches[1] }
    return [pscustomobject]@{ Home = $jdkHome; Java = $java; Major = $major; Raw = ($out.Trim() -replace "`r`n", " ") }
}

function Find-Jdk {
    if ($Jdk) {
        $found = Test-Jdk $Jdk
        if (-not $found) { Bad ("-Jdk 指定的目录不是有效 JDK：" + $Jdk) } else { return $found }
        return $null
    }
    if ($env:JAVA_HOME) {
        $found = Test-Jdk $env:JAVA_HOME
        if ($found -and $found.Major -ge 17) { return $found }
    }
    $candidates = @()
    foreach ($pattern in @(
            "C:\Program Files\Java\jdk*",
            "C:\Program Files\Eclipse Adoptium\jdk*",
            "C:\Program Files\Microsoft\jdk*",
            "C:\Program Files\Amazon Corretto\jdk*",
            "C:\Program Files\Android\Android Studio\jbr",
            "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
            "C:\Program Files\JetBrains\*\jbr",
            "$env:LOCALAPPDATA\Programs\PyCharm*\jbr")) {
        $candidates += (Get-ChildItem $pattern -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
    }
    foreach ($candidate in ($candidates | Sort-Object -Unique)) {
        $found = Test-Jdk $candidate
        if ($found -and $found.Major -ge 17) { return $found }
    }
    return $null
}

# ---------- Android SDK ----------
function Test-Sdk([string]$path) {
    if (-not $path -or -not (Test-Path $path)) { return $null }
    if (-not (Test-Path (Join-Path $path "platform-tools"))) { return $null }
    return $path
}

function Find-Sdk {
    if ($Sdk) { return (Test-Sdk $Sdk) }
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        $found = Test-Sdk $candidate
        if ($found) { return $found }
    }
    if (Test-Path $LocalProps) {
        $line = (Get-Content $LocalProps | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1)
        if ($line) {
            $found = Test-Sdk (($line -replace '^sdk\.dir=', '') -replace '\\\\', '\')
            if ($found) { return $found }
        }
    }
    foreach ($candidate in @("$env:LOCALAPPDATA\Android\Sdk", "C:\Android\Sdk", "D:\Android\Sdk")) {
        $found = Test-Sdk $candidate
        if ($found) { return $found }
    }
    return $null
}

# ---------- Python ----------
function Test-Python([string]$exe) {
    if (-not $exe -or -not (Test-Path $exe)) { return $null }
    $out = & $exe -c "import sys;print(sys.version.split()[0])" 2>&1
    if ($LASTEXITCODE -ne 0) { return $null }
    return [pscustomobject]@{ Exe = $exe; Version = ($out | Out-String).Trim() }
}

function Find-Python {
    $cands = @()
    if ($Python) { $cands += $Python }
    if ($env:PVZH_PYTHON) { $cands += $env:PVZH_PYTHON }
    foreach ($name in @("python.exe", "python3.exe")) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($cmd) { $cands += $cmd.Source }
    }
    $cands += (Get-ChildItem "$env:LOCALAPPDATA\Programs\Python\Python*\python.exe" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
    $cands += (Get-ChildItem "C:\Python*\python.exe" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
    foreach ($candidate in ($cands | Sort-Object -Unique)) {
        $found = Test-Python $candidate
        if ($found) { return $found }
    }
    return $null
}

# ============ 1. JDK ============
Step "1/5 Java 开发工具包（JDK 17 或 21）"
$jdkInfo = Find-Jdk
if (-not $jdkInfo) {
    Bad "没有找到可用的 JDK 17/21"
    Hint "装一个 JDK 21（Temurin / Oracle / Microsoft 都行），或直接用 Android Studio 自带的 jbr："
    Hint "  在 Android Studio 里 File > 设置 > Build Tools > Gradle 的 Gradle JDK 能看到路径"
    Hint "  也可以显式指定：.\build.ps1 -Jdk `"C:\Program Files\Java\jdk-21`""
} elseif ($jdkInfo.Major -lt 17) {
    Bad ("JDK 版本过低（" + $jdkInfo.Major + "），需要 17 或 21：" + $jdkInfo.Home)
    Hint "Gradle 8.9 不支持 JDK 8/11，也建议避开 JDK 24+"
} elseif ($jdkInfo.Major -gt 21) {
    Warn ("JDK " + $jdkInfo.Major + " 可能不被 Gradle 8.9 支持，建议 17 或 21：" + $jdkInfo.Home)
    Ok ("仍将使用：" + $jdkInfo.Home)
} else {
    Ok ("JDK " + $jdkInfo.Major + "： " + $jdkInfo.Home)
}

# ============ 2. Android SDK ============
Step "2/5 Android SDK（platform 35 / build-tools / NDK 27.2.12479018）"
$sdkPath = Find-Sdk
if (-not $sdkPath) {
    Bad "没有找到 Android SDK（需要含 platform-tools 的目录）"
    Hint "推荐：装 Android Studio，首次启动按向导装 SDK；或只装命令行工具后："
    Hint "  sdkmanager `"platforms;android-35`" `"build-tools;35.0.0`" `"platform-tools`" `"ndk;27.2.12479018`""
    Hint "然后指定：.\build.ps1 -Sdk `"C:\Android\Sdk`"，或设环境变量 ANDROID_HOME"
} else {
    Ok ("SDK：" + $sdkPath)

    $platform = Join-Path $sdkPath "platforms\android-35"
    if (Test-Path $platform) { Ok "platforms;android-35 已安装" }
    else {
        Bad "缺少 platforms;android-35（compileSdk = 35）"
        Hint ("装：sdkmanager `"platforms;android-35`"   （SDK 路径：" + $sdkPath + "）")
    }

    $bt = Get-ChildItem (Join-Path $sdkPath "build-tools") -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^35\.' } | Select-Object -First 1
    if ($bt) { Ok ("build-tools " + $bt.Name) }
    else {
        Bad "缺少 build-tools 35.x"
        Hint "装：sdkmanager `"build-tools;35.0.0`""
    }

    $ndk = Join-Path $sdkPath "ndk\27.2.12479018"
    if (Test-Path (Join-Path $ndk "ndk-build.cmd")) { Ok "NDK 27.2.12479018 已安装" }
    else {
        Bad "缺少 NDK 27.2.12479018（项目用 ndk-build 编译 frida_bridge.cpp）"
        Hint "装：sdkmanager `"ndk;27.2.12479018`""
    }

    $adb = Join-Path $sdkPath "platform-tools\adb.exe"
    if (Test-Path $adb) { Ok "platform-tools（adb）已安装" } else { Warn "缺少 platform-tools，装好后可用 adb 直接安装 APK" }
}

# ============ 3. Frida devkit ============
Step "3/5 Frida Core devkit（libfrida-core.a，约 60 MB 压缩包）"
$coreLib = Join-Path $DevkitDir "libfrida-core.a"
$coreHdr = Join-Path $DevkitDir "frida-core.h"
$incMk   = Join-Path $DevkitDir "includes.mk"
if ((Test-Path $coreLib) -and (Test-Path $coreHdr) -and (Test-Path $incMk)) {
    $mb = [math]::Round((Get-Item $coreLib).Length / 1MB, 1)
    Ok ("devkit 已就位（libfrida-core.a " + $mb + " MB）")
} elseif ((Test-Path $coreLib) -and (Test-Path $coreHdr)) {
    # 手动解压的用户缺 includes.mk：按头文件所在目录现生成（与 tools/fetch_frida_devkit.py 一致）
    Note "devkit 文件齐全但缺少 includes.mk，按头文件目录自动生成"
    $dirs = @(Get-ChildItem $DevkitDir -Recurse -File -Filter *.h -ErrorAction SilentlyContinue |
        ForEach-Object { $_.DirectoryName } | Sort-Object -Unique)
    $entries = @()
    foreach ($dir in $dirs) {
        $rel = $dir.Substring($DevkitDir.Length).TrimStart('\').Replace("\", "/")
        if ($rel -eq "") { $entries += "`$(LOCAL_PATH)/frida-devkit/." }
        else { $entries += ("`$(LOCAL_PATH)/frida-devkit/" + $rel + "/.") }
    }
    if ($entries.Count -eq 0) { $entries = @("`$(LOCAL_PATH)/frida-devkit/.") }
    $content = "FRIDA_DEVKIT_INCLUDE_DIRS := " + ($entries -join (" \" + [Environment]::NewLine + "  ")) + [Environment]::NewLine
    [System.IO.File]::WriteAllText($incMk, $content, [System.Text.UTF8Encoding]::new($false))
    if (Test-Path $incMk) { Ok ("已生成 includes.mk（" + $entries.Count + " 个头文件目录）") }
    else { Bad "includes.mk 生成失败" }
} else {
    Note "devkit 不在本地，需要从 Frida 官方 release 下载 17.27.3 android-arm64 版"
    $pyInfo = Find-Python
    if (-not $pyInfo) {
        Bad "缺少 Python（下载 devkit 需要它）"
        Hint "装 Python 3.8+（勾选 Add to PATH），或手动下载后解压到 app\src\main\jni\frida-devkit\："
        Hint "  https://github.com/frida/frida/releases/download/17.27.3/frida-core-devkit-17.27.3-android-arm64.tar.xz"
        Hint "下载后可用 7-Zip 解压该 .tar.xz（先解 xz 再解 tar）"
    } else {
        Ok ("Python " + $pyInfo.Version + "： " + $pyInfo.Exe)
        if (-not $CheckOnly) {
            Note "开始下载并解压 devkit（首次构建约 1~3 分钟，取决于网速）"
            $fetch = Join-Path $Root "tools\fetch_frida_devkit.py"
            & $pyInfo.Exe $fetch
            if ($LASTEXITCODE -ne 0) {
                Bad "devkit 下载失败"
                Hint "github.com 可能被限速/拦截：可以设代理后重跑，或把下载地址换成镜像再重跑："
                Hint "  `$env:PVZH_DEVKIT_URL='https://你的镜像/.../frida-core-devkit-17.27.3-android-arm64.tar.xz'"
                Hint "  也可以手动下载后解压到 app\src\main\jni\frida-devkit\（需含 frida-core.h 与 libfrida-core.a）"
            } elseif ((Test-Path $coreLib) -and (Test-Path $coreHdr) -and (Test-Path $incMk)) {
                Ok "devkit 安装完成"
            } else {
                Bad "devkit 下载完成但文件不完整"
            }
        }
    }
}

# ============ 4. local.properties ============
Step "4/5 local.properties（告诉 Gradle 你的 SDK 在哪）"
if (-not $sdkPath) {
    Warn "跳过：还没找到 SDK"
} else {
    $desired = "sdk.dir=" + $sdkPath.Replace("\", "\\")
    $current = if (Test-Path $LocalProps) { (Get-Content $LocalProps -Raw).Trim() } else { "" }
    if ($current -eq $desired) {
        Ok "已存在且正确"
    } else {
        Set-Content -Path $LocalProps -Value $desired -Encoding ASCII
        Ok ("已写入：" + $desired)
    }
}

# ============ 5. 构建 ============
Step "5/5 构建 debug APK"
if ($Script:Problems.Count -gt 0) {
    Write-Host ""
    Write-Host "环境还不完整，先解决上面标红的项：" -ForegroundColor Red
    foreach ($item in $Script:Problems) { Write-Host ("  - " + $item) -ForegroundColor Red }
    if ($Script:Hints.Count -gt 0) {
        Write-Host ""
        Write-Host "下一步建议：" -ForegroundColor Yellow
        foreach ($item in $Script:Hints) { Write-Host ("  * " + $item) -ForegroundColor Yellow }
    }
    if ($CheckOnly) { exit 1 }
    Write-Host ""
    Write-Host "已中止构建（修复后重跑 .\build.ps1）" -ForegroundColor Red
    exit 1
}

if ($CheckOnly) {
    Write-Host ""
    Ok "环境体检全部通过，可以构建了：.\build.ps1"
    exit 0
}

$env:JAVA_HOME = $jdkInfo.Home
$env:ANDROID_HOME = $sdkPath
$env:ANDROID_SDK_ROOT = $sdkPath
$env:PATH = (Join-Path $jdkInfo.Home "bin") + ";" + (Join-Path $sdkPath "platform-tools") + ";" + $env:PATH

$gradlew = if ($Gradle) { Join-Path $Gradle "bin\gradle.bat" } else { Join-Path $Root "gradlew.bat" }
if (-not (Test-Path $gradlew)) {
    Write-Host ("找不到 Gradle 入口：" + $gradlew) -ForegroundColor Red
    Write-Host "  默认用仓库里的 gradlew.bat；若已装 Gradle 8.9，可用 -Gradle `"C:\gradle-8.9`"" -ForegroundColor Yellow
    exit 1
}
if ($Gradle) { Note ("使用本地 Gradle：" + $gradlew) } else { Note "使用仓库自带的 Gradle wrapper（首次会下载 gradle-8.9，约 136 MB）" }
$versionName = "unknown"
if (Test-Path $AppGradle) {
    $line = Get-Content $AppGradle | Where-Object { $_ -match 'versionName' } | Select-Object -First 1
    if ($line -match '"([^"]+)"') { $versionName = $Matches[1] }
}
Note ("目标版本：" + $versionName + "，日志：" + $LogPath)

Push-Location $Root
try {
    if ($Clean) {
        Note "清理旧产物（gradlew clean）"
        & $gradlew --no-daemon clean 2>&1 | Out-File -FilePath $LogPath -Encoding utf8
        if ($LASTEXITCODE -ne 0) { Note "clean 有告警，继续构建" }
    }
    Note "开始编译（首次构建要下载 Gradle 与依赖，可能 5~15 分钟；之后约 30 秒）"
    $started = Get-Date
    & $gradlew --no-daemon :app:assembleDebug *>> $LogPath
    $code = $LASTEXITCODE
    $took = [int]((Get-Date) - $started).TotalSeconds
}
finally {
    Pop-Location
}

if ($code -ne 0 -or -not (Test-Path $ApkPath)) {
    Write-Host ""
    Write-Host "构建失败（退出码 " -ForegroundColor Red -NoNewline
    Write-Host ($code.ToString() + "）：" + $LogPath) -ForegroundColor Red
    $tail = Get-Content $LogPath -Tail 45 -ErrorAction SilentlyContinue
    Write-Host ""
    Write-Host "--- 日志末尾 ---" -ForegroundColor DarkGray
    foreach ($line in $tail) { Write-Host $line -ForegroundColor DarkGray }

    Write-Host ""
    Write-Host "--- 常见原因对照 ---" -ForegroundColor Yellow
    $logText = (Get-Content $LogPath -Raw -ErrorAction SilentlyContinue)
    if ($logText -match 'Unsupported class file major version') { Write-Host "  * JDK 版本与 Gradle 不兼容：换 JDK 17 或 21（不要用 24+）" -ForegroundColor Yellow }
    if ($logText -match 'Could not determine the dependencies|SDK location not found') { Write-Host "  * SDK 路径没配好：检查 local.properties 的 sdk.dir" -ForegroundColor Yellow }
    if ($logText -match 'NDK not configured|ndk-build') { Write-Host "  * NDK 缺失或版本不对：需要 ndk;27.2.12479018" -ForegroundColor Yellow }
    if ($logText -match 'frida-core|frida-core.h') { Write-Host "  * Frida devkit 不完整：删除 app\src\main\jni\frida-devkit 后重跑本脚本" -ForegroundColor Yellow }
    if ($logText -match 'Could not resolve|Read timed out|Connection reset') { Write-Host "  * 依赖下载失败：检查网络/代理，或给 Gradle 配国内镜像" -ForegroundColor Yellow }
    if ($logText -match 'daemon has terminated unexpectedly') { Write-Host "  * 这条是 Kotlin daemon 噪声，通常不影响结果，看最终 BUILD 行" -ForegroundColor Yellow }
    exit $code
}

$apk = Get-Item $ApkPath
$hash = (Get-FileHash $ApkPath -Algorithm SHA256).Hash
Write-Host ""
Ok ("构建成功（用时 " + $took + " 秒）")
Write-Host ""
Write-Host ("  APK    : " + $ApkPath) -ForegroundColor Green
Write-Host ("  版本   : " + $versionName) -ForegroundColor Green
Write-Host ("  大小   : " + [math]::Round($apk.Length / 1MB, 1) + " MB") -ForegroundColor Green
Write-Host ("  SHA256 : " + $hash) -ForegroundColor Green
Write-Host ""
Write-Host "安装到手机（USB 调试已开）：" -ForegroundColor Cyan
Write-Host ("  adb install -r `"" + $ApkPath + "`"") -ForegroundColor Gray
Write-Host ""
Write-Host "两台手机都要装同一个 APK，否则 RVA 与游戏版本对不上。" -ForegroundColor Gray
exit 0
