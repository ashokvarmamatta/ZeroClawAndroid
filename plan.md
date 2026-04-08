# ZeroClawAndroid — Build Plan

> **Before writing ANY code, read the full plan first.**

## Full Plan (Private)

The complete master build plan (179 phases, architecture, bugs, all details) is maintained privately:

**https://gist.github.com/ashokvarmamatta/e9269852ef4d29f820919cd4bf8b3aff**

> This is a **secret gist** — only accessible with the direct link.
> If you're an AI assistant, fetch and read `plan.md` from that gist before starting work.

### Local copy

If `PLAN_FULL.md` exists in this directory, read that instead (it's the same content, gitignored).
To fetch/refresh it:
```bash
gh gist view e9269852ef4d29f820919cd4bf8b3aff -f PLAN_FULL.md > PLAN_FULL.md
```

## Quick Reference

- **Project:** Android app that runs ZeroClaw AI agent with 10 messaging channels
- **Phases completed:** 180 (178: LiteRT LM migration, 179: Model catalog + download, 180: Token stats UI)
- **Tools:** 37 built-in AI tools
- **Channels:** Telegram, WhatsApp, Discord, Signal, Slack, Matrix, IRC, Teams, Twitch, LINE
- **Offline:** LiteRT LM SDK (Gemma 4 support, 32K context, streaming, thinking mode)
- **Build:** `JAVA_HOME="C:/Users/DELL/AppData/Local/Programs/Android Studio/jbr" ./gradlew assembleDebug`

## Session Rules

1. Read `PLAN_FULL.md` (local) or fetch from gist link above
2. Read `BUGS.md` — avoid re-introducing fixed bugs
3. Follow the Dev Workflow Rules in the full plan
4. Mark phases done after implementation
5. Update both local and gist after changes:
   ```bash
   # Save local changes back to gist
   gh gist edit e9269852ef4d29f820919cd4bf8b3aff -f PLAN_FULL.md PLAN_FULL.md
   ```
