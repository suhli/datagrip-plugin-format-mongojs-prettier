package com.suhli.mongoshellformat

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.database.dialects.base.startOffset
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.lang.javascript.psi.JSBlockStatement
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSExpressionStatement
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor
import com.intellij.psi.util.PsiEditorUtil
import com.intellij.psi.util.PsiTreeUtil
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.nio.file.Path


class FormatRequest(val range: TextRange, val text: String) {
    var result = ""
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
        val tempFile = File.createTempFile("format", "js", null)

        runBackgroundableTask("Format MongoJS", project, true) {
            var fraction = 0.0
            it.fraction = fraction
            for (child in requests) {
                writeFile(tempFile, child.text)
                val cmd = GeneralCommandLine(
                    "prettier",
                    "--stdin-filepath",
                    "format.js"
                ).withWorkDirectory(project.basePath)
                cmd.withInput(tempFile)
                val output = ExecUtil.execAndGetOutput(cmd)
                if (output.exitCode != 0) {
                    Log.info("format error:${output.stderr}")
                } else {
                    child.result = output.stdout
                }
                fraction += 1 / requests.size
                it.fraction = fraction
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