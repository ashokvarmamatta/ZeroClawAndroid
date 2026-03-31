# README Generator Prompt

> Copy-paste this prompt into Claude Code (or any AI assistant) to generate a professional, visual README for any project.
> Replace `<PROJECT_PATH_OR_GIT_URL>` with your actual project path or GitHub URL.

---

## The Prompt

```
Analyze the project at <PROJECT_PATH_OR_GIT_URL> and generate a professional README.md for it.

## How to analyze:
1. Read the project structure (all folders, key files)
2. Read build files (build.gradle, package.json, Cargo.toml, etc.) to understand dependencies
3. Read the main source files to understand what the app actually does
4. Check git log for recent features and active development
5. Check for existing docs, configs, tests

## README format rules:

### Header (centered):
- App icon if exists (screenshots/ or assets/)
- Project name as H1 with emoji
- One-liner subtitle: "### Your tagline here"
- Animated typing SVG showing 4-5 key features:
  <img src="https://readme-typing-svg.herokuapp.com?font=Fira+Code&weight=500&size=18&pause=1000&color=00D4AA&center=true&vCenter=true&width=500&lines=Feature+1;Feature+2;Feature+3" />
- Badge row: Platform, Language, UI Framework, License (use style=for-the-badge)

### "Why This Project Exists" section:
- 2-3 paragraphs in first person explaining motivation
- Use blockquote format with questions:
  > 🤔 **Can X do Y?** → Yes, here's how...
- Mention it's actively being worked on, has real bugs, real limitations
- If AI tools were used, explain HOW they were used (as a tool, not a replacement)

### "What is <ProjectName>?" section:
- 2-3 sentence plain-language explanation (HR-friendly, no jargon)
- ASCII flow diagram with emojis showing how the app works:
  ```
  📱 Input → 🔧 Processing → 💬 Output
  ```

### Screenshots section (if screenshots exist):
- Table layout: | Screenshot 1 | Screenshot 2 | Screenshot 3 |

### Features section:
- Each feature group gets its own ### heading with:
  - Emoji prefix
  - Inline badge labels: ![](https://img.shields.io/badge/Label-Color?style=flat-square)
  - Use 2-3 badges per heading showing key stats or status
- Use TABLES (not bullet lists) for features:
  | Feature | What it does |
  With emoji in every row
- Group related features logically
- For tool/item lists, use multi-column <table><tr><td> layout
- Add collapsible <details> for long sections

### Status labels to use on feature headings:
- Stable: ![](https://img.shields.io/badge/✅_Stable-4CAF50?style=flat-square)
- In Progress: ![](https://img.shields.io/badge/🔧_In_Progress-FF9800?style=flat-square)
- Opt-in: ![](https://img.shields.io/badge/Opt--In-blue?style=flat-square)
- Free: ![](https://img.shields.io/badge/Free-4CAF50?style=flat-square)
- Any count: ![](https://img.shields.io/badge/30_Items-7F52FF?style=flat-square)

### "Work in Progress" section:
- Status badge table (Stable / In Progress / Known Bugs)
- Link to bug tracker if exists
- Link to issues page
- Note about checking docs if something doesn't work

### Tech Stack section:
- Badge row with all major technologies (style=flat-square)
- Table with emoji + Layer + Technology columns

### Architecture section (collapsible):
- Project structure tree with emoji folder icons

### Getting Started section:
- Prerequisites with emoji bullets
- Build & run code block
- First-time setup as numbered steps with emojis

### Footer (centered):
- Bold tagline with emoji
- Author name as ### heading with GitHub link
- Social badges in <p align="center"> with &nbsp; spacing:
  GitHub (black), LinkedIn (blue), Portfolio (green), Email (red)
  Use style=for-the-badge
- Capsule render wave footer:
  <img src="https://capsule-render.vercel.app/api?type=waving&color=0:0d1117,50:00D4AA,100:7C5CFC&height=80&section=footer" width="100%"/>

## Style rules:
- Every heading gets emoji + inline badges
- Every list becomes a table with emoji column
- Use plain language — an HR person should understand feature descriptions
- No phase numbers, no internal jargon
- Keep descriptions SHORT (1 line per feature in tables)
- Use <details> for anything longer than 10 items
- All optional/toggleable features must say so with badges
- Colors: green=#4CAF50 (stable/free), orange=#FF9800 (WIP), red=#F44336 (bugs/security), blue=#2196F3 (info), purple=#7F52FF (advanced), teal=#00D4AA (accent)

## What NOT to include:
- No phase numbers or internal tracking
- No commit hashes or branch names in feature descriptions
- No implementation details (save for plan.md)
- No "TODO" items in the README
- No empty sections — skip if nothing to show

Generate the full README.md content now. Make it visually stunning on GitHub dark mode.
```

---

## Usage Examples

### For a local project:
```
Analyze the project at D:\MyProjects\WeatherApp and generate a professional README.md for it.
```

### For a GitHub repo:
```
Analyze the project at https://github.com/username/repo and generate a professional README.md for it.
```

### For the current directory:
```
Analyze the project in the current directory and generate a professional README.md for it.
```

---

## Quick Badge Reference

```markdown
<!-- Status badges -->
![](https://img.shields.io/badge/✅_Stable-4CAF50?style=flat-square)
![](https://img.shields.io/badge/🔧_WIP-FF9800?style=flat-square)
![](https://img.shields.io/badge/🐛_Known_Bugs-F44336?style=flat-square)
![](https://img.shields.io/badge/Opt--In-blue?style=flat-square)
![](https://img.shields.io/badge/Free-4CAF50?style=flat-square)

<!-- Count badges (change number and color) -->
![](https://img.shields.io/badge/30_Tools-7F52FF?style=flat-square)
![](https://img.shields.io/badge/11_Channels-26A5E4?style=flat-square)

<!-- Tech badges (for-the-badge style for headers) -->
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Python](https://img.shields.io/badge/Python-3776AB?style=for-the-badge&logo=python&logoColor=white)](https://python.org)
[![React](https://img.shields.io/badge/React-61DAFB?style=for-the-badge&logo=react&logoColor=black)](https://react.dev)
[![Node.js](https://img.shields.io/badge/Node.js-339933?style=for-the-badge&logo=nodedotjs&logoColor=white)](https://nodejs.org)
[![Rust](https://img.shields.io/badge/Rust-000000?style=for-the-badge&logo=rust&logoColor=white)](https://www.rust-lang.org)

<!-- Social badges (for footer) -->
<a href="https://github.com/USERNAME"><img src="https://img.shields.io/badge/GitHub-181717?style=for-the-badge&logo=github&logoColor=white" /></a>
<a href="https://linkedin.com/in/USERNAME"><img src="https://img.shields.io/badge/LinkedIn-0077B5?style=for-the-badge&logo=linkedin&logoColor=white" /></a>
<a href="https://YOUR_PORTFOLIO"><img src="https://img.shields.io/badge/Portfolio-00D4AA?style=for-the-badge&logo=googlechrome&logoColor=white" /></a>
<a href="mailto:YOUR_EMAIL"><img src="https://img.shields.io/badge/Email-D14836?style=for-the-badge&logo=gmail&logoColor=white" /></a>

<!-- Typing animation -->
<img src="https://readme-typing-svg.herokuapp.com?font=Fira+Code&weight=500&size=18&pause=1000&color=00D4AA&center=true&vCenter=true&width=500&lines=Line+1;Line+2;Line+3" />

<!-- Wave footer -->
<img src="https://capsule-render.vercel.app/api?type=waving&color=0:0d1117,50:00D4AA,100:7C5CFC&height=80&section=footer" width="100%"/>
```
