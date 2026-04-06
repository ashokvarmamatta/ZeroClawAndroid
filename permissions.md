# ZeroClawAndroid — Permissions & Compliance

> **Purpose:** Track every Android permission declared, why it's needed, and Play Store compliance status.
> **Rule:** Update this file whenever any permission is added, removed, or changed.

---

## Declared Permissions

### Network & System

| Permission | Since | Required By | Justification | Runtime? |
|-----------|-------|-------------|---------------|----------|
| `INTERNET` | Phase 1 | All network operations | Required for LLM API calls, Telegram/WhatsApp/Discord/etc polling, web search tools, API data sources | No (normal) |
| `ACCESS_NETWORK_STATE` | Phase 1 | Connectivity checks | Determines if device is online before making API calls; used by failover logic | No (normal) |
| `FOREGROUND_SERVICE` | Phase 6 | ZeroClawService | Keeps the AI agent service alive in background with persistent notification | No (normal) |
| `FOREGROUND_SERVICE_DATA_SYNC` | Phase 172 | ZeroClawService | Android 14+ requires declaring foreground service type; `dataSync` covers bot polling and LLM calls | No (normal) |
| `RECEIVE_BOOT_COMPLETED` | Phase 13 | BootReceiver | Auto-starts service on device reboot so user doesn't have to manually open app | No (normal) |
| `WAKE_LOCK` | Phase 6 | ZeroClawService | Prevents CPU sleep during message processing; ensures polling loops don't stall | No (normal) |
| `POST_NOTIFICATIONS` | Phase 127 | RichNotifications | Android 13+ requires runtime permission to show notifications | **Yes (runtime)** |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Phase 130 | AutoRecovery | Requests exemption from Doze mode so the foreground service isn't throttled | No (special) |

### Device Access

| Permission | Since | Required By | Justification | Runtime? |
|-----------|-------|-------------|---------------|----------|
| `READ_CALENDAR` | Phase 91 | CalendarTool | Reads device calendar events when user asks "what's on my calendar" | **Yes (runtime)** |
| `WRITE_CALENDAR` | Phase 91 | CalendarTool | Creates calendar events when user asks "add meeting at 3pm" | **Yes (runtime)** |
| `READ_CONTACTS` | Phase 92 | ContactsTool | Searches contacts when user asks "find John's phone number" | **Yes (runtime)** |
| `ACCESS_FINE_LOCATION` | Phase 93 | LocationTool | Provides GPS coordinates for "where am I" and nearby places | **Yes (runtime)** |
| `ACCESS_COARSE_LOCATION` | Phase 93 | LocationTool | Fallback for approximate location when fine location denied | **Yes (runtime)** |
| `USE_BIOMETRIC` | Phase 128 | BiometricLock | Fingerprint/face authentication to protect app access | No (normal) |
| `USE_FINGERPRINT` | Phase 128 | BiometricLock | Legacy fingerprint API for older devices (pre-Android 9) | No (normal) |
| `VIBRATE` | Phase 127 | RichNotifications | Vibration feedback for incoming message notifications | No (normal) |

---

## Play Store Compliance Notes

### Foreground Service Declaration
- **Type:** `dataSync`
- **Notification:** Persistent notification shown while service runs (channel: `zeroclaw_service`, ID: 1001)
- **Play Store requirement:** Must declare `foregroundServiceType` in manifest (done in Phase 172)

### Sensitive Permissions Requiring Play Store Declaration
| Permission | Play Store Category | Declaration Needed? | Status |
|-----------|-------------------|-------------------|--------|
| `READ_CALENDAR` / `WRITE_CALENDAR` | Calendar | Yes — must explain in Data Safety form | Pending |
| `READ_CONTACTS` | Contacts | Yes — must explain in Data Safety form | Pending |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Location | Yes — must explain in Data Safety form + background location policy if used in background | Pending |
| `POST_NOTIFICATIONS` | — | No special declaration (standard Android 13+ requirement) | OK |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | — | May be flagged in review; must justify | Pending |

### Data Safety Form (Play Store)
When publishing to Play Store, declare:
- **Data collected:** None (all data stays on device)
- **Data shared:** None (API keys sent only to user-configured providers)
- **Calendar/Contacts/Location:** Accessed on-device only, never transmitted to third parties
- **Encryption:** API keys stored in DataStore (optionally EncryptedSharedPreferences)

### Background Location
- `ACCESS_FINE_LOCATION` is currently used only when user explicitly triggers LocationTool via chat
- **Not** used in background polling — no `ACCESS_BACKGROUND_LOCATION` declared
- If background location is ever needed, must submit separate Play Store review

### Biometric
- Uses AndroidX Biometric library (supports fingerprint, face, iris depending on device)
- Falls back to device credential (PIN/pattern/password) if biometric unavailable
- No biometric data is stored or transmitted

---

## Permission Request Flow

All runtime permissions are requested **only when the corresponding tool is first used**, not at app startup:

1. User sends message like "what's on my calendar today"
2. LLM decides to use `calendar_read` tool
3. CalendarTool checks `READ_CALENDAR` permission
4. If not granted → returns error message asking user to grant permission in Settings
5. If granted → reads calendar and returns results

This follows Android best practices: request permissions in context, not upfront.

---

## Permissions NOT Used (and why)

| Permission | Why Not |
|-----------|---------|
| `CAMERA` | No camera features; image analysis uses URLs or file paths |
| `RECORD_AUDIO` | STT uses Whisper API (uploads audio files), not live recording |
| `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` | Uses SAF (Storage Access Framework) for file picker; no broad storage access |
| `ACCESS_BACKGROUND_LOCATION` | Location only used on explicit user request, not in background |
| `SEND_SMS` / `READ_SMS` | Messaging done through app APIs (Telegram, WhatsApp, etc.), not SMS |
| `READ_PHONE_STATE` | Not needed for any current feature |
| `SYSTEM_ALERT_WINDOW` | No overlay UI; everything is in-app |

---

*Update this file whenever any permission is added, removed, or its justification changes.*
