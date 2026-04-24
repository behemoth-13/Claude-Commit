package com.bocman.claudecommit

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "ClaudeCommitSettings",
    storages = [Storage("claude-commit.xml")]
)
class ClaudeCommitSettings : PersistentStateComponent<ClaudeCommitSettings.State> {

    /** Mutable backing bean serialised to XML. Use public fields (no val/var) for JAXB. */
    enum class EffortLevel(val cliValue: String, val displayName: String) {
        LOW("low", "Low"),
        MEDIUM("medium", "Medium"),
        HIGH("high", "High"),
        XHIGH("xhigh", "Extra High"),
        MAX("max", "Max");

        override fun toString() = displayName
    }

    class State {
        @JvmField var claudePath: String = ""
        @JvmField var promptTemplate: String = DEFAULT_PROMPT
        @JvmField var effortLevel: String = EffortLevel.LOW.cliValue
    }

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    var claudePath: String
        get() = myState.claudePath
        set(v) { myState.claudePath = v }

    var promptTemplate: String
        get() = myState.promptTemplate
        set(v) { myState.promptTemplate = v }

    var effortLevel: EffortLevel
        get() = EffortLevel.entries.firstOrNull { it.cliValue == myState.effortLevel } ?: EffortLevel.LOW
        set(v) { myState.effortLevel = v.cliValue }

    companion object {
        fun getInstance(): ClaudeCommitSettings =
            ApplicationManager.getApplication().getService(ClaudeCommitSettings::class.java)

        /**
         * Default prompt mirrors the JetBrains AI "Commit Message Generation" built-in action.
         * Placeholders: {BRANCH} and {DIFF}.
         */
        val DEFAULT_PROMPT: String = """
You are an expert software developer writing a git commit message.

Branch: {BRANCH}

Staged changes:
```diff
{DIFF}
```

Rules:
- Subject line: imperative mood, ≤72 characters (e.g. "Fix null pointer on login screen")
- If the subject alone is not enough, add ONE blank line then a short body explaining WHY
- Reference the JIRA ticket from the branch name when present (e.g. ANDROID-12345)
- Be specific; avoid vague words like "update" or "fix stuff"
- Output ONLY the commit message — no markdown, no code blocks, no explanations
        """.trimIndent()
    }
}
