package com.suhli

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.prettierjs.PrettierConfigurable

class OpenPrettierConfigAction : NotificationAction("Open Prettier Configuration"){
    override fun actionPerformed(e: AnActionEvent, notification: Notification) {
        val project = e.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(project,PrettierConfigurable::class.java)
    }
}