package com.bocman.claudecommit

import com.intellij.ide.DataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.InclusionListener
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.ui.RefreshableOnComponent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.event.AncestorEvent
import javax.swing.event.AncestorListener

/**
 * Adds a "Generate with Claude" button to the commit-options strip (the row
 * containing the Amend checkbox). Generation logic is delegated to
 * [GenerateWithClaudeAction] to keep it in one place.
 */
class ClaudeCommitHandler(private val panel: CheckinProjectPanel) : CheckinHandler() {

    companion object {
        private val LOG = Logger.getInstance(ClaudeCommitHandler::class.java)
    }

    private val worker = GenerateWithClaudeAction()

    override fun getBeforeCheckinConfigurationPanel(): RefreshableOnComponent {
        val button = JButton("Generate with Claude").apply {
            isEnabled = false
            toolTipText = "Select files to commit first"
        }

        var lastEnabled = false
        fun updateButton() {
            val hasChanges = panel.selectedChanges.isNotEmpty()
            if (hasChanges != lastEnabled) {
                LOG.info("[ClaudeCommit] Button ${if (hasChanges) "enabled" else "disabled"} — ${panel.selectedChanges.size} file(s) checked")
                lastEnabled = hasChanges
            }
            button.isEnabled = hasChanges
            button.toolTipText = if (hasChanges)
                "Generate a commit message using the local Claude Code CLI"
            else
                "Select files to commit first"
        }

        button.addAncestorListener(object : AncestorListener {
            private var listenerAttached = false

            override fun ancestorAdded(e: AncestorEvent) {
                updateButton()
                if (!listenerAttached) {
                    val ui = DataManager.getInstance()
                        .getDataContext(panel.component)
                        .getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
                    if (ui != null) {
                        ui.addInclusionListener(
                            object : InclusionListener {
                                override fun inclusionChanged() =
                                    ApplicationManager.getApplication().invokeLater { updateButton() }
                            },
                            ui
                        )
                        listenerAttached = true
                        LOG.info("[ClaudeCommit] InclusionListener attached via CommitWorkflowUi")
                    } else {
                        LOG.warn("[ClaudeCommit] CommitWorkflowUi not available — button state won't update dynamically")
                    }
                }
            }

            override fun ancestorRemoved(e: AncestorEvent) {}
            override fun ancestorMoved(e: AncestorEvent)   {}
        })

        button.addActionListener { generateCommitMessage() }

        return object : RefreshableOnComponent {
            override fun getComponent(): JComponent = button
            override fun refresh() {
                button.isEnabled = panel.selectedChanges.isNotEmpty()
            }
            override fun saveState()    {}
            override fun restoreState() {}
        }
    }

    private fun generateCommitMessage() {
        val project = panel.project
        val selectedChanges = panel.selectedChanges.toList()

        if (selectedChanges.isEmpty()) {
            LOG.info("[ClaudeCommit] Handler button clicked with no checked files")
            Messages.showWarningDialog(
                project,
                "No changes selected. Check the files you want to commit first.",
                "Claude Commit"
            )
            return
        }

        LOG.info("[ClaudeCommit] Handler button clicked — ${selectedChanges.size} file(s): ${selectedChanges.map { it.virtualFile?.name ?: "?" }}")

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Generating commit message with Claude…", /* canBeCancelled = */ true
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Reading branch name…"
                    val branch = worker.getBranchName(project)
                    LOG.info("[ClaudeCommit] Branch: $branch")

                    indicator.text = "Reading diff…"
                    val diff = worker.getDiff(project, selectedChanges)

                    if (diff.isBlank()) {
                        LOG.warn("[ClaudeCommit] Diff is empty for selected files")
                        showWarning(project, "No diff found for the selected files.")
                        return
                    }
                    LOG.info("[ClaudeCommit] Diff: ${diff.lines().size} lines, ${diff.length} chars")
                    LOG.debug("[ClaudeCommit] Diff content:\n$diff")

                    indicator.checkCanceled()
                    indicator.text = "Calling Claude…"

                    val settings = ClaudeCommitSettings.getInstance()
                    val claudePath = worker.findClaude(settings)
                        ?: throw RuntimeException(
                            "Claude Code CLI not found.\n" +
                            "Install Claude Code or set its path under Settings → Tools → Claude Commit."
                        )
                    LOG.info("[ClaudeCommit] Using Claude at: $claudePath")

                    val prompt = settings.promptTemplate
                        .replace("{BRANCH}", branch)
                        .replace("{DIFF}", diff)

                    LOG.info("[ClaudeCommit] Calling Claude (prompt: ${prompt.length} chars)…")
                    val message = worker.callClaude(claudePath, prompt, indicator)
                    LOG.info("[ClaudeCommit] Claude responded (${message.length} chars): ${message.take(120).replace('\n', '↵')}")

                    ApplicationManager.getApplication().invokeLater {
                        panel.setCommitMessage(message.trim())
                    }
                } catch (ex: ProcessCanceledException) {
                    LOG.info("[ClaudeCommit] Generation cancelled by user")
                    throw ex
                } catch (ex: Exception) {
                    LOG.error("[ClaudeCommit] Generation failed", ex)
                    showError(project, ex.message ?: ex.toString())
                }
            }
        })
    }

    private fun showWarning(project: Project, msg: String) =
        ApplicationManager.getApplication().invokeLater {
            Messages.showWarningDialog(project, msg, "Claude Commit")
        }

    private fun showError(project: Project, msg: String) =
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, msg, "Claude Commit — Error")
        }
}
