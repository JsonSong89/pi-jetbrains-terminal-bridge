package com.piterminal.bridge.actions

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.piterminal.bridge.PiFileRefs
import com.piterminal.bridge.conversations.PiConversationService

/**
 * Send file(s) from Project View to the active conversation input.
 * Format: @relative/path/to/file.go
 *
 * Project View often also exposes the parent directory. If any file is
 * selected, only files are sent — never their ancestor folders.
 */
class SendFileToPiAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val virtualFiles = getFiles(e)
        if (virtualFiles.isEmpty()) return

        val references = PiFileRefs.atFiles(project, virtualFiles)
        if (references.isBlank()) return
        PiConversationService.getInstance(project).appendToDraft(references, block = true)
    }

    override fun update(e: AnActionEvent) {
        // Shared shortcut with SendSelection: disable this action for editor
        // keystrokes, but keep it on Project View / Editor Tab popups.
        val fromFilePopup = e.place == ActionPlaces.PROJECT_VIEW_POPUP ||
            e.place == ActionPlaces.EDITOR_TAB_POPUP
        val editorHasFocus = e.getData(CommonDataKeys.EDITOR) != null
        e.presentation.isVisible = true
        e.presentation.isEnabled = e.project != null &&
            getFiles(e).isNotEmpty() &&
            (fromFilePopup || !editorHasFocus)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun getFiles(e: AnActionEvent): List<VirtualFile> {
        val found = LinkedHashSet<VirtualFile>()

        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.forEach { found.add(it) }
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { found.add(it) }
        e.getData(PlatformDataKeys.VIRTUAL_FILE_ARRAY)?.forEach { found.add(it) }

        e.getData(LangDataKeys.PSI_ELEMENT_ARRAY)?.forEach { addPsiFile(found, it) }
        e.getData(CommonDataKeys.PSI_ELEMENT)?.let { addPsiFile(found, it) }
        e.getData(CommonDataKeys.PSI_FILE)?.virtualFile?.let { found.add(it) }

        e.getData(CommonDataKeys.NAVIGATABLE_ARRAY)?.forEach { navigatable ->
            when (navigatable) {
                is VirtualFile -> found.add(navigatable)
                is PsiFileSystemItem -> navigatable.virtualFile?.let { found.add(it) }
            }
        }

        return PiFileRefs.preferFiles(found)
    }

    private fun addPsiFile(found: MutableSet<VirtualFile>, element: PsiElement) {
        when (element) {
            is PsiFile -> element.virtualFile?.let { found.add(it) }
            is PsiDirectory -> element.virtualFile?.let { found.add(it) }
            is PsiFileSystemItem -> element.virtualFile?.let { found.add(it) }
            else -> element.containingFile?.virtualFile?.let { found.add(it) }
        }
    }
}
