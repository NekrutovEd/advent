package com.remoteclaude.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Key
import com.remoteclaude.plugin.mdns.MdnsAdvertiser
import com.remoteclaude.plugin.mdns.UdpBroadcaster
import com.remoteclaude.plugin.server.WsServer
import com.remoteclaude.plugin.terminal.TerminalTabsWatcher

class RemoteClaudeStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val server = WsServer.getInstance(project)
        server.start()

        val watcher = TerminalTabsWatcher.getInstance(project)
        watcher.start(server)

        val mdns = MdnsAdvertiser()
        mdns.start(server.port)

        val udp = UdpBroadcaster()
        udp.start(server.port)

        // Store watcher and mdns references so they can be cleaned up later
        project.putUserData(WATCHER_KEY, watcher)
        project.putUserData(MDNS_KEY, mdns)
        project.putUserData(UDP_KEY, udp)
    }

    companion object {
        val WATCHER_KEY: Key<TerminalTabsWatcher> =
            Key.create("remoteclaude.watcher")
        val MDNS_KEY: Key<MdnsAdvertiser> =
            Key.create("remoteclaude.mdns")
        val UDP_KEY: Key<UdpBroadcaster> =
            Key.create("remoteclaude.udp")
    }
}
