package com.openminis.app.backup

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun copyBackupVerified(
    source: File,
    openOutput: () -> OutputStream?,
    openVerificationInput: () -> InputStream?,
    onProgress: (Long, Long) -> Unit = { _, _ -> },
) {
    val context = currentCoroutineContext()
    context.ensureActive()
    val expectedSize = source.length()
    if (!source.isFile || expectedSize <= 0) throw IOException("Backup package is missing or empty")
    val expectedDigest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    var lastPercent = -1L
    source.inputStream().use { input ->
        (openOutput() ?: throw IOException("Cannot open backup destination")).use { output ->
            while (true) {
                context.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
                expectedDigest.update(buffer, 0, count)
                copied += count
                val percent = copied * 100 / expectedSize
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(copied, expectedSize)
                }
            }
            output.flush()
        }
    }
    if (copied != expectedSize) throw IOException("Backup package size changed during export")
    context.ensureActive()
    val actualDigest = MessageDigest.getInstance("SHA-256")
    var verified = 0L
    (openVerificationInput() ?: throw IOException("Cannot verify saved backup")).use { input ->
        while (true) {
            context.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            actualDigest.update(buffer, 0, count)
            verified += count
        }
    }
    if (verified != expectedSize || !expectedDigest.digest().contentEquals(actualDigest.digest())) {
        throw IOException("Saved backup verification failed")
    }
    context.ensureActive()
}
