# 构建说明

这份文档只讲一件事：**怎么把 Android 工具 App 构建出来**。服务端的部署见 [README](../README.md#三步开跑)。

不想看过程的话，最短路径是三条命令：

```powershell
cd <仓库目录>
.\build.ps1 -CheckOnly     # 先体检，缺什么会直接告诉你装什么
.\build.ps1                # 体检通过后一键构建
```

Windows 上也可以直接双击 `build.cmd`（等于 `build.ps1` 加一个暂停）。

> 网络在国内的话，Gradle 本体和 Frida devkit 大概率要先按第四节的办法换成镜像/手动放置，再跑一键构建。


---

## 一、环境清单

| 项 | 要求 | 说明 |
| --- | --- | --- |
| 操作系统 | Windows 10/11（推荐）；Linux/macOS 需用 `./gradlew` 手动构建 | 脚本是 PowerShell，非 Windows 也能手动跑 Gradle |
| **JDK** | **17 或 21**（必需） | Gradle 8.9 不支持 JDK 8/11，也不建议 24+。Android Studio 自带的 `jbr` 就是 21，可以直接用 |
| **Android SDK** | `compileSdk = 35` | 即 `platforms;android-35` |
| **build-tools** | `35.x`（如 35.0.0） | |
| **NDK** | **27.2.12479018**（版本必须一致） | `frida_bridge.cpp` 用 ndk-build 编译 arm64-v8a |
| **platform-tools** | 任意版本 | 用来 `adb install`，可选但强烈建议 |
| **Python** | 3.8+ | 仅在需要自动下载 Frida devkit 时用到 |
| **磁盘** | 约 1.5 GB 空闲 | Gradle 缓存 ~600 MB、SDK/NDK ~1 GB、devkit 350 MB |
| **网络** | 首次构建需要 | 下载 Gradle 8.9（~136 MB）、Maven 依赖、Frida devkit（~60 MB 压缩） |

工具链版本都在仓库里写死了，别自己升级：

- Gradle：`gradle/wrapper/gradle-wrapper.properties` → `gradle-8.9-bin.zip`
- NDK：`app/build.gradle.kts` → `ndkVersion = "27.2.12479018"`
- compileSdk / targetSdk：`35`，minSdk `26`，ABI 只有 `arm64-v8a`
- Frida Core：`17.27.3`（`tools/fetch_frida_devkit.py` 里的版本号）

## 二、装环境

### 方式 A：用 Android Studio（最省事）

1. 装 Android Studio，首次启动时让它装 SDK。
2. 打开 **Settings → Languages & Frameworks → Android SDK**：
   - **SDK Platforms** 勾 `Android 15 (API 35)`
   - **SDK Tools** 勾 `Android SDK Build-Tools 35`、`NDK (Side by side)` 里选 **27.2.12479018**、`Android SDK Platform-Tools`
3. 记下 SDK 路径（一般是 `C:\Users\<你>\AppData\Local\Android\Sdk`）。
4. 再装一个 JDK 21，或者直接用 Android Studio 自带的：
   `Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK` 里显示的路径（结尾是 `\jbr`）。

脚本会自动去这些常见位置找 JDK 和 SDK，通常你什么都不用配。

### 方式 B：只用命令行工具

```powershell
# 假设 cmdline-tools 解压在 C:\Android\Sdk\cmdline-tools\latest
$env:ANDROID_HOME = "C:\Android\Sdk"
C:\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat `
  "platforms;android-35" "build-tools;35.0.0" "platform-tools" "ndk;27.2.12479018"
```

JDK 21 可以从 [Eclipse Temurin](https://adoptium.net/) 或 Microsoft OpenJDK 装，装完设 `JAVA_HOME`。

## 三、构建

### 一键脚本

```powershell
.\build.ps1                 # 体检 → 补齐 local.properties → 按需下 devkit → 构建
.\build.ps1 -CheckOnly      # 只体检，不构建（改环境后反复用这个）
.\build.ps1 -Clean          # 先 clean 再构建，改过 Kotlin/JS 资源后建议用
.\build.ps1 -Jdk "C:\Program Files\Java\jdk-21" -Sdk "C:\Android\Sdk"
.\build.ps1 -Python "C:\Python313\python.exe"
.\build.ps1 -Gradle "C:\gradle-8.9"   # 已装本地 Gradle 8.9 时用它，跳过 wrapper 的 136 MB 下载
```

| 参数 | 作用 |
| --- | --- |
| `-CheckOnly` | 只做 1~4 步体检，不编译。缺什么直接告诉你装什么 |
| `-Clean` | 构建前先 `clean`，清掉旧的 Kotlin/资源缓存 |
| `-Jdk` / `-Sdk` / `-Python` | 显式指定 JDK 目录 / SDK 目录 / Python 解释器，跳过低效的自动探测 |
| `-Gradle` | 传入一个本地 Gradle 8.9 安装目录（如 `C:\gradle-8.9`），用它的 `bin\gradle.bat` 代替 `gradlew.bat`。国内网络下最省事 |

脚本做的事：

1. 找 JDK（`-Jdk` → `JAVA_HOME` → 常见安装路径），要求主版本 17~21；版本不对会**中止**而不是硬编。
2. 找 Android SDK（`-Sdk` → `ANDROID_HOME` / `ANDROID_SDK_ROOT` → `local.properties` → 常见路径），然后逐项检查 platform 35 / build-tools 35.x / NDK 27.2.12479018 / adb。
3. 检查 Frida devkit，缺失就调 `tools/fetch_frida_devkit.py` 下载（支持 `-Python` 指定解释器、`PVZH_DEVKIT_URL` 换下载地址）；如果 devkit 文件都在只缺 `includes.mk`，脚本会按头文件目录自动生成。
4. 生成/修正 `local.properties`（`sdk.dir=...`）。
5. 跑 Gradle：默认 `gradlew.bat --no-daemon :app:assembleDebug`（首次会下载 Gradle 8.9），给了 `-Gradle` 就用本地 Gradle 的 `bin\gradle.bat`。日志写到 `build-<时间戳>.log`。
6. 成功则打印 APK 路径、版本号、大小、**SHA256** 和 `adb install` 命令；失败则打印日志末尾，并按关键字给出对照原因。

### 手动构建（不用脚本 / 非 Windows）

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
echo "sdk.dir=/path/to/android-sdk" > local.properties   # 路径里的 \ 要写成 \\
python tools/fetch_frida_devkit.py                        # 只需一次
./gradlew --no-daemon :app:assembleDebug
```

### 产物

```
app/build/outputs/apk/debug/app-debug.apk
```

安装（两台手机都要装同一份，不过国内最推荐的方法不是指令，而是上传到QQ，再手机下载就行了）：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 四、离线 / 国内网络

| 卡在哪 | 处理 |
| --- | --- |
| Gradle 本体下载极慢（`services.gradle.org`） | 改 `gradle/wrapper/gradle-wrapper.properties` 里的一行：`distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip`，然后重跑 |
| Maven 依赖拉不动 | 在 `settings.gradle.kts` 的 `repositories` 前面加阿里云镜像 `maven { url = uri("https://maven.aliyun.com/repository/google") }` 等 |
| Frida devkit 下不动 | 手动下 [frida-core-devkit-17.27.3-android-arm64.tar.xz](https://github.com/frida/frida/releases/download/17.27.3/frida-core-devkit-17.27.3-android-arm64.tar.xz)，用 7-Zip 先解 `.xz` 再解 `.tar`，把里面的 `frida-core.h`、`libfrida-core.a` 和头文件目录放进 `app/src/main/jni/frida-devkit/`（`build.ps1` 第一次成功构建时会自动补 `includes.mk`，找不到就手写一行 `FRIDA_DEVKIT_INCLUDE_DIRS := ...`） |
| 完全离线 | 在联网机器上跑一遍 `build.ps1`，把 `%USERPROFILE%\.gradle`、`app/src/main/jni/frida-devkit` 一起拷过去 |

几个能直接抄的做法：

1. **Gradle 本体**：不想让 wrapper 下载时，装一份 Gradle 8.9 解压到任意目录（如 `C:\gradle-8.9`），然后 `.\build.ps1 -Gradle "C:\gradle-8.9"`。这条路径不碰 `services.gradle.org`。
2. **Frida devkit**：`github.com` 打不开时，一行环境变量换镜像再跑脚本即可：

```powershell
$env:PVZH_DEVKIT_URL = "https://你的镜像/frida-core-devkit-17.27.3-android-arm64.tar.xz"
.\build.ps1
```

   也可以手动下载那个 tar.xz，用 7-Zip 先解 `.xz` 再解 `.tar`，把 `frida-core.h`、`libfrida-core.a` 及头文件目录放进 `app/src/main/jni/frida-devkit/`。少了 `includes.mk` 不用管，`build.ps1` 会自己补（内容就是 `FRIDA_DEVKIT_INCLUDE_DIRS := $(LOCAL_PATH)/frida-devkit/.`）。
3. **Maven 依赖**：把 `settings.gradle.kts` 两个 `repositories` 改成镜像在前，例如

```kotlin
pluginManagement {
    repositories { maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }; google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { maven { url = uri("https://maven.aliyun.com/repository/google") }; google(); mavenCentral() }
}
```

## 四·五、这套脚本的实测状态

2026-11-24 在一台干净的 Windows 11 机器上完整跑通过一次，配置是：

| 项 | 实测值 |
| --- | --- |
| JDK | BellSoft Liberica **17.0.16**（`C:\Program Files\BellSoft\LibericaJDK-17`） |
| Android SDK | `C:\Android\Sdk`（platform 35 / build-tools 35.0.0 / NDK 27.2.12479018 / platform-tools） |
| Gradle | 本地 8.9（`-Gradle` 传入） |
| devkit | Frida Core 17.27.3 android-arm64（331.8 MB `libfrida-core.a`） |
| 结果 | `BUILD SUCCESSFUL`，28 秒，产物 106.4 MB |

同一台机器上没验证到的部分（写在这里免得你以为是脚本坏了）：`gradlew` 自动下载 Gradle 8.9、`fetch_frida_devkit.py` 自动下载 devkit —— 因为那台机器访问 `services.gradle.org` 与 `github.com` 都超时，所以这两条路径走的是本地 Gradle + 本地 devkit。你那边网络正常的话，直接 `.\build.ps1` 也会走同样的代码路径。



## 五、常见错误对照

| 报错关键字 | 原因 | 处理 |
| --- | --- | --- |
| `Unsupported class file major version 68` | JDK 太新（24+） | 换 JDK 17/21，或 `-Jdk` 指过去 |
| `Could not determine the dependencies of task` / `SDK location not found` | SDK 路径没配 | 检查 `local.properties` 的 `sdk.dir`（反斜杠要双写） |
| `NDK not configured` / `ndk-build` 失败 | NDK 缺失或版本不一致 | `sdkmanager "ndk;27.2.12479018"` |
| `fatal error: 'frida-core.h' file not found` | devkit 不完整 | 删掉 `app/src/main/jni/frida-devkit` 后重跑脚本 |
| `Could not resolve ...` / `Read timed out` | 依赖下载失败 | 配代理或国内镜像 |
| `The daemon has terminated unexpectedly on startup attempt #1 with error code: 0` | Kotlin daemon 噪声 | 忽略，看最后的 `BUILD SUCCESSFUL` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 手机上装过同包名不同签名的版本 | 先 `adb uninstall com.pvzh.online`（即卸载原有安装包） |
| 构建成功但装上去连不上 Gadget | 游戏 APK 没注入 gadget / 端口不是 27042 | 这是环境问题，看 README 的 L2 说明 |

## 六、构建完怎么验

装好后按 README 的四层自检走一遍：L1 网络 → L2 Frida → 一键联机看到 `RVA 校验 6/6` 与 `Relay 已确认房间 xxxxxx` → 进对局看「隧道流量」和「注入」计数是否在涨。

> 只改客户端、不改服务端时，服务端不需要重新部署；反之亦然。
