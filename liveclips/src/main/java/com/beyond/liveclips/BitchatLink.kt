package com.beyond.liveclips

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal data class FileOffer(val name: String, val size: Long, val digest: ByteArray, val credit: String)

/** Uses Android's authenticated Bluetooth Classic link for direct offline transfers. */
@SuppressLint("MissingPermission")
internal class BitchatLink(
    context: Context,
    private val scope: CoroutineScope,
    private val directory: File,
    private val onEvent: (Event) -> Unit
) {
    sealed interface Event {
        data class Status(val message: String, val connected: Boolean = false) : Event
        data class Message(val text: String, val incoming: Boolean) : Event
        data class Offer(val offer: FileOffer?) : Event
        data class Received(val file: File, val credit: String) : Event
    }

    val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var session: Job? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var server: BluetoothServerSocket? = null
    private var active: Connection? = null
    @Volatile private var generation = 0

    fun paired(): List<BluetoothDevice> = adapter?.bondedDevices.orEmpty().sortedBy { it.name ?: it.address }

    fun listen() = start("Waiting for a paired device…") {
        val listening = checkNotNull(adapter).listenUsingRfcommWithServiceRecord("Beyond Live Clips", SERVICE)
        server = listening
        try {
                    listening.accept(120_000)
        } finally {
            listening.close()
            if (server === listening) server = null
        }
    }

    fun connect(device: BluetoothDevice) = start("Connecting to ${device.name ?: "paired device"}…") {
        val connecting = device.createRfcommSocketToServiceRecord(SERVICE)
        socket = connecting
        try {
            connecting.connect()
            connecting
        } catch (e: Exception) {
            connecting.close()
            throw e
        }
    }

    private fun start(status: String, open: () -> BluetoothSocket) {
        close()
        val attempt = generation
        onEvent(Event.Status(status))
        session = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val connected = open()
                    socket = connected
                    connected.use { bluetooth ->
                        val input = DataInputStream(BufferedInputStream(bluetooth.inputStream))
                        val output = DataOutputStream(BufferedOutputStream(bluetooth.outputStream))
                        output.writeUTF("BeyondLiveClips/Bitchat/1")
                        output.flush()
                        require(input.readUTF() == "BeyondLiveClips/Bitchat/1") { "This device uses a different sharing protocol." }
                        val connection = Connection(input, output)
                        withContext(Dispatchers.Main) {
                            if (generation != attempt) throw CancellationException()
                            active = connection
                            onEvent(Event.Status("Connected to ${bluetooth.remoteDevice.name ?: "paired device"}.", true))
                        }
                        connection.read(attempt)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == attempt) onEvent(Event.Status(
                    e.message?.takeIf { e is IllegalArgumentException } ?: "Connection ended. Reconnect to continue."
                ))
            } finally {
                if (generation == attempt) {
                    socket = null
                    active = null
                    onEvent(Event.Offer(null))
                }
            }
        }
    }

    fun close() {
        generation++
        session?.cancel()
        runCatching { server?.close() }
        runCatching { socket?.close() }
        active = null
        onEvent(Event.Offer(null))
        onEvent(Event.Status("Disconnected."))
    }

    suspend fun message(text: String) {
        val connection = requireConnection()
        require(text.isNotBlank() && text.length <= 2_000)
        connection.send(TEXT) { writeUTF(text) }
        onEvent(Event.Message(text, false))
    }

    suspend fun offer(file: File, credit: String) {
        val connection = requireConnection()
        val details = withContext(Dispatchers.IO) {
            val size = file.length()
            require(size in 1..MAX_BYTES) { "Choose a file up to 32 MB." }
            FileOffer(file.name, size, digest(file), credit.take(800))
        }
        require(connection.outgoing == null) { "Wait for the current transfer to finish." }
        connection.outgoing = file to details
        try {
            connection.send(OFFER) {
                writeUTF(details.name)
                writeLong(details.size)
                write(details.digest)
                writeUTF(details.credit)
            }
        } catch (e: Exception) {
            connection.outgoing = null
            throw e
        }
        if (connection.outgoing != null) onEvent(Event.Status("Waiting for the recipient to accept.", true))
    }

    suspend fun decide(accept: Boolean) {
        val connection = requireConnection()
        val offer = connection.incoming ?: return
        connection.accepted = if (accept) offer else null
        connection.incoming = null
        onEvent(Event.Offer(null))
        if (accept) onEvent(Event.Status("Receiving ${offer.name}…", true))
        connection.send(if (accept) ACCEPT else DECLINE) {}
    }

    private fun requireConnection() = checkNotNull(active) { "Connect to another Beyond Live Clips device first." }

    private inner class Connection(
        private val input: DataInputStream,
        private val output: DataOutputStream
    ) {
        private val writeLock = Mutex()
        @Volatile var incoming: FileOffer? = null
        @Volatile var accepted: FileOffer? = null
        @Volatile var outgoing: Pair<File, FileOffer>? = null
        private var sending = false

        suspend fun send(kind: Int, body: DataOutputStream.() -> Unit) = withContext(Dispatchers.IO) {
            writeLock.withLock {
                output.writeByte(kind)
                output.body()
                output.flush()
            }
        }

        suspend fun read(attempt: Int) {
            while (true) {
                when (input.readUnsignedByte()) {
                    TEXT -> {
                        val text = input.readUTF()
                        require(text.isNotBlank() && text.length <= 2_000)
                        emit(attempt, Event.Message(text, true))
                    }
                    OFFER -> {
                        require(incoming == null && accepted == null)
                        val name = input.readUTF()
                        val size = input.readLong()
                        val digest = ByteArray(32).also { input.readFully(it) }
                        val credit = input.readUTF()
                        require(size in 1..MAX_BYTES && name.length in 1..160 && credit.length <= 800)
                        incoming = FileOffer(name, size, digest, credit)
                        emit(attempt, Event.Offer(incoming))
                    }
                    ACCEPT -> {
                        val (file, details) = requireNotNull(outgoing)
                        require(!sending)
                        sending = true
                        emit(attempt, Event.Status("Sending ${details.name}…", true))
                        scope.launch {
                            try {
                                send(DATA) {
                                    file.inputStream().use { source ->
                                        val buffer = ByteArray(64 * 1024)
                                        var remaining = details.size
                                        while (remaining > 0) {
                                            val count = source.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                                            if (count == -1) throw IOException("File changed during transfer.")
                                            write(buffer, 0, count)
                                            remaining -= count
                                        }
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                if (generation == attempt) close()
                            }
                        }
                    }
                    DECLINE -> {
                        require(outgoing != null && !sending)
                        outgoing = null
                        emit(attempt, Event.Status("The recipient declined the file.", true))
                    }
                    DATA -> {
                        val details = requireNotNull(accepted)
                        val file = receive(details)
                        accepted = null
                        send(DELIVERED) {}
                        emit(attempt, Event.Received(file, details.credit))
                    }
                    DELIVERED -> {
                        require(sending && outgoing != null)
                        outgoing = null
                        sending = false
                        emit(attempt, Event.Status("File delivered and verified.", true))
                    }
                    else -> throw IOException("Unknown message.")
                }
            }
        }

        private fun receive(details: FileOffer): File {
            val name = details.name.replace(Regex("[^a-zA-Z0-9._ -]"), "_").take(100).ifBlank { "file" }
            val output = File(directory, "${UUID.randomUUID().toString().take(8)}-$name")
            val partial = File(directory, "${output.name}.part")
            try {
                val checksum = MessageDigest.getInstance("SHA-256")
                partial.outputStream().use { target ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = details.size
                    while (remaining > 0) {
                        val count = minOf(remaining, buffer.size.toLong()).toInt()
                        input.readFully(buffer, 0, count)
                        target.write(buffer, 0, count)
                        checksum.update(buffer, 0, count)
                        remaining -= count
                    }
                }
                require(MessageDigest.isEqual(checksum.digest(), details.digest)) { "File verification failed." }
                if (details.credit.isNotBlank()) File(directory, "${output.name}.credit.txt").writeText(details.credit)
                if (!partial.renameTo(output)) throw IOException("Couldn't save the received file.")
                return output
            } finally {
                partial.delete()
            }
        }
    }

    private suspend fun emit(attempt: Int, event: Event) = withContext(Dispatchers.Main) {
        if (generation == attempt) onEvent(event)
    }

    companion object {
        private val SERVICE = UUID.fromString("f2b04568-6ea9-4e6f-99fa-5d479330f251")
        private const val TEXT = 1
        private const val OFFER = 2
        private const val ACCEPT = 3
        private const val DECLINE = 4
        private const val DATA = 5
        private const val DELIVERED = 6
        private const val MAX_BYTES = 32_000_000L

        private fun digest(file: File): ByteArray {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    hash.update(buffer, 0, count)
                }
            }
            return hash.digest()
        }
    }
}
