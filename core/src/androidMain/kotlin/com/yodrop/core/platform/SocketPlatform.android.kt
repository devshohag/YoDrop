package com.yodrop.core.platform

import com.yodrop.core.protocol.RawFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

actual class PlatformSocket(private val socket: Socket) {
    private val outputStream: OutputStream by lazy { socket.getOutputStream() }
    private val inputStream: InputStream by lazy { socket.getInputStream() }

    actual val isConnected: Boolean get() = socket.isConnected && !socket.isClosed

    actual suspend fun connect(host: String, port: Int) {
        withContext(Dispatchers.IO) {
            socket.connect(InetSocketAddress(host, port), 5000)
        }
    }

    actual suspend fun writeFrame(frame: RawFrame) {
        withContext(Dispatchers.IO) {
            val bytes = frame.encode()
            outputStream.write(bytes)
            outputStream.flush()
        }
    }

    actual suspend fun readFrame(): RawFrame {
        return withContext(Dispatchers.IO) {
            val lenBuf = ByteArray(4)
            readFully(inputStream, lenBuf, 4)
            val length = ((lenBuf[0].toInt() and 0xFF) shl 24) or
                    ((lenBuf[1].toInt() and 0xFF) shl 16) or
                    ((lenBuf[2].toInt() and 0xFF) shl 8) or
                    (lenBuf[3].toInt() and 0xFF)

            val frameBuf = ByteArray(length)
            readFully(inputStream, frameBuf, length)

            val fullPacket = lenBuf + frameBuf
            RawFrame.decode(fullPacket)
        }
    }

    actual suspend fun close() {
        withContext(Dispatchers.IO) {
            runCatching { socket.close() }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read == -1) throw IllegalStateException("Socket stream closed prematurely")
            offset += read
        }
    }
}

actual class PlatformServerSocket(private val serverSocket: ServerSocket) {
    actual val localPort: Int get() = serverSocket.localPort

    actual suspend fun accept(): PlatformSocket {
        return withContext(Dispatchers.IO) {
            val clientSocket = serverSocket.accept()
            PlatformSocket(clientSocket)
        }
    }

    actual suspend fun close() {
        withContext(Dispatchers.IO) {
            runCatching { serverSocket.close() }
        }
    }
}

actual fun createPlatformSocket(): PlatformSocket = PlatformSocket(Socket())
actual fun createPlatformServerSocket(port: Int): PlatformServerSocket = PlatformServerSocket(ServerSocket(port))
