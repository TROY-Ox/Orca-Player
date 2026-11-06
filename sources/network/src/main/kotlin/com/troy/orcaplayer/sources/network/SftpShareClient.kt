package com.troy.orcaplayer.sources.network

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.InputStream
import java.security.PublicKey
import java.security.Security
import java.util.EnumSet

/**
 * SFTP through sshj. The server's host key is trusted on first use and checked on every
 * connection after that, so a changed key (a different machine) is refused.
 */
class SftpShareClient(
    private val conn: ShareConnection,
    private val onNewHostKey: (String) -> Unit,
) : ShareClient {
    override val root: String = "/" + conn.path.trim('/')

    private val ssh: SSHClient
    private val sftp: SFTPClient

    init {
        installProvider()
        ssh = SSHClient(DefaultConfig())
        try {
            ssh.connectTimeout = 15_000
            ssh.timeout = 30_000
            ssh.addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(hostname: String?, port: Int, key: PublicKey?): Boolean {
                    val fingerprint = key?.let { SecurityUtils.getFingerprint(it) } ?: return false
                    val known = conn.hostKey
                    if (known == null) {
                        onNewHostKey(fingerprint)
                        return true
                    }
                    return known == fingerprint
                }

                override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
            })
            ssh.connect(conn.host, conn.effectivePort)
            ssh.authPassword(conn.username, conn.password)
            sftp = ssh.newSFTPClient()
        } catch (e: Exception) {
            runCatching { ssh.disconnect() }
            throw ShareException(e.message ?: "Couldn't connect", e)
        }
    }

    override fun list(path: String): List<ShareEntry> = wrap {
        sftp.ls(path.ifEmpty { "/" }).mapNotNull { info ->
            val name = info.name
            if (name == "." || name == ".." || name.startsWith(".")) return@mapNotNull null
            val attrs = info.attributes
            val folder = attrs.type == FileMode.Type.DIRECTORY ||
                (attrs.type == FileMode.Type.SYMLINK && runCatching { sftp.stat(info.path).type == FileMode.Type.DIRECTORY }.getOrDefault(false))
            ShareEntry(
                name = name,
                path = ShareUris.join(path, name),
                isFolder = folder,
                size = if (folder) -1 else attrs.size,
                modified = attrs.mtime * 1000L,
            )
        }
    }

    override fun open(path: String, position: Long): ShareStream = wrap {
        val file = sftp.open(path, EnumSet.of(OpenMode.READ))
        val length = file.length()
        val input: InputStream = file.RemoteFileInputStream(position)
        ShareStream(input, length) { file.close() }
    }

    override fun close() {
        runCatching { sftp.close() }
        runCatching { ssh.disconnect() }
    }

    private inline fun <T> wrap(block: () -> T): T = try {
        block()
    } catch (e: ShareException) {
        throw e
    } catch (e: Exception) {
        throw ShareException(e.message ?: "SFTP error", e)
    }

    companion object {
        @Volatile private var installed = false

        /** Android ships a cut-down "BC" provider; sshj needs the full one for modern key exchange. */
        fun installProvider() {
            if (installed) return
            synchronized(this) {
                if (installed) return
                Security.removeProvider("BC")
                Security.addProvider(BouncyCastleProvider())
                installed = true
            }
        }
    }
}
