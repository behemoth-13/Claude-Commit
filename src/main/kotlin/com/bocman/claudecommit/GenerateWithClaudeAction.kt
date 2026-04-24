package com.bocman.claudecommit

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
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

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val commitMessageI = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
        // Capture the selected changes on EDT before entering the background thread.
        val changes = e.getData(VcsDataKeys.CHANGES)?.toList()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Generating commit message with Claude…", /* canBeCancelled = */ true
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Reading branch name…"
                    val branch = getBranchName(project)

                    indicator.text = "Reading diff…"
                    val diff = getDiff(project, changes)

                    if (diff.isBlank()) {
                        showWarning(project, "No diff found for the selected files.\nMake sure the files have unsaved changes.")
                        return
                    }

                    indicator.checkCanceled()
                    indicator.text = "Calling Claude…"

                    val settings = ClaudeCommitSettings.getInstance()
                    val claudePath = findClaude(settings)
                        ?: throw RuntimeException(
                            "Claude Code CLI not found.\n" +
                            "Install Claude Code or configure its path under Settings → Tools → Claude Commit."
                        )

                    val prompt = settings.promptTemplate
                        .replace("{BRANCH}", branch)
                        .replace("{DIFF}", diff)

                    val message = callClaude(claudePath, prompt, indicator)

                    ApplicationManager.getApplication().invokeLater {
                        commitMessageI?.setCommitMessage(message.trim())
                            ?: showInfo(project, message.trim())
                    }
                } catch (_: ProcessCanceledException) {
                    // user hit the stop button — nothing to do
                } catch (ex: Exception) {
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

    /**
     * Build the diff for the given [changes].
     *
     * IntelliJ's commit dialog (without staging area) never runs `git add` until
     * the actual commit, so `git diff --cached` is usually empty. Instead we run
     * `git diff HEAD -- <files>` against the specific paths checked in the dialog.
     * This reads directly from the working tree and is always current.
     */
    internal fun getDiff(project: Project, changes: Collection<Change>?): String {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
            ?: return ""
        val workDir = File(repo.root.path)

        if (!changes.isNullOrEmpty()) {
            val filePaths = changes.mapNotNull { change ->
                (change.afterRevision?.file ?: change.beforeRevision?.file)?.path
            }
            if (filePaths.isNotEmpty()) {
                // Working-tree vs HEAD for selected files — works regardless of staging state.
                val diff = git(workDir, "diff", "HEAD", "--", *filePaths.toTypedArray())
                if (diff.isNotBlank()) return diff

                // Newly added files that are already staged show up here.
                val cached = git(workDir, "diff", "--cached", "--", *filePaths.toTypedArray())
                if (cached.isNotBlank()) return cached
            }
        }

        // Fallback when called without a change list (e.g. from tests or old handler path).
        val staged = git(workDir, "diff", "--cached")
        return staged.ifBlank { git(workDir, "diff", "HEAD") }
    }

    private fun git(workDir: File, vararg args: String): String {
        val process = ProcessBuilder("git", *args)
            .directory(workDir)
            .redirectErrorStream(true)
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

            val pb = ProcessBuilder(claudePath, "--print")
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
