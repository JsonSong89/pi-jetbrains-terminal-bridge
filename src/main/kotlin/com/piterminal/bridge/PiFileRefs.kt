package com.piterminal.bridge

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** Formats @path / @path#L refs the way Pi and this panel already understand. */
object PiFileRefs {

    fun relative(project: Project, file: VirtualFile): String {
        val projectPath = project.basePath ?: ""
        val path = file.path
        return if (projectPath.isNotEmpty() && path.startsWith(projectPath)) {
            path.removePrefix(projectPath).removePrefix("/").removePrefix("\\")
        } else {
            path
        }
    }

    fun atFile(project: Project, file: VirtualFile): String = "@${relative(project, file)}"

    fun atSelection(project: Project, editor: Editor, file: VirtualFile): String {
        val path = relative(project, file)
        val selection = editor.selectionModel
        if (!selection.hasSelection()) return "@$path"
        val startLine = editor.document.getLineNumber(selection.selectionStart) + 1
        val endLine = editor.document.getLineNumber(selection.selectionEnd) + 1
        return if (startLine == endLine) "@$path#L$startLine" else "@$path#L$startLine-$endLine"
    }

    fun atFiles(project: Project, files: Collection<VirtualFile>): String {
        val chosen = preferFiles(files)
        return chosen.joinToString("\n") { atFile(project, it) }
    }

    fun preferFiles(files: Collection<VirtualFile>): List<VirtualFile> {
        val distinct = files.distinctBy { it.path }
        val onlyFiles = distinct.filter { !it.isDirectory }
        return onlyFiles.ifEmpty { distinct.filter { it.isDirectory } }
    }
}
