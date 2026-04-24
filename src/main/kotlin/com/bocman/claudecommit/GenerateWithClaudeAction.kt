package com.bocman.claudecommit

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import git4idea.repo.GitRepositoryManager
import java.io.File
import java.util.concurrent.CompletableFuture

class GenerateWithClaudeAction : AnAction("Generate with Claude"), DumbAware {

    companion object {
        private val LOG = Logger.getInstance(GenerateWithClaudeAction::class.java)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val ui = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
        e.presentation.isVisible = e.project != null
        e.presentation.isEnabled = ui != null && ui.getIncludedChanges().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val commitMessageI = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
        val changes = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)?.getIncludedChanges() ?: emptyList()

        if (changes.isEmpty()) {
            LOG.info("[ClaudeCommit] Toolbar action triggered with no checked files")
            Messages.showWarningDialog(project, "No files checked. Select files to commit first.", "Claude Commit")
            return
        }

        LOG.info("[ClaudeCommit] Toolbar action triggered — ${changes.size} file(s): ${changes.map { it.virtualFile?.name ?: "?" }}")

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Generating commit message with Claude…", /* canBeCancelled = */ true
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Reading branch name…"
                    val branch = getBranchName(project)
                    LOG.info("[ClaudeCommit] Branch: $branch")

                    indicator.text = "Reading diff…"
                    val diff = getDiff(project, changes)

                    if (diff.isBlank()) {
                        LOG.warn("[ClaudeCommit] Diff is empty for selected files")
                        showWarning(project, "No diff found for the selected files.\nMake sure the files have unsaved changes.")
                        return
                    }
                    LOG.info("[ClaudeCommit] Diff: ${diff.lines().size} lines, ${diff.length} chars")
                    LOG.debug("[ClaudeCommit] Diff content:\n$diff")

                    indicator.checkCanceled()
                    indicator.text = "Calling Claude…"

                    val settings = ClaudeCommitSettings.getInstance()
                    val claudePath = findClaude(settings)
                        ?: throw RuntimeException(
                            "Claude Code CLI not found.\n" +
                            "Install Claude Code or configure its path under Settings → Tools → Claude Commit."
                        )
                    LOG.info("[ClaudeCommit] Using Claude at: $claudePath")

                    val prompt = settings.promptTemplate
                        .replace("{BRANCH}", branch)
                        .replace("{DIFF}", diff)

                    LOG.info("[ClaudeCommit] Calling Claude (prompt: ${prompt.length} chars)…")
                    val message = callClaude(claudePath, prompt, indicator)
                    LOG.info("[ClaudeCommit] Claude responded (${message.length} chars): ${message.take(120).replace('\n', '↵')}")

                    ApplicationManager.getApplication().invokeLater {
                        commitMessageI?.setCommitMessage(message.trim())
                            ?: showInfo(project, message.trim())
                    }
                } catch (_: ProcessCanceledException) {
                    LOG.info("[ClaudeCommit] Generation cancelled by user")
                } catch (ex: Exception) {
                    LOG.error("[ClaudeCommit] Generation failed", ex)
                    showError(project, ex.message ?: ex.toString())
                }
            }
        })
    }

    // -------------------------------------------------------------------------
    // Git helpers
    // -------------------------------------------------------------------------

    internal fun getBranchName(project: Project): String =
        GitRepositoryManager.getInstance(project)
            .repositories
            .firstOrNull()
            ?.currentBranchName
            ?: "unknown"

    internal fun getDiff(project: Project, changes: Collection<Change>?): String {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
            ?: return ""
        val workDir = File(repo.root.path)

        if (!changes.isNullOrEmpty()) {
            val filePaths = changes.mapNotNull { change ->
                (change.afterRevision?.file ?: change.beforeRevision?.file)?.path
            }
            LOG.info("[ClaudeCommit] getDiff — ${filePaths.size} path(s): $filePaths")

            if (filePaths.isNotEmpty()) {
                val diff = git(workDir, "diff", "HEAD", "--", *filePaths.toTypedArray())
                if (diff.isNotBlank()) {
                    LOG.info("[ClaudeCommit] getDiff strategy: HEAD diff")
                    return diff
                }

                val cached = git(workDir, "diff", "--cached", "--", *filePaths.toTypedArray())
                if (cached.isNotBlank()) {
                    LOG.info("[ClaudeCommit] getDiff strategy: cached diff")
                    return cached
                }

                val noIndex = filePaths.joinToString("\n") { path ->
                    git(workDir, "diff", "--no-index", "/dev/null", path)
                }
                if (noIndex.isNotBlank()) {
                    LOG.info("[ClaudeCommit] getDiff strategy: no-index (empty repo)")
                    return noIndex
                }

                LOG.warn("[ClaudeCommit] getDiff: all strategies returned empty for $filePaths")
            }
        }

        // Fallback when called without a change list (e.g. from tests).
        LOG.info("[ClaudeCommit] getDiff strategy: fallback (no change list)")
        val staged = git(workDir, "diff", "--cached")
        return staged.ifBlank { git(workDir, "diff", "HEAD") }
    }

    private fun git(workDir: File, vararg args: String): String {
        val process = ProcessBuilder("git", *args)
            .directory(workDir)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }

    // -------------------------------------------------------------------------
    // Claude CLI
    // -------------------------------------------------------------------------

    internal fun findClaude(settings: ClaudeCommitSettings): String? {
        val configured = settings.claudePath.trim()
        if (configured.isNotBlank()) {
            return if (File(configured).canExecute()) configured
            else throw RuntimeException("Configured Claude path not executable: $configured")
        }
        val home = System.getProperty("user.home")
        listOf(
            "/usr/local/bin/claude",
            "/opt/homebrew/bin/claude",
            "$home/.local/bin/claude",
            "$home/.npm-global/bin/claude",
            "/usr/bin/claude",
        ).firstOrNull { File(it).canExecute() }?.let { return it }

        return try {
            val p = ProcessBuilder("which", "claude").redirectErrorStream(true).start()
            val result = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() == 0 && result.isNotBlank()) result else null
        } catch (_: Exception) { null }
    }

    /**
     * Runs `claude --print` with the prompt piped via a temp file.
     * Polls [indicator] every 200 ms so the user can cancel with the stop button;
     * the claude process is force-killed on cancellation.
     */
    internal fun callClaude(claudePath: String, prompt: String, indicator: ProgressIndicator): String {
        val tmp = File.createTempFile("claude_commit_", ".txt")
        try {
            tmp.writeText(prompt)

            val pb = ProcessBuilder(claudePath, "--print", "--effort", ClaudeCommitSettings.getInstance().effortLevel.cliValue)
                .redirectErrorStream(false)
                .redirectInput(tmp)
            with(pb.environment()) {
                val home = System.getProperty("user.home")
                putIfAbsent("HOME", home)
                val path = getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin")
                put("PATH", "$path:/usr/local/bin:/opt/homebrew/bin:$home/.local/bin:$home/.npm-global/bin")
            }

            val process = pb.start()

            // Read streams off the main thread so the process pipe buffer never fills up.
            val stdoutFuture = CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().readText()
            }
            val stderrFuture = CompletableFuture.supplyAsync {
                process.errorStream.bufferedReader().readText()
            }

            // Poll for cancellation — gives the stop button in the progress bar its power.
            try {
                while (!stdoutFuture.isDone) {
                    indicator.checkCanceled()
                    Thread.sleep(200)
                }
            } catch (e: ProcessCanceledException) {
                process.destroyForcibly()
                throw e
            }

            val stdout = stdoutFuture.get()
            val stderr = stderrFuture.get()
            if (process.waitFor() != 0) {
                throw RuntimeException("Claude failed:\n${stderr.take(600)}")
            }
            return stdout
        } finally {
            tmp.delete()
        }
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private fun showWarning(project: Project, msg: String) =
        ApplicationManager.getApplication().invokeLater {
            Messages.showWarningDialog(project, msg, "Claude Commit")
        }

    private fun showError(project: Project, msg: String) =
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, msg, "Claude Commit — Error")
        }

    private fun showInfo(project: Project, msg: String) =
        ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage(project, msg, "Claude Commit")
        }
}
