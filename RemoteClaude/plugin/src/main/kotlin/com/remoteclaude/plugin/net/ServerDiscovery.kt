package com.remoteclaude.plugin.net

/**
 * Configuration for connecting to the central RemoteClaude server.
 * By default, connects to localhost:8765 (server runs on the same machine).
 */
object ServerDiscovery {

    private var host: String = "localhost"
    private var port: Int = 8765

    fun getServerHost(): String = host
    fun getServerPort(): Int = port

    fun configure(host: String, port: Int) {
        this.host = host
        this.port = port
    }
}
