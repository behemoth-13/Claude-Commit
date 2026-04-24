# Claude Commit

An IntelliJ plugin that generates Git commit messages using the [Claude Code](https://claude.ai/download) CLI installed locally on your machine.

![Plugin Icon](src/main/resources/META-INF/pluginIcon.svg)

## How it works

1. Open the Git commit dialog (`⌘0` / `Alt+0`)
2. Check the files you want to commit
3. Click **Generate with Claude** in the toolbar (or the button in the commit options strip)
4. The plugin reads the branch name and diff of the selected files, sends them to the local `claude` CLI, and fills in the commit message

No API key required — the plugin delegates entirely to the Claude Code CLI you already have installed.

## Requirements

- IntelliJ-based IDE (IntelliJ IDEA, Android Studio, etc.) 2023.2 or newer
- [Claude Code CLI](https://claude.ai/download) installed and accessible on `$PATH`

## Installation

Install from the JetBrains Marketplace, or build locally:

```bash
./gradlew buildPlugin
```

Then install the ZIP from `build/distributions/` via **Settings → Plugins → Install Plugin from Disk**.

## Settings

**Settings → Tools → Claude Commit**

| Setting | Description |
|---|---|
| Claude executable | Path to the `claude` binary. Leave empty to auto-detect from common locations and `$PATH`. |
| Effort level | How much reasoning Claude applies: Low (fastest) → Medium → High → Extra High → Max |
| Prompt template | The prompt sent to Claude. Use `{BRANCH}` and `{DIFF}` as placeholders. |

## Building

```bash
./gradlew runIde      # launch a sandbox IDE with the plugin loaded
./gradlew buildPlugin # produce a distributable ZIP
```

## License

MIT
