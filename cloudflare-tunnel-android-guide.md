<div align="center">

# ☁️ Cloudflare Tunnels on Android

### The guide nobody wrote. Until now.

<img src="https://readme-typing-svg.herokuapp.com?font=Fira+Code&weight=500&size=18&pause=1000&color=00D4AA&center=true&vCenter=true&width=600&lines=Run+cloudflared+on+any+Android+device;Free+public+HTTPS+URL+in+seconds;5+problems+you+WILL+hit+%2B+solutions;Zero+root+required;Works+with+official+Cloudflare+binary" alt="Typing SVG" />

<br/>

[![Android](https://img.shields.io/badge/📱_Android_10+-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#)
[![Cloudflare](https://img.shields.io/badge/☁️_Cloudflare-F38020?style=for-the-badge&logo=cloudflare&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/🟣_Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](#)
[![Go Binary](https://img.shields.io/badge/🔵_Go_Binary-00ADD8?style=for-the-badge&logo=go&logoColor=white)](#)

</div>

---

## 💡 What This Is

You have an Android app running a local server. You want a **public HTTPS URL** so the world can reach it.

```
📱 Your Android App (localhost:8088)
              ↓
       ☁️ Cloudflare Tunnel
              ↓
🌍 https://cool-name.trycloudflare.com  ← anyone can access this
```

> 🤔 **Can Android run cloudflared?** → Yes. Android IS Linux.
>
> 🤔 **Does it need root?** → No. But you'll fight 5 different Android restrictions.
>
> 🤔 **Is it free?** → Yes. Quick Tunnels need no account, no credit card, nothing.
>
> 🤔 **Why is this guide so long?** → Because every existing "just run cloudflared" tutorial silently fails on Android. We document WHY and HOW to fix each failure.

**Built for [ZeroClaw Android](https://github.com/ashokvarmamatta/ZeroClawAndroid).** Works for any Android app.

---

## 🚨 The 5 Problems (and Solutions)

### Problem 1: Getting the Binary ![](https://img.shields.io/badge/Easy-4CAF50?style=flat-square)

| What | Details |
|------|---------|
| ❌ **Problem** | Cloudflare doesn't publish Android builds |
| ✅ **Solution** | Android IS Linux — use the `linux-arm64` build |
| 📥 **Download** | `cloudflared-linux-arm64` from GitHub releases |

```bash
curl -L -o libcloudflared.so \
  https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64
```

> ⚠️ We name it `libcloudflared.so` — this matters for Problem 2.

---

### Problem 2: Permission Denied (W^X Policy) ![](https://img.shields.io/badge/Critical-F44336?style=flat-square)

| What | Details |
|------|---------|
| ❌ **Problem** | Android 10+ blocks executing files from app directories |
| 💀 **Error** | `error=13, Permission denied` |
| 🤯 **Also fails** | Dynamic linker: `unexpected e_type: 2` |
| ✅ **Solution** | Bundle as native library in APK |

<details>
<summary>💀 What DOESN'T Work</summary>

```kotlin
// ❌ FAILS — filesDir is noexec
val binary = File(context.filesDir, "cloudflared")
binary.setExecutable(true)
ProcessBuilder(binary.absolutePath).start()  // Permission denied

// ❌ FAILS — linker expects shared libs, not executables
ProcessBuilder("/system/bin/linker64", binary.absolutePath).start()
// "unexpected e_type: 2"
```

</details>

#### ✅ The Fix — 4 Steps

**Step 1** — Place binary in jniLibs:
```
app/src/main/jniLibs/arm64-v8a/libcloudflared.so
```

**Step 2** — Force extraction (`build.gradle.kts`):
```kotlin
android {
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}
```

**Step 3** — Force extraction (`AndroidManifest.xml`):
```xml
<application android:extractNativeLibs="true" ...>
```

**Step 4** — Execute from nativeLibraryDir:
```kotlin
val binary = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")
ProcessBuilder(binary.absolutePath, "tunnel", "--url", "http://localhost:8088").start()
// ✅ nativeLibraryDir HAS execute permission!
```

> 💡 **Why both build.gradle AND manifest?** Modern Android keeps `.so` files compressed inside the APK. You need BOTH settings to extract them as real files on disk.

---

### Problem 3: DNS Completely Broken ![](https://img.shields.io/badge/Critical-F44336?style=flat-square) ![](https://img.shields.io/badge/Hardest_Problem-000000?style=flat-square)

| What | Details |
|------|---------|
| ❌ **Problem** | Go reads `/etc/resolv.conf` for DNS — **doesn't exist on Android** |
| 💀 **Error** | `lookup api.trycloudflare.com on [::1]:53: connection refused` |
| 🧱 **Root cause** | Path is HARDCODED in Go source. No env var override exists. |

<details>
<summary>💀 Everything We Tried That FAILED</summary>

| Attempt | Why It Failed |
|---------|-------------|
| 📝 Create `/etc/resolv.conf` | `/etc/` is read-only (symlink to `/system/etc/`) |
| 🔧 `RES_CONF` env var | Not a real Go thing — we made it up hoping it existed |
| 🔧 `GODEBUG=netdns=cgo` | cloudflared is statically linked, no cgo available |
| 🔌 DNS relay on port 53 | `EACCES` — non-root can't bind ports below 1024 |
| 🌐 `HTTPS_PROXY` env var | cloudflared uses custom `http.Transport{}` with `Proxy: nil` — ignores proxy |
| 🔀 HTTP CONNECT proxy | Same — cloudflared bypasses proxy entirely |
| 📦 Build with `GOOS=android` | Would fix DNS, but requires compiling cloudflared from source |

**7 approaches. All failed.** Then we found the actual solution:

</details>

#### ✅ The Fix — Do DNS from Java, Bypass Go Entirely

Java's `InetAddress.getByName()` uses Android's native DNS resolver. **It works perfectly.** The trick: do ALL DNS work from Java and pass results to cloudflared.

**Phase 1** — Register tunnel from Java (not cloudflared):

```kotlin
// ✅ Java DNS works on Android!
val client = OkHttpClient()
val request = Request.Builder()
    .url("https://api.trycloudflare.com/tunnel")
    .post("".toRequestBody("application/json".toMediaType()))
    .build()

val response = client.newCall(request).execute()
val result = JSONObject(response.body!!.string()).getJSONObject("result")

val tunnelId   = result.getString("id")           // UUID
val hostname   = result.getString("hostname")      // xxx.trycloudflare.com
val accountTag = result.getString("account_tag")
val secret     = result.getString("secret")        // base64
```

**Phase 2** — Write credentials file:

```kotlin
File(context.cacheDir, "tunnel_creds.json").writeText(
    JSONObject().apply {
        put("AccountTag", accountTag)
        put("TunnelID", tunnelId)
        put("TunnelSecret", secret)
    }.toString()
)
```

**Phase 3** — Write config file:

```kotlin
File(context.cacheDir, "tunnel_config.yml").writeText("""
    tunnel: $tunnelId
    credentials-file: ${credsFile.absolutePath}
    protocol: http2
    ingress:
      - hostname: $hostname
        service: http://localhost:8088
      - service: http_status:404
""".trimIndent())
```

**Phase 4** — Resolve edge IPs from Java:

```kotlin
val edgeIps = mutableListOf<String>()
for (host in listOf("region1.v2.argotunnel.com", "region2.v2.argotunnel.com")) {
    InetAddress.getAllByName(host)
        .filter { it is Inet4Address }
        .forEach { edgeIps.add("${it.hostAddress}:7844") }
}
```

**Phase 5** — Run cloudflared with zero DNS needed:

```kotlin
val cmd = mutableListOf(binary.absolutePath, "tunnel",
    "--config", configFile.absolutePath,
    "--edge-ip-version", "4",
    "--no-autoupdate")

for (ip in edgeIps.take(4)) cmd.addAll(listOf("--edge", ip))
cmd.addAll(listOf("run", tunnelId))

ProcessBuilder(cmd).directory(context.cacheDir).redirectErrorStream(true).start()
```

> 🎉 **The tunnel URL is known INSTANTLY from Phase 1** — no stdout parsing needed!

---

### Problem 4: Edge Discovery Fails Too ![](https://img.shields.io/badge/High-FF9800?style=flat-square)

| What | Details |
|------|---------|
| ❌ **Problem** | After registration, cloudflared needs SRV records for edge servers |
| 💀 **Error** | `lookup _v2-origintunneld._tcp.argotunnel.com on [::1]:53: refused` |
| ✅ **Solution** | Resolve edge hostnames from Java, pass via `--edge` flag |

Already handled in Phase 4 above. The key insight: **cloudflared needs DNS for TWO things** — the API call AND edge discovery. You must handle both from Java.

---

### Problem 5: --edge Flag Format ![](https://img.shields.io/badge/Medium-FF9800?style=flat-square)

| What | Details |
|------|---------|
| ❌ **Problem** | Passing comma-separated IPs to `--edge` |
| 💀 **Error** | `too many colons in address` |
| ✅ **Solution** | One `--edge` per address |

```bash
# ❌ WRONG
--edge 198.41.192.77:7844,198.41.192.107:7844

# ✅ RIGHT
--edge 198.41.192.77:7844 --edge 198.41.192.107:7844
```

---

## 🏗️ Complete Architecture

```
┌─────────────────────────────────────────────────┐
│              📱 ANDROID APP                      │
│                                                  │
│  ┌──────────────────────────────────────────┐   │
│  │    ☕ Java/Kotlin  (DNS works here!)      │   │
│  │                                          │   │
│  │  1️⃣ POST api.trycloudflare.com/tunnel    │   │
│  │     → tunnel ID, hostname, credentials   │   │
│  │                                          │   │
│  │  2️⃣ InetAddress.getAllByName(             │   │
│  │       "region1.v2.argotunnel.com")       │   │
│  │     → edge server IPs                    │   │
│  │                                          │   │
│  │  3️⃣ Write creds.json + config.yml        │   │
│  └────────────────┬─────────────────────────┘   │
│                   ↓                              │
│  ┌──────────────────────────────────────────┐   │
│  │  🔵 cloudflared (libcloudflared.so)       │   │
│  │     from: nativeLibraryDir (exec ✅)      │   │
│  │                                          │   │
│  │  --config config.yml                     │   │
│  │  --edge 198.41.192.77:7844               │   │
│  │  --edge-ip-version 4                     │   │
│  │  --no-autoupdate                         │   │
│  │  run <tunnel-id>                         │   │
│  │                                          │   │
│  │  → Direct IP connection. ZERO DNS. ✅     │   │
│  └────────────────┬─────────────────────────┘   │
│                   ↓                              │
│  ┌──────────────────────────────────────────┐   │
│  │     🖥️ Your Local Server (:8088)          │   │
│  └──────────────────────────────────────────┘   │
└─────────────────────────────────────────────────┘
                    ↓
    🌍 https://xxx.trycloudflare.com
         accessible worldwide!
```

---

## ✅ Quick Checklist

| # | Step | Done? |
|---|------|-------|
| 1 | `libcloudflared.so` in `jniLibs/arm64-v8a/` | ☐ |
| 2 | `android:extractNativeLibs="true"` in manifest | ☐ |
| 3 | `useLegacyPackaging = true` in build.gradle | ☐ |
| 4 | API call to `trycloudflare.com` done from **Java** | ☐ |
| 5 | Edge IPs resolved from **Java** | ☐ |
| 6 | Credentials + config written to `cacheDir` | ☐ |
| 7 | Separate `--edge IP:port` flags (not comma-separated) | ☐ |
| 8 | `--edge-ip-version 4` flag | ☐ |
| 9 | `--no-autoupdate` flag | ☐ |
| 10 | `INTERNET` permission in manifest | ☐ |

---

## ⚠️ Non-Fatal Errors (Ignore These)

After tunnel connects, you'll see these. They're **harmless**:

| Error | Why It's Fine |
|-------|-------------|
| `Failed to fetch features ... cfd-features.argotunnel.com` | Optional feature flags — tunnel works without them |
| `GID not within ping_group_range` | Android restricts ping — tunnel doesn't need it |
| `open /proc/sys/net/ipv4/ping_group_range: permission denied` | Same — irrelevant to tunnel operation |

---

## ❓ FAQ

| Question | Answer |
|----------|--------|
| 🔄 **URL changes on restart?** | Quick Tunnels: yes. Named Tunnels (with token): no |
| 📦 **APK size impact?** | +37MB. Use Git LFS for the repo |
| 🖥️ **x86 emulator?** | No — ARM64 binary only. Need real device or ARM64 emulator |
| 🔨 **Why not `GOOS=android`?** | Would fix DNS natively, but requires compiling cloudflared from Go source |
| 🔀 **ngrok instead?** | Same jniLibs approach works. ngrok may handle DNS better |
| 👤 **Need Cloudflare account?** | Quick Tunnels: **no**. Named Tunnels: yes |

---

## 📊 Bugs We Hit

> All documented in [BUGS.md](https://github.com/ashokvarmamatta/ZeroClawAndroid/blob/main/bugs.md)

| Bug | Severity | Problem | Fix |
|-----|----------|---------|-----|
| BUG-25 | ![](https://img.shields.io/badge/Critical-F44336?style=flat-square) | W^X policy blocks execution | Bundle in jniLibs |
| BUG-26 | ![](https://img.shields.io/badge/Critical-F44336?style=flat-square) | Go DNS broken on Android | Java-side DNS |
| BUG-27 | ![](https://img.shields.io/badge/High-FF9800?style=flat-square) | HTTPS_PROXY ignored | Java-side API call |
| BUG-28 | ![](https://img.shields.io/badge/Critical-F44336?style=flat-square) | Edge discovery DNS fails | Java edge resolution |
| BUG-29 | ![](https://img.shields.io/badge/High-FF9800?style=flat-square) | --edge comma format | Separate flags |

---

<div align="center">

### Built for [ZeroClaw Android](https://github.com/ashokvarmamatta/ZeroClawAndroid)

### By [Ashok Varma Matta](https://github.com/ashokvarmamatta)

<p align="center">
  <a href="https://github.com/ashokvarmamatta"><img src="https://img.shields.io/badge/GitHub-181717?style=for-the-badge&logo=github&logoColor=white" alt="GitHub" /></a>&nbsp;
  <a href="https://www.linkedin.com/in/ashokvarmamatta"><img src="https://img.shields.io/badge/LinkedIn-0077B5?style=for-the-badge&logo=linkedin&logoColor=white" alt="LinkedIn" /></a>&nbsp;
  <a href="https://ashokvarmamatta.github.io/portfolio/"><img src="https://img.shields.io/badge/Portfolio-00D4AA?style=for-the-badge&logo=googlechrome&logoColor=white" alt="Portfolio" /></a>&nbsp;
  <a href="mailto:mashokvarma1997@gmail.com"><img src="https://img.shields.io/badge/Email-D14836?style=for-the-badge&logo=gmail&logoColor=white" alt="Email" /></a>
</p>

<br/>

*If this saved you time, star the repo!* ⭐

<img src="https://capsule-render.vercel.app/api?type=waving&color=0:0d1117,50:F38020,100:7C5CFC&height=80&section=footer" width="100%"/>

</div>
