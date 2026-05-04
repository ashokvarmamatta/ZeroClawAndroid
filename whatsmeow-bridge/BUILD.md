# whatsmeow-bridge — build & bundle

This directory holds the Go source for `libwhatsmeow.so`, the binary that
ZeroClaw spawns at runtime to talk to WhatsApp's multi-device protocol via
the [whatsmeow](https://github.com/tulir/whatsmeow) library.

The final `.so` lives at `../app/src/main/jniLibs/arm64-v8a/libwhatsmeow.so`,
side by side with `libcloudflared.so`.

> ⚠️ Why "lib*.so" — Android only extracts files matching `lib*.so` from the APK
> into a place where they can be `exec()`'d at runtime. The file isn't actually
> a shared library — it's a position-independent executable. We just abuse the
> naming so Android does the right thing. Same trick the existing
> `libcloudflared.so` uses (see `app/build.gradle.kts → packaging.jniLibs`).

> ⚠️ This is **unofficial**. The whatsmeow protocol speaks the same wire format
> as WhatsApp Web. Account ban risk exists, especially for new numbers and high
> message volumes. Do not use a primary phone number for production.

---

## 1. Prerequisites

- Go ≥ 1.22 (`go version`)
- Android NDK r25+ (sets `aarch64-linux-android21-clang` on `$PATH`)
- ~500MB free disk for module cache

On Windows the NDK is at `%LOCALAPPDATA%\Android\Sdk\ndk\<version>\toolchains\llvm\prebuilt\windows-x86_64\bin`.

## 2. Resolve dependencies

```bash
cd whatsmeow-bridge
go mod tidy
```

This pulls the latest tagged whatsmeow + go-sqlite3 + indirect deps.

## 3. Cross-compile to arm64 Android

The output **must** be CGO-enabled because go-sqlite3 needs libc.

### macOS / Linux

```bash
NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64/bin"

CGO_ENABLED=1 \
GOOS=android GOARCH=arm64 \
CC="$NDK_BIN/aarch64-linux-android21-clang" \
go build -trimpath -buildmode=pie -ldflags='-s -w' \
  -o ../app/src/main/jniLibs/arm64-v8a/libwhatsmeow.so .
```

### Windows (PowerShell)

```powershell
$NDK_BIN = "$env:LOCALAPPDATA\Android\Sdk\ndk\27.0.11718014\toolchains\llvm\prebuilt\windows-x86_64\bin"

$env:CGO_ENABLED = "1"
$env:GOOS = "android"
$env:GOARCH = "arm64"
$env:CC = "$NDK_BIN\aarch64-linux-android21-clang.cmd"

go build -trimpath -buildmode=pie -ldflags='-s -w' `
  -o ../app/src/main/jniLibs/arm64-v8a/libwhatsmeow.so .
```

The result is ~25–35MB unstripped, ~15MB after `-s -w`.

## 4. Verify

```bash
file ../app/src/main/jniLibs/arm64-v8a/libwhatsmeow.so
# expected: ELF 64-bit LSB pie executable, ARM aarch64, …
```

## 5. (Optional) armeabi-v7a for older devices

ZeroClaw's minSdk is 26 (Android 8.0+) and almost every device is arm64 by then,
so 32-bit is **not bundled by default**. If you really need armv7 add a second
build with `GOARCH=arm GOARM=7 CC=…/armv7a-linux-androideabi21-clang` and copy
the output to `app/src/main/jniLibs/armeabi-v7a/libwhatsmeow.so`.

## 6. Run after building

1. Drop the `.so` into `app/src/main/jniLibs/arm64-v8a/`.
2. Reinstall the APK (`./gradlew installDebug`).
3. In ZeroClaw → Settings → WhatsApp (Native), tap **Start** → scan the QR or
   request the 8-digit pair code.

The session DB is stored at `<filesDir>/whatsmeow/session.db` and survives app
upgrades. Delete it to force a fresh QR.

---

## Protocol cheat-sheet

The bridge talks line-based UTF-8 over stdio. Anything containing whitespace or
newlines is base64-encoded (see comments in `main.go`).

```
stdin   → PAIR <e164_phone>
stdin   → SEND <jid> <b64_text>
stdin   → STOP
stdout  ← STATUS connecting | qr_ready | pair_ready | connected <jid> | …
stdout  ← QR <text>
stdout  ← PAIRCODE <8_digits>
stdout  ← MSG <jid> <b64_pushname> <b64_text>
stdout  ← LOG <b64_text>
```

The Kotlin side that consumes this is `WhatsAppNativeManager.kt`.
