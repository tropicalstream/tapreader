package com.tapreader.app

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.util.EnumSet

/**
 * Minimal SMB/CIFS (SMB2/3) access run ON the glasses. A browser can't speak SMB,
 * so the web companion posts connection details here and the glasses do the
 * listing/reading, then import the chosen file into the library. Each call opens
 * a fresh connection — simple and robust for browse/download.
 */
object SmbClient {
    data class Entry(val name: String, val isDir: Boolean, val size: Long)

    private fun <T> withShare(
        host: String, share: String, user: String, pass: String, domain: String, block: (DiskShare) -> T
    ): T {
        val client = SMBClient()
        client.connect(host).use { conn ->
            val ac = if (user.isBlank()) AuthenticationContext.anonymous()
            else AuthenticationContext(user, pass.toCharArray(), domain)
            val session = conn.authenticate(ac)
            (session.connectShare(share) as DiskShare).use { disk -> return block(disk) }
        }
    }

    /** Enumerate the server's browseable disk shares (srvsvc NetShareEnum), so the
     *  user can start from the root of the NAS instead of typing a share name. */
    fun shares(host: String, user: String, pass: String, domain: String): List<String> {
        val client = SMBClient()
        client.connect(host).use { conn ->
            val ac = if (user.isBlank()) AuthenticationContext.anonymous()
            else AuthenticationContext(user, pass.toCharArray(), domain)
            val session = conn.authenticate(ac)
            val transport = SMBTransportFactories.SRVSVC.getTransport(session)
            return ServerService(transport).shares0
                .map { it.netName.orEmpty() }
                .filter { it.isNotBlank() && !it.endsWith("$") }   // hide IPC$/ADMIN$/print$
                .sortedBy { it.lowercase() }
        }
    }

    /** List a directory (backslash-separated path; "" is the share root). */
    fun list(host: String, share: String, user: String, pass: String, domain: String, path: String): List<Entry> =
        withShare(host, share, user, pass, domain) { disk ->
            disk.list(path)
                .filter { it.fileName != "." && it.fileName != ".." }
                .map { Entry(it.fileName, it.fileAttributes and 0x10L != 0L, it.endOfFile) }
                .sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
        }

    fun read(host: String, share: String, user: String, pass: String, domain: String, path: String): ByteArray =
        withShare(host, share, user, pass, domain) { disk ->
            disk.openFile(
                path, EnumSet.of(AccessMask.GENERIC_READ), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null
            ).use { it.inputStream.readBytes() }
        }

    fun join(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir\\$name"
}
