package com.wififtp.server

import org.apache.ftpserver.ConnectionConfigFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File

object FtpServerBuilder {

    fun build(cfg: AppSettings): FtpServer {
        val home = File(cfg.homePath)
        require(home.isDirectory) { "Folder not found: ${cfg.homePath}" }

        val user = BaseUser()
        user.setName(cfg.username)
        user.setPassword(cfg.password)
        user.setHomeDirectory(home.absolutePath)
        user.setEnabled(true)
        user.setMaxIdleTime(600)

        val authorities = ArrayList<Authority>()
        authorities.add(ConcurrentLoginPermission(10, 10))
        if (!cfg.readOnly) authorities.add(WritePermission())
        user.setAuthorities(authorities)

        val listenerFactory = ListenerFactory()
        listenerFactory.setPort(cfg.port)
        listenerFactory.setIdleTimeout(600)

        val connectionConfig = ConnectionConfigFactory()
        connectionConfig.setAnonymousLoginEnabled(false)
        connectionConfig.setMaxLogins(10)
        connectionConfig.setMaxLoginFailures(5)
        connectionConfig.setLoginFailureDelay(500)

        val serverFactory = FtpServerFactory()
        serverFactory.addListener("default", listenerFactory.createListener())
        serverFactory.setUserManager(SingleUserManager(user, cfg.password))
        serverFactory.setConnectionConfig(connectionConfig.createConnectionConfig())
        return serverFactory.createServer()
    }
}
