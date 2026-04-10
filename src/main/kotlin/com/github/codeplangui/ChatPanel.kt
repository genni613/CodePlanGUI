package com.github.codeplangui

import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

class ChatPanel(project: Project) : JPanel(BorderLayout()) {

    init {
        if (!JBCefApp.isSupported()) {
            add(
                JLabel(
                    "<html><center>Chat 面板需要 JetBrains Runtime (JBR) 支持<br>" +
                        "请在 Help > Find Action > Switch Boot JDK 中切换至 JBR</center></html>",
                    SwingConstants.CENTER
                ),
                BorderLayout.CENTER
            )
        } else {
            val browser = JBCefBrowser()
            val chatService = ChatService.getInstance(project)
            val bridge = BridgeHandler(browser, chatService)

            bridge.register()

            val html = javaClass.getResourceAsStream("/webview/index.html")
                ?.bufferedReader()
                ?.readText()
                ?: "<html><body><p>Webview not built. Run: cd webview &amp;&amp; npm run build</p></body></html>"

            browser.loadHTML(html)
            add(browser.component, BorderLayout.CENTER)
        }
    }
}
