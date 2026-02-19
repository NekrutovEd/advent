package com.remoteclaude.plugin.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.intellij.openapi.project.Project
import com.remoteclaude.plugin.server.TabInfo
import com.remoteclaude.plugin.server.WsServer
import java.awt.*
import java.awt.image.BufferedImage
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.table.DefaultTableModel

class RemoteClaudeToolWindow(private val project: Project) {

    private val panel = JPanel(BorderLayout())

    // Status labels
    private val statusLabel = JLabel("Starting...")
    private val clientsLabel = JLabel("Clients: 0")
    private val qrLabel = JLabel()

    // Tabs table
    private val tableModel = DefaultTableModel(arrayOf("ID", "Title", "State", "Project"), 0)
    private val tabsTable = JTable(tableModel)

    // Refresh timer
    private var refreshTimer: Timer? = null

    init {
        buildUi()
        startRefreshTimer()
    }

    private fun buildUi() {
        panel.border = EmptyBorder(8, 8, 8, 8)

        // Top: status + clients
        val topPanel = JPanel(GridLayout(0, 1, 4, 4))
        topPanel.add(statusLabel)
        topPanel.add(clientsLabel)

        // Center: QR code
        val qrPanel = JPanel(FlowLayout(FlowLayout.CENTER))
        qrLabel.preferredSize = Dimension(210, 210)
        qrLabel.horizontalAlignment = SwingConstants.CENTER
        qrLabel.verticalAlignment = SwingConstants.CENTER
        qrPanel.add(qrLabel)

        // Bottom: tabs list
        val scrollPane = JScrollPane(tabsTable)
        scrollPane.preferredSize = Dimension(400, 150)

        val centerPanel = JPanel(BorderLayout(0, 8))
        centerPanel.add(topPanel, BorderLayout.NORTH)
        centerPanel.add(qrPanel, BorderLayout.CENTER)
        centerPanel.add(scrollPane, BorderLayout.SOUTH)

        panel.add(centerPanel, BorderLayout.CENTER)

        // Refresh button
        val refreshButton = JButton("Refresh")
        refreshButton.addActionListener { refresh() }
        panel.add(refreshButton, BorderLayout.SOUTH)
    }

    private fun startRefreshTimer() {
        refreshTimer = Timer(5000) { refresh() }
        refreshTimer?.isRepeats = true
        refreshTimer?.start()
        // Initial refresh on next EDT tick
        SwingUtilities.invokeLater { refresh() }
    }

    private fun refresh() {
        val server = try {
            WsServer.getInstance(project)
        } catch (e: Exception) {
            statusLabel.text = "Plugin not started"
            return
        }

        val port = server.port
        if (port == 0) {
            statusLabel.text = "Server not started"
            return
        }

        val ip = getLanAddress()
        statusLabel.text = "Running: $ip:$port"
        clientsLabel.text = "Clients: ${server.sessionManager.clientCount()}"

        // Update QR code
        val qrImage = generateQrCode(ip, port)
        if (qrImage != null) {
            qrLabel.icon = ImageIcon(qrImage)
            qrLabel.text = null
        } else {
            qrLabel.text = "QR unavailable"
        }

        // Update tabs table
        val tabs = server.registry.getAllTabs()
        updateTabsTable(tabs)
    }

    private fun updateTabsTable(tabs: List<TabInfo>) {
        tableModel.rowCount = 0
        for (tab in tabs) {
            tableModel.addRow(
                arrayOf(
                    tab.id,
                    tab.title,
                    tab.state.name,
                    tab.projectPath ?: ""
                )
            )
        }
    }

    private fun getLanAddress(): String {
        // Keywords that indicate a VPN/tunnel virtual adapter (checked in name and display name)
        val vpnKeywords = listOf("tun", "tap", "ppp", "wg", "vpn", "tunnel", "wireguard", "proton", "nordlynx", "openvpn")

        fun isVpnInterface(iface: NetworkInterface): Boolean {
            val name = iface.name.lowercase()
            val display = iface.displayName.lowercase()
            return vpnKeywords.any { kw -> name.contains(kw) || display.contains(kw) }
        }

        fun isPrivateLan(addr: String): Boolean =
            addr.startsWith("192.168.") ||
            addr.startsWith("10.") ||
            Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(addr)

        return try {
            // Walk all network interfaces, skip VPN/loopback/virtual, pick first LAN IPv4
            val candidate = NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.filter { iface -> iface.isUp && !iface.isLoopback && !iface.isVirtual && !isVpnInterface(iface) }
                ?.flatMap { iface -> iface.inetAddresses.asSequence() }
                ?.filterIsInstance<Inet4Address>()
                ?.filter { addr -> !addr.isLoopbackAddress }
                ?.map { addr -> addr.hostAddress }
                ?.firstOrNull { addr -> isPrivateLan(addr) }

            candidate ?: run {
                // Fallback: datagram-socket trick (may return VPN address if VPN is on,
                // but better than nothing when no LAN interface was matched above)
                java.net.DatagramSocket().use { socket ->
                    socket.connect(InetAddress.getByName("8.8.8.8"), 53)
                    socket.localAddress.hostAddress ?: InetAddress.getLocalHost().hostAddress
                }
            }
        } catch (_: Exception) {
            InetAddress.getLocalHost().hostAddress
        }
    }

    private fun generateQrCode(ip: String, port: Int): BufferedImage? {
        return try {
            val content = "rc://$ip:$port"
            val writer = QRCodeWriter()
            val matrix = writer.encode(content, BarcodeFormat.QR_CODE, 200, 200)
            MatrixToImageWriter.toBufferedImage(matrix)
        } catch (e: Exception) {
            null
        }
    }

    fun getContent(): JComponent = panel

    fun dispose() {
        refreshTimer?.stop()
        refreshTimer = null
    }
}
