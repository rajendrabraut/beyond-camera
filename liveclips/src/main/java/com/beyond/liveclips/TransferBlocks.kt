package com.beyond.liveclips

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Independently framed blocks keep working memory constant regardless of file size. */
internal object TransferBlocks {
    private const val BLOCK = 64 * 1024

    fun send(source: InputStream, wire: DataOutputStream, size: Long) {
        val plain = ByteArray(BLOCK)
        val packed = ByteArray(BLOCK)
        val input = DataInputStream(source)
        val deflater = Deflater(Deflater.BEST_SPEED, true)
        try {
            var remaining = size
            var skipCompression = 0
            while (remaining > 0) {
                val count = minOf(remaining, BLOCK.toLong()).toInt()
                input.readFully(plain, 0, count)
                var compressed = 0
                if (skipCompression == 0) {
                    deflater.reset()
                    deflater.setInput(plain, 0, count)
                    deflater.finish()
                    val length = deflater.deflate(packed, 0, count)
                    if (deflater.finished() && length <= count - 64) compressed = length
                    if (compressed == 0) skipCompression = 15
                } else {
                    skipCompression--
                }
                wire.writeInt(compressed)
                if (compressed == 0) wire.write(plain, 0, count)
                else wire.write(packed, 0, compressed)
                remaining -= count
            }
        } finally {
            deflater.end()
        }
    }

    fun receive(wire: DataInputStream, target: OutputStream, size: Long): ByteArray {
        val plain = ByteArray(BLOCK)
        val packed = ByteArray(BLOCK)
        val inflater = Inflater(true)
        val checksum = MessageDigest.getInstance("SHA-256")
        try {
            var remaining = size
            while (remaining > 0) {
                val count = minOf(remaining, BLOCK.toLong()).toInt()
                val compressed = wire.readInt()
                require(compressed in 0 until count) { "Invalid compressed block size." }
                if (compressed == 0) {
                    wire.readFully(plain, 0, count)
                } else {
                    wire.readFully(packed, 0, compressed)
                    inflater.reset()
                    inflater.setInput(packed, 0, compressed)
                    val restored = inflater.inflate(plain, 0, count)
                    require(restored == count && inflater.finished() && inflater.remaining == 0) {
                        "Invalid compressed data."
                    }
                }
                target.write(plain, 0, count)
                checksum.update(plain, 0, count)
                remaining -= count
            }
            return checksum.digest()
        } finally {
            inflater.end()
        }
    }
}
