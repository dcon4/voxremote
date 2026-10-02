package dev.minios.ocremote.debug

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.R
import dev.minios.ocremote.data.repository.DiagnosticLogRepository
import dev.minios.ocremote.logging.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the debug-log export and opens Android's share sheet for it.
 *
 * Used from both the Diagnostics screen and the one-tap button on the
 * Home screen so non-technical users can always send a log without a cable.
 * The file follows the AGENTS naming convention so log-hunting agents can
 * find it: voxremote-debug.log.YYYY-MM-DD-HH-MM.txt
 */
@Singleton
class DebugLogShare @Inject constructor(
    private val repository: DiagnosticLogRepository,
    @ApplicationContext private val appContext: Context,
) {
    /** Full export text, including the device/version header. */
    suspend fun exportText(): String {
        AppLogger.flush()
        val limit = repository.exportEntryLimit.first()
        val logLevel = repository.logLevel.first()
        val all = repository.entries.first()
        val exported = all.takeLast(limit)
        val export = DiagnosticLogRepository.export(exported, exported.size)
        val timeRange = exported.takeIf { it.isNotEmpty() }?.let {
            "${java.time.Instant.ofEpochMilli(it.first().timestamp)}..${java.time.Instant.ofEpochMilli(it.last().timestamp)}"
        } ?: "empty"
        return buildString {
            appendLine("VoxRemote diagnostics")
            appendLine("Generated: ${java.time.Instant.now()}")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Persistent log level: $logLevel")
            appendLine("Included entries: ${exported.size} of ${all.size}")
            appendLine("Time range: $timeRange")
            appendLine("Dropped queue entries: ${AppLogger.droppedEntryCount()}")
            appendLine("Included: lifecycle, connection, REST/SSE result classes, reducer transitions, updates, voice, and crashes; no chat or terminal payloads")
            appendLine()
            append(export.ifBlank { appContext.getString(R.string.diagnostics_empty) })
        }
    }

    /** Write the export to a voxremote-named file and open the share sheet. */
    suspend fun share(from: Context = appContext) {
        val text = exportText()
        val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US).format(Date())
        val file = withContext(Dispatchers.IO) {
            val directory = File(appContext.cacheDir, "diagnostics").apply { mkdirs() }
            directory.listFiles()?.forEach { it.delete() }
            File(directory, "voxremote-debug.log.$stamp.txt").apply { writeText(text) }
        }
        val uri = FileProvider.getUriForFile(from, "${appContext.packageName}.fileprovider", file)
        from.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("VoxRemote debug log", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                from.getString(R.string.diagnostics_share),
            ),
        )
    }
}
