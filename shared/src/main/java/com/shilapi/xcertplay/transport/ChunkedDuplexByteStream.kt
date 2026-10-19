package com.shilapi.xcertplay.transport

/**
 * Keeps USB writes small enough for head units whose USB stack stalls on large iAP2 records.
 * Reads and lifecycle calls are delegated unchanged.
 */
internal class ChunkedDuplexByteStream(
    private val delegate: BlockingDuplexByteStream,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) : BlockingDuplexByteStream {
    init {
        require(chunkSize > 0) { "chunkSize must be positive" }
    }

    override fun send(data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            delegate.send(data.copyOfRange(offset, end))
            offset = end
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? =
        delegate.recv(maxBytes, timeoutMillis)

    override fun close() = delegate.close()

    companion object {
        const val DEFAULT_CHUNK_SIZE = 256
    }
}
