# ZeroClawAndroid — Build Plan

> **Before writing ANY code, read the full plan first.**

## Full Plan (Private Repo)

The complete master build plan (180+ phases, architecture, bugs, all details) is in a **private repo**:

**https://github.com/ashokvarmamatta/zeroclaw-docs/blob/main/ZeroClawAndroid/plan.md**

> This is a **private repository** — only accessible when logged in as the owner.
> If you're an AI assistant, read `PLAN_FULL.md` in this directory (gitignored local copy).

### Local copy

If `PLAN_FULL.md` exists in this directory, read that (it's the same content, gitignored).
To fetch/refresh from the private repo:
```bash
cd /tmp && git clone https://github.com/ashokvarmamatta/zeroclaw-docs.git
cp zeroclaw-docs/ZeroClawAndroid/plan.md PLAN_FULL.md
```

## Quick Reference

- **Project:** Android app that runs ZeroClaw AI agent with 10 messaging channels
- **Phases completed:** 182 (178: LiteRT LM migration, 179: Model catalog + download, 180: Token stats UI, 181: Offline vision + config dialog, 182: Document Knowledge Graph)
- **Tools:** 36 built-in AI tools (+ Document Knowledge Graph)
- **Channels:** Telegram, WhatsApp, Discord, Signal, Slack, Matrix, IRC, Teams, Twitch, LINE
- **Offline:** LiteRT LM SDK (Gemma 4 support, 32K context, streaming, thinking mode)
- **Build:** `JAVA_HOME="C:/Users/DELL/AppData/Local/Programs/Android Studio/jbr" ./gradlew assembleDebug`

## Phase 182 — Document Knowledge Graph (feat/document-graph)

**Status:** Implemented

### What it does
Ingest any PDF, DOCX, TXT, MD, CSV, or HTML file into an on-device knowledge graph. Extracts entities (people, technologies, concepts, orgs) and relationships via LLM. Stores text chunks with vector embeddings for semantic search. Users can then ask any question about the document — answers are backed by RAG (Retrieval Augmented Generation) + graph traversal.

### Files
- `DocumentGraphDatabase.kt` — Room DB: documents, doc_chunks, graph_nodes, graph_edges tables + DAO with graph traversal queries
- `DocumentGraphTool.kt` — 7 actions: ingest, query, entities, connections, list, summary, delete
- `ChatScreen.kt` — Added "Ingest to Graph" option in attach dropdown menu
- `ToolTestSheet.kt` — Added file picker ("Browse Files") button for source parameter fields
- `InfoData.kt` — Added feature item + updated tool count

### Architecture
```
File Upload → PdfReadTool/DocReadTool (extract text)
           → Chunk text (800 char paragraphs)
           → VectorMemory (embed chunks)
           → LlmRouter.extractOnly() (extract entities + relationships)
           → DocumentGraphDatabase (store nodes + edges)

Query → Semantic search (cosine similarity on chunk embeddings)
      → Graph traversal (find connected entities)
      → LlmRouter.extractOnly() (generate answer from context)
```

## Session Rules

1. Read `PLAN_FULL.md` (local) or clone from private repo above
2. Read `RULES.md` in the private repo for full dev workflow rules
3. Read `BUGS.md` — avoid re-introducing fixed bugs
4. Mark phases done after implementation
5. Sync plan back to private repo after changes:
   ```bash
   cp PLAN_FULL.md /tmp/zeroclaw-docs/ZeroClawAndroid/plan.md
   cd /tmp/zeroclaw-docs && git add -A && git commit -m "sync plan" && git push
   ```
