package com.onesignal.logger.crash

import com.onesignal.logger.ILogFileStore
import com.onesignal.logger.ILogTelemetryRemote
import com.onesignal.logger.ILogger
import com.onesignal.logger.ILoggerPlatformProvider
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

/** Reads locally-buffered crash reports and ships them to OneSignal on the next app start. */
class LogCrashUploader internal constructor(
    private val platformProvider: ILoggerPlatformProvider,
    private val remote: ILogTelemetryRemote,
    private val fileStore: ILogFileStore,
    private val logger: ILogger,
) {
    /**
     * Starts the uploader. Uploads nothing when remote logging is disabled, by the kill switch or by a
     * NONE / null level, but still purges legacy files so a later flip is not poisoned.
     */
    @Throws(Exception::class)
    suspend fun start() {
        val remoteLogLevel = platformProvider.remoteLogLevel
        val isRemoteLoggingEnabled = platformProvider.isRemoteLoggingEnabled
        // Logging can be revoked while a usable level stays cached, so both must agree.
        if (!isRemoteLoggingEnabled || remoteLogLevel == null || remoteLogLevel == "NONE") {
            logger.info(
                "LogCrashUploader: remote logging disabled " +
                    "(enabled: $isRemoteLoggingEnabled, level: $remoteLogLevel)",
            )
            purgeUnrecognizedEntries()
            return
        }
        logger.info("LogCrashUploader: starting")
        logger.debug(
            "LogCrashUploader: path=${platformProvider.crashStoragePath} " +
                "minFileAgeMs=${platformProvider.minFileAgeForReadMillis} level=$remoteLogLevel",
        )
        // Purge must run even when the upload pass throws: a messy crash dir is when it matters most.
        try {
            internalStart()
        } finally {
            purgeUnrecognizedEntries()
        }
    }

    /**
     * Two passes: reports go out as early as possible, and one from the previous crash may only become
     * readable once [ILoggerPlatformProvider.minFileAgeForReadMillis] has elapsed.
     */
    internal suspend fun internalStart() {
        sendReports()
        delay(platformProvider.minFileAgeForReadMillis)
        sendReports()
    }

    /** Removes leftover files this store does not own, typically bare-millis OTEL disk-buffering files. */
    private suspend fun purgeUnrecognizedEntries() {
        val minAgeMillis = platformProvider.minFileAgeForReadMillis
        val deleted =
            try {
                fileStore.deleteUnrecognizedEntries(minAgeMillis)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("LogCrashUploader: failed to purge unrecognized files: ${e.message}")
                return
            }
        if (deleted > 0) {
            logger.info("LogCrashUploader: purged $deleted unrecognized/legacy crash file(s)")
        } else {
            logger.debug("LogCrashUploader: no unrecognized/legacy crash files to purge")
        }
    }

    private suspend fun sendReports() {
        val reports = fileStore.listReadable(platformProvider.minFileAgeForReadMillis)
        val inventory =
            reports.joinToString(separator = "; ") { report ->
                "id=${report.id} bytes=${report.bytes.size}"
            }
        logger.debug(
            "LogCrashUploader: readable reports count=${reports.size}" +
                if (reports.isEmpty()) "" else " [$inventory]",
        )
        var sent = 0
        for (report in reports) {
            logger.debug(
                "LogCrashUploader: posting id=${report.id} bytes=${report.bytes.size} " +
                    "(OTLP/protobuf payload)",
            )
            val success =
                try {
                    remote.exportEncoded(report.bytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("LogCrashUploader: export threw for ${report.id}: ${e.message}")
                    false
                }
            logger.debug("LogCrashUploader: done id=${report.id} success=$success")
            if (success) {
                // Only delete on success so a failed upload is retried next launch.
                fileStore.delete(report.id)
                sent++
            } else {
                // Stop on first failure to avoid hammering a failing network.
                break
            }
        }
        logger.debug("LogCrashUploader: pass complete sent=$sent of ${reports.size}")
    }
}
