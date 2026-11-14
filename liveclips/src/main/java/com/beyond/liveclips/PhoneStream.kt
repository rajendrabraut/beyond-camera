package com.beyond.liveclips

import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

internal data class StreamLink(val host: String, val port: Int, val token: String, val fingerprint: String) {
    val uri: Uri get() = Uri.parse(URI("beyond-stream", null, host, port, "/$token", null, fingerprint).toASCIIString())

    companion object {
        fun parse(input: String): StreamLink {
            require(input.length <= 512) { "Enter a valid Beyond stream link." }
            val value = URI(input.trim())
            val token = value.path?.removePrefix("/").orEmpty()
            val fingerprint = value.fragment.orEmpty()
            require(value.scheme == "beyond-stream" && value.host != null &&
                value.port in 1..65535 && value.userInfo == null && value.query == null &&
                token.matches(Regex("[a-f0-9]{48}")) && fingerprint.matches(Regex("[a-f0-9]{64}"))
            ) { "Enter a valid Beyond stream link." }
            return StreamLink(value.host.removeSurrounding("[", "]"), value.port, token, fingerprint)
        }
    }
}

internal class PhoneStream(private val file: File, private val server: SSLServerSocket, val link: StreamLink) : Closeable {
    private val closed = AtomicBoolean()
    private val viewers = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(4)

    init {
        Thread(::accept, "Beyond stream listener").apply { isDaemon = true; start() }
    }

    private fun accept() {
        while (!closed.get()) {
            val socket = try { server.accept() as SSLSocket } catch (_: IOException) { return }
            if (!slots.tryAcquire()) {
                socket.close()
                continue
            }
            viewers.add(socket)
            if (closed.get()) runCatching { socket.close() }
            Thread({
                try {
                    socket.use { serve(it) }
                } catch (error: javax.net.ssl.SSLException) {
                    android.util.Log.d("BeyondStream", "Viewer TLS handshake failed", error)
                } catch (_: IOException) {
                    // The player may disconnect or seek while a response is in progress.
                } finally {
                    viewers.remove(socket)
                    slots.release()
                }
            }, "Beyond stream viewer").apply { isDaemon = true; start() }
        }
    }

    private fun serve(socket: SSLSocket) {
        socket.soTimeout = 8_000
        socket.startHandshake()
        val input = BufferedInputStream(socket.inputStream)
        val output = BufferedOutputStream(socket.outputStream)
        val target = readHttpLine(input).split(' ')
        if (target.size != 3 || target[0] != "GET" ||
            !MessageDigest.isEqual(target[1].toByteArray(), "/${link.token}".toByteArray())
        ) {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
            return
        }
        var range: String? = null
        var ended = false
        repeat(32) {
            if (ended) return@repeat
            val line = readHttpLine(input)
            if (line.isEmpty()) {
                ended = true
                return@repeat
            }
            if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim()
        }
        if (!ended) throw IOException("Too many stream headers.")
        RandomAccessFile(file, "r").use { data ->
            val size = data.length()
            val bytes = streamRange(range, size)
            if (bytes == null) {
                output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } else {
                val length = bytes.last - bytes.first + 1
                val response = buildString {
                    append(if (range == null) "HTTP/1.1 200 OK\r\n" else "HTTP/1.1 206 Partial Content\r\n")
                    append("Content-Type: video/mp4\r\nContent-Length: $length\r\nAccept-Ranges: bytes\r\n")
                    if (range != null) append("Content-Range: bytes ${bytes.first}-${bytes.last}/$size\r\n")
                    append("Cache-Control: no-store\r\nConnection: close\r\n\r\n")
                }
                output.write(response.toByteArray())
                data.seek(bytes.first)
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0 && !closed.get()) {
                    val count = data.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
            output.flush()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { server.close() }
            viewers.forEach { runCatching { it.close() } }
        }
    }

    companion object {
        private const val ALIAS = "beyond_phone_stream_ec"

        fun start(file: File, advertisedHost: String, port: Int): PhoneStream {
            require(file.isFile && file.length() > 0) { "Select an exported clip." }
            val address = advertisedHost.trim().removeSurrounding("[", "]")
            val validated = URI("https", null, address, port, null, null, null)
            require(validated.host != null && port in 1024..65535) { "Enter a host name or IP address and a valid port." }
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keys.containsAlias(ALIAS)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                    initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                        .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                        .build())
                }.generateKeyPair()
            }
            val certificate = keys.getCertificate(ALIAS) as X509Certificate
            val key = keys.getKey(ALIAS, null) as PrivateKey
            val manager = object : X509ExtendedKeyManager() {
                override fun getServerAliases(type: String?, issuers: Array<out Principal>?) =
                    if (type == "EC") arrayOf(ALIAS) else null
                override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?) =
                    getServerAliases(type, issuers)?.first()
                override fun chooseEngineServerAlias(type: String?, issuers: Array<out Principal>?, engine: javax.net.ssl.SSLEngine?) =
                    getServerAliases(type, issuers)?.first()
                override fun getClientAliases(type: String?, issuers: Array<out Principal>?): Array<String>? = null
                override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
                override fun getCertificateChain(alias: String?) = if (alias == ALIAS) arrayOf(certificate) else null
                override fun getPrivateKey(alias: String?) = if (alias == ALIAS) key else null
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(arrayOf(manager), null, null) }
            val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.hex()
            val server = ssl.serverSocketFactory.createServerSocket() as SSLServerSocket
            try {
                server.enabledProtocols = server.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                server.bind(InetSocketAddress(port), 4)
                return PhoneStream(file, server, StreamLink(address, port, token, fingerprint(certificate)))
            } catch (e: Exception) {
                server.close()
                throw e
            }
        }
    }
}

internal fun streamRange(header: String?, size: Long): LongRange? {
    if (size <= 0) return null
    if (header == null) return 0L until size
    val fields = Regex("bytes=(\\d{1,19})-(\\d{0,19})").matchEntire(header) ?: return null
    val start = fields.groupValues[1].toLongOrNull() ?: return null
    val end = fields.groupValues[2].ifEmpty { (size - 1).toString() }.toLongOrNull() ?: return null
    return if (start in 0 until size && end >= start) start..minOf(end, size - 1) else null
}

private fun readHttpLine(input: InputStream): String {
    val bytes = ByteArray(4096)
    var count = 0
    while (count < bytes.size) {
        val value = input.read()
        if (value == -1) throw EOFException("Stream connection ended.")
        if (value == 10) return String(bytes, 0, count, Charsets.US_ASCII).trimEnd('\r')
        bytes[count++] = value.toByte()
    }
    throw IOException("Stream header is too long.")
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
private fun fingerprint(cert: X509Certificate) = MessageDigest.getInstance("SHA-256").digest(cert.encoded).hex()

@OptIn(UnstableApi::class)
internal class PhoneStreamDataSource(private val link: StreamLink) : BaseDataSource(true) {
    private var socket: SSLSocket? = null
    private var body: BufferedInputStream? = null
    private var remaining = 0L
    private var opened = false

    override fun open(spec: DataSpec): Long {
        transferInitializing(spec)
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<out X509Certificate>, auth: String) {
                throw CertificateException("Client certificates are unsupported.")
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>, auth: String) {
                val cert = chain.firstOrNull() ?: throw CertificateException("The host did not send a certificate.")
                cert.checkValidity()
                if (fingerprint(cert) != link.fingerprint) throw CertificateException("The phone identity does not match the link.")
            }
        }
        try {
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            val peer = (tls.socketFactory.createSocket() as SSLSocket).also { socket = it }
            peer.enabledProtocols = peer.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            peer.connect(InetSocketAddress(link.host, link.port), 8_000)
            peer.soTimeout = 15_000
            peer.startHandshake()
            val end = if (spec.length == C.LENGTH_UNSET.toLong()) "" else (spec.position + spec.length - 1).toString()
            peer.outputStream.apply {
                write(("GET /${link.token} HTTP/1.1\r\nHost: ${link.host}\r\n" +
                    "Range: bytes=${spec.position}-$end\r\nConnection: close\r\n\r\n").toByteArray())
                flush()
            }
            val input = BufferedInputStream(peer.inputStream)
            if (readHttpLine(input).split(' ').getOrNull(1) != "206") throw IOException("The clip is no longer available.")
            repeat(32) {
                val line = readHttpLine(input)
                if (line.isEmpty()) {
                    if (remaining <= 0) throw IOException("Missing stream length.")
                    body = input
                    opened = true
                    transferStarted(spec)
                    return remaining
                }
                if (line.startsWith("Content-Length:", true)) remaining = line.substringAfter(':').trim().toLongOrNull() ?: 0
            }
            throw IOException("Invalid stream response.")
        } catch (e: Exception) {
            close()
            if (e is IOException) throw e
            throw IOException("Couldn't connect to the clip host.", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = checkNotNull(body).read(buffer, offset, minOf(remaining, length.toLong()).toInt())
        if (count < 0) throw EOFException("The clip stream was interrupted.")
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri() = link.uri

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        body = null
        remaining = 0
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
