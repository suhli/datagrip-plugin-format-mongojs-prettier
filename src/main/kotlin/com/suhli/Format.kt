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
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.TextRange
import com.intellij.prettierjs.PrettierConfiguration
import com.intellij.prettierjs.PrettierLanguageService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor
import com.intellij.psi.util.PsiEditorUtil
import com.intellij.psi.util.elementType
import com.intellij.sql.dialects.SqlLanguageDialect
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


enum class BlockType {
    JS,
    SQL
}

class FormatRequest(val range: TextRange, val text: String, val type: BlockType) {
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
        val project = source.project
        val prettierContext = getPrettierContext(source)
        if (!prettierContext.isEnable()) {
            val notification = NotificationGroupManager.getInstance().getNotificationGroup("MongoJS Format")
                .createNotification("Prettier disabled!", NotificationType.ERROR)
                .addAction(OpenPrettierConfigAction())
            notification.notify(project)
            return
        }
        val requests = source
            .children
            .filter {
                it is JSExpressionStatement || it.elementType?.toString()?.equals("JS:SQL_SOURCE") ?: false
            }
            .sortedByDescending {
                it.startOffset
            }
            .map {
                FormatRequest(it.textRange, it.text, if (it is JSExpressionStatement) BlockType.JS else BlockType.SQL)
            }
        val tempFile = File.createTempFile("format", ".js", null)
        //EDT write error
        runBackgroundableTask("MongoJS Format") {
            for (child in requests) {
                //mongo shell
                if (child.type.equals(BlockType.JS)) {
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
                    val result = awaitFuture.result
                    if (result.isNotBlank()) {
                        WriteCommandAction.runWriteCommandAction(project, Computable {
                            editor.document.replaceString(child.range.startOffset, child.range.endOffset, result)
                        })
                    }
                }
                //mongo sql
                else {
                    WriteCommandAction.runWriteCommandAction(project, Computable {
                        val language = SqlLanguageDialect.getGenericDialect()
                        val file = PsiFileFactory.getInstance(project).createFileFromText(language, child.text)
                        CodeStyleManager.getInstance(project).reformat(file)
                        editor.document.replaceString(child.range.startOffset, child.range.endOffset, file.text)
                        null
                    })
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