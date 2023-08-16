package com.suhli

import com.intellij.database.dialects.base.startOffset
import com.intellij.javascript.nodejs.util.NodePackage
import com.intellij.lang.javascript.psi.JSExpressionStatement
import com.intellij.lang.javascript.service.JSLanguageServiceUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.util.TextRange
import com.intellij.prettierjs.PrettierConfiguration
import com.intellij.prettierjs.PrettierLanguageService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor
import com.intellij.psi.util.PsiEditorUtil
import java.io.File
import java.io.FileWriter
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible

inline fun <reified T> T.callPrivateFunc(name: String, vararg args: Any?): Any? =
    T::class
        .declaredMemberFunctions
        .firstOrNull { it.name == name }
        ?.apply { isAccessible = true }
        ?.call(this, *args)


inline fun <reified T> T.callPublic(name: String, vararg args: Any?): Any? =
    T::class
        .declaredMemberFunctions
        .firstOrNull { it.name == name }
        ?.call(this, *args)

class FormatRequest(val range: TextRange, val text: String) {
    var result = ""
}

class PrettierContext(
    val service: PrettierLanguageService,
    val nodePackage: NodePackage,
    val configuration: PrettierConfiguration
) {
    public fun isEnable(): Boolean {
        return !(configuration.callPrivateFunc("isDisabled") as Boolean)
    }
}

class Format : PostFormatProcessor {
    companion object {
        val Log = Logger.getInstance(PostFormatProcessor::class.toString())
    }


    override fun processElement(source: PsiElement, settings: CodeStyleSettings): PsiElement {
        if (!source.language.id.startsWith("MongoJS")) {
            return source
        }
        format(source.containingFile)
        return source
    }

    private fun writeFile(file: File, text: String) {
        val writer = FileWriter(file, false)
        writer.write(text)
        writer.close()
    }

    private fun getPrettierContext(file: PsiFile): PrettierContext {
        val configuration = PrettierConfiguration.getInstance(file.project)
        val nodePackage = configuration.getPackage(file)
        val vFile = file.virtualFile
        return PrettierContext(
            PrettierLanguageService.getInstance(file.project, vFile, nodePackage),
            nodePackage,
            configuration
        )
    }

    private fun format(source: PsiFile) {
        val editor = PsiEditorUtil.findEditor(source) ?: return
        val requests = source
            .children
            .filterIsInstance<JSExpressionStatement>()
            .sortedByDescending {
                it.startOffset
            }
            .map { FormatRequest(it.textRange, it.text) }
        val project = source.project
        val prettierContext = getPrettierContext(source)
        if (!prettierContext.isEnable()) {
            val notification = NotificationGroupManager.getInstance().getNotificationGroup("MongoJS Format")
                .createNotification("Prettier disabled!", NotificationType.ERROR)
                .addAction(OpenPrettierConfigAction())
            notification.notify(source.project)
            return
        }
        val tempFile = File.createTempFile("format", ".js", null)
        runBackgroundableTask("MongoJS Format") {
            for (child in requests) {
                writeFile(tempFile, child.text)
                val awaitFuture = JSLanguageServiceUtil.awaitFuture(
                    prettierContext.service.format(
                        tempFile.path,
                        null,
                        child.text,
                        prettierContext.nodePackage,
                        child.range
                    ),
                    2000
                ) ?: continue
                if (awaitFuture.unsupported) {
                    Log.info("error format unsupported:${tempFile.path}")
                    continue
                }
                if (awaitFuture.error != null) {
                    Log.info("error format:${awaitFuture.error}")
                    continue
                }
                child.result = awaitFuture.result
            }
            WriteCommandAction.runWriteCommandAction(project) {
                for (request in requests.filter { it.result.isNotBlank() }) {
                    editor.document.replaceString(request.range.startOffset, request.range.endOffset, request.result)
                }
            }
            it.fraction = 1.0
        }
    }

    override fun processText(source: PsiFile, rangeToReformat: TextRange, settings: CodeStyleSettings): TextRange {
        if (!source.language.id.startsWith("MongoJS")) {
            return rangeToReformat
        }
        format(source)
        return rangeToReformat
    }
}