package com.bocman.claudecommit

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows

/** Shown under Settings → Tools → Claude Commit. */
class ClaudeCommitConfigurable : BoundConfigurable("Claude Commit") {

    private val settings = ClaudeCommitSettings.Companion.getInstance()

    override fun createPanel(): DialogPanel = panel {
        row("Claude executable:") {
            textField()
                .bindText(settings::claudePath)
                .comment("Leave empty to auto-detect. Example: /opt/homebrew/bin/claude")
                .align(AlignX.FILL)
        }
        collapsibleGroup("Prompt Template") {
            row {
                textArea()
                    .bindText(settings::promptTemplate)
                    .rows(18)
                    .align(AlignX.FILL)
                    .comment("Use {BRANCH} and {DIFF} as placeholders for branch name and git diff.")
            }.resizableRow()
        }
        row {
            button("Reset To Default") {
                settings.promptTemplate = ClaudeCommitSettings.Companion.DEFAULT_PROMPT
                // recreate panel to refresh the text area
                reset()
            }
        }
    }
}
