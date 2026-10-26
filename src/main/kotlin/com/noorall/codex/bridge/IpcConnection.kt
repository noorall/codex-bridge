/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.noorall.codex.bridge

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets

internal const val MAX_FRAME_BYTES = 256 * 1024 * 1024

internal interface IpcConnection : Closeable {
    val isOpen: Boolean
    fun readFrame(): String
    fun writeFrame(message: String)
}

internal class SocketChannelConnection(
    private val channel: SocketChannel,
) : IpcConnection {
    override val isOpen: Boolean
        get() = channel.isOpen

    override fun readFrame(): String {
        val lenBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        readFully(lenBuffer)
        lenBuffer.flip()
        val length = lenBuffer.int
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw IOException("Codex IDE context frame is too large: $length")
        }

        val payload = ByteBuffer.allocate(length)
        readFully(payload)
        payload.flip()
        return StandardCharsets.UTF_8.decode(payload).toString()
    }

    override fun writeFrame(message: String) {
        val payload = message.toByteArray(StandardCharsets.UTF_8)
        val lenBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size)
        lenBuffer.flip()
        writeFully(lenBuffer)
        writeFully(ByteBuffer.wrap(payload))
    }

    private fun readFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer)
            if (count < 0) {
                throw EOFException("Codex IDE context connection closed")
            }
        }
    }

    private fun writeFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    override fun close() {
        channel.close()
    }
}
