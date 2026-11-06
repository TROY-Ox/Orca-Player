package com.troy.orcaplayer.sources.network

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.FilterInputStream
import java.time.Duration

/**
 * FTP and FTPS (explicit TLS) through Apache Commons Net. FTP sends one file at a time per
 * connection, so browsing keeps its own connection and every read opens another one, starting at
 * the wanted offset with REST.
 */
class FtpShareClient(private val conn: ShareConnection) : ShareClient {
    override val root: String = "/" + conn.path.trim('/')

    private val browser: FTPClient = login()

    private fun login(): FTPClient {
        val ftp = if (conn.type == ShareType.FTPS) FTPSClient(false) else FTPClient()
        try {
            ftp.connectTimeout = 15_000
            ftp.setDefaultTimeout(30_000)
            ftp.controlEncoding = "UTF-8"
            ftp.setControlKeepAliveTimeout(Duration.ofSeconds(60))
            ftp.connect(conn.host, conn.effectivePort)
            if (!FTPReply.isPositiveCompletion(ftp.replyCode)) throw ShareException("The server refused the connection")
            if (ftp is FTPSClient) {
                ftp.execPBSZ(0)
                ftp.execPROT("P")
            }
            val user = conn.username.ifBlank { "anonymous" }
            val pass = if (conn.anonymous) "guest" else conn.password
            if (!ftp.login(user, pass)) throw ShareException("Wrong user name or password")
            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
            ftp.soTimeout = 30_000
            return ftp
        } catch (e: Exception) {
            runCatching { ftp.disconnect() }
            throw if (e is ShareException) e else ShareException(e.message ?: "Couldn't connect", e)
        }
    }

    @Synchronized
    override fun list(path: String): List<ShareEntry> = wrap {
        browser.listFiles(path.ifEmpty { "/" }).filterNotNull().mapNotNull { f ->
            val name = f.name ?: return@mapNotNull null
            if (name == "." || name == ".." || name.startsWith(".")) return@mapNotNull null
            ShareEntry(
                name = name,
                path = ShareUris.join(path, name),
                isFolder = f.isDirectory,
                size = if (f.isDirectory) -1 else f.size,
                modified = f.timestamp?.timeInMillis ?: 0,
            )
        }
    }

    @Synchronized
    private fun sizeOf(path: String): Long = runCatching {
        browser.mlistFile(path)?.size?.takeIf { it >= 0 }
            ?: browser.listFiles(path).firstOrNull()?.size
    }.getOrNull() ?: -1L

    override fun open(path: String, position: Long): ShareStream = wrap {
        val length = sizeOf(path)
        val ftp = login()
        try {
            if (position > 0) ftp.restartOffset = position
            val raw = ftp.retrieveFileStream(path) ?: throw ShareException("Couldn't open the file (${ftp.replyCode})")
            val input = object : FilterInputStream(raw) {}
            // Abandoning a transfer part-way is normal when seeking; drop the connection instead of
            // waiting for the server to finish it.
            ShareStream(input, length) { runCatching { ftp.abort() }; runCatching { ftp.disconnect() } }
        } catch (e: Exception) {
            runCatching { ftp.disconnect() }
            throw e
        }
    }

    override fun close() {
        runCatching { browser.logout() }
        runCatching { browser.disconnect() }
    }

    private inline fun <T> wrap(block: () -> T): T = try {
        block()
    } catch (e: ShareException) {
        throw e
    } catch (e: Exception) {
        throw ShareException(e.message ?: "FTP error", e)
    }
}
