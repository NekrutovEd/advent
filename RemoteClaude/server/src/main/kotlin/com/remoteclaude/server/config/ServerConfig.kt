package com.remoteclaude.server.config

data class ServerConfig(
    val port: Int = 8765,
    val enableMdns: Boolean = true,
    val enableUdpBroadcast: Boolean = true,
)
