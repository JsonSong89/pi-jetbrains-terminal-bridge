package com.piterminal.bridge.services

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.piterminal.bridge.PiBridgeUi
import com.piterminal.bridge.bridge.PiBridgeInstaller
import com.piterminal.bridge.bridge.PiBridgeServer
import com.piterminal.bridge.settings.PiSettings
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Manages one Pi terminal tab per conversation.
 */
@Service(Service.Level.PROJECT)
class PiTerminalService(private val project: Project) : Disposable {

    interface SessionListener {
        fun onSessionEnded(conversationId: String) {}
        fun onSessionStarted(conversationId: String) {}
    }

    private class TerminalSession(
        val conversationId: String,
        val tabName: String,
        var component: JComponent? = null,
        var sendTextFn: ((String, Boolean) -> Unit)? = null,
        var content: Content? = null,
        var initialized: Boolean = false
    )

    private val logger = Logger.getInstance(PiTerminalService::class.java)
    private val sessions = ConcurrentHashMap<String, TerminalSession>()
    private val listeners = CopyOnWriteArrayList<SessionListener>()
    private val closingIds = ConcurrentHashMap.newKeySet<String>()
    private val timers = CopyOnWriteArrayList<Timer>()
    var onRestartRequested: ((conversationId: String, tabName: String) -> Unit)? = null
    @Volatile
    private var contentListenerInstalled = false
    /** Filled off-EDT. getShellPath() runBlocking-throws if called on the EDT. */
    @Volatile
    private var cachedShellPath: String? = null

    init {
        ApplicationManager.getApplication().executeOnPooledThread {
            cachedShellPath = probeTerminalShellPath()
        }
    }

    fun addListener(listener: SessionListener) {
        listeners.add(listener)
    }

    fun launch(conversationId: String, tabName: String, piSessionId: String, title: String) {
        val existing = sessions[conversationId]
        if (existing != null && isTerminalAlive(existing)) {
            selectTab(conversationId, requestFocus = false)
            return
        }
        if (existing != null) {
            reset(conversationId, notify = false)
        }

        try {
            showNotification("Starting $tabName...", NotificationType.INFORMATION)
            val workingDir = project.basePath ?: System.getProperty("user.home")
            createTerminalTab(conversationId, tabName, workingDir)
            PiStatusWidget.update(project)
            startPiWithDelay(conversationId, piSessionId, title, workingDir)
        } catch (e: Exception) {
            logger.error("Failed to launch Pi terminal for $tabName", e)
            reset(conversationId, notify = false)
            showNotification("Failed to start $tabName: ${e.message}", NotificationType.ERROR)
        }
    }

    fun close(conversationId: String) {
        closingIds.add(conversationId)
        try {
            val session = sessions[conversationId]
            if (session != null) {
                closeTerminalTab(session)
            }
            reset(conversationId, notify = false)
            PiStatusWidget.update(project)
        } finally {
            closingIds.remove(conversationId)
        }
    }

    fun isAlive(conversationId: String): Boolean {
        val session = sessions[conversationId] ?: return false
        return isTerminalAlive(session)
    }

    fun runningCount(): Int = sessions.values.count { isTerminalAlive(it) }

    fun isReady(): Boolean = runningCount() > 0

    /**
     * Send one user message to the Pi TUI.
     * executeCommand treats every '\n' as Enter, so a multi-line draft becomes
     * many messages. Paste the whole block (bracketed paste) and submit once.
     */
    fun sendText(conversationId: String, text: String) {
        val fn = sessions[conversationId]?.sendTextFn ?: return
        val body = text.replace("\r\n", "\n").replace('\r', '\n')
        fn("\u001b[200~$body\u001b[201~", false)
        startTimer(Timer(80, null).apply {
            isRepeats = false
            addActionListener { fn("\r", false) }
        })
    }

    fun selectTab(conversationId: String, requestFocus: Boolean) {
        val session = sessions[conversationId] ?: return
        if (!isTerminalAlive(session)) return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return
        toolWindow.show {
            selectSessionContent(session, requestFocus)
        }
    }

    private fun isTerminalAlive(session: TerminalSession): Boolean {
        val component = session.component ?: return false
        if (component.parent == null) return false
        val content = session.content
        if (content != null) {
            val manager = content.manager ?: return false
            if (!manager.contents.contains(content)) return false
        }
        return session.initialized
    }

    @Suppress("DEPRECATION")
    private fun createTerminalTab(conversationId: String, tabName: String, workingDir: String) {
        val managerClass = Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager")
        val getInstanceMethod = managerClass.getMethod("getInstance", Project::class.java)
        val manager = getInstanceMethod.invoke(null, project)

        val createMethod = managerClass.getMethod(
            "createLocalShellWidget",
            String::class.java,
            String::class.java
        )
        val widget = createMethod.invoke(manager, workingDir, tabName)
        installPathHyperlinks(widget)
        val component = widget.javaClass.getMethod("getComponent").invoke(widget) as JComponent

        val session = TerminalSession(
            conversationId = conversationId,
            tabName = tabName,
            component = component,
            sendTextFn = { text, execute ->
                try {
                    if (execute) {
                        widget.javaClass.getMethod("executeCommand", String::class.java)
                            .invoke(widget, text)
                    } else {
                        val starter = widget.javaClass.getMethod("getTerminalStarter").invoke(widget)
                        if (starter != null) {
                            starter.javaClass.getMethod(
                                "sendString",
                                String::class.java,
                                Boolean::class.javaPrimitiveType
                            ).invoke(starter, text, false)
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to send text to $tabName: ${e.message}")
                }
            },
            initialized = true
        )
        session.content = findContent(session)
        sessions[conversationId] = session
        ensureContentListener()
        selectTab(conversationId, requestFocus = false)
        monitorProcess(session, widget)
        listeners.forEach { it.onSessionStarted(conversationId) }
        logger.info("Pi terminal tab created: $tabName")
    }

    /**
     * Classic JediTerm (what createLocalShellWidget always returns) does not
     * get Reworked-2025's built-in path links. Attach FILE_PATH:LINE filters
     * when the widget exposes addMessageFilter; skip silently otherwise.
     */
    private fun installPathHyperlinks(widget: Any) {
        try {
            val filterClass = Class.forName("com.intellij.execution.filters.Filter")
            val regexpClass = Class.forName("com.intellij.execution.filters.RegexpFilter")
            val add = widget.javaClass.getMethod("addMessageFilter", filterClass)
            val ctor = regexpClass.getConstructor(Project::class.java, String::class.java)
            val file = regexpClass.getField("FILE_PATH_MACROS").get(null) as String
            val line = regexpClass.getField("LINE_MACROS").get(null) as String
            val column = regexpClass.getField("COLUMN_MACROS").get(null) as String
            for (pattern in listOf("$file:$line:$column", "$file:$line")) {
                add.invoke(widget, ctor.newInstance(project, pattern))
            }
        } catch (e: Exception) {
            logger.info("Path hyperlinks not attached: ${e.message}")
        }
    }

    private fun findContent(session: TerminalSession): Content? {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return null
        val component = session.component ?: return null
        return toolWindow.contentManager.contents.firstOrNull { content ->
            content.component == component ||
                SwingUtilities.isDescendingFrom(component, content.component)
        } ?: toolWindow.contentManager.contents.firstOrNull { content ->
            content.displayName == session.tabName
        }
    }

    private fun selectSessionContent(session: TerminalSession, requestFocus: Boolean) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return
        val contentManager = toolWindow.contentManager
        val content = session.content?.takeIf { contentManager.contents.contains(it) }
            ?: findContent(session)?.also { session.content = it }
        if (content != null) {
            contentManager.setSelectedContent(content, requestFocus)
        }
        if (requestFocus) {
            session.component?.requestFocusInWindow()
        }
    }

    private fun closeTerminalTab(session: TerminalSession) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return
        val contentManager = toolWindow.contentManager
        val content = session.content?.takeIf { contentManager.contents.contains(it) }
            ?: findContent(session)
        if (content != null) {
            contentManager.removeContent(content, true)
        }
    }

    private fun ensureContentListener() {
        if (contentListenerInstalled) return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                val removed = event.content
                val session = sessions.values.firstOrNull { session ->
                    session.content == removed ||
                        session.component?.let { SwingUtilities.isDescendingFrom(it, removed.component) } == true
                } ?: return
                val notify = session.conversationId !in closingIds
                reset(session.conversationId, notify = notify)
                PiStatusWidget.update(project)
            }
        })
        contentListenerInstalled = true
    }

    private fun startPiWithDelay(
        conversationId: String,
        piSessionId: String,
        title: String,
        workingDir: String
    ) {
        PiBridgeInstaller.ensureInstalled()
        val endpoint = PiBridgeServer.getInstance(project).ensureStarted()

        val settings = PiSettings.getInstance().state
        val resume = sessionFileExists(piSessionId, workingDir)
        val extra = sanitizeExtraArgs(settings.extraArgs)
        if (extra.dropped.isNotEmpty()) {
            val flags = extra.dropped.distinct().joinToString(", ")
            showNotification(
                "Dropped Extra arguments ($flags). Pi Bridge already sets the session — resume or start from the conversation panel, not via --session / --resume / --continue / --fork.",
                NotificationType.WARNING
            )
        }

        val shell = classifyTerminalShell()
        if (shell == ShellKind.CMD) {
            showNotification(
                "The IDE Terminal shell looks like cmd.exe. Pi Bridge can only inject the live channel in PowerShell (or Git Bash). Pi will still start, but the panel will not stay in sync. Settings → Tools → Terminal → Shell path → powershell.exe",
                NotificationType.WARNING
            )
        }

        val command = buildString {
            // Env prefix for the bridge extension. cmd.exe cannot parse it, so skip.
            if (endpoint != null && shell != ShellKind.CMD) {
                if (shell == ShellKind.POWERSHELL) {
                    append("\$env:PI_LAUNCHER_PORT='${endpoint.port}'; ")
                    append("\$env:PI_LAUNCHER_TOKEN='${endpoint.token}'; ")
                    append("\$env:PI_LAUNCHER_TAB_KEY='$conversationId'; ")
                } else {
                    // `env` prefix works in bash/zsh/fish alike, unlike VAR=val.
                    append("env PI_LAUNCHER_PORT=${endpoint.port} ")
                    append("PI_LAUNCHER_TOKEN='${endpoint.token}' ")
                    append("PI_LAUNCHER_TAB_KEY='$conversationId' ")
                }
            }

            append(settings.piCommand)

            if (resume) {
                append(" --session ${quote(piSessionId)}")
            } else {
                append(" --session-id ${quote(piSessionId)}")
            }
            append(" --name ${quote(title)}")
            append(" --session-dir ${quote(sessionDir().toString())}")

            val model = if (settings.customModelId.isNotBlank()) {
                settings.customModelId
            } else if (settings.model != "Default") {
                settings.model
            } else null

            if (model != null) {
                append(" --model ")
                append(quote(model))
            }

            if (settings.thinkingLevel != "Default" && settings.thinkingLevel.isNotBlank()) {
                append(" --thinking ")
                append(quote(settings.thinkingLevel))
            }

            if (extra.kept.isNotBlank()) {
                append(" ")
                append(extra.kept)
            }
        }

        var attempts = 0
        val timer = object : Timer(300, null) {
            init {
                isRepeats = true
                addActionListener {
                    attempts++
                    val fn = sessions[conversationId]?.sendTextFn
                    if (fn != null) {
                        fn(command, true)
                        stop()
                    } else if (attempts > 20) {
                        logger.warn("Terminal not ready after 6s for $conversationId")
                        stop()
                    }
                }
            }
        }
        startTimer(timer)
    }

    private fun monitorProcess(session: TerminalSession, widget: Any) {
        val conversationId = session.conversationId
        val checkTimer = object : Timer(2000, null) {
            init {
                isRepeats = true
                addActionListener {
                    try {
                        val current = sessions[conversationId]
                        if (current == null || !current.initialized) {
                            stop()
                            return@addActionListener
                        }
                        val connector = widget.javaClass.getMethod("getTtyConnector").invoke(widget)
                        if (connector != null) {
                            val isConnected = connector.javaClass.getMethod("isConnected").invoke(connector) as Boolean
                            if (!isConnected) {
                                stop()
                                handleProcessExit(conversationId, session.tabName)
                            }
                        }
                    } catch (_: Exception) {
                        stop()
                    }
                }
            }
        }
        startTimer(Timer(5000, null).apply {
            isRepeats = false
            addActionListener { startTimer(checkTimer) }
        })
    }

    private fun handleProcessExit(conversationId: String, tabName: String) {
        val session = sessions[conversationId]
        if (session != null) {
            closingIds.add(conversationId)
            try {
                closeTerminalTab(session)
            } finally {
                closingIds.remove(conversationId)
            }
        }
        reset(conversationId, notify = true)
        PiStatusWidget.update(project)
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(PiBridgeUi.NOTIFICATION_GROUP_ID)
            .createNotification(
                "$tabName process exited unexpectedly",
                "Click to restart Pi. On Windows, if the Terminal shell is cmd.exe, switch it to powershell.exe (Settings → Tools → Terminal) — otherwise the launch command fails.",
                NotificationType.WARNING
            )
        notification.addAction(object : NotificationAction("Restart Pi") {
            override fun actionPerformed(e: AnActionEvent, notification: com.intellij.notification.Notification) {
                notification.expire()
                val handler = onRestartRequested
                if (handler != null) {
                    handler(conversationId, tabName)
                } else {
                    logger.warn("No restart handler for $tabName")
                }
            }
        })
        notification.notify(project)
    }

    private fun startTimer(timer: Timer) {
        timers.add(timer)
        timer.start()
    }

    override fun dispose() {
        timers.forEach { it.stop() }
        timers.clear()
        sessions.clear()
        listeners.clear()
    }

    private fun reset(conversationId: String, notify: Boolean) {
        sessions.remove(conversationId)
        if (notify) {
            listeners.forEach { it.onSessionEnded(conversationId) }
        }
    }

    private fun showNotification(message: String, type: NotificationType) {
        PiBridgeUi.notify(project, message, type)
    }

    private enum class ShellKind { POWERSHELL, POSIX, CMD }

    private fun classifyTerminalShell(): ShellKind {
        if (!SystemInfo.isWindows) return ShellKind.POSIX
        val path = cachedShellPath?.trim()?.trim('"')
            ?: PiSettings.getInstance().state.shellPath.trim().trim('"').takeIf { it.isNotEmpty() }
            ?: return ShellKind.POWERSHELL
        val base = shellExecutableName(path)
        return when {
            base == "cmd.exe" || base == "cmd" -> ShellKind.CMD
            base == "powershell.exe" || base == "powershell" ||
                base == "pwsh.exe" || base == "pwsh" -> ShellKind.POWERSHELL
            base.contains("bash") || base == "zsh" || base == "fish" || base == "sh" -> ShellKind.POSIX
            else -> ShellKind.POWERSHELL
        }
    }

    /** Must not run on the EDT: TerminalProjectOptionsProvider.getShellPath runBlocking-throws. */
    private fun probeTerminalShellPath(): String? {
        val specs = listOf(
            Triple("org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider", true, listOf("getShellPath", "getDefaultShellPath")),
            Triple("org.jetbrains.plugins.terminal.TerminalOptionsProvider", false, listOf("getShellPath", "getShellPathOrDefault", "getDefaultShellPath"))
        )
        for ((className, needsProject, getters) in specs) {
            try {
                val cls = Class.forName(className)
                val instance = if (needsProject) {
                    cls.getMethod("getInstance", Project::class.java).invoke(null, project)
                } else {
                    cls.getMethod("getInstance").invoke(null)
                } ?: continue
                for (getter in getters) {
                    try {
                        val value = cls.getMethod(getter).invoke(instance) as? String
                        if (!value.isNullOrBlank()) return value
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }
        return PiSettings.getInstance().state.shellPath.takeIf { it.isNotBlank() }
    }

    private fun shellExecutableName(raw: String): String {
        val trimmed = raw.trim().trim('"')
        val exe = Regex("""(?i)([^\\/]+\.(?:exe|cmd|bat))\b""").find(trimmed)?.groupValues?.get(1)
        val token = exe ?: trimmed.substringBefore(' ').replace('\\', '/').substringAfterLast('/')
        return token.lowercase()
    }

    companion object {
        fun getInstance(project: Project): PiTerminalService = project.service()

        /** Pi session storage the plugin pins via --session-dir. */
        fun sessionDir(): Path =
            Paths.get(System.getProperty("user.home"), ".pi", "agent", "sessions")

        /** Mirrors pi's cwd → directory mapping: strip leading separator, /, \, : become '-'. */
        private fun sessionDirFor(cwd: String): Path {
            val cleaned = cwd.trimStart('/', '\\').replace('/', '-').replace('\\', '-').replace(':', '-')
            return sessionDir().resolve("--$cleaned--")
        }

        fun sessionFileExists(piSessionId: String, cwd: String): Boolean = try {
            val dir = sessionDirFor(cwd)
            Files.isDirectory(dir) && Files.list(dir).use { stream ->
                stream.anyMatch { it.fileName.toString().endsWith("_${piSessionId}.jsonl") }
            }
        } catch (_: Exception) {
            false
        }

        private data class SanitizedExtraArgs(val kept: String, val dropped: List<String>)

        /** Drops flags that would conflict with our --session-id/--session injection. */
        private fun sanitizeExtraArgs(extraArgs: String): SanitizedExtraArgs {
            if (extraArgs.isBlank()) return SanitizedExtraArgs("", emptyList())
            val valueFlags = setOf("--session", "--session-id", "--fork")
            val keep = mutableListOf<String>()
            val dropped = mutableListOf<String>()
            val tokens = extraArgs.trim().split(Regex("\\s+"))
            var skipNext = false
            for (token in tokens) {
                if (skipNext) {
                    skipNext = false
                    continue
                }
                val flag = token.substringBefore('=')
                if (flag in CONFLICT_FLAGS) {
                    dropped.add(flag)
                    skipNext = flag in valueFlags && !token.contains('=')
                    continue
                }
                keep.add(token)
            }
            return SanitizedExtraArgs(keep.joinToString(" "), dropped)
        }

        private val CONFLICT_FLAGS = setOf("--continue", "--resume", "--session", "--session-id", "--fork")

        private fun quote(value: String): String {
            val escaped = if (SystemInfo.isWindows) {
                value.replace("'", "''")
            } else {
                value.replace("'", "'\\''")
            }
            return "'$escaped'"
        }
    }
}
