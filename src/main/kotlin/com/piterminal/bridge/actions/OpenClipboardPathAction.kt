package com.piterminal.bridge.actions

import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.SimpleListCellRenderer
import com.piterminal.bridge.clipboard.ClipboardPathParser
import com.piterminal.bridge.clipboard.ParsedLocation
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Path
import javax.swing.JList

/**
 * Open a file location copied to the clipboard. Independent of the pi bridge.
 */
class OpenClipboardPathAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null
        e.presentation.isEnabled = project != null && clipboardHasSinglePath()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val nativeFile = nativeClipboardFile()
        if (nativeFile != null) {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(nativeFile)
            if (vf == null || vf.isDirectory) {
                status(project, if (vf?.isDirectory == true) "Clipboard path is a directory" else "No file for clipboard path")
                return
            }
            open(project, vf, null)
            return
        }
        val parsed = ClipboardPathParser.parse(clipboardText()) ?: return
        val contextFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val matches = resolve(project, parsed, contextFile)
        when (matches.size) {
            0 -> status(project, "No file for clipboard path")
            1 -> open(project, matches[0], parsed)
            else -> choose(project, matches, parsed)
        }
    }

    private fun clipboardHasSinglePath(): Boolean {
        if (nativeClipboardFile() != null) return true
        return ClipboardPathParser.parse(clipboardText()) != null
    }

    private fun nativeClipboardFile(): File? {
        val contents = CopyPasteManager.getInstance().contents ?: return null
        return try {
            if (!FileCopyPasteUtil.isFileListFlavorAvailable(contents.transferDataFlavors)) return null
            val files = FileCopyPasteUtil.getFileList(contents) ?: return null
            files.singleOrNull()?.takeIf { it.isFile }
        } catch (_: Exception) {
            null
        }
    }

    private fun clipboardText(): String? {
        return try {
            CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolve(project: Project, parsed: ParsedLocation, contextFile: VirtualFile?): List<VirtualFile> {
        val fs = LocalFileSystem.getInstance()
        val path = parsed.path
        if (isAbsolute(path)) {
            return fs.refreshAndFindFileByPath(normalizeFs(path))
                ?.takeUnless { it.isDirectory }
                ?.let { listOf(it) }
                ?: emptyList()
        }
        val relative = path.removePrefix("./").removePrefix(".\\")
        project.basePath?.let { base ->
            fs.refreshAndFindFileByPath(normalizeFs("$base/$relative"))
                ?.takeUnless { it.isDirectory }
                ?.let { return listOf(it) }
        }
        contextFile?.parent?.takeIf { it.isInLocalFileSystem }?.let { dir ->
            runCatching { Path.of(dir.path).resolve(relative).normalize().toString() }
                .getOrNull()
                ?.let { fs.refreshAndFindFileByPath(normalizeFs(it)) }
                ?.takeUnless { it.isDirectory }
                ?.let { return listOf(it) }
        }
        if (DumbService.isDumb(project)) return emptyList()
        val name = relative.substringAfterLast('/').substringAfterLast('\\')
        if (name.isEmpty()) return emptyList()
        val needle = relative.replace('\\', '/')
        val named = FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project))
            .filter { !it.isDirectory }
        val suffixed = named.filter { vf ->
            val rel = relativeToProject(project, vf) ?: vf.path.replace('\\', '/')
            rel == needle || rel.endsWith("/$needle")
        }
        if (suffixed.size == 1) return suffixed
        if (suffixed.size > 1) return suffixed.distinctBy { it.path }
        if (needle.contains('/')) return emptyList()
        return named.distinctBy { it.path }
    }

    private fun isAbsolute(path: String): Boolean =
        path.startsWith("/") ||
            path.startsWith("\\\\") ||
            path.startsWith("//") ||
            (path.length >= 3 && path[1] == ':' && (path[2] == '\\' || path[2] == '/'))

    private fun normalizeFs(path: String): String = path.replace('\\', File.separatorChar).replace('/', File.separatorChar)

    private fun relativeToProject(project: Project, vf: VirtualFile): String? {
        val index = ProjectFileIndex.getInstance(project)
        val root = index.getContentRootForFile(vf) ?: return project.basePath?.let { base ->
            vf.path.replace('\\', '/').removePrefix(base.replace('\\', '/')).trimStart('/')
        }
        return vf.path.replace('\\', '/').removePrefix(root.path.replace('\\', '/')).trimStart('/')
    }

    private fun choose(project: Project, files: List<VirtualFile>, parsed: ParsedLocation) {
        val sorted = files.sortedBy { relativeToProject(project, it) ?: it.path }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(sorted)
            .setTitle("Open clipboard path")
            .setRenderer(object : SimpleListCellRenderer<VirtualFile>() {
                override fun customize(
                    list: JList<out VirtualFile>,
                    value: VirtualFile?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean
                ) {
                    text = value?.let { relativeToProject(project, it) ?: it.path }.orEmpty()
                }
            })
            .setItemChosenCallback { file -> open(project, file, parsed) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private fun open(project: Project, file: VirtualFile, parsed: ParsedLocation?) {
        val line = parsed?.line
        if (line == null) {
            OpenFileDescriptor(project, file).navigate(true)
            return
        }
        val document = FileDocumentManager.getInstance().getDocument(file)
        if (document == null || document.lineCount == 0) {
            OpenFileDescriptor(project, file, (line - 1).coerceAtLeast(0), 0).navigate(true)
            return
        }
        val line0 = (line - 1).coerceIn(0, document.lineCount - 1)
        val start = document.getLineStartOffset(line0)
        val end = document.getLineEndOffset(line0)
        val maxCol = (end - start).coerceAtLeast(0)
        val col0 = parsed.column?.let { (it - 1).coerceIn(0, maxCol) } ?: 0
        OpenFileDescriptor(project, file, line0, col0).navigate(true)
    }

    private fun status(project: Project, message: String) {
        WindowManager.getInstance().getStatusBar(project)?.info = message
    }
}
