package com.wififtp.server

import org.apache.ftpserver.ftplet.Authentication
import org.apache.ftpserver.ftplet.AuthenticationFailedException
import org.apache.ftpserver.ftplet.FtpException
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.impl.BaseUser
import java.security.MessageDigest

/**
 * In-memory user manager holding exactly one account.
 * Anonymous login is rejected.
 */
class SingleUserManager(
    private val user: BaseUser,
    private val password: String
) : UserManager {

    private val userName: String = user.name

    override fun getUserByName(username: String?): User? =
        if (username == userName) user else null

    override fun getAllUserNames(): Array<String> = arrayOf(userName)

    override fun delete(username: String?) {
        throw FtpException("Not supported")
    }

    override fun save(user: User?) {
        throw FtpException("Not supported")
    }

    override fun doesExist(username: String?): Boolean = username == userName

    override fun authenticate(authentication: Authentication?): User {
        if (authentication is UsernamePasswordAuthentication) {
            val u = authentication.username
            val p = authentication.password
            if (u != null && p != null && u == userName &&
                MessageDigest.isEqual(p.toByteArray(), password.toByteArray())
            ) {
                return user
            }
        }
        throw AuthenticationFailedException("Authentication failed")
    }

    override fun getAdminName(): String = "no-admin"

    override fun isAdmin(username: String?): Boolean = false
}
