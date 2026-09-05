package com.openminis.app.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupLocalCopyTest {
    @get:Rule val files = TemporaryFolder()
    private fun source(size: Int = 150000): File = files.newFile().apply {
        writeBytes(ByteArray(size) { (it % 251).toByte() })
    }
    private fun rejects(block: suspend () -> Unit) {
        try { runBlocking { block() }; fail("Expected a copy failure") }
        catch (_: IOException) { }
    }
    @Test fun copiesAndVerifiesMultipleChunksWithoutChangingSource() = runBlocking {
        val original = source()
        val output = ByteArrayOutputStream()
        var lastBytes = 0L
        copyBackupVerified(original, { output }, { ByteArrayInputStream(output.toByteArray()) }) { bytes, total ->
            assertTrue(bytes >= lastBytes)
            assertEquals(original.length(), total)
            lastBytes = bytes
        }
        assertArrayEquals(original.readBytes(), output.toByteArray())
        assertEquals(original.length(), lastBytes)
    }
    @Test fun rejectsMissingOutput() {
        rejects { copyBackupVerified(source(), { null }, { null }) }
    }
    @Test fun rejectsUnavailableVerificationStream() {
        rejects { copyBackupVerified(source(), { ByteArrayOutputStream() }, { null }) }
    }
    @Test fun detectsTruncatedDestination() {
        rejects { copyBackupVerified(source(), { ByteArrayOutputStream() }, { ByteArrayInputStream(byteArrayOf(1)) }) }
    }
    @Test fun detectsSameLengthCorruption() {
        rejects { copyBackupVerified(source(500), { ByteArrayOutputStream() }, { ByteArrayInputStream(ByteArray(500)) }) }
    }
    @Test fun doesNotDiscardSourceWhenDiskIsFull() {
        val original = source()
        rejects {
            copyBackupVerified(original, { object : OutputStream() {
                override fun write(value: Int) { throw IOException("No space left") }
            } }, { null })
        }
        assertEquals(150000L, original.length())
    }
    @Test fun closesOutputAndPropagatesCancellation() {
        var closed = false
        var verified = false
        val original = source()
        try {
            runBlocking {
                copyBackupVerified(original, { object : ByteArrayOutputStream() {
                    override fun close() { closed = true; super.close() }
                } }, { verified = true; null }) { _, _ -> throw CancellationException("Stopped") }
            }
            fail("Cancellation must escape")
        } catch (_: CancellationException) { }
        assertTrue(closed)
        assertFalse(verified)
        assertTrue(original.exists())
    }
    @Test fun rejectsEmptyPackageBeforeOpeningDestination() {
        var opened = false
        rejects { copyBackupVerified(source(0), { opened = true; ByteArrayOutputStream() }, { null }) }
        assertFalse(opened)
    }
    @Test fun doesNotReportSuccessWhenClosingDestinationFails() {
        var verified = false
        rejects {
            copyBackupVerified(source(), { object : ByteArrayOutputStream() {
                override fun close() { throw IOException("Provider write failed") }
            } }, { verified = true; null })
        }
        assertFalse(verified)
    }
}
