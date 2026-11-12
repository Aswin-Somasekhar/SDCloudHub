package com.sdcloud.hub.server

import android.util.Log
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File

class FtpServerManager(
    private val port: Int = 2121,
    private val rootDir: File,
    private val anonymousAccess: Boolean = true
) {
    companion object {
        private const val TAG = "FtpServerManager"
    }

    private var server: FtpServer? = null

    fun start() {
        if (server != null && !server!!.isStopped) return

        try {
            val serverFactory = FtpServerFactory()
            val listenerFactory = ListenerFactory().apply {
                this.port = this@FtpServerManager.port
            }

            serverFactory.addListener("default", listenerFactory.createListener())

            // Configure Default User (anonymous or admin)
            val userManager = serverFactory.userManager
            val user = BaseUser().apply {
                name = if (anonymousAccess) "anonymous" else "admin"
                password = if (anonymousAccess) "" else "admin"
                homeDirectory = rootDir.absolutePath
                authorities = listOf(WritePermission())
            }

            userManager.save(user)

            server = serverFactory.createServer()
            server?.start()
            Log.i(TAG, "FTP Server started on port $port serving ${rootDir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FTP Server", e)
        }
    }

    fun stop() {
        try {
            server?.stop()
            server = null
            Log.i(TAG, "FTP Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping FTP Server", e)
        }
    }

    fun isRunning(): Boolean = server != null && !server!!.isStopped
}
