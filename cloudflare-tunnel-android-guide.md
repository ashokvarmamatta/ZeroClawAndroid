# Running Cloudflare Tunnels on Android - The Complete Guide

> **TL;DR:** You CAN run `cloudflared` on Android, but Go's DNS is completely broken on Android. This guide shows every problem you'll hit and exactly how to solve each one.

## What This Guide Covers

You want to expose a local server running on an Android device to the internet using Cloudflare Tunnels. Sounds simple. It's not.

This guide documents **every problem we encountered** building this for [ZeroClaw Android](https://github.com/ashokvarmamatta/ZeroClawAndroid) and the **exact solution** for each one. If you're building an Android app that needs a public URL, this will save you days of debugging.

---

## The Goal

```
Your Android App (localhost:8088)
         |
    Cloudflare Tunnel
         |
https://random-name.trycloudflare.com  <-- accessible from anywhere
```

**Why?** Webhooks (Telegram, Twilio, Stripe), remote API access, sharing a local dev server, etc.

---

## Problem 1: Getting the Binary

### The Problem
`cloudflared` is a Go binary. Cloudflare only publishes Linux/Mac/Windows builds. No Android build exists.

### The Solution
Android IS Linux under the hood. The `cloudflared-linux-arm64` binary runs on Android!

```bash
# Download the ARM64 Linux binary
curl -L -o libcloudflared.so \
  https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64
```

> **Note:** We name it `libcloudflared.so` - this is important (see Problem 2).

---

## Problem 2: Can't Execute Downloaded Binaries (W^X Policy)

### The Problem
Android 10+ enforces **W^X (Write XOR Execute)** - app data directories are mounted with `noexec`.

```kotlin
// This FAILS on Android 10+
val binary = File(context.filesDir, "cloudflared")
binary.setExecutable(true)
ProcessBuilder(binary.absolutePath, ...).start()
// -> error=13, Permission denied
```

You also can't use the dynamic linker:
```
/system/bin/linker64 /data/.../cloudflared
// -> "unexpected e_type: 2" (expects shared lib, got executable)
```

### The Solution
**Bundle the binary as a native library in the APK.**

Android extracts `jniLibs/` to `nativeLibraryDir` on install - that directory HAS execute permission.

#### Step 1: Place the binary
```
app/src/main/jniLibs/arm64-v8a/libcloudflared.so
```

#### Step 2: Force extraction (build.gradle.kts)
```kotlin
android {
    packaging {
        jniLibs {
            useLegacyPackaging = true  // Force extraction to disk
        }
    }
}
```

#### Step 3: Force extraction (AndroidManifest.xml)
```xml
<application
    android:extractNativeLibs="true"
    ...>
```

> **Why both?** Modern Android keeps `.so` files compressed inside the APK by default. Both settings are needed to ensure the binary is extracted as a real file on disk.

#### Step 4: Execute from nativeLibraryDir
```kotlin
val binary = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")
// binary.absolutePath -> /data/app/.../lib/arm64/libcloudflared.so
// This path HAS execute permission!
ProcessBuilder(binary.absolutePath, "tunnel", "--url", "http://localhost:8088").start()
```

#### Git LFS (optional but recommended)
The binary is ~37MB. Track it with Git LFS:
```bash
git lfs track "app/src/main/jniLibs/arm64-v8a/libcloudflared.so"
```

---

## Problem 3: Go's DNS is Completely Broken on Android

### The Problem
This is the **big one**. When you run cloudflared, you'll see:

```
lookup api.trycloudflare.com on [::1]:53: read udp [::1]:...->[::1]:53: connection refused
```

**Root cause:** Go's DNS resolver reads `/etc/resolv.conf` to find DNS servers. This path is **hardcoded** in Go's source code. On Android, **this file doesn't exist**. Go falls back to `127.0.0.1:53` and `[::1]:53` - neither has a DNS server.

### What Doesn't Work

| Approach | Why It Fails |
|----------|-------------|
| Create `/etc/resolv.conf` | `/etc/` is read-only (symlink to `/system/etc/`) |
| `RES_CONF` env var | Not a real Go thing - Go hardcodes the path |
| `GODEBUG=netdns=cgo` | cloudflared is statically linked, no cgo |
| DNS relay on port 53 | `EACCES` - non-root can't bind ports below 1024 |
| `HTTPS_PROXY` env var | cloudflared uses custom `http.Transport{}` with `Proxy: nil`, ignoring proxy env vars |
| HTTP CONNECT proxy | Same issue - cloudflared bypasses it |
| Build with `GOOS=android` | Would fix DNS, but you'd need to compile cloudflared yourself |

### The Solution: Handle DNS from Java, Bypass Go Entirely

**Java's `InetAddress.getByName()` uses Android's native DNS resolver, which works perfectly.** The trick is to do all DNS-requiring work from Java and pass the results to cloudflared.

#### Phase 1: Register the tunnel from Java

cloudflared's Quick Tunnel normally does `POST https://api.trycloudflare.com/tunnel`. We do this from Java instead:

```kotlin
// Java/Kotlin - DNS works here!
val client = OkHttpClient()
val request = Request.Builder()
    .url("https://api.trycloudflare.com/tunnel")
    .post("".toRequestBody("application/json".toMediaType()))
    .build()

val response = client.newCall(request).execute()
val json = JSONObject(response.body!!.string())
val result = json.getJSONObject("result")

val tunnelId = result.getString("id")           // UUID
val hostname = result.getString("hostname")      // xxx.trycloudflare.com
val accountTag = result.getString("account_tag") // account identifier
val secret = result.getString("secret")          // base64 secret
```

#### Phase 2: Write credentials file

```kotlin
val credsFile = File(context.cacheDir, "tunnel_creds.json")
credsFile.writeText(JSONObject().apply {
    put("AccountTag", accountTag)
    put("TunnelID", tunnelId)
    put("TunnelSecret", secret)
}.toString())
```

#### Phase 3: Write config file

```kotlin
val configFile = File(context.cacheDir, "tunnel_config.yml")
configFile.writeText("""
    tunnel: $tunnelId
    credentials-file: ${credsFile.absolutePath}
    protocol: http2
    ingress:
      - hostname: $hostname
        service: http://localhost:8088
      - service: http_status:404
""".trimIndent())
```

#### Phase 4: Resolve edge IPs from Java

cloudflared also needs DNS for edge server discovery (`_v2-origintunneld._tcp.argotunnel.com` SRV record). We resolve the edge hostnames from Java:

```kotlin
val edgeIps = mutableListOf<String>()
for (host in listOf("region1.v2.argotunnel.com", "region2.v2.argotunnel.com")) {
    InetAddress.getAllByName(host)
        .filter { it is Inet4Address }
        .forEach { edgeIps.add("${it.hostAddress}:7844") }
}
```

#### Phase 5: Run cloudflared with everything pre-resolved

```kotlin
val cmd = mutableListOf(
    binary.absolutePath,
    "tunnel",
    "--config", configFile.absolutePath,
    "--edge-ip-version", "4",
    "--no-autoupdate"
)

// Pass each edge IP as a separate --edge flag
for (ip in edgeIps.take(4)) {
    cmd.addAll(listOf("--edge", ip))
}

cmd.addAll(listOf("run", tunnelId))

val process = ProcessBuilder(cmd)
    .directory(context.cacheDir)
    .redirectErrorStream(true)
    .start()
```

**The tunnel URL is already known from Step 1** - no need to parse cloudflared's output!

---

## Problem 4: --edge Flag Format

### The Problem
```
--edge 198.41.192.77:7844,198.41.192.107:7844
// -> "too many colons in address"
```

### The Solution
One `--edge` flag per address:
```
--edge 198.41.192.77:7844 --edge 198.41.192.107:7844
```

---

## Problem 5: Non-Fatal Errors (Ignore These)

After the tunnel connects, you'll see some harmless errors:

```
ERR Failed to fetch features ... lookup cfd-features.argotunnel.com on [::1]:53
WRN The user running cloudflared process has a GID ... ping_group_range
```

**These are safe to ignore.** Features fetch is optional, and ping isn't needed for tunnel operation.

---

## Complete Architecture

```
+-----------------------------------------------------+
|                   ANDROID APP                        |
|                                                      |
|  +----------------------------------------------+   |
|  |        Java/Kotlin Layer (DNS works!)         |   |
|  |                                               |   |
|  |  1. OkHttp POST api.trycloudflare.com/tunnel  |   |
|  |     -> Gets tunnel ID, hostname, credentials  |   |
|  |                                               |   |
|  |  2. InetAddress.getAllByName(                  |   |
|  |       "region1.v2.argotunnel.com")            |   |
|  |     -> Gets edge server IPs                   |   |
|  |                                               |   |
|  |  3. Write credentials.json + config.yml       |   |
|  +----------------------+------------------------+   |
|                         |                            |
|                         v                            |
|  +----------------------------------------------+   |
|  |      cloudflared (libcloudflared.so)          |   |
|  |      From: nativeLibraryDir (exec OK)         |   |
|  |                                               |   |
|  |  cloudflared tunnel                           |   |
|  |    --config /cache/tunnel_config.yml          |   |
|  |    --edge 198.41.192.77:7844                  |   |
|  |    --edge 198.41.192.107:7844                 |   |
|  |    --edge-ip-version 4                        |   |
|  |    --no-autoupdate                            |   |
|  |    run <tunnel-id>                            |   |
|  |                                               |   |
|  |  -> Connects to edge using pre-resolved IPs   |   |
|  |  -> NO DNS needed!                            |   |
|  +----------------------+------------------------+   |
|                         |                            |
|                         v                            |
|  +----------------------------------------------+   |
|  |         Your Local Server (:8088)             |   |
|  +----------------------------------------------+   |
|                                                      |
+-----------------------------------------------------+
                          |
                          v
            https://xxx.trycloudflare.com
                 (accessible worldwide)
```

---

## Quick Checklist

- [ ] `libcloudflared.so` in `app/src/main/jniLibs/arm64-v8a/`
- [ ] `android:extractNativeLibs="true"` in AndroidManifest.xml
- [ ] `jniLibs.useLegacyPackaging = true` in build.gradle.kts
- [ ] API call to `api.trycloudflare.com/tunnel` done from Java (not cloudflared)
- [ ] Edge IPs resolved from Java via `InetAddress.getAllByName()`
- [ ] Credentials + config written to `cacheDir`
- [ ] Separate `--edge IP:port` flags (not comma-separated)
- [ ] `--edge-ip-version 4` flag set
- [ ] `--no-autoupdate` flag set (auto-update fails without DNS)
- [ ] `INTERNET` permission in AndroidManifest.xml

---

## Settings UI (Optional)

We added three tunnel modes:

| Mode | Description |
|------|-------------|
| **Off** | No tunnel - LAN access only |
| **Quick Tunnel** (default) | Free random URL via `trycloudflare.com` - no account needed |
| **Named Tunnel** | Persistent URL - requires Cloudflare Zero Trust token |

---

## Testing

We included a `test_tunnel.html` file that:
1. Takes a tunnel URL as input
2. Tests connectivity via `GET /v1/models`
3. Sends chat messages via `POST /v1/chat/completions`
4. Shows response with model name, tokens, and latency

Open it in any browser to verify your tunnel works.

---

## FAQ

**Q: Does the tunnel URL change on restart?**
A: Quick Tunnels generate a new URL each restart. Use Named Tunnels (with Cloudflare token) for a persistent URL.

**Q: How big does the APK get?**
A: cloudflared adds ~37MB to the APK. Use Git LFS for the repo, and consider app bundles for Play Store distribution.

**Q: Does this work on x86 Android emulators?**
A: No - the binary is ARM64 only. You need a real device or ARM64 emulator.

**Q: Why not compile cloudflared with GOOS=android?**
A: That would fix DNS natively (Go uses Android's resolver for GOOS=android). But it requires building cloudflared from source with Go, which most Android developers don't have set up. Our approach works with the official pre-built binary.

**Q: Can I use ngrok instead?**
A: Yes! The same binary-bundling approach works. Just place the ngrok ARM64 binary as `libngrok.so` in jniLibs. ngrok has its own DNS handling that may work better on Android.

---

## Credits

Built for [ZeroClaw Android](https://github.com/ashokvarmamatta/ZeroClawAndroid) by [Ashok Varma Matta](https://github.com/ashokvarmamatta).

Bugs encountered and documented in [BUGS.md](https://github.com/ashokvarmamatta/ZeroClawAndroid/blob/main/bugs.md) (BUG-25 through BUG-29).

---

*If this saved you time, star the repo!*
