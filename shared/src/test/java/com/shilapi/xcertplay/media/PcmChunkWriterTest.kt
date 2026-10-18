package com.shilapi.xcertplay.media

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmChunkWriterTest {
    @Test fun partialWritesPreserveEveryByteAndOrder() {
        val input = ByteArray(5_000) { (it * 37).toByte() }
        val output = ByteArrayOutputStream()
        var preparedChunks = 0
        val writer = PcmChunkWriter(
            chunkBytes = 1_024,
            stallTimeoutNs = 100_000_000L,
            nanoTime = { 0L },
            pause = {},
        )

        val result = writer.write(
            data = input,
            offset = 0,
            length = input.size,
            prepareChunk = { _, _ -> preparedChunks++ },
            writeChunk = { chunk, offset, length, blocking ->
                assertFalse(blocking)
                minOf(length, 137).also { output.write(chunk, offset, it) }
            },
            afterWrite = { _, _, _ -> },
            shouldContinue = { true },
        )

        assertArrayEquals(input, output.toByteArray())
        assertEquals(input.size, result.bytesWritten)
        assertEquals(5, preparedChunks)
        assertTrue(result.partialWrites > 0)
        assertTrue(result.zeroWrites == 0)
        assertFalse(result.fellBackToBlocking)
    }

    @Test fun zeroProgressRetriesWithoutDroppingPcm() {
        val input = ByteArray(3_000) { (255 - it).toByte() }
        val output = ByteArrayOutputStream()
        var now = 0L
        var zeroWritesLeft = 4
        val writer = PcmChunkWriter(
            chunkBytes = 512,
            stallTimeoutNs = 100_000_000L,
            nanoTime = { now },
            pause = { now += 1_000_000L },
        )

        val result = writer.write(
            data = input,
            offset = 0,
            length = input.size,
            prepareChunk = { _, _ -> },
            writeChunk = { chunk, offset, length, blocking ->
                assertFalse(blocking)
                if (zeroWritesLeft > 0) {
                    zeroWritesLeft--
                    0
                } else {
                    output.write(chunk, offset, length)
                    length
                }
            },
            afterWrite = { _, _, _ -> },
            shouldContinue = { true },
        )

        assertArrayEquals(input, output.toByteArray())
        assertEquals(input.size, result.bytesWritten)
        assertEquals(4, result.zeroWrites)
        assertFalse(result.fellBackToBlocking)
        assertTrue(writer.usesNonBlocking)
    }

    @Test fun persistentZeroProgressFallsBackOnceAndKeepsWriting() {
        var now = 0L
        val writer = PcmChunkWriter(
            chunkBytes = 256,
            stallTimeoutNs = 5_000_000L,
            nanoTime = { now },
            pause = { now += 1_000_000L },
        )
        val first = ByteArray(800) { it.toByte() }
        val firstOutput = ByteArrayOutputStream()

        val firstResult = writer.write(
            data = first,
            offset = 0,
            length = first.size,
            prepareChunk = { _, _ -> },
            writeChunk = { chunk, offset, length, blocking ->
                if (blocking) {
                    firstOutput.write(chunk, offset, length)
                    length
                } else {
                    0
                }
            },
            afterWrite = { _, _, _ -> },
            shouldContinue = { true },
        )

        assertArrayEquals(first, firstOutput.toByteArray())
        assertTrue(firstResult.fellBackToBlocking)
        assertFalse(writer.usesNonBlocking)

        val second = ByteArray(300) { (it + 1).toByte() }
        val secondOutput = ByteArrayOutputStream()
        val secondResult = writer.write(
            data = second,
            offset = 0,
            length = second.size,
            prepareChunk = { _, _ -> },
            writeChunk = { chunk, offset, length, blocking ->
                assertTrue(blocking)
                secondOutput.write(chunk, offset, length)
                length
            },
            afterWrite = { _, _, _ -> },
            shouldContinue = { true },
        )

        assertArrayEquals(second, secondOutput.toByteArray())
        assertFalse("fallback is only reported once", secondResult.fellBackToBlocking)
    }

    @Test fun partialWritesNeverApplyGainTwice() {
        val input = samples(1000, -1000, 2000, -2000, 3000, -3000)
        val expected = input.copyOf().also { PcmGain.scaleS16(it, 0, it.size, 0.5f) }
        val output = ByteArrayOutputStream()
        val writer = PcmChunkWriter(
            chunkBytes = 2 * 1024,
            stallTimeoutNs = 100_000_000L,
            nanoTime = { 0L },
            pause = {},
        )

        writer.write(
            data = input,
            offset = 0,
            length = input.size,
            prepareChunk = { chunk, length -> PcmGain.scaleS16(chunk, 0, length, 0.5f) },
            writeChunk = { chunk, offset, _, _ ->
                output.write(chunk, offset, 1)
                1
            },
            afterWrite = { _, _, _ -> },
            shouldContinue = { true },
        )

        assertArrayEquals(expected, output.toByteArray())
    }

    private fun samples(vararg values: Int): ByteArray = ByteArray(values.size * 2).also { out ->
        values.forEachIndexed { index, value ->
            out[index * 2] = value.toByte()
            out[index * 2 + 1] = (value shr 8).toByte()
        }
    }
}
