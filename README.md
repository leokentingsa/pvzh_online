# PVZH Online — 改版也能好友对战的联机中继

任何修改了卡牌数据、英雄血量等实战数值的改版，**都不得用于排位对战**。本工具只服务好友房间。

**它解决的核心问题**：两个人都替换了 `CardData` 之类的卡牌文件，官方联机就会对不上、直接崩。本工具把 PvP 的出牌消息改走自己的中继通道，于是**改版也能和朋友打好友对战**。

它不改卡牌数据、不改规则、不替换官方匹配。官方匹配和 `InitGame` 走的还是官方路径，只有 PvP 的 Play 消息换了条传输通道。

**本仓库不包含、也不分发游戏本体。** 工具 App 从 Releases 下，游戏安装包要你自己用自己的 APK 跑一次注入脚本产出。

当前版本：工具 App `0.5.0-r9`（versionCode 11）｜中继服务 `0.3.0`｜对应游戏 `1.67.8`（versionCode 294）

---

## 开打前先看这三条

1. **手机省电策略会掐断联机。** 工具 App 必须设成「无限制 / 允许后台运行 / 允许自启动」，最好在多任务界面下拉锁定一下。打牌时别锁屏。这是掉线最常见的原因，症状是打一半突然断，日志里干净得看不出问题（服务端 90 秒空闲超时会清连接）。
2. **大约 3 秒的延迟 = 通了。** 不是卡，不是 bug，是游戏轮询节奏。反过来，**延迟突然变成 0 且立刻报错**，说明消息根本没转发出去。
3. **两台手机必须装同一份注入产物。** Agent 里写死了 6 个 RVA，游戏版本或注入包不一致就 Hook 不上。

---

## 谁需要做什么

不用两个人都折腾电脑。**一个人当房主，一个人当玩家。**

| 角色 | 要下载什么 | 要动手做什么 | 需要电脑吗 |
| --- | --- | --- | --- |
| **房主**（发起的人） | 工具 App + 中继要用的 Python | 选一种方式把中继跑起来；把自己的 APK 注入一次，装自己手机，再把**同一个**注入包发给朋友 | 要一台能跑 Python 的电脑（家里电脑、树莓派、VPS 都行） |
| **一起玩的朋友** | 只有工具 App | 装工具 App；装上房主发来的注入版游戏；App 里填房主给的地址；等房主给房间码 | 完全不用。手机收文件就行 |
| **喜欢折腾的开发者** | 仓库源码 | 可以查看源码，自己构建工具，学习研究等等 | 要 |

**怎么把注入包发给朋友**：微信、QQ、网盘、数据线、AirDroid，随便都可以。发到 QQ 上让对方点安装就行，不需要会 `adb`，也不需要电脑。

**如果两个人都没有电脑**：找一个愿意帮忙的朋友当代跑，只看下面「模式 C」——他连游戏都不用装，装上 Python 就能替你们跑中继。

---

## 第一步：双方都装工具 App

从 [Releases] 下载工具 App 的 APK，两台手机都装。

- 想自己构建：见 **[docs/BUILD.md](docs/BUILD.md)**，或直接 `.\build.ps1 -CheckOnly` 体检、体检没问题后`.\build.ps1` 一键构建。
- 系统要求：`arm64-v8a`，Android 8.0（API 26）以上。

---

## 第二步：准备注入版游戏

需要：JDK 17/21、`apktool.jar`、arm64 版 `frida-gadget`、Android build-tools（`zipalign` / `apksigner`）。这些工具可以去下载，

把 `apktool.jar` 和 gadget 放进 `tools\`，脚本会自动找到：

```
tools\apktool.jar
tools\frida-gadget-17.27.3-android-arm64.so
```

```powershell
cd <项目根目录>          # 就是含 Start-PVZH-Online.ps1 的那一层
.\tools\patch-game.ps1 -Apk "D:\downloads\pvzh.apk"
# 产物：D:\downloads\pvzh-pvzh-online.apk（脚本打印大小与 SHA256）
```

Windows 上直接双击 `patch-game.cmd`、把 APK 拖进去也行。

脚本做的事：解包 → 塞 gadget 到 `lib/arm64-v8a/libfrida-gadget.so` 并写 `listen 127.0.0.1:27042` → 在启动 Activity 的 `<clinit>` 插一行 `loadLibrary("frida-gadget")` → 回编译 → `zipalign` → `apksigner` 签名（首次自动生成 `%USERPROFILE%\.pvzh-online\patch.keystore`）→ 校验并打印 SHA256。

| 参数 | 说明 |
| --- | --- |
| `-Apk` | 你的原版 APK（必填） |
| `-Out` | 输出路径，默认 `<原名>-pvzh-online.apk` |
| `-Apktool` / `-Gadget` / `-Java` / `-Sdk` | 显式指定工具路径 |
| `-Port` | gadget 监听端口，默认 `27042`（改了这个 App 就找不到，别乱动） |
| `-OnLoad` | `resume`（默认，正常启动、随时可连）/ `wait`（启动即挂起等你连，attach 更早） |
| `-Keep32Bit` | 保留 `armeabi-v7a`（默认删掉，这样 32 位手机在安装阶段就报错，而不是启动崩） |

**装到手机**：把 APK 拷到手机上点安装即可（给自己发一份也一样）。注入包与原版签名不同，装之前要先卸载官方版——本地进度会丢，存档在 EA 账号里的，重装后登录会拉回来。

**发给朋友**：把这一个 APK 通过 QQ / 网盘发过去，让他卸掉官方版再装。**两台手机必须是同一份文件**（不放心可以通过对比 SHA256 最稳，但是这一步出错概率接近0）。

---

## 第三步：房主把中继跑起来

中继就是个 Python 服务，它只解决一件事：**让对方手机能找到它**。四种方式挑一种。

| 模式 | 适合 | 成本 | 朋友要装什么 | 证书 |
| --- | --- | --- | --- | --- |
| **A. 同一局域网** | 两人在同一个 WiFi / 热点 | 0 | 什么都不用 | 不用，走 http |
| **B. Tailscale Funnel** | 两人异地，零配置零成本 | 0 | 什么都不用 | Tailscale 自动签 |
| **C. 找人代跑中继** | 你们俩都没电脑 | 0 | 什么都不用 | 看代跑的人用哪种 |
| **D. 自己的公网入口** | 有 VPS / 公网 IPv6 / 端口映射 | VPS 约 ¥10–20/月 | 什么都不用 | 自己的域名证书 |

四种模式**客户端完全不用改**：App 里填的是「房间服务器地址」，中继的实际地址由服务端通过 `/api/rooms` 下发给两端（`relay_host` / `relay_port` / `relay_tls`）。

### 模式 A：同一局域网（最快验证链路）

电脑和两台手机连同一个路由器。先查电脑的局域网 IP：`ipconfig` 里「无线局域网适配器 WLAN」下的 IPv4 地址，例如 `192.168.1.9`。自己操作时下面都换成你自己的IPv4地址。

先检查
1.有python3.13，若没有，安装的时候务必勾选Add python.exe to PATH。
2.确认是专用网络
```powershell
Get-NetConnectionProfile       # 预期是 NetworkCategory : Private,如果是 Public，Windows 防火墙会挡端口。此时需要用用管理员打开powershell再运行以下代码
Set-NetConnectionProfile -InterfaceAlias "WLAN" -NetworkCategory Private。
```

检查完成后执行如下操作：

```powershell
cd <项目根目录>
py -3 -m venv .venv          # 没有 py 启动器就写 python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r server\requirements.txt

# 只在这一台电脑的当前窗口生效
$env:PVZH_API_HOST           = "0.0.0.0"      # 监听所有网卡，手机才连得进来
$env:PVZH_RELAY_BIND_HOST    = "0.0.0.0"      # 这条最容易漏，漏了手机连 8768 就是 Connection reset
$env:PVZH_PUBLIC_RELAY_HOST  = "192.168.1.9"  # 换成你自己的局域网 IP
$env:PVZH_PUBLIC_RELAY_TLS   = "false"        # 局域网内不套 TLS
$env:PVZH_ALLOW_INSECURE_LAN = "true"         # 明文监听的显式授权，漏了服务会拒绝启动

.\.venv\Scripts\python.exe server\server.py
```

手机端填 `http://192.168.1.9:8780`（带端口）。

三个最常见的坑：

1. **`0.0.0.0` 有两处**：`PVZH_API_HOST` 和 `PVZH_RELAY_BIND_HOST` 都要改。只改前者，服务端 8768 仍只听 `127.0.0.1`，手机连过来直接被 RST，症状就是 L1 报 `SocketException: connection reset`。
2. **Windows 防火墙**：首次启动会弹窗，两个都选「允许访问」。拒过一次就直接手动放行：
   `netsh advfirewall firewall add rule name="PVZH" dir=in action=allow protocol=TCP localport=8780,8768`
3. **路由器的 AP 隔离 / 访客网络**：会让手机连不上电脑，现象是 L1 直接超时。换主网络、关隔离。

### 模式 B：Tailscale Funnel（异地推荐，零成本）

Tailscale 只干一件事：给家里这台电脑一个公网可达的 HTTPS 入口。免费，**不需要公网 IP、不需要端口映射、不需要买域名**，而且支持 **raw TCP**（关键，见模式 D 的「不行」清单）。

1. 装 [Tailscale](https://tailscale.com/download) 并登录。域名形如 `xxx.tailXXXX.ts.net`，用 `tailscale status` 或管理台都能看到。
2. Tailscale管理台把三个开关打开：**DNS → HTTPS Certificates**、**MagicDNS**，以及 **ACL 里允许 Funnel**。

这里插入关于Funnel和证书的问题，Funnel可以这样开启
```powershell
$env:PATH = "C:\Program Files\Tailscale;" + $env:PATH
# 如果报错，请查看你把tailscale安装在了哪里。随后查看状态
tailscale status
# 如果能看到自己的电脑和 Tailscale 网络设备，说明已经登录成功。
# 先开放 API：
tailscale funnel --https=443 --bg http://127.0.0.1:8780
# 再开放 Relay：
tailscale funnel --tcp=8443 --bg tcp://127.0.0.1:8768

# 如果第一次开启，Tailscale 可能会提示你打开网页授权。点击网页中的允许或启用即可。
# 查看 Funnel 状态
tailscale funnel status

# 如果看到公网域名和转发地址，就说明开启成功。
```

知道域名之后，保存证书
```
$CertDir="此处替换为你下载的路径\server\certs"

New-Item -ItemType Directory -Force -Path $CertDir

tailscale cert --min-validity=168h `
  --cert-file="$CertDir\relay.crt" `
  --key-file="$CertDir\relay.key" `
  此处替换为你的域名.ts.net
```

3. 把 `Start-PVZH-Online.ps1`文件里 **第 12 行**的 `$Domain` 改成你自己的域名。
4. 以**管理员**身份运行：

```powershell
cd <项目根目录>
py -3 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r server\requirements.txt
.\Start-PVZH-Online.ps1
```

脚本也会自动：检查 Tailscale → 按需签/续证书（剩不足 7 天才续，写到 `server\certs\`）→ 以 `127.0.0.1:8780`（API）/ `127.0.0.1:8768`（Relay，自带 TLS）起服务 → `tailscale funnel --bg` 把公网 **443 → 8780**、**8443 → 8768** 接出去。

手机端填 `https://<你的节点>.ts.net`（**不带端口**）。验证：

```powershell
Invoke-RestMethod https://<你的域名>/health
# {"ok":true,"service":"pvzh-online","version":"0.3.0","rooms":0}
```

四个必须记住的点：

- **重启电脑后要重跑一次脚本**。Funnel 路由是持久配置所以还在，但本地 Python 进程没了 —— 表现就是建房返回 **HTTP 502**。
- **朋友不用装 Tailscale**。Funnel 是对公网开放的，他只填那个 `ts.net` 域名。
- **不用了就关掉**：`.\Stop-PVZH-Online.ps1`（等价于 `tailscale funnel reset`），否则域名一直对公网开着。
- **改脚本的两个注意**：改完必须保存为 **UTF-8 带 BOM**，否则 Windows PowerShell 5.1 按 GBK 解码，中文变乱码、引号被吃掉，报一堆 `表达式或语句中包含意外的标记`；另外只改第 12 行域名，别碰第 19 行那句守卫判断。如果提示「找不到 tailscale 命令」，先在当前窗口补 PATH：`$env:PATH = "C:\Program Files\Tailscale;" + $env:PATH`。

### 模式 C：让有电脑的朋友代跑中继

中继只是个普通 Python 服务，**谁跑都行**：代跑的人不需要装游戏、不需要装工具 App，也不用 JDK / SDK。

他只需要 Python 3，走 Funnel 的话再加 Tailscale：

```powershell
cd pvzh-online
py -3 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r server\requirements.txt
# 把 Start-PVZH-Online.ps1 第 12 行 $Domain 改成他自己的 ts.net 域名，然后管理员运行
.\Start-PVZH-Online.ps1
```

他把 `https://xxx.ts.net` 发给你们俩，你们在 App 里填同一个地址：A 建房、B 填码加入，和模式 B 完全一样。

四件注意的事清：

1. **主机不能关机、不能睡眠**。房间状态在内存里，主机一睡房间就没了。
2. **主机重启后要重跑脚本**，否则 502。
3. **主机理论上能看到你们的出牌数据**（在 TLS 内是明文转发）。
4. **主机离你们太远会更卡**，那 3 秒是固定开销，在此之上再加 RTT。

一台中继能同时服务多对人（默认上限 5000 个房间，单房存活 2 小时），所以一个朋友可以同时帮好几组人跑。

### 模式 D：自己的公网入口

想摆脱 Tailscale，就得自己解决「公网可达 + 可信证书」。**三条硬要求，缺一条都连不上**：

1. 手机能访问 API 端口（默认 8780，或套在 443 上）；
2. 手机能访问 **裸 TCP** 的 Relay 端口（默认 8768）；
3. 证书必须和 `PVZH_PUBLIC_RELAY_HOST` 里的**域名**匹配 —— App 建 TLS 时开了主机名校验，填 IP 或用自签证书都会握手失败。

> ⚠️ 这个模式**代码已支持，但我并没有在付费服务器上实测过**。真出问题时优先怀疑：安全组没放行 8780/8768、证书不被信任或域名不匹配、relay 端口被当成 HTTP 反代转发了。

**D1. 有 VPS（最稳）**

```bash
# VPS 上
python3 -m venv .venv && ./.venv/bin/pip install -r server/requirements.txt
export PVZH_API_HOST=127.0.0.1
export PVZH_API_PORT=8780
export PVZH_RELAY_BIND_HOST=0.0.0.0
export PVZH_RELAY_PORT=8768
export PVZH_RELAY_TLS_CERT=/etc/letsencrypt/live/your.domain/fullchain.pem
export PVZH_RELAY_TLS_KEY=/etc/letsencrypt/live/your.domain/privkey.pem
export PVZH_PUBLIC_RELAY_HOST=your.domain
export PVZH_PUBLIC_RELAY_PORT=8768
export PVZH_PUBLIC_RELAY_TLS=true
./.venv/bin/python server/server.py
```

再用 Caddy 把 API 也套上 HTTPS（Caddy 自动签证书）：

```
your.domain {
    reverse_proxy 127.0.0.1:8780
}
```

手机填 `https://your.domain`。注意 8768 要**直连**，不能塞进 Caddy。

**D2. 家里有公网 IPv6（零成本）**

域名加一条 `AAAA` 记录指向家宽 IPv6，防火墙放行 8780/8768，证书用 **DNS-01** 校验签发（家宽 80 端口基本被封，`HTTP-01` 走不通，`DNS-01` 不需要开放端口）。IPv6 没有 NAT，不用端口映射；地址会变，配 DDNS。

**D3. 家里有公网 IPv4 + 端口映射 + DDNS**

路由器把 8780/8768 映射到电脑，域名带 `A` 记录，证书同样用 `DNS-01`。可行，但证书这道坎比 D1/D2 麻烦。

**这些方案不行**（relay 是裸 TCP，不是 HTTP）：

- **Cloudflare Tunnel**：HTTP 能过，TCP 要客户端侧也跑 `cloudflared`，手机装不了；
- **ngrok 免费版**：TCP 隧道是付费功能；
- 任何「只反代 HTTP」的方案；Nginx 的 `stream` 模块做纯 TCP 转发可以，但 `http` 反代不行。

### 四种模式的共同注意点

- `PVZH_PUBLIC_RELAY_HOST` 必须是**对方手机能解析、能连到**的地址。留 `127.0.0.1` 的话，对方拿到房间信息后连的是他自己的本机，必然失败。
- 房间状态在内存里，服务重启即失效；只能单进程跑，不要开多 worker。
- 服务端不挑系统，Linux 上手动配环境变量即可（另见 `server/README.md`）。

---

## 第四步：手机上开打

1. 打开工具 App，把「房间服务器地址」填成房主给的那一串：局域网 `http://192.168.1.9:8780` / 公网 `https://xxx.ts.net` / 自建 `https://your.domain`。
2. 双方各自**先开游戏到主菜单，再把游戏切到后台**，然后回到工具 App。
3. A 点「创建六位房间码」，把 6 位码发给 B；B 填码点「加入好友房间」。两边都会显示 `房间 xxxxxx · 身份 A/B · <地址>:端口`。
4. 点 **L1 网络 → 自检 Relay 连通**。**创建房间之后再点一次**，这样测的才是「API + TLS + Relay + token」全链路。再点 **L2 Frida → 自检 Gadget 27042**。
5. 点 **一键：预检 → 连接 PVZH → 启动联机**。成功时会依次出现 `libil2cpp.so 已定位` → `RVA 校验 6/6` → `全部安装（4 个关键 Hook）` → `Relay 已确认房间 xxxxxx 身份 A`。
6. 回游戏，进好友对战开打。

**对局期间不要切回工具 App**：数据不会丢，但会触发系统冻结游戏进程，很容易出意外。出问题就把游戏和工具的后台都清掉，重走一遍上面的顺序。

---

## 怎么判断「到底通没通」

> **约 3 秒延迟 = 通。** 这是游戏 `ProcessResponse` 的轮询节奏，不是 bug，也不需要消除。
> **延迟变 0 且立刻报错 = 消息根本没转发**，本机在自己本地转了一圈。

三个数字能一锤定音：

| 看什么 | 通 | 不通 |
| --- | --- | --- |
| Hook 命中 | `stringNew` 几千以上，`authority / serialize / postNext / processResponse` 都在涨 | 全是 0：脚本没跑，或游戏进程被冻结 |
| 隧道流量 | 出站 `send`、入站 `deliver`、`accepted` 都在涨 | `deliver 0` 且 `accepted 0`：消息没到服务器，连接多半已断 |
| 转发计数 | 发出 ↑ / 已确认 ↑ / 收到 ↑ / 注入 ↑ | 已确认 0：自己没发出去；收到 0：对方没进同一个房间 |
| 隧道保活 | 每 20 秒刷新，显示「N 秒前」且 N < 45 | 一直「尚未开始」或停住：工具被冻结 / Agent 没 join |

## 四层自检

| 层 | 检查什么 | 失败通常意味着 |
| --- | --- | --- |
| **L1 网络** | API 是否可达；建房后测 TLS Relay + token join | 地址错 / 证书不匹配 / 中继没起（502）/ token 失效 / 局域网 AP 隔离 |
| **L2 Frida** | 读 `/proc/net/tcp` 预检 27042，再连 Gadget 枚举进程并 attach | 游戏没开 / 注入失败 / 端口被占 / 上次连接没释放 |
| **L3 Agent** | 等 `libil2cpp.so` 映射、校验 6 个 RVA、逐个装 Hook | 游戏版本与 RVA 不匹配 / 脚本运行时异常 |
| **L4 转发** | 发出 / 已确认 / 收到 / 注入 + 隧道流量 + 保活 | 能定位到是哪一端、哪个方向断的 |

任何一层红了，点「导出并分享」，日志会带上该层的原始报错。

---

## 已实测 / 未实测

**已实测跑通**：

- **同一局域网**，明文 TCP，`http://<内网IP>:8780`
- **Tailscale Funnel**，公网 HTTPS + 8443 裸 TCP，手机用流量也能连
- 双机完整对局：出牌 / 换牌 / 异能 / 格挡 / 结算 / 多局，稳定约 3 秒注入延迟
- 中继 `tools/e2e_probe.py`（12 项断言，走公网 Funnel）：12/12 PASS
- `tools/patch-game.ps1`（1.67.8 原版 222.7 MB → 产出 189.8 MB 签名 APK，`apksigner verify` 通过）

**没实测**（代码支持了，但我确实没在真实环境中跑过）：

- 自建 VPS / 家里服务器 + 自己域名 + 真 CA 证书
- 家宽端口映射 / IPv4、IPv6 直连

这两条路径在服务端和 App 里都接好了（relay 端口走 TLS、API 走 `https://`），但我没在付费主机上验证。有人跑通了欢迎反馈并和其他玩家交流；在此之前请当成「按设计应该能跑，未确认」。

---

## 常见问题

**建房返回 HTTP 502（模式 B/C）** — 电脑重启后本地 Python 没起来。Funnel 配置还在，但后面没人监听。管理员重跑 `Start-PVZH-Online.ps1` 即可。

**L1 报 `SocketException: connection reset`（局域网）** — 九成是只设了 `PVZH_API_HOST=0.0.0.0`，漏了 `PVZH_RELAY_BIND_HOST=0.0.0.0`，8768 还只听本机。补上再重启服务。

**服务一启动就报 `requires TLS; use PVZH_ALLOW_INSECURE_LAN only on a trusted LAN`** — 明文对外监听要显式授权，补 `$env:PVZH_ALLOW_INSECURE_LAN = "true"`。

**L1 在局域网下直接超时** — 依次查：两个 `0.0.0.0` 设了没、Windows 防火墙放行了没、路由器是不是开了 AP 隔离 / 用了访客网络。

**L1 报证书错误（模式 D）** — `PVZH_PUBLIC_RELAY_HOST` 的域名和证书 CN/SAN 不一致，或用了自签证书。App 强制校验主机名。

**L2 报 `Unable to connect to remote frida-server ... connection timed out`** — 不是「没监听」（那样会秒回 `Connection refused`），而是端口上挂着上一次的死连接。Gadget 的 `listen` 模式只服务一条连接：把游戏从最近任务里划掉重开，再点 L2 → 一键联机。

**L3 通过但 Hook 命中全 0** — 脚本装上了但游戏没在执行这些函数：多半还没进对局，或游戏进程被冻结（看「Agent 心跳」是否超过 15 秒没跳）。

**两边都显示「尚未收到对方任何消息」** — `deliver` 一条都没到。看中继日志里有没有两次 `relay joined room=<code>`；只有一次就是有一端没连上。

**打一局中途突然断，日志干净** — 手机省电策略把工具 App 冻结了，20 秒保活停摆，服务端 90 秒空闲超时清掉连接。去设置里给工具 App 设「无限制 / 允许后台 / 自启动」，打牌别锁屏。

**装注入版时提示 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`** — 手机上还装着官方版（签名不同），先卸载。

**手机重启后要做什么** — 中继重跑一次；手机上先开游戏到主菜单，再开工具走 L2 → 一键联机。

---

## 版本与 RVA

本版本对应 **1.67.8 / versionCode 294**。换版本要重新 dump RVA，工具会打印每个 RVA 处的头 16 字节（例如 `authority=fe0f1ff8`），这是判断「RVA 是否真的落在函数入口」的快速手段。

`/health` 里的 `version: 0.3.0` 是**中继服务**的版本，和工具 App 的 `0.5.0-r9` 不是一回事，不用对齐。

---

## 协议

隧道两端各说一行 JSON（JSONL），改协议时两边必须同时改。

客户端 → 服务器：

| op | 字段 | 说明 |
| --- | --- | --- |
| `join` | `room` `role` `token` | 用房间凭据接入中继 |
| `send` | `id` `message` | 游戏序列化后的 JSON，须含 `ty`，≤ 4 MB |
| `ack` | `seq` | 确认收到某条投递，清空本端 pending |
| `ping` | — | 保活，服务器回 `pong` |

服务器 → 客户端：

| op | 字段 | 说明 |
| --- | --- | --- |
| `joined` | `room` `role` | 接入成功 |
| `accepted` | `id` `seq` | 无论对端是否在线都立即返回，所以「已确认 0」一定是自己没发出去 |
| `deliver` | `seq` `from` `id` `message` | 投递对端消息，重连后按序补发 |
| `error` | `error` | 凭据 / 格式错误 |

Agent 的遥测事件（`boot / module / rva / hooks / ready / stats / hook-hit / relay-*`）也写进同一个日志文件。

---

## 目录结构

```
.
├── app/                           Android 工具 App
│   ├── src/main/assets/agents/      p2p_agent.js（带遥测）/ pvzh_lan_original.js（原始对照，不用管这个）
│   ├── src/main/java/com/pvzh/online/
│   │   ├── MainActivity.kt         房间 / 四层卡片 / 日志导出
│   │   ├── RelayTunnel.kt          本地 socket ⇄ 中继，含 20 秒保活
│   │   ├── FridaBridge.kt          JNI 封装
│   │   ├── GadgetPort.kt           只读 /proc/net/tcp 判断 27042 状态
│   │   ├── Telemetry.kt            Agent 事件解析与健康判定
│   │   └── NetworkProbe.kt         L1 自检
│   └── src/main/jni/frida_bridge.cpp
├── server/                        房间 API + TLS Relay（FastAPI + asyncio）
│   ├── server.py
│   └── tests/
├── tools/
│   ├── patch-game.ps1              给你的原版 APK 注入 gadget
│   ├── e2e_probe.py                中继 12 项端到端断言
│   └── fetch_frida_devkit.py       按需下载 Frida Core（构建工具 App 用）
├── docs/
│   └── BUILD.md                    构建环境清单、离线方案、报错对照
├── build.ps1 / build.cmd           一键构建工具 App
├── patch-game.cmd                  双击注入游戏 APK
├── Start-PVZH-Online.ps1 / Stop-PVZH-Online.ps1
└── README.md
```

---

## 构建工具 App

见 **[docs/BUILD.md](docs/BUILD.md)**：环境清单、Android Studio / sdkmanager 两种装法、一键脚本参数、国内镜像与离线方案、报错对照表。

Release 只放工具 App 的 APK 和脚本，**不包含任何游戏安装包**。

---

**If contacted by EA / PopCap:** this repository will remove any pre-patched build or distribution link on request, and will retain only the original tool code, injection scripts, and documentation. No legal defense is claimed or intended against such a request.

---

## 免责声明

非官方项目，与 EA / PopCap 无关，不代表其立场。

本仓库**不包含、也不分发游戏本体或任何游戏资源**，只有自己写的工具 App、注入脚本（在你自己的安装包上做本地修改）和中继服务器。请仅在你已合法拥有的游戏上使用。

Repo 和 Release 里没有游戏安装包，也没有注入好的成品包；注入产物由你自己用自己的 APK 生成，怎么在自己和朋友之间传递由你决定，风险也由你承担（分发第三方游戏安装包可能触及版权问题）。

排位对战与本链路完全无关：不走这条通道，也不用改版文件。**改版只用于好友房间。**

使用本工具或改版可能出现存档异常、账号风险或违反游戏服务条款的情况，相关风险自负，本项目不承担责任。

## License

[MIT](LICENSE) —— 仅覆盖本仓库原创的代码与文档。

---

## English

**PVZH Online** — a friendly-battle relay for modded PvZ Heroes builds.

**What this is:** a relay tool that lets two players, each running their own locally modded copy of the game, fight in a friendly room.

**What this is not:** a mod pack, a game APK distributor, a ranked-match tool, or an official EA / PopCap product.

This project does not change card data or game rules, and does not replace official matchmaking. Official matchmaking and official match creation still go through the official path; only PvP play messages are routed over an alternative transport. Modded data not be used in ranked matches — modded builds are for friendly rooms only.

Both players must use the same game build; the agent's RVA offsets are tied to it.

No mod files are included. You need to supply your own modded files.

This repository does not contain or distribute the game itself. Run the injection script on an APK you legally own to produce a playable build. Releases contain only the tool app and injection scripts — no game APK.

**How the work splits:** one player (the host) runs the relay and patches their own APK once, then sends that same patched APK to their friend over whatever channel they like. The friend only installs two apps, types one address, and waits for a room code. Nobody needs `adb`, and only the host needs a computer.

**Tested configurations**

Verified end-to-end with two phones, modded carddata, in a friendly room:

- Same Wi-Fi / same LAN — plain TCP relay, `http://<host>:8780`
- Tailscale Funnel — public HTTPS plus raw TCP on 8443

Not verified (implemented, but not run on real hardware yet):

- Self-hosted VPS or home server with your own domain and a real CA certificate
- Home broadband with port forwarding / direct IPv4 or IPv6 reachability

These paths are wired up in the server and the app — TLS on the relay port, `https://` API — but I have not tested them on a paid host. If something misbehaves there, it is most likely one of: firewall or cloud security-group rules blocking 8780/8768, a certificate that is self-signed, expired, or not for the exact domain, or the relay port being put behind an HTTP reverse proxy instead of exposed as raw TCP. Until someone reports a successful run, treat these as "should work, unconfirmed".

Plain TCP is intended for same-LAN testing only. For public play, use Tailscale Funnel or a relay endpoint with a matching TLS certificate.

The ~3 second delay between a play and its arrival on the other phone is the success signal, not a bug — it means the relay path is working end-to-end. Actual latency depends on the transport you choose.

Ranked matches never touch this relay. Pairing is manual (one player creates a room code, the other joins), so there's nothing for the two to collide with.

**Battery saver kills the tunnel.** If the tool app gets frozen in the background, keep-alives stop and the server drops the connection after 90 seconds. Set the app to unrestricted / allow background activity / autostart, and don't let the screen lock while playing.

**Disclaimer**

Unofficial project. Not affiliated with, endorsed by, or acting on behalf of EA / PopCap.

This repository does not contain or distribute the game or any game assets. It contains only original tool code, injection scripts (which modify your own local copy), and a relay server. Use it only with a copy of the game you legally own. You must obtain the game APK yourself from official channels; this repository provides no download links or acquisition methods. The repository and its releases contain no game APK and no pre-patched build — patched builds are produced locally with your own APK, and how you pass them around is your own call and your own risk.

The relay server does not collect or store game accounts or personal information; it only forwards PvP gameplay messages.

The project is not responsible for any game issues, save corruption, account risk, or ToS consequences arising from use of this tool.

MIT licensed; use at your own risk.
