package com.yodrop.core

import com.yodrop.core.crypto.SessionKeys
import com.yodrop.core.platform.createPlatformServerSocket
import com.yodrop.core.platform.createPlatformSocket
import com.yodrop.core.protocol.*
import com.yodrop.core.session.ReceiverState
import com.yodrop.core.session.ReceiverStateMachine
import kotlinx.coroutines.*
import java.io.OutputStream
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class T4BoundedLengthsTest {

    @Test
    fun testOversizeFrameThrowsAndClosesSocket() = runBlocking(Dispatchers.IO) {
        val server = createPlatformServerSocket(0)
        val port = server.localPort

        val acceptJob = async {
            val sock = server.accept()
            assertFailsWith<IllegalArgumentException> {
                sock.readFrame()
            }
            sock.close()
        }

        val client = Socket("127.0.0.1", port)
        val out: OutputStream = client.getOutputStream()

        // Send length header claiming 100 MiB (exceeds 4MiB+1KiB cap)
        val evilLength = 100 * 1024 * 1024
        val lenBuf = byteArrayOf(
            ((evilLength shr 24) and 0xFF).toByte(),
            ((evilLength shr 16) and 0xFF).toByte(),
            ((evilLength shr 8) and 0xFF).toByte(),
            (evilLength and 0xFF).toByte()
        )
        out.write(lenBuf)
        out.flush()

        acceptJob.await()
        client.close()
        server.close()
    }

    @Test
    fun testNegativeFrameThrowsAndClosesSocket() = runBlocking(Dispatchers.IO) {
        val server = createPlatformServerSocket(0)
        val port = server.localPort

        val acceptJob = async {
            val sock = server.accept()
            assertFailsWith<IllegalArgumentException> {
                sock.readFrame()
            }
            sock.close()
        }

        val client = Socket("127.0.0.1", port)
        val out: OutputStream = client.getOutputStream()

        // Send negative length header (-1)
        val lenBuf = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        out.write(lenBuf)
        out.flush()

        acceptJob.await()
        client.close()
        server.close()
    }

    @Test
    fun testPathTraversalManifestRejected() {
        val evilFile = FileMeta("id-1", "../../etc/passwd", 100L, "text/plain", ByteArray(32))
        assertFailsWith<IllegalArgumentException> {
            evilFile.validate()
        }

        val evilManifest = ManifestMessage(listOf(evilFile))
        assertFailsWith<IllegalArgumentException> {
            evilManifest.validate()
        }
    }

    @Test
    fun testNegativeFileSizeManifestRejected() {
        val evilFile = FileMeta("id-1", "normal.txt", -50L, "text/plain", ByteArray(32))
        assertFailsWith<IllegalArgumentException> {
            evilFile.validate()
        }
    }
}
