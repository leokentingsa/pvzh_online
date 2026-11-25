#Requires -Version 5.1
<#
    patch-game.ps1 —— 给我自己合法拥有的 PVZH 安装包注入 Frida Gadget

    它做的事（全程本地，不联网）：
      1. apktool 解包你的 APK
      2. 把 frida-gadget 放进 lib/arm64-v8a/，并写好 listen 配置（127.0.0.1:27042）
      3. 在启动 Activity 的 <clinit> 里加一行 loadLibrary("frida-gadget")
      4. apktool 回编译 → zipalign → apksigner 签名
      5. 输出可直接安装的 APK，并打印 SHA256

    用法：
      .\tools\patch-game.ps1 -Apk "D:\downloads\pvzh.apk"
      .\tools\patch-game.ps1 -Apk "pvzh.apk" -Out "D:\pvzh-patched.apk"
      .\tools\patch-game.ps1 -Apk "pvzh.apk" -OnLoad wait -Port 27042

    依赖：JDK 17/21、apktool.jar、frida-gadget（arm64）、Android build-tools（zipalign/apksigner）
#>
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$Out,
    [string]$Gadget,
    [string]$Apktool,
    [string]$Java,
    [string]$Sdk,
    [string]$Keystore,
    [string]$StorePass = "pvzh-online",
    [int]$Port = 27042,
    [ValidateSet("resume", "wait")][string]$OnLoad = "resume",
    [switch]$Keep32Bit,
    [switch]$KeepWork
)

$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

function Note($m) { Write-Host ("  ----   " + $m) -ForegroundColor Cyan }
function Ok($m)   { Write-Host ("  [OK]   " + $m) -ForegroundColor Green }
function Bad($m)  { Write-Host ("  [!!]   " + $m) -ForegroundColor Red }
function Hint($m) { Write-Host ("         " + $m) -ForegroundColor Yellow }

# ---------- 0. 输入检查 ----------
if (-not (Test-Path -LiteralPath $Apk)) { Bad "找不到 APK：$Apk"; exit 1 }
$ApkPath = (Resolve-Path -LiteralPath $Apk).Path
if ([System.IO.Path]::GetExtension($ApkPath) -notin ".apk", ".apks") { Bad "$ApkPath 不是 .apk"; exit 1 }
$t0 = Get-Date

# ---------- 1. 找工具 ----------
$javaExe = $null
if ($Java) { $javaExe = Join-Path $Java "bin\java.exe" }
elseif ($env:JAVA_HOME) { $javaExe = Join-Path $env:JAVA_HOME "bin\java.exe" }
else {
    foreach ($c in @("C:\Program Files\BellSoft\LibericaJDK-17\bin\java.exe",
                     "C:\Program Files\Eclipse Adoptium\*\bin\java.exe",
                     "C:\Program Files\Java\*\bin\java.exe",
                     "C:\Program Files\Microsoft\jdk-*\bin\java.exe",
                     "C:\Program Files\Android\Android Studio\jbr\bin\java.exe",
                     "C:\Program Files\JetBrains\*\jbr\bin\java.exe")) {
        $hit = Get-ChildItem $c -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($hit) { $javaExe = $hit.FullName; break }
    }
}
if (-not $javaExe -or -not (Test-Path $javaExe)) { Bad "找不到 java.exe（装 JDK 17 或 21，或用 -Java 指定）"; exit 1 }
Ok ("Java： " + $javaExe)

$apktoolJar = $null
$candidates = @()
if ($Apktool) { $candidates += $Apktool }
if ($env:PVZH_APKTOOL) { $candidates += $env:PVZH_APKTOOL }
$candidates += (Join-Path $Root "tools\apktool.jar")
$candidates += (Join-Path $Root "tools\apktool\apktool.jar")
foreach ($c in $candidates) { if ($c -and (Test-Path $c)) { $apktoolJar = (Resolve-Path $c).Path; break } }
if (-not $apktoolJar) {
    Bad "找不到 apktool.jar"
    Hint "下载 https://github.com/iBotPeaches/Apktool/releases 的 apktool_2.11.x.jar，"
    Hint "放到仓库 tools\apktool.jar，或用 -Apktool 指定路径"
    exit 1
}
Ok ("apktool： " + $apktoolJar)

$gadgetSo = $null
$candidates = @()
if ($Gadget) { $candidates += $Gadget }
if ($env:PVZH_GADGET) { $candidates += $env:PVZH_GADGET }
$candidates += (Get-ChildItem (Join-Path $Root "tools") -Filter "frida-gadget*arm64*.so" -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
foreach ($c in $candidates) { if ($c -and (Test-Path $c)) { $gadgetSo = (Resolve-Path $c).Path; break } }
if (-not $gadgetSo) {
    Bad "找不到 arm64 版 frida-gadget"
    Hint "下载 https://github.com/frida/frida/releases 的 frida-gadget-<版本>-android-arm64.so"
    Hint "放到仓库 tools\ 下（文件名带 arm64 即可），或用 -Gadget 指定路径"
    exit 1
}
Ok ("gadget： " + $gadgetSo + "（" + [math]::Round((Get-Item $gadgetSo).Length / 1MB, 1) + " MB）")

$sdkPath = $null
if ($Sdk) { $sdkPath = $Sdk }
elseif ($env:ANDROID_HOME) { $sdkPath = $env:ANDROID_HOME }
elseif ($env:ANDROID_SDK_ROOT) { $sdkPath = $env:ANDROID_SDK_ROOT }
else {
    $lp = Join-Path $Root "local.properties"
    if (Test-Path $lp) {
        $line = (Get-Content $lp | Where-Object { $_ -match "^sdk\.dir=" } | Select-Object -First 1)
        if ($line) { $sdkPath = $line.Substring(8).Replace("\\", "\").Trim() }
    }
    if (-not $sdkPath) {
        foreach ($c in @((Join-Path $env:LOCALAPPDATA "Android\Sdk"), "C:\Android\Sdk", "D:\Android\Sdk")) {
            if (Test-Path (Join-Path $c "platform-tools")) { $sdkPath = $c; break }
        }
    }
}
if (-not $sdkPath -or -not (Test-Path $sdkPath)) {
    Bad "找不到 Android SDK（-Sdk 指定，或设 ANDROID_HOME）"
    exit 1
}
$bt = Get-ChildItem (Join-Path $sdkPath "build-tools") -Directory -ErrorAction SilentlyContinue |
      Sort-Object Name -Descending | Select-Object -First 1
if (-not $bt) { Bad "SDK 里没有 build-tools（sdkmanager `"build-tools;35.0.0`"）"; exit 1 }
$zipalign = Join-Path $bt.FullName "zipalign.exe"
$apksigner = Join-Path $bt.FullName "apksigner.bat"
if (-not (Test-Path $zipalign)) { Bad "缺少 zipalign：$zipalign"; exit 1 }
if (-not (Test-Path $apksigner)) { Bad "缺少 apksigner：$apksigner"; exit 1 }
Ok ("build-tools：" + $bt.Name)

$keytool = Join-Path (Split-Path $javaExe) "keytool.exe"
if (-not (Test-Path $keytool)) { Bad "缺少 keytool.exe（应该在 JDK 的 bin 里）"; exit 1 }

# ---------- 2. 输出路径 / 工作目录 ----------
if (-not $Out) {
    $dir = Split-Path -Parent $ApkPath
    $base = [System.IO.Path]::GetFileNameWithoutExtension($ApkPath)
    $Out = Join-Path $dir ($base + "-pvzh-online.apk")
}
$OutPath = [System.IO.Path]::GetFullPath($Out)
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("pvzh-patch-" + (Get-Date -Format "yyyyMMddHHmmss"))

Write-Host ""
Write-Host "== 1/6 解包 APK ==" -ForegroundColor White
Note ("输入： " + $ApkPath + "（" + [math]::Round((Get-Item $ApkPath).Length / 1MB, 1) + " MB）")
& $javaExe -Xmx3g -jar $apktoolJar d -f -o $work $ApkPath 2>&1 | Select-Object -Last 3 | ForEach-Object { Hint $_ }
if (-not (Test-Path (Join-Path $work "AndroidManifest.xml"))) { Bad "解包失败"; exit 1 }
Ok "解包完成"

# ---------- 3. 找启动 Activity 的 smali ----------
Write-Host ""
Write-Host "== 2/6 定位注入点 ==" -ForegroundColor White
$manifestPath = Join-Path $work "AndroidManifest.xml"
$manifestText = [System.IO.File]::ReadAllText($manifestPath)

$targetClass = $null
$activityMatches = [regex]::Matches($manifestText, "<activity[\s\S]*?</activity>")
foreach ($m in $activityMatches) {
    if ($m.Value -match "android.intent.category.LAUNCHER") {
        if ($m.Value -match 'android:name="([^"]+)"') { $targetClass = $Matches[1]; break }
    }
}
if (-not $targetClass -and $manifestText -match '<application[^>]*android:name="([^"]+)"') { $targetClass = $Matches[1] }
if (-not $targetClass) { Bad "没找到启动 Activity，无法确定注入点"; exit 1 }
Note ("注入点： " + $targetClass)

$relPath = $targetClass.TrimStart(".").Replace(".", "\") + ".smali"
$smaliFile = $null
foreach ($d in (Get-ChildItem $work -Directory -Filter "smali*")) {
    $p = Join-Path $d.FullName $relPath
    if (Test-Path $p) { $smaliFile = $p; break }
}
if (-not $smaliFile) { Bad ("找不到 smali：" + $relPath); exit 1 }
Ok ("smali： " + $smaliFile.Substring($work.Length).TrimStart("\"))

# 用 EA 自己的 Utility.loadLibrary（它内部 catch 了异常，32 位设备不会崩）
$loaderCall = "Lcom/ea/nimble/Utility;->loadLibrary(Ljava/lang/String;)V"
$utilitySmali = Join-Path $work ("smali_classes2\com\ea\nimble\Utility.smali")
if (Test-Path $utilitySmali) { Ok "加载方式： Nimble Utility.loadLibrary（异常安全）" }
else { $loaderCall = "Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V"; Note "加载方式： System.loadLibrary" }

# ---------- 4. 改 smali + 放 gadget ----------
Write-Host ""
Write-Host "== 3/6 注入 gadget ==" -ForegroundColor White
$lines = [System.IO.File]::ReadAllLines($smaliFile)
$already = $smaliFile -and ([System.IO.File]::ReadAllText($smaliFile).Contains("frida-gadget"))
if ($already) {
    Ok "smali 里已有 frida-gadget 调用，跳过（幂等）"
} else {
    $methodStart = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i].Trim() -eq ".method static constructor <clinit>()V") { $methodStart = $i; break }
    }
    if ($methodStart -lt 0) {
        Bad "启动 Activity 没有 <clinit>，这个版本需要手工处理"
        exit 1
    }
    $methodEnd = -1
    for ($i = $methodStart + 1; $i -lt $lines.Count; $i++) {
        if ($lines[$i].Trim() -eq ".end method") { $methodEnd = $i; break }
    }
    # 寄存器够不够用 v0
    $regLine = -1
    for ($i = $methodStart + 1; $i -lt $methodEnd; $i++) {
        if ($lines[$i].Trim() -match "\.(locals|registers) (\d+)") {
            $regLine = $i
            $n = [int]$Matches[2]
            if ($n -lt 1) {
                $lines[$i] = $lines[$i].Replace("0", "1")
                Note "把 .locals 0 提到 1（给 loadLibrary 用 v0）"
            }
            break
        }
    }
    # 插在 clinit 的最后一个 return-void 前
    $retIdx = -1
    for ($i = $methodEnd - 1; $i -gt $methodStart; $i--) {
        if ($lines[$i].Trim() -eq "return-void") { $retIdx = $i; break }
    }
    if ($retIdx -lt 0) { Bad "<clinit> 里没有 return-void"; exit 1 }
    $inject = @(
        "",
        '    const-string v0, "frida-gadget"',
        "",
        "    invoke-static {v0}, $loaderCall"
    )
    $newLines = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt $retIdx; $i++) { $newLines.Add($lines[$i]) }
    foreach ($l in $inject) { $newLines.Add($l) }
    for ($i = $retIdx; $i -lt $lines.Count; $i++) { $newLines.Add($lines[$i]) }
    [System.IO.File]::WriteAllLines($smaliFile, $newLines, (New-Object System.Text.UTF8Encoding($false)))
    Ok ("已写入 loadLibrary(""frida-gadget"")（插在 <clinit> 的 return-void 之前）")
}

# gadget + 配置
$abiDir = Join-Path $work "lib\arm64-v8a"
if (-not (Test-Path $abiDir)) { New-Item $abiDir -ItemType Directory -Force | Out-Null }
Copy-Item $gadgetSo (Join-Path $abiDir "libfrida-gadget.so") -Force
$configJson = @"
{
  "interaction": {
    "type": "listen",
    "address": "127.0.0.1",
    "port": $Port,
    "on_load": "$OnLoad"
  }
}
"@
[System.IO.File]::WriteAllText((Join-Path $abiDir "libfrida-gadget.config.so"), $configJson, (New-Object System.Text.UTF8Encoding($false)))
Ok ("已放入 lib/arm64-v8a/libfrida-gadget.so（" + [math]::Round((Get-Item (Join-Path $abiDir "libfrida-gadget.so")).Length / 1MB, 1) + " MB）")
Ok ("已写入配置： listen 127.0.0.1:" + $Port + " / on_load=" + $OnLoad)

$v7 = Join-Path $work "lib\armeabi-v7a"
if ((Test-Path $v7) -and -not $Keep32Bit) {
    Remove-Item $v7 -Recurse -Force
    Note "已移除 lib/armeabi-v7a（没有 armv7 版 gadget；64 位手机才装得上，这是故意让安装直接报错而不是启动崩）"
}
if ($Keep32Bit) { Note "-Keep32Bit 已保留 32 位库（32 位设备没有 gadget，联机功能用不了）" }

# ---------- 5. 回编译 ----------
Write-Host ""
Write-Host "== 4/6 回编译 + 对齐 ==" -ForegroundColor White
$unsigned = Join-Path $work "unsigned.apk"
& $javaExe -Xmx3g -jar $apktoolJar b -f -o $unsigned $work 2>&1 | Select-Object -Last 6 | ForEach-Object { Hint $_ }
if (-not (Test-Path $unsigned)) { Bad "回编译失败（看上面的 apktool 输出）"; exit 1 }
Ok ("回编译完成：" + [math]::Round((Get-Item $unsigned).Length / 1MB, 1) + " MB")

$aligned = Join-Path $work "aligned.apk"
& $zipalign -p -f 4 $unsigned $aligned 2>&1 | ForEach-Object { Hint $_ }
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $aligned)) { Bad "zipalign 失败"; exit 1 }
Ok "zip 对齐完成"

# ---------- 6. 签名 ----------
Write-Host ""
Write-Host "== 5/6 签名 ==" -ForegroundColor White
if (-not $Keystore) { $Keystore = Join-Path $env:USERPROFILE ".pvzh-online\patch.keystore" }
if (-not (Test-Path $Keystore)) {
    $ksDir = Split-Path -Parent $Keystore
    if (-not (Test-Path $ksDir)) { New-Item $ksDir -ItemType Directory -Force | Out-Null }
    Note ("首次运行，生成签名密钥：" + $Keystore)
    & $keytool -genkeypair -keystore $Keystore -storetype PKCS12 -alias pvzh -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass $StorePass -keypass $StorePass -dname "CN=PVZH Online Patch, OU=Local, O=Local, L=Local, S=Local, C=CN" *> $null
    if (-not (Test-Path $Keystore)) { Bad "生成 keystore 失败"; exit 1 }
}
Ok ("keystore： " + $Keystore)

$signOut = $OutPath
if (Test-Path $signOut) { Remove-Item $signOut -Force }
& $apksigner sign --ks $Keystore --ks-pass ("pass:" + $StorePass) --key-pass ("pass:" + $StorePass) `
    --ks-key-alias pvzh --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
    --out $signOut $aligned 2>&1 | ForEach-Object { Hint $_ }
if (-not (Test-Path $signOut)) { Bad "签名失败"; exit 1 }

# ---------- 7. 校验 ----------
Write-Host ""
Write-Host "== 6/6 校验 ==" -ForegroundColor White
$verify = & $apksigner verify --print-certs $signOut 2>&1
if ($LASTEXITCODE -ne 0) { Bad "签名校验未通过"; $verify | ForEach-Object { Hint $_ }; exit 1 }
Ok "签名校验通过"

Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::OpenRead($signOut)
$hasGadget = ($z.Entries | Where-Object { $_.FullName -eq "lib/arm64-v8a/libfrida-gadget.so" }).Count -gt 0
$hasConfig = ($z.Entries | Where-Object { $_.FullName -eq "lib/arm64-v8a/libfrida-gadget.config.so" }).Count -gt 0
$z.Dispose()
if (-not $hasGadget) { Bad "产物里没有 libfrida-gadget.so"; exit 1 }
Ok "产物内含 libfrida-gadget.so"
if (-not $hasConfig) { Bad "产物里没有 gadget 配置"; exit 1 }
Ok "产物内含 libfrida-gadget.config.so"

$hash = (Get-FileHash $signOut -Algorithm SHA256).Hash
$size = [math]::Round((Get-Item $signOut).Length / 1MB, 1)
$secs = [math]::Round(((Get-Date) - $t0).TotalSeconds, 0)

Write-Host ""
Write-Host "  注入完成" -ForegroundColor Green
Write-Host ("  APK    : " + $signOut)
Write-Host ("  大小   : " + $size + " MB")
Write-Host ("  SHA256 : " + $hash)
Write-Host ("  用时   : " + $secs + " 秒")
Write-Host ""
Write-Host "  装到手机（USB 调试已开）：" -ForegroundColor White
Write-Host ("  " + (Join-Path $sdkPath "platform-tools\adb.exe") + " install -r """ + $signOut + """")
Write-Host ""
Write-Host ("  装好后：先打开游戏，切到后台，回到工具 App 点 L2 自检 Gadget " + $Port)
Write-Host "  两台手机必须装同一个注入产物，否则 RVA 与游戏版本对不上。" -ForegroundColor White

if (-not $KeepWork) { Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue }
else { Note ("工作目录保留在：" + $work) }
exit 0
