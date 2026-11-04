# Antigravity.md — Antigravity Agent & AntiGem Reference

> **Purpose:** Antigravity (`agy`) kya hai, AntiGem kya hai, aur Jarvis ke saath iska kya relation hai.  
> **Source:** `research_repos/antigem/` — sirf reference ke liye, copy/decompile nahi karna.  
> **Last Updated:** Nov 3, 2026

---

## 🌟 Antigravity (agy) Kya Hai?

**Antigravity** ek agentic AI coding assistant hai jo Android pe natively chalta hai. Iska CLI binary naam `agy` hai. Ye ek **language server daemon** ki tarah kaam karta hai jisme:

- **gRPC Hub Protocol** se connect karte hain (binary protobuf, type-safe)
- Real-time token streaming
- Multi-step subagent execution
- Tool confirmations + interactive Q&A
- Live file synchronization

### AntiGem App Kya Hai?

AntiGem ek Android app hai jo **Antigravity + Claude Code** dono ko ek saath run karti hai:

```
┌────────────────────────────────────────────┐
│           AntiGem Android App              │
│      (Jetpack Compose + PTY Terminal)      │
└──────────────┬─────────────────────────────┘
               │ Binary Protobuf / gRPC + WebSockets
┌──────────────▼─────────────────────────────┐
│        Embedded Go IDE Bridge              │
│            (agy_ide_bridge)                │
└──────────┬───────────────────┬─────────────┘
           │                   │
    gRPC Hub Protocol    stream-json relay
           │                   │
┌──────────▼──────┐  ┌─────────▼────────────┐
│  Antigravity    │  │   Claude Code CLI    │
│  (agy daemon)   │  │  (one per chat tab)  │
└─────────────────┘  └──────────────────────┘
```

---

## 📦 App Variants

| Variant | Package ID | Kaise Kaam Karta Hai |
|---|---|---|
| **termux** (Recommended) | `com.termux` | All-in-one. Khud ka Linux environment, apt/pkg, shell sab andar. Termux install ki zarurat nahi. |
| **standard** | `com.antigem` | Alag Termux app ki zarurat. `agy_ide_bridge` Termux mein chalao, AntiGem usse localhost pe connect karta hai. |

---

## ✨ Key Features (AntiGem / Antigravity se sikhne ke liye)

### 1. Agentic AI Chat
- Direct gRPC connection to `agy` (Square Wire protobuf)
- Live token streaming with thought collapse/expansion
- Tool execution cards + subagent tracking
- Interactive confirmation modals (Claude Code style)
- Model switching: Gemini Flash/Pro/Ultra, Claude Sonnet, GPT-4o

### 2. Claude Code Integration
- Official `claude` CLI — stream-json protocol
- One long-lived process per chat tab
- Live model/effort/thinking/permission mode switching
- Usage tracking: prompt-cache timer, context window, weekly limits
- Slash commands (`/compact`, etc.), edit/regenerate messages

### 3. Native Linux Terminal
- High-performance PTY terminal emulator
- Pre-configured: bash, Python, Node.js, Git, Go, Glibc
- Live Terminal MCP: AI agents can run commands
- Continuous foreground execution with streaming output

### 4. Mobile IDE Code Editor
- Syntax highlighting: Python, Go, Kotlin, Java, JS/TS, Rust, C/C++, HTML/CSS, JSON, YAML, Markdown
- Multi-tab switcher, line numbers, find/replace, undo/redo
- File explorer, auto-indentation

### 5. Integrated Web Browser + Automation
- In-app browser for local dev server preview
- Browser automation: AI inspect DOM, test UI, verify frontend

### 6. Dynamic Chat Artifacts
- Expandable diff blocks
- Step carousels
- Copyable code blocks

---

## 🔌 Protocol: Claude Code CLI (stream-json)

AntiGem ne Claude Code ka protocol reverse engineer kiya hai. Key points:

### Architecture
```
Extension Host (Node)
     │ stdin/stdout NDJSON
     ▼
claude CLI process (ek per chat tab, alive across turns)
     │
     ├── reads/writes ~/.claude/projects/<cwd>/<sessionId>.jsonl  (history)
     ├── ~/.claude/settings.json                                   (settings)
     └── ~/.claude/.credentials.json                              (OAuth)
```

### Key Rules
- **Koi socket, HTTP server, gRPC nahi** — sirf newline-delimited JSON on stdin/stdout
- **Ek process per chat tab** — turns ke beech alive rehta hai
- **Control messages** model switch, stop, usage sab isi pipe se
- **Naya process** sirf naye chat ke liye, ya `--resume` ke liye

### Spawning CLI
```bash
claude \
  --output-format stream-json \
  --verbose \
  --input-format stream-json \
  --max-thinking-tokens 31999 \
  --cwd /path/to/project
```

---

## 🔗 How Jarvis Uses These Ideas

Jarvis mein Antigravity/AntiGem ke concepts yahan use hote hain:

| AntiGem Feature | Jarvis Equivalent | File |
|---|---|---|
| Code Editor | Code Studio IDE | `codestudio/editor/CodeEditor.kt` |
| AI Code Edit | AI Edit Engine | `codestudio/ai/AiEditEngine.kt` |
| Auto-Fix | Auto-Fix Engine | `codestudio/ai/AutoFixEngine.kt` |
| Terminal | Terminal Panel | `codestudio/terminal/TerminalPanel.kt` |
| Project Generator | Project Generator | `codestudio/generator/ProjectGenerator.kt` |
| Live Preview | Live Preview Panel | `codestudio/preview/LivePreviewPanel.kt` |
| AI Tool Execution | JarvisToolDispatcher | `automation/dispatcher/JarvisToolDispatcher.kt` |
| Voice TTS | Edge TTS + Maria | `voice/edge/EdgeTtsClient.kt` |

---

## 📂 Research Repo Location

```
research_repos/antigem/
├── README.md                 — Overview (source for this doc)
├── AGENTS.md                 — Agent rules (Gemini model specific)
├── CLAUDE_CLI_PROTOCOL.md   — Claude Code stream-json protocol (detailed)
├── extension_docs/
│   ├── antigem-api.md
│   ├── chrome-api.md
│   ├── developer-mcp.md
│   ├── google-flow.md
│   ├── lifecycle.md
│   └── mcp.md
└── extensions/README.md
```

---

## ⚠️ Important Notes

1. **Code copy mat karo** — AntiGem open-source reference hai, isko read karke apna implement karo
2. **gRPC khud implement karna zaroori nahi** — Jarvis mein `AiTextSynthesizer` already hai
3. **Claude Code CLI** — Agar Jarvis mein Claude Code integration chahiye, `CLAUDE_CLI_PROTOCOL.md` padho
4. **Termux variant** — Ye Jarvis ke Termux build ke liye relevant hai

---

## 🚀 If Adding Antigravity Agent to Jarvis

Step-by-step:
1. `agy` binary Termux mein install karo
2. gRPC connection setup: Square Wire protobuf
3. `JarvisToolDispatcher.kt` mein tools register karo
4. `AiTextSynthesizer` ke alternative routing mein `agy` add karo
5. UI mein agent selector add karo (Settings → AI Agent)

> **Note:** Ye abhi pending hai. User ne abhi tak specifically nahi manga.
