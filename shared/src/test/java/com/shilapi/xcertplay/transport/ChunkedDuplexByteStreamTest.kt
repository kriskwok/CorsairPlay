package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ChunkedDuplexByteStreamTest {
    @Test fun splitsWritesWithoutChangingTheirOrder() {
        val delegate = RecordingStream()
        val stream = ChunkedDuplexByteStream(delegate, chunkSize = 4)

        stream.send(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9))

        assertEquals(listOf(4, 4, 1), delegate.writes.map { it.size })
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9),
            delegate.writes.flatMap { it.asIterable() }.toByteArray(),
        )
    }

    @Test fun delegatesReadsAndClose() {
        val delegate = RecordingStream()
        val stream = ChunkedDuplexByteStream(delegate)

        stream.send(byteArrayOf(1))
        assertArrayEquals(byteArrayOf(7, 8), stream.recv(2, 25))
        stream.close()

        assertEquals(1, delegate.sends)
        assertEquals(2, delegate.lastMaxBytes)
        assertEquals(25, delegate.lastTimeoutMillis)
        assertEquals(true, delegate.closed)
    }

    private class RecordingStream : BlockingDuplexByteStream {
        val writes = mutableListOf<ByteArray>()
        var sends = 0
        var lastMaxBytes = 0
        var lastTimeoutMillis = 0L
        var closed = false

        override fun send(data: ByteArray) {
            sends++
            writes += data.copyOf()
        }

        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray {
            lastMaxBytes = maxBytes
            lastTimeoutMillis = timeoutMillis
            return byteArrayOf(7, 8)
        }

        override fun close() {
            closed = true
        }
    }
}
