package com.beyond.liveclips

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlin.random.Random

class TransferBlocksTest {
    @Test fun compressesRepeatedDataAndRestoresChecksum() {
        val file = ByteArray(150_000) { (it % 17).toByte() }
        assertTrue(roundTrip(file) < file.size / 10)
    }

    @Test fun sendsIncompressibleBlocksWithoutExpansionExceptHeaders() {
        val file = Random(302).nextBytes(131_081)
        assertTrue(roundTrip(file) <= file.size + 12)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedBlocks() {
        val wire = ByteArrayOutputStream()
        DataOutputStream(wire).writeInt(65_536)
        TransferBlocks.receive(DataInputStream(wire.toByteArray().inputStream()), ByteArrayOutputStream(), 65_536)
    }

    private fun roundTrip(source: ByteArray): Int {
        val wire = ByteArrayOutputStream()
        TransferBlocks.send(source.inputStream(), DataOutputStream(wire), source.size.toLong())
        val restored = ByteArrayOutputStream()
        val digest = TransferBlocks.receive(
            DataInputStream(wire.toByteArray().inputStream()), restored, source.size.toLong()
        )
        assertArrayEquals(source, restored.toByteArray())
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(source), digest)
        return wire.size()
    }
}
