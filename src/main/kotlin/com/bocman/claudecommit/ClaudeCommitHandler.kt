package com.bocman.claudecommit

import com.intellij.ide.DataManager
import com.intellij.openapi.application.ApplicationManager
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

    private val worker = GenerateWithClaudeAction()

    override fun getBeforeCheckinConfigurationPanel(): RefreshableOnComponent {
        val button = JButton("Generate with Claude").apply {
            isEnabled = false
            toolTipText = "Select files to commit first"
        }

        fun updateButton() {
            val hasChanges = panel.selectedChanges.isNotEmpty()
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
            Messages.showWarningDialog(
                project,
                "No changes selected. Check the files you want to commit first.",
                "Claude Commit"
            )
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Generating commit message with Claude…", /* canBeCancelled = */ true
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Reading branch name…"
                    val branch = worker.getBranchName(project)

                    indicator.text = "Reading diff…"
                    val diff = worker.getDiff(project, selectedChanges)

                    if (diff.isBlank()) {
                        showWarning(project, "No diff found for the selected files.")
                        return
                    }

                    indicator.checkCanceled()
                    indicator.text = "Calling Claude…"

                    val settings = ClaudeCommitSettings.Companion.getInstance()
                    val claudePath = worker.findClaude(settings)
                        ?: throw RuntimeException(
                            "Claude Code CLI not found.\n" +
                            "Install Claude Code or set its path under Settings → Tools → Claude Commit."
                        )

                    val prompt = settings.promptTemplate
                        .replace("{BRANCH}", branch)
                        .replace("{DIFF}", diff)

                    val message = worker.callClaude(claudePath, prompt, indicator)

                    ApplicationManager.getApplication().invokeLater {
                        panel.setCommitMessage(message.trim())
                    }
                } catch (_: ProcessCanceledException) {
                    // user hit the stop button — nothing to do
                } catch (ex: Exception) {
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
