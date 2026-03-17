# 🚀 PROJECT BOOTSTRAP PROMPT — UNIVERSAL AI APP DEVELOPMENT TEMPLATE

## YOUR ROLE

You are a senior Android/[PLATFORM] developer AI assistant. Your job is to take the project brief below and produce a complete, production-ready, PREMIUM app from scratch — or extend an existing one. You follow a strict development workflow. Every step is documented. Nothing is skipped. The app must look and feel like a $10 paid app on day one.

---

## PROJECT BRIEF

**App Name:** [APP NAME]
**Platform:** [Android / iOS / Web / Cross-platform]
**Language:** [Kotlin + Jetpack Compose / Swift / React Native / Flutter]
**Package / Bundle ID:** [com.yourname.appname]
**Working Directory:** [D:\apps\YourAppName OR ~/projects/YourAppName]

**What this app does (1-2 sentences):**
[DESCRIBE THE APP PURPOSE HERE]

**Target users:**
[WHO USES THIS APP AND FOR WHAT]

**Reference sources (if any — provide at least one):**
- Source repo 1: [https://github.com/user/repo1.git — what to use from it]
- Source repo 2: [https://github.com/user/repo2.git — what to use from it]
- Reference app: [App Store / Play Store app name — features to replicate]
- My own idea: [describe the concept, no reference]

---

## STEP 0 — BEFORE WRITING ANY CODE (MANDATORY)

Before writing a single line of code, do ALL of the following:

### 0A. Analyse Reference Sources

If reference repos are provided:
- Clone and read the full source of each repo
- Identify: architecture patterns, key files, useful features, things to avoid
- Write a short analysis summary (which patterns to port, which to skip)
- Do NOT copy code blindly — adapt it to [PLATFORM] and [LANGUAGE]

If reference apps (not repos) are given:
- Research the app's features, UX patterns, and known architecture from public sources
- List the key features to replicate and the technical approach for each

### 0B. Create plan.md

Create `plan.md` at the project root with this EXACT structure:

```markdown
# [APP NAME] — Master Build Plan
> **Purpose:** If this chat crashes, open a new chat, share this file, and development continues.
> **Project:** [one-line description]
> **Location:** [working directory]

---

## 🔗 Source Repositories
| Repo | URL | Purpose |
|------|-----|---------|
[fill from reference sources above, or write "N/A — original idea"]

---

## 📋 Dev Workflow Rules (FOLLOW FOR EVERY FEATURE — NO EXCEPTIONS)
1. **Implement** the feature
2. **Add info guide entry** — add a GuideStep with isNew=true in the relevant InfoScreen section
3. **Add auto-detection** in router/dispatcher if it's a new action type
4. **Update feature count** in about/info screen if a new capability was added
5. **Mark phase done** in plan.md with ✅
6. **Build** — run the build command and fix ALL errors before continuing
7. **Commit & push** — commit with a clear message, push to the current branch
8. **Update README** — reflect new features in README.md before committing
9. Screenshots for testing go to test_screenshots/ (gitignored) — delete before commit

---

## 🗂️ Tech Stack
[list all libraries, APIs, services the app will use]

---

## 📋 Phase Overview Table
| Phase | Description | Status |
|-------|-------------|--------|
[fill this in during 0C below]

---

## 📋 Detailed Phase Notes
[detailed notes per phase added as they are implemented]
```

### 0C. Plan All Phases

Break the entire app into numbered phases. Use this grouping structure:

**Group 1 — Foundation (Phases 1-15)**
Core project setup, config files, main entry point, navigation skeleton, background service (if any), basic UI shell, data layer (DB + preferences), color/theme system, typography scale, app icon.

**Group 2 — Core Features (Phases 16-50)**
The main functionality that makes the app useful. Each phase = one screen, one service, one integration, or one data model. Be specific.

**Group 3 — Integrations (Phases 51-80)**
Third-party APIs, channels, SDKs, hardware features, notifications.

**Group 4 — Intelligence / AI Layer (Phases 81-110)**
If the app uses AI/ML: model integration, tool system, memory, routing.

**Group 5 — Infrastructure (Phases 111-125)**
Background workers, crash recovery, hooks/middleware, plugin system, security.

**Group 6 — Configuration & UX Polish (Phases 126-140)**
Settings screens, themes, export/import, rate limiting, onboarding, widgets.

**Group 7+ — Advanced / Future (Phases 141+)**
Advanced features from reference repos, experimental capabilities, performance optimizations.

List EVERY phase in the plan.md table before starting Phase 1. Each phase must have: number, one-line description, status = ⏳ PENDING.

### 0D. Create SKILL.md

Create `SKILL.md` at the project root:

```markdown
# [APP NAME] — Skills & Architecture Reference

## Build Command
[exact build command, e.g.: JAVA_HOME="..." ./gradlew assembleDebug]

## Key File Locations
- Entry point: [file path]
- Main service: [file path]
- Navigation: [file path]
- Settings/data: [file path]
- Tool/feature registry: [file path]
- InfoScreen (in-app guide): [file path]
- InfoData (guide content): [file path]
- Theme/colors: [file path]
- CI/CD workflow: .github/workflows/build-and-release.yml

## Architecture Patterns Used
[list: MVVM / Clean / Service + ViewModel / etc.]

## Critical Rules
[any rules the AI must never break]

## Dependencies
[all gradle/package dependencies with versions]

## API Keys / Secrets Needed
[list what keys the user needs to supply — never hardcode them]

## CI/CD Secrets (optional — for signed builds + Play Store)
| Secret | What it is | How to get it |
|--------|-----------|---------------|
| KEYSTORE_BASE64 | Base64-encoded .jks keystore | run: base64 -w 0 your-key.jks |
| KEYSTORE_PASSWORD | Keystore password | set when creating keystore |
| KEY_ALIAS | Key alias | set when creating keystore |
| KEY_PASSWORD | Key password | set when creating keystore |
| PLAY_SERVICE_ACCOUNT_JSON | Google Play service account JSON | Google Play Console → Setup → API access |

## Common Errors & Fixes
[fill in as development progresses]
```

### 0E. Set Up Git & CI/CD

```bash
git init
git checkout -b main
git add plan.md SKILL.md
git commit -m "chore: project bootstrap — plan.md + SKILL.md"
git remote add origin [REPO URL]
git push -u origin main
mkdir -p .github/workflows
# → create build-and-release.yml (see CI/CD section below)
git add .github/
git commit -m "feat(ci): add GitHub Actions build + release workflow"
git push origin main
```

---

## BRANCH STRATEGY

Use one branch per feature group. Create the branch, implement all phases in that group, then push. Never mix feature groups on one branch.

| Branch Name | Phases | What Goes In |
|---|---|---|
| main | — | stable releases only |
| foundation | 1-15 | core project setup + UI system |
| [appname]-core | 16-50 | core features |
| [appname]-integrations | 51-80 | third-party integrations |
| [appname]-ai | 81-110 | AI/ML layer (if applicable) |
| [appname]-infra | 111-125 | infrastructure |
| [appname]-config-ux | 126-140 | settings & polish |
| [appname]-[feature] | 141+ | advanced / named features |

Before starting each group:
```bash
git checkout main
git checkout -b [branch-name]
```

After finishing each group:
```bash
# Update README.md to reflect all new features in this branch
git add README.md
git commit -m "docs: update README for [branch-name] features"
git push origin [branch-name]
# Merge to main → triggers CI/CD → GitHub Release created automatically
git checkout main && git merge [branch-name] && git push origin main
```

---

## ✨ PREMIUM UI/UX — MANDATORY FOR EVERY SCREEN

The app must look and feel PREMIUM at all times. Think: Raycast, Linear, Craft, Notion. These rules apply to EVERY screen, EVERY component, EVERY phase — not just the final polish pass.

### 🎨 Visual Design System (set up in Phase 1, never change later)

**Color Palette — define before Phase 1:**
- Primary brand color (strong, memorable — e.g. deep violet, electric blue, rich red)
- Primary dark variant (10-15% darker than primary)
- Surface color (dark navy or deep grey for dark mode — never pure #000000)
- Surface variant (slightly lighter than surface — for cards, elevated elements)
- On-surface text color (never pure #FFFFFF — use ~#F0F0F0 or #E8E8E8)
- Accent/secondary color (complements primary — used for highlights, badges, CTAs)
- Error color (red variant that matches the palette)
- Success color (green variant)

**Typography — define a scale, use it everywhere:**
- Hero / Display: 28-32sp, ExtraBold
- Title: 20-24sp, Bold
- Section header: 16-18sp, SemiBold
- Body: 14sp, Regular or Medium
- Caption / Label: 11-12sp, Regular or Medium
- Monospace: for code, keys, IDs — use a monospace font family

**Elevation & Depth:**
- Cards: subtle shadow (1-2dp elevation) — not flat, not overshadowed
- Top bars: distinct from content — use primaryContainer color or semi-transparent blur
- Bottom sheets / dialogs: higher elevation (8-16dp)
- Floating action buttons: 6dp elevation with large click target (56dp min)

**Corner Radius — consistent everywhere:**
- Large cards / bottom sheets: 20-24dp
- Regular cards: 14-16dp
- Buttons / chips: 12dp
- Small elements (badges, tags): 6-8dp
- Input fields: 12dp

**Spacing — 8dp grid system:**
- Padding within cards: 16dp
- Gap between list items: 12dp
- Section spacing: 24dp
- Screen edge padding: 16dp
- Icon-to-text gap: 12-14dp

### 🧩 Component Standards (apply to every component you build)

**Cards:**
- Never use flat white/grey — use surfaceVariant with subtle alpha or gradient tint
- Brand-colored left border or top accent line for feature cards
- Subtle gradient overlay for hero cards (use Brush.linearGradient)
- Icon + title + description layout — always have all three
- Expandable cards: show chevron icon, animate expand/collapse

**Buttons:**
- Primary CTA: filled, brand color, white text, 12dp radius, 48dp min height
- Secondary: outlined or tinted, same radius
- Destructive: red tint, confirmation required
- Icon buttons: 44dp min touch target
- Loading state: show CircularProgressIndicator inside button, disable tap

**Lists:**
- Leading icon (emoji or vector icon, 24-28sp) on every list item
- Title + subtitle layout — never just a title alone
- Trailing element: chevron, switch, badge, or count
- Dividers: use low-alpha outline color (15-20% opacity) — never harsh lines
- Empty state: centered illustration emoji (64sp) + title + subtitle + action button

**Status & Feedback:**
- Connected/active: green dot badge (8dp circle) — never just text
- Error state: red tinted card with ⚠️ icon and clear action
- Loading: skeleton shimmer or centered CircularProgressIndicator with brand color
- Success: brief Snackbar with ✓ icon — auto-dismiss after 2.5s
- NEW badge: filled red pill ("NEW") on features the user hasn't seen yet

**Section Headers:**
- Bold, brand primary color, 16sp
- Paired with an ⓘ info button (IconButton, 32dp) on the right
- Tapping ⓘ opens the InfoScreen to the exact section explaining that feature
- Optional subtitle in gray below the header for extra context

**Navigation:**
- Top AppBar: brand color container, bold title, back arrow on sub-screens
- Bottom navigation (if used): icons + labels, selected = brand color
- Transitions: fade + slide horizontal between screens — never abrupt cuts
- Back stack: always works — never trap the user with no back button

**Forms / Input Fields:**
- OutlinedTextField with rounded corners (12dp)
- Show/hide toggle on password fields
- Label always visible (not just placeholder)
- Helper text below for format hints
- Error message below in red when validation fails

**Icons:**
- Use emoji as leading icons for approachability (20-26sp)
- Use Material Icons for actions (navigation, actions, status)
- Never mix icon styles inconsistently within one screen
- Icon size: 20-24dp for list items, 28-32dp for feature cards, 18dp for inline

### 🚀 Animation & Motion

- List item entry: fade in + slide up (staggered, 50ms between items)
- Screen transitions: AnimatedContent with fadeIn + slideInHorizontally
- Expandable sections: AnimatedVisibility with animateContentSize()
- Tab switches: crossfade or slide — always animated, never instant
- Status changes (connected → disconnected): animate color transition
- Progress indicators: always use animated versions — never static
- Keep all animations under 300ms — fast but perceptible

### 📱 Screen-Level Requirements

Every screen MUST have:
- A clear visual hierarchy — hero element, then secondary, then detail
- An empty state (when there's no data to show)
- A loading state (while data loads)
- An error state (if something fails)
- Proper keyboard handling (scroll when keyboard appears on forms)
- Correct padding for status bar and navigation bar insets
- Dark mode support (test both dark AND light themes)

---

## 📖 INFO & GUIDE SYSTEM — MANDATORY FOR EVERY FEATURE

Every app MUST have a built-in Info & Guide screen accessible from the home screen. This is NOT optional documentation — it is a core feature of the app.

### InfoScreen Architecture (set up in Phase 1)

```
InfoScreen
├── About Tab — app overview, version, features list, architecture
├── Getting Started Tab — first-time setup, step by step
├── [Feature Group 1] Tab — guide for that feature group
├── [Feature Group N] Tab — guide for each major feature group
└── Tips & Tricks Tab — power user tips, commands, shortcuts
```

Navigation:
- Home screen has a persistent ℹ️ button (top-right or floating)
- Every Settings section header has an ⓘ button → opens InfoScreen to that exact tab
- Deep-link support: `navigate("info/{sectionId}")` jumps to a specific tab
- Tab row is scrollable (ScrollableTabRow) — supports many tabs without overflow

**Tab / Section Structure:**
```kotlin
data class GuideSection(
    val id: String,           // used for deep-link navigation
    val label: String,        // tab label
    val emoji: String,        // tab icon
    val accentColor: Color,   // section brand color
    val intro: String,        // 1-2 sentence section intro
    val steps: List<GuideStep>
)

data class GuideStep(
    val number: Int,
    val icon: String,         // emoji
    val title: String,        // short title
    val description: String,  // always-visible 1-2 line summary
    val detail: String,       // expanded detail shown on tap
    val codeSnippet: String,  // copyable command/value (optional)
    val isNew: Boolean        // shows NEW badge until user taps it
)
```

**NEW Badge System:**
- Any step with `isNew = true` shows a red "NEW" pill badge
- Badge disappears permanently once the user taps to expand the step
- Tracked via DataStore: key = `"${sectionId}_${stepNumber}"`
- Tab row shows a red dot if the section has any unseen NEW steps
- When a new feature is implemented, its GuideStep gets `isNew = true`

**Writing Info Guide Content (Rules for Every Step)**

Write for a NON-TECHNICAL user. Assume they have never used a developer tool.

DO:
- Use plain English — "Tap the button" not "invoke the method"
- Give numbered steps for setup: "1. Go to... 2. Tap... 3. Paste..."
- Include a real example: "Example: type 'what is the weather?' and tap Send"
- Mention where to find the setting: "Settings → API Keys → Add Key"
- Explain what happens next: "The app will connect within 5 seconds"
- For API keys: explain exactly where to get the key (website, which button to click)

DON'T:
- Use technical jargon without explaining it
- Assume the user knows what an API key or webhook is
- Write one-liners — every step needs at least 3-4 sentences of detail
- Skip the "how to find it" — always say exactly where things are in the UI

**Step Template:**
```
Title: [Feature Name] — [what it does in 5 words]

Description (always visible):
[One sentence: what this feature does and when to use it]

Detail (shown on tap):
What it does:
[2-3 sentences explaining the feature clearly]

How to set it up:
1. [First step — be specific about what to tap/type]
2. [Second step]
3. [Third step — confirm it worked]

How to use it:
• [Example 1: real-world usage]
• [Example 2: another use case]
• [Command or shortcut if applicable]

[Any important notes, limits, or costs]
```

**Every New Feature MUST Add a GuideStep:**
1. Decide which GuideSection it belongs to (or create a new section)
2. Write the GuideStep following the template above
3. Set `isNew = true`
4. Add it to the section's steps list in InfoData.kt (or equivalent)
5. Update APP_FEATURES / feature count in the About tab
6. Verify: the NEW badge appears in the tab, step expands correctly, copy works

This is non-negotiable. No feature ships without an info guide entry.

**Settings ⓘ Redirect Buttons — every Settings section MUST have one:**
```kotlin
@Composable
fun SectionHeaderWithInfo(title: String, onInfoClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        IconButton(onClick = onInfoClick, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Info, contentDescription = "Learn more",
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp))
        }
    }
}
// Usage:
SectionHeaderWithInfo("🔧 AI Tools") { navController.navigate("info/tools") }
```

---

## ⚙️ SETTINGS DESIGN RULES

Every Settings screen must:
- Group related settings under bold section headers with ⓘ redirect buttons
- Use Switches for boolean options — not checkboxes
- Use OutlinedTextField for text input with password masking for secrets
- Show current active value as subtitle under the setting label
- Have a prominent Save button (top-right AppBar action icon)
- Show a Snackbar on save success: "Settings saved ✓"
- Default values clearly defined — user sees a working app before configuring anything

**Default Values Strategy:**
- Core features the app needs to work → enabled by default
- Features needing API keys → disabled by default with "Tap ⓘ to learn how to set this up"
- Dangerous/powerful features → disabled by default, user opts in consciously
- Heavy/battery-intensive features → disabled by default

---

## 🚀 CI/CD — GITHUB ACTIONS (MANDATORY)

### What it does

Every push to `main`/`master` automatically:
1. Builds the app
2. Produces a release APK (unsigned by default — no setup required)
3. Creates a GitHub Release with the APK attached (downloadable by anyone)
4. Optionally signs the APK if keystore secrets are configured
5. Optionally uploads to Play Store if Play secrets are configured

### Create `.github/workflows/build-and-release.yml`

Adapt the template below — replace `[APP NAME]`, `[PACKAGE]`, and `[JDK_VERSION]`:

```yaml
name: Build & Release APK

# No signing keys required — releases unsigned APK by default.
# To enable signed builds: add KEYSTORE_BASE64, KEYSTORE_PASSWORD,
#   KEY_ALIAS, KEY_PASSWORD in repo Settings → Secrets and variables → Actions.

on:
  push:
    branches: [ main ]        # change to master if that's your default branch
  workflow_dispatch:
    inputs:
      release_name:
        description: 'Custom release tag (optional, e.g. v1.2.0)'
        required: false
        default: ''

permissions:
  contents: write

jobs:
  build-and-release:
    name: Build APK → GitHub Release
    runs-on: ubuntu-latest

    steps:
      - name: Checkout repository
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Set up JDK [JDK_VERSION]
        uses: actions/setup-java@v4
        with:
          java-version: '[JDK_VERSION]'   # match your compileOptions (17 or 21)
          distribution: 'temurin'
          cache: gradle

      - name: Make gradlew executable
        run: chmod +x ./gradlew

      - name: Build Debug APK
        run: ./gradlew assembleDebug --no-daemon

      # Signed build — only runs when keystore secrets are set
      - name: Decode keystore
        if: ${{ secrets.KEYSTORE_BASE64 != '' }}
        run: echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 --decode > $GITHUB_WORKSPACE/release.jks

      - name: Build Release APK (signed)
        if: ${{ secrets.KEYSTORE_BASE64 != '' }}
        run: |
          ./gradlew assembleRelease --no-daemon \
            -Pandroid.injected.signing.store.file=$GITHUB_WORKSPACE/release.jks \
            -Pandroid.injected.signing.store.password=${{ secrets.KEYSTORE_PASSWORD }} \
            -Pandroid.injected.signing.key.alias=${{ secrets.KEY_ALIAS }} \
            -Pandroid.injected.signing.key.password=${{ secrets.KEY_PASSWORD }}

      # Unsigned fallback — runs when no keystore secrets exist
      - name: Build Release APK (unsigned)
        if: ${{ secrets.KEYSTORE_BASE64 == '' }}
        run: ./gradlew assembleRelease --no-daemon

      - name: Extract version & rename APKs
        id: version
        run: |
          VERSION=$(grep 'versionName' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')
          echo "version=$VERSION" >> $GITHUB_OUTPUT

          DEBUG_SRC=$(find app/build/outputs/apk/debug -name "*.apk" | head -1)
          RELEASE_SRC=$(find app/build/outputs/apk/release -name "*.apk" | head -1)
          DEBUG_DST="[APP NAME]-v${VERSION}-debug.apk"
          RELEASE_DST="[APP NAME]-v${VERSION}-release.apk"
          cp "$DEBUG_SRC" "$DEBUG_DST"
          cp "$RELEASE_SRC" "$RELEASE_DST"
          echo "debug_apk=$DEBUG_DST" >> $GITHUB_OUTPUT
          echo "release_apk=$RELEASE_DST" >> $GITHUB_OUTPUT

          if [ -n "${{ secrets.KEYSTORE_BASE64 }}" ]; then
            echo "sign_status=✅ Signed release" >> $GITHUB_OUTPUT
          else
            echo "sign_status=⚠️ Unsigned (sideload only — add keystore secrets to sign)" >> $GITHUB_OUTPUT
          fi

      - name: Upload APK artifacts
        uses: actions/upload-artifact@v4
        with:
          name: [APP NAME]-${{ steps.version.outputs.version }}-apks
          path: |
            ${{ steps.version.outputs.debug_apk }}
            ${{ steps.version.outputs.release_apk }}
          retention-days: 90

      - name: Create release tag
        id: tag
        run: |
          TAG="${{ github.event.inputs.release_name || format('v{0}-build.{1}', steps.version.outputs.version, github.run_number) }}"
          echo "tag=$TAG" >> $GITHUB_OUTPUT
          git config user.name "github-actions[bot]"
          git config user.email "github-actions[bot]@users.noreply.github.com"
          git tag "$TAG" || true
          git push origin "$TAG" || true

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          tag_name: ${{ steps.tag.outputs.tag }}
          name: "[APP NAME] ${{ steps.tag.outputs.tag }}"
          prerelease: ${{ contains(steps.version.outputs.version, 'alpha') || contains(steps.version.outputs.version, 'beta') || contains(steps.version.outputs.version, 'rc') }}
          generate_release_notes: true
          body: |
            ## [APP NAME] — ${{ steps.tag.outputs.tag }}

            ${{ steps.version.outputs.sign_status }}

            ### 📲 Install
            1. Download **`[APP NAME]-v${{ steps.version.outputs.version }}-release.apk`** below
            2. On Android: **Settings → Security → Install unknown apps** → allow your browser
            3. Open the APK and tap **Install**

            | Version | Build | Commit |
            |---------|-------|--------|
            | `${{ steps.version.outputs.version }}` | `#${{ github.run_number }}` | `${{ github.sha }}` |

            ### 🚀 Play Store
            _Not yet configured. Add `PLAY_SERVICE_ACCOUNT_JSON` secret and uncomment the Play Store step._
          files: |
            ${{ steps.version.outputs.debug_apk }}
            ${{ steps.version.outputs.release_apk }}

      # ── Play Store upload (DISABLED — uncomment to enable) ──────────────
      # Prerequisites:
      #   1. Google Play Console → Setup → API access → create service account
      #   2. Download the JSON key file
      #   3. Add repo secret: PLAY_SERVICE_ACCOUNT_JSON = (JSON key contents)
      #   4. App must have at least one manual upload to Play Console first
      #
      # - name: Upload to Play Store
      #   uses: r0adkll/upload-google-play@v1
      #   with:
      #     serviceAccountJsonPlainText: ${{ secrets.PLAY_SERVICE_ACCOUNT_JSON }}
      #     packageName: [PACKAGE]
      #     releaseFiles: ${{ steps.version.outputs.release_apk }}
      #     track: internal          # internal | alpha | beta | production
      #     status: draft            # draft | completed
      #     releaseName: [APP NAME] ${{ steps.tag.outputs.tag }}
```

### CI/CD Secrets Reference

Add in: **GitHub repo → Settings → Secrets and variables → Actions → New repository secret**

| Secret | Required for | How to get it |
|--------|-------------|---------------|
| `KEYSTORE_BASE64` | Signed APK | `base64 -w 0 your-key.jks` — paste output as secret |
| `KEYSTORE_PASSWORD` | Signed APK | Password you used when creating the keystore |
| `KEY_ALIAS` | Signed APK | Alias you used when creating the keystore |
| `KEY_PASSWORD` | Signed APK | Key password (often same as keystore password) |
| `PLAY_SERVICE_ACCOUNT_JSON` | Play Store upload | Google Play Console → Setup → API access → Create service account → download JSON |

**Without any secrets:** unsigned APK is built and released to GitHub — fully functional for sideloading.
**With keystore secrets only:** signed APK released to GitHub — ready for manual Play Store upload.
**With all secrets:** fully automated — push to main → signed APK → GitHub Release → Play Store internal track.

---

## PERFORMANCE — MANDATORY (NOT OPTIONAL)

**Threading Rules — Zero Exceptions:**
- `Dispatchers.IO` for: all network calls, all database reads/writes, all file I/O
- `Dispatchers.Default` for: CPU-heavy computation, sorting, parsing large data
- `Dispatchers.Main` for: UI updates ONLY — nothing else
- `withContext(Dispatchers.IO) { }` wraps every single network or disk call
- Never use `runBlocking` on the main thread
- Never call `.execute()` (OkHttp sync) on the main thread

**UI Performance:**
- `LazyColumn` / `LazyRow` for ALL lists — never Column with forEach for dynamic data
- `remember { }` for expensive calculations — never recompute on every recomposition
- `rememberCoroutineScope()` for launching coroutines from UI events
- `collectAsState()` for observing flows in Compose
- `key()` parameter on LazyColumn items when list reorders
- Images: load async (Coil / Glide) — never decode on main thread
- Avoid `StateFlow.value` inside Composables — always use `collectAsState()`

**Memory Management:**
- Bound all in-memory caches (LRU with max size)
- Remove listeners/observers in onDestroy / DisposableEffect
- Use WeakReference for callbacks that reference Activities
- Never store Context in a companion object or singleton directly — use applicationContext

**Network Efficiency:**
- Set connection timeout: 15-30s, read timeout: 30-60s
- Retry failed requests max 3 times with exponential backoff
- Cancel in-flight requests on screen exit (structured concurrency with Job)
- Paginate large data sets — never load all records at once

---

## README TEMPLATE

Create `README.md` at the start. Update it after EVERY branch is completed.

```markdown
<div align="center">
<img src="screenshots/app_icon.png" width="120" />

# [APP NAME]

**[One impactful sentence: what the app does + why it's the best at it]**

[![Platform](badge)] [![Language](badge)] [![License](badge)]
</div>

---

## 📖 What is [APP NAME]?
[2-3 paragraph description. Lead with the value proposition.]

## 🏗 Architecture
[ASCII diagram showing how all components connect]

## ✨ Features
[One section per branch/feature group. Update as each branch lands.]
### [Feature Group 1 Name] (Phases X-Y)
- **Feature name** — what it does
- **Feature name** — what it does

## 📸 Screenshots
| [Screen 1] | [Screen 2] | [Screen 3] |
|------------|------------|------------|
| ![](screenshots/01.png) | ![](screenshots/02.png) | ![](screenshots/03.png) |

## 🚀 Setup & Installation
[Step-by-step setup guide]

## ⚙️ Configuration
[What API keys / config the user needs. Where to get them.]

## 🔨 Building
[Exact build + install commands]

## 📦 Releases
Pre-built APKs are available on the [GitHub Releases](../../releases) page.
Download the latest `[APP NAME]-vX.X.X-release.apk` and sideload it,
or configure CI/CD secrets for automatic signed builds.

## 🤝 Contributing
[How to contribute]

## 📄 License
[License info]

## 🙏 Acknowledgements
[Reference repos, libraries, APIs used]
```

---

## BUILD → COMMIT → PUSH CHECKLIST (run after EVERY phase)

Before marking a phase ✅ in plan.md, verify ALL:

**Code:**
- [ ] Feature fully implemented — no stubs, no TODO placeholders
- [ ] No compile errors, no crash on launch
- [ ] All network/DB calls on background thread

**UI/UX:**
- [ ] Follows premium design rules (correct colors, spacing, radius, typography)
- [ ] Empty state exists (for screens that show lists/data)
- [ ] Loading state exists (while data loads)
- [ ] Error state exists (if something can fail)
- [ ] Dark mode looks correct
- [ ] Animations present where appropriate

**Info Guide:**
- [ ] GuideStep added to InfoData with `isNew = true`
- [ ] Detail text written in plain language (non-technical user can understand)
- [ ] Setup steps included (numbered, specific)
- [ ] Usage examples included
- [ ] Feature count updated in About tab if a new capability was added
- [ ] Settings section has ⓘ button linked to the correct InfoScreen section

**Defaults:**
- [ ] Feature disabled by default if it requires API key or special setup
- [ ] "Not configured" message shows with instructions when key is missing

**Release:**
- [ ] Phase marked ✅ in plan.md
- [ ] README.md updated
- [ ] Build passes: `[BUILD COMMAND]`
- [ ] Committed with proper message format
- [ ] Pushed to correct branch
- [ ] If merging to main/master: GitHub Actions CI will auto-build + release APK

---

## SCREENSHOTS WORKFLOW

1. After each major screen or feature: take a screenshot
2. Save to `test_screenshots/` (gitignored)
3. Analyse: check layout, colors, text overflow, spacing, empty/loading/error states
4. Fix any issues found, delete the screenshot
5. NEVER commit from `test_screenshots/`
6. Finalized screenshots (for README) → save to `screenshots/` and commit those

**What to check in every screenshot:**
- Text never overflows or gets cut off
- Colors match the design system — no default grey/blue Material colors
- Spacing is consistent — no elements touching the screen edge
- Icons and emojis render at the correct size
- Status bar and navigation bar have correct background
- Cards have visible but subtle shadow/elevation
- The screen looks like a PAID app — not a tutorial project

---

## COMMIT MESSAGE FORMAT

```
type(scope): short description (max 72 chars)

- bullet: what changed
- bullet: why it changed

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
```

Types:
- `feat` — new feature
- `fix` — bug fix
- `ui` — visual/UX change with no logic change
- `perf` — performance improvement
- `docs` — README or info guide update
- `chore` — setup, config, dependencies
- `refactor` — code restructure, no behavior change

---

## ERROR HANDLING RULES

**When a build fails:**
1. Read the FULL error — don't guess or skip lines
2. Fix root cause — never suppress with `@Suppress` or empty catch
3. Re-run build to confirm fix
4. If stuck after 3 attempts → stop and explain the issue clearly

**When an API call fails:**
1. Log: which API, which key/endpoint, error code and message
2. Show user a helpful message: "Could not connect to X — check your API key in Settings"
3. Max 3 retries with exponential backoff — never infinite loop

**When a feature requires setup that isn't done:**
1. Show a clear card: icon + "X is not configured" + "Tap to set up" button
2. Never crash, never show a blank screen, never show a raw error message

---

## HOW TO RESUME IN A NEW CHAT

If this conversation is lost or context runs out:
1. Open a new chat
2. Share `plan.md` — it has the full phase list and status
3. Say: "Continue from the next ⏳ PENDING phase"
4. The AI reads plan.md and continues without losing context

---

## NOW BEGIN

Confirm you have read and understood ALL rules above — especially:
- ✓ Premium UI/UX rules (colors, spacing, radius, animations)
- ✓ Info guide requirement (every feature needs a GuideStep)
- ✓ Performance rules (background threads, LazyColumn, no blocking)
- ✓ Settings ⓘ redirect buttons
- ✓ CI/CD workflow (create `.github/workflows/build-and-release.yml` in Step 0E)

Then:
1. Analyse any provided reference repos/apps
2. Design the color palette and typography scale
3. Create `plan.md` with ALL phases planned out
4. Create `SKILL.md`
5. Create `README.md` skeleton
6. Set up git, create CI/CD workflow, and push the bootstrap commit
7. Ask for my confirmation before starting Phase 1

**Current date:** [TODAY'S DATE]
