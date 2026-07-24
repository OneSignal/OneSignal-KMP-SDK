package com.onesignal.logger.crash

import com.onesignal.logger.ILogFileStore
import com.onesignal.logger.ILogTelemetryRemote
import com.onesignal.logger.ILogger
import com.onesignal.logger.ILoggerPlatformProvider
import kotlinx.coroutines.delay

/**
 * Reads locally-buffered crash reports and ships them to OneSignal on the next app
 * start. Mirrors `OtelCrashUploader`, but reads our own simple disk format instead of
 * OpenTelemetry's disk-buffering library.
 *
 * Usage:
 * ```kotlin
 * val uploader = LoggerFactory.createCrashUploader(provider, remote, fileStore, logger)
 * scope.launch { uploader.start() }
 * ```
 */
class LogCrashUploader internal constructor(
    private val platformProvider: ILoggerPlatformProvider,
    private val remote: ILogTelemetryRemote,
    private val fileStore: ILogFileStore,
    private val logger: ILogger,
) {
    /**
     * Starts the uploader. No-op when remote logging is disabled (NONE / null level).
     */
    suspend fun start() {
        val remoteLogLevel = platformProvider.remoteLogLevel
        if (remoteLogLevel == null || remoteLogLevel == "NONE") {
            logger.info("LogCrashUploader: remote logging disabled (level: $remoteLogLevel)")
            // Still drop legacy OTEL files so a later module flip is not poisoned.
            purgeUnrecognizedEntries()
            return
        }
        logger.info(
            "LogCrashUploader: starting path=${platformProvider.crashStoragePath} " +
                "minFileAgeMs=${platformProvider.minFileAgeForReadMillis} level=$remoteLogLevel",
        )
        // Purge must run even if listReadable/export throws — a messy crash dir is
        // exactly when leftover legacy files most need reclaiming.
        try {
            internalStart()
        } finally {
            purgeUnrecognizedEntries()
        }
    }

    /**
     * Sends reports twice for the same reasons as the old uploader:
     *  1. Send crash reports as soon as possible (app may crash again quickly).
     *  2. A report from the previous crash may only become readable after
     *     [ILoggerPlatformProvider.minFileAgeForReadMillis] has elapsed (so we never
     *     read a file the crashing process may still have been writing).
     */
    internal suspend fun internalStart() {
        sendReports()
        delay(platformProvider.minFileAgeForReadMillis)
        sendReports()
    }

    /**
     * After owned `*.otlp` uploads finish (or when remote logging is off), remove
     * leftover files this store does not own — typically bare-millis OTEL
     * disk-buffering files from when both modules shared one crash directory.
     */
    private suspend fun purgeUnrecognizedEntries() {
        val deleted =
            try {
                fileStore.deleteUnrecognizedEntries()
            } catch (e: Exception) {
                logger.error("LogCrashUploader: failed to purge unrecognized files: ${e.message}")
                return
            }
        if (deleted > 0) {
            logger.info("LogCrashUploader: purged $deleted unrecognized/legacy crash file(s)")
        } else {
            logger.info("LogCrashUploader: no unrecognized/legacy crash files to purge")
        }
    }

    private suspend fun sendReports() {
        val reports = fileStore.listReadable(platformProvider.minFileAgeForReadMillis)
        val inventory =
            reports.joinToString(separator = "; ") { report ->
                "id=${report.id} bytes=${report.bytes.size}"
            }
        logger.info(
            "LogCrashUploader: readable reports count=${reports.size}" +
                if (reports.isEmpty()) "" else " [$inventory]",
        )
        var sent = 0
        for (report in reports) {
            logger.info(
                "LogCrashUploader: posting id=${report.id} bytes=${report.bytes.size} " +
                    "(OTLP/protobuf payload)",
            )
            val success =
                try {
                    remote.exportEncoded(report.bytes)
                } catch (e: Exception) {
                    logger.error("LogCrashUploader: export threw for ${report.id}: ${e.message}")
                    false
                }
            logger.info("LogCrashUploader: done id=${report.id} success=$success")
            if (success) {
                // Only delete on success so a failed upload is retried next launch.
                fileStore.delete(report.id)
                sent++
            } else {
                // Stop on first failure to avoid hammering a failing network.
                break
            }
        }
        logger.info("LogCrashUploader: pass complete sent=$sent of ${reports.size}")
    }
}
