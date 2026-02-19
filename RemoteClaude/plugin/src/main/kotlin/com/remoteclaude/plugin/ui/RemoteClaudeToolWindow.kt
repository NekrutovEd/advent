package com.remoteclaude.plugin.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.intellij.openapi.project.Project
import com.remoteclaude.plugin.net.NetworkUtils
import com.remoteclaude.plugin.net.ServerDiscovery
import com.remoteclaude.plugin.server.TabInfo
import com.remoteclaude.plugin.server.WsPluginClient
import com.remoteclaude.plugin.server.WsServer
import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.table.DefaultTableModel

class RemoteClaudeToolWindow(private val project: Project) {

    private val panel = JPanel(BorderLayout())

    // Status labels
    private val statusLabel = JLabel("Starting...")
    private val serverConnectionLabel = JLabel("Server: Connecting...")
    private val localPortLabel = JLabel("Local port: -")

    // Tabs table
    private val tableModel = DefaultTableModel(arrayOf("Title", "State"), 0)
    private val tabsTable = JTable(tableModel)

    // Refresh timer
    private var refreshTimer: Timer? = null

    init {
        buildUi()
        startRefreshTimer()
    }

    private fun buildUi() {
        panel.border = EmptyBorder(8, 8, 8, 8)

        // Top: status
        val topPanel = JPanel(GridLayout(0, 1, 4, 4))
        topPanel.add(statusLabel)
        topPanel.add(serverConnectionLabel)
        topPanel.add(localPortLabel)

        // Middle: tabs table
        val scrollPane = JScrollPane(tabsTable)
        scrollPane.preferredSize = Dimension(300, 200)

        val centerPanel = JPanel(BorderLayout(0, 8))
        centerPanel.add(topPanel, BorderLayout.NORTH)
        centerPanel.add(scrollPane, BorderLayout.CENTER)

        panel.add(centerPanel, BorderLayout.CENTER)

        // Refresh button
        val refreshButton = JButton("Refresh")
        refreshButton.addActionListener { refresh() }
        panel.add(refreshButton, BorderLayout.SOUTH)
    }

    private fun startRefreshTimer() {
        refreshTimer = Timer(3000) { refresh() }
        refreshTimer?.isRepeats = true
        refreshTimer?.start()
        SwingUtilities.invokeLater { refresh() }
    }

    private fun refresh() {
        // Check WsPluginClient status
        val pluginClient = try {
            WsPluginClient.getInstance(project)
        } catch (e: Exception) {
            null
        }

        if (pluginClient != null) {
            val serverHost = ServerDiscovery.getServerHost()
            val serverPort = ServerDiscovery.getServerPort()
            if (pluginClient.connected) {
                serverConnectionLabel.text = "Server: Connected ($serverHost:$serverPort)"
                serverConnectionLabel.foreground = Color(0, 160, 0)
            } else {
                serverConnectionLabel.text = "Server: Reconnecting ($serverHost:$serverPort)..."
                serverConnectionLabel.foreground = Color(200, 100, 0)
            }
            statusLabel.text = "Plugin ID: ${pluginClient.pluginId}"
        } else {
            serverConnectionLabel.text = "Server: Not started"
            serverConnectionLabel.foreground = Color.GRAY
        }

        // Local HTTP server (for /api/notify)
        val server = try {
            WsServer.getInstance(project)
        } catch (e: Exception) {
            null
        }
        val localPort = server?.port ?: 0
        localPortLabel.text = "Local notify port: $localPort"

        // Update tabs table
        val tabs = server?.registry?.getAllTabs() ?: emptyList()
        updateTabsTable(tabs)
    }

    private fun updateTabsTable(tabs: List<TabInfo>) {
        tableModel.rowCount = 0
        for (tab in tabs) {
            tableModel.addRow(arrayOf(tab.title, tab.state.name))
        }
    }

    fun getContent(): JComponent = panel

    fun dispose() {
        refreshTimer?.stop()
        refreshTimer = null
    }
}
