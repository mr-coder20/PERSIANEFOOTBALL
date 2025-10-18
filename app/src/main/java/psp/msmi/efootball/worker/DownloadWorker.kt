package psp.msmi.efootball.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import psp.msmi.efootball.R
import psp.msmi.efootball.utils.DownloadPrefs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class DownloadWorker(private val appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val KEY_INPUT_URL = "KEY_INPUT_URL"
        const val KEY_OUTPUT_FILE_NAME = "KEY_OUTPUT_FILE_NAME"
        const val KEY_RESUME_OFFSET = "KEY_RESUME_OFFSET"
        const val KEY_PROGRESS = "KEY_PROGRESS"
        const val KEY_DOWNLOAD_STATE = "KEY_DOWNLOAD_STATE"
        const val WORK_NAME = "efootballDataDownload"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "efootball_download_channel"
        private const val TAG = "DownloadWorker"
        private const val NUMBER_OF_CHUNKS = 4
        private fun getChunkPartFile(outputDir: File, workName: String, index: Int) =
            File(outputDir, "$workName.part$index")
    }

    private data class Chunk(
        val index: Int, val startByte: Long, val endByte: Long, var bytesDownloaded: Long = 0L
    )

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork started with multi-part logic. Worker ID: $id")

        val downloadUrl = inputData.getString(KEY_INPUT_URL) ?: return reportFailure(
            "URL دانلود موجود نیست.",
            0,
            0
        )
        val zipFileName = inputData.getString(KEY_OUTPUT_FILE_NAME)
            ?: return reportFailure("نام فایل خروجی موجود نیست.", 0, 0)
        val resumeOffset = inputData.getLong(KEY_RESUME_OFFSET, 0L)

        try {
            setForeground(createInitialForegroundInfo())
            Log.i(TAG, "Initial setForeground called successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error calling setForeground.", e)
            return reportFailure("خطا در اجرای سرویس پیش‌زمینه: ${e.message}", 0, 0)
        }

        try {
            val totalBytes = getTotalFileSize(downloadUrl)
            if (totalBytes <= 0) {
                return reportFailure("امکان دریافت حجم فایل وجود ندارد.", 0, 0)
            }

            val appSpecificDir = getAppSpecificDownloadsDir() ?: return reportFailure(
                "پوشه دانلود در دسترس نیست.",
                0,
                0
            )

            val chunks = prepareChunks(totalBytes, appSpecificDir, WORK_NAME, resumeOffset > 0)
            val totalBytesDownloadedSoFar = chunks.sumOf { it.bytesDownloaded }

            reportProgress(
                calculateProgress(totalBytesDownloadedSoFar, totalBytes),
                DownloadWorkerState.DOWNLOADING
            )

            return downloadInChunks(chunks, downloadUrl, zipFileName, totalBytes, appSpecificDir)

        } catch (e: CancellationException) {
            Log.i(TAG, "Download was cancelled for $WORK_NAME.", e)
            val finalBytes = DownloadPrefs.getBytesRead(appContext, WORK_NAME)
            val totalBytes = DownloadPrefs.getTotalBytes(appContext, WORK_NAME)
            reportProgress(
                calculateProgress(finalBytes, totalBytes),
                DownloadWorkerState.CANCELLED,
                "دانلود لغو شد"
            )
            return Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled exception in doWork for $WORK_NAME: ${e.message}", e)
            val lastKnownBytes = DownloadPrefs.getBytesRead(appContext, WORK_NAME)
            val totalBytes = DownloadPrefs.getTotalBytes(appContext, WORK_NAME)
            return reportFailure(
                "خطای پیش‌بینی نشده: ${e.message}",
                calculateProgress(lastKnownBytes, totalBytes),
                lastKnownBytes
            )
        }
    }

    private suspend fun downloadInChunks(
        chunks: List<Chunk>, url: String, zipFileName: String, totalBytes: Long, outputDir: File
    ): Result {
        val client = OkHttpClient()
        var totalDownloadedBytes = chunks.sumOf { it.bytesDownloaded }
        var lastLoggedProgress = -1
        var lastSaveTime = System.currentTimeMillis()

        try {
            coroutineScope {
                chunks.map { chunk ->
                    launch(Dispatchers.IO) {
                        if (chunk.bytesDownloaded >= (chunk.endByte - chunk.startByte + 1)) {
                            Log.i(TAG, "Chunk ${chunk.index} for $WORK_NAME already completed.")
                            return@launch
                        }

                        val downloadOffset = chunk.startByte + chunk.bytesDownloaded
                        val rangeHeader = "bytes=$downloadOffset-${chunk.endByte}"
                        val request =
                            Request.Builder().url(url).header("Range", rangeHeader).build()
                        val response = client.newCall(request).execute()

                        if (!response.isSuccessful) throw IOException("خطای سرور (${response.code}) برای بخش ${chunk.index}")

                        val body = response.body
                            ?: throw IOException("پاسخ سرور برای بخش ${chunk.index} خالی است")
                        val partFile = getChunkPartFile(outputDir, WORK_NAME, chunk.index)

                        RandomAccessFile(partFile, "rw").use { raf ->
                            raf.seek(chunk.bytesDownloaded)
                            body.byteStream().use { input ->
                                val buffer = ByteArray(8 * 1024)
                                var len: Int
                                while (input.read(buffer).also { len = it } != -1) {
                                    ensureActive()
                                    if (isStopped || DownloadPrefs.shouldPause(
                                            appContext, WORK_NAME
                                        )
                                    ) {
                                        throw CancellationException("دانلود متوقف شد.")
                                    }

                                    raf.write(buffer, 0, len)
                                    chunk.bytesDownloaded += len

                                    synchronized(this) {
                                        totalDownloadedBytes += len
                                    }

                                    val progressPercent =
                                        calculateProgress(totalDownloadedBytes, totalBytes)
                                    if (progressPercent > lastLoggedProgress) {
                                        reportProgress(
                                            progressPercent, DownloadWorkerState.DOWNLOADING
                                        )
                                        lastLoggedProgress = progressPercent
                                    }

                                    if (System.currentTimeMillis() - lastSaveTime > 2000) {
                                        withContext(NonCancellable) {
                                            DownloadPrefs.setBytesRead(
                                                appContext, WORK_NAME, totalDownloadedBytes
                                            )
                                        }
                                        lastSaveTime = System.currentTimeMillis()
                                    }
                                }
                            }
                        }
                        Log.i(TAG, "Chunk ${chunk.index} for $WORK_NAME finished.")
                    }
                }.joinAll()
            }

            // ۶. ترکیب فایل‌ها
            val finalDownloadedBytes = chunks.sumOf { it.bytesDownloaded }
            if (finalDownloadedBytes < totalBytes) {
                Log.e(
                    TAG,
                    "Verification failed! Downloaded bytes ($finalDownloadedBytes) is less than total bytes ($totalBytes). File is incomplete."
                )
                return reportFailure(
                    "فایل به طور کامل دانلود نشد. لطفا دوباره تلاش کنید.",
                    calculateProgress(finalDownloadedBytes, totalBytes),
                    finalDownloadedBytes
                )
            }

// ۷. ترکیب فایل‌ها (فقط اگر بررسی موفق بود)
            Log.i(TAG, "Verification successful. Merging files...")
            val finalOutputFile = File(outputDir, zipFileName)
            mergeFiles(
                chunks.map { getChunkPartFile(outputDir, WORK_NAME, it.index) }, finalOutputFile
            )

            // ۷. استخراج فایل با مدیریت خطا، آپدیت نوتیفیکیشن و تغییر مسیر
            try {
                Log.i(TAG, "File merged. Starting extraction.")
                // نمایش وضعیت "در حال نصب"
                reportProgress(
                    100, DownloadWorkerState.DOWNLOADING, "در حال نصب دیتا، لطفا صبر کنید..."
                )

                // تغییر مسیر استخراج به ریشه حافظه خارجی
                val extractionDir = Environment.getExternalStorageDirectory()
                Log.d(TAG, "Extraction target directory set to: ${extractionDir.absolutePath}")

                extractZipFile(finalOutputFile.absolutePath, extractionDir.absolutePath)
                Log.i(TAG, "Extraction completed successfully.")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract zip file.", e)
                return reportFailure("خطا در استخراج فایل: ${e.message}", 100, totalDownloadedBytes)
            }

            // ۸. پاک‌سازی و گزارش موفقیت
            withContext(NonCancellable) {
                finalOutputFile.delete()
                chunks.forEach { getChunkPartFile(outputDir, WORK_NAME, it.index).delete() }
                DownloadPrefs.clearDownloadState(appContext, WORK_NAME)
            }
            reportCompletion()
            return Result.success()

        } catch (e: CancellationException) {
            Log.i(TAG, "Download paused or cancelled for $WORK_NAME.")
            withContext(NonCancellable) {
                DownloadPrefs.setBytesRead(appContext, WORK_NAME, totalDownloadedBytes)
            }
            val state = if (DownloadPrefs.shouldPause(
                    appContext, WORK_NAME
                )
            ) DownloadWorkerState.PAUSED else DownloadWorkerState.CANCELLED
            reportProgress(
                calculateProgress(totalDownloadedBytes, totalBytes),
                state,
                if (state == DownloadWorkerState.PAUSED) "دانلود متوقف شد" else "دانلود لغو شد"
            )
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error during chunked download for $WORK_NAME: ${e.message}", e)
            withContext(NonCancellable) {
                DownloadPrefs.setBytesRead(appContext, WORK_NAME, totalDownloadedBytes)
            }
            return reportFailure(
                "خطا در دانلود: ${e.message}",
                calculateProgress(totalDownloadedBytes, totalBytes),
                totalDownloadedBytes
            )
        }
    }

    private suspend fun getTotalFileSize(url: String): Long {
        var totalBytes = DownloadPrefs.getTotalBytes(appContext, WORK_NAME)
        if (totalBytes > 0) {
            Log.d(TAG, "Using cached total file size for $WORK_NAME: $totalBytes bytes")
            return totalBytes
        }

        Log.d(TAG, "Fetching total file size from server for $WORK_NAME...")
        try {
            val request = Request.Builder().url(url).head().build()
            val response = withContext(Dispatchers.IO) { OkHttpClient().newCall(request).execute() }
            if (response.isSuccessful) {
                totalBytes = response.header("Content-Length")?.toLongOrNull() ?: -1L
                if (totalBytes > 0) {
                    DownloadPrefs.setTotalBytes(appContext, WORK_NAME, totalBytes)
                    Log.i(TAG, "Fetched and set total file size for $WORK_NAME: $totalBytes bytes")
                }
            } else {
                Log.e(TAG, "Failed to get file size. Server response: ${response.code}")
            }
        } catch (e: IOException) {
            Log.e(TAG, "IOException while fetching file size for $url", e)
            return -1
        }
        return totalBytes
    }

    private fun prepareChunks(
        totalBytes: Long, outputDir: File, workName: String, isResuming: Boolean
    ): List<Chunk> {
        val chunkSize = totalBytes / NUMBER_OF_CHUNKS
        val chunks = (0 until NUMBER_OF_CHUNKS).map { i ->
            val startByte = i * chunkSize
            val endByte =
                if (i == NUMBER_OF_CHUNKS - 1) totalBytes - 1 else (startByte + chunkSize) - 1
            Chunk(index = i, startByte = startByte, endByte = endByte)
        }

        val isAnyPartFilePresent =
            chunks.any { getChunkPartFile(outputDir, workName, it.index).exists() }
        val shouldResumeFromParts = isResuming || isAnyPartFilePresent

        if (shouldResumeFromParts) {
            Log.d(TAG, "Resuming download, calculating downloaded size from existing part files...")
            chunks.forEach { chunk ->
                val partFile = getChunkPartFile(outputDir, workName, chunk.index)
                if (partFile.exists()) {
                    chunk.bytesDownloaded = partFile.length()
                }
            }
            val totalBytesOnDisk = chunks.sumOf { it.bytesDownloaded }
            DownloadPrefs.setBytesRead(appContext, workName, totalBytesOnDisk)
        } else {
            Log.d(TAG, "Starting new download, deleting old part files...")
            chunks.forEach { chunk ->
                getChunkPartFile(outputDir, workName, chunk.index).delete()
            }
            DownloadPrefs.setBytesRead(appContext, workName, 0L)
        }

        Log.d(TAG, "Chunks prepared for $workName. Details: $chunks")
        return chunks
    }

    private fun mergeFiles(parts: List<File>, outputFile: File) {
        if (outputFile.exists()) outputFile.delete()
        outputFile.outputStream().use { output ->
            parts.forEach { part ->
                if (part.exists()) {
                    part.inputStream().use { input -> input.copyTo(output) }
                } else {
                    Log.w(TAG, "Chunk file missing during merge: ${part.absolutePath}")
                    throw IOException("فایل بخش ${part.name} برای ادغام یافت نشد.")
                }
            }
        }
        Log.i(TAG, "Successfully merged ${parts.size} parts into ${outputFile.absolutePath}")
    }

    private fun getAppSpecificDownloadsDir(): File? {
        val downloadsDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val appSpecificDir = File(downloadsDir, "PersianEFootball")
        if (!appSpecificDir.exists()) {
            if (!appSpecificDir.mkdirs()) {
                Log.e(TAG, "Failed to create directory: ${appSpecificDir.path}")
                return null
            }
        }
        return appSpecificDir
    }

    private suspend fun reportProgress(
        progress: Int, state: DownloadWorkerState, message: String? = null
    ) {
        val actualProgress = progress.coerceIn(0, 100)
        val isIndeterminate = progress < 0
        val contentText = message ?: when (state) {
            DownloadWorkerState.DOWNLOADING -> if (!isIndeterminate) "در حال دانلود... $actualProgress%" else "در حال آماده سازی..."
            DownloadWorkerState.PAUSED -> "دانلود متوقف شد"
            DownloadWorkerState.CANCELLED -> "دانلود لغو شد"
            else -> ""
        }
        val notification = createNotification(contentText, actualProgress, true, isIndeterminate)
        notificationManager.notify(NOTIFICATION_ID, notification)
        val progressDataOut = workDataOf(
            KEY_PROGRESS to actualProgress, KEY_DOWNLOAD_STATE to state.name
        )
        setProgress(progressDataOut)
    }

    private suspend fun reportFailure(
        errorMessage: String, progressPercent: Int, bytesSoFar: Long
    ): Result {
        Log.e(
            TAG, "Download FAILED: $errorMessage, Progress: $progressPercent%, Bytes: $bytesSoFar"
        )
        val notification =
            createNotification("دانلود ناموفق: $errorMessage", progressPercent, false)
        notificationManager.notify(NOTIFICATION_ID, notification)
        val outputData = workDataOf(
            KEY_PROGRESS to progressPercent.coerceIn(0, 100),
            KEY_DOWNLOAD_STATE to DownloadWorkerState.FAILED.name,
            "error" to errorMessage
        )
        return Result.failure(outputData)
    }

    private suspend fun reportCompletion() {
        Log.i(TAG, "Download and extraction COMPLETED.")
        val finalNotification = createNotification("دانلود و نصب کامل شد", 100, false)
        notificationManager.notify(NOTIFICATION_ID, finalNotification)
        val finalData = workDataOf(
            KEY_PROGRESS to 100, KEY_DOWNLOAD_STATE to DownloadWorkerState.COMPLETED.name
        )
        setProgress(finalData)
    }

    private fun createInitialForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = createNotification("در حال آماده سازی برای دانلود...", 0, true, true)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(
        contentText: String, progress: Int, ongoing: Boolean, indeterminate: Boolean = false
    ) = NotificationCompat.Builder(appContext, CHANNEL_ID).setContentTitle("دانلود دیتای بازی")
        .setTicker("دانلود دیتای بازی").setContentText(contentText)
        .setSmallIcon(R.drawable.ic_download).setOngoing(ongoing).setAutoCancel(!ongoing)
        .setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW).apply {
            if (ongoing && progress >= 0) {
                setProgress(100, progress, indeterminate)
            }
        }.build()

    private fun calculateProgress(bytesRead: Long, totalBytes: Long): Int {
        return if (totalBytes > 0) ((bytesRead * 100.0) / totalBytes).toInt() else -1
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                val name = "کانال دانلود"
                val descriptionText = "نوتیفیکیشن های مربوط به دانلود دیتا"
                val importance = NotificationManager.IMPORTANCE_LOW
                val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                    description = descriptionText
                }
                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "Notification channel $CHANNEL_ID created.")
            }
        }
    }

    /**
     * تابع استخراج فایل ZIP (نسخه نهایی و بهبود یافته)
     * این نسخه شامل بررسی‌های دقیق‌تر برای ایجاد پوشه‌ها و مدیریت بهتر خطاهاست.
     */
    private fun extractZipFile(zipFilePath: String, outputDirString: String) {
        Log.i(TAG, "Attempting to extract '$zipFilePath' to '$outputDirString'.")
        val outputDir = File(outputDirString)
        // اطمینان حاصل می‌کند که مسیر خروجی وجود دارد و یک پوشه است.
        if (!outputDir.exists()) {
            if (!outputDir.mkdirs()) throw IOException("امکان ایجاد پوشه مقصد برای استخراج وجود ندارد: $outputDirString")
        } else if (!outputDir.isDirectory) {
            throw IOException("مسیر مقصد برای استخراج یک پوشه نیست: $outputDirString")
        }

        ZipInputStream(FileInputStream(zipFilePath)).use { zipInput ->
            var entry: ZipEntry? = zipInput.nextEntry
            while (entry != null) {
                val newFile = File(outputDir, entry.name)

                // بررسی امنیتی برای جلوگیری از حمله Zip Slip
                val canonicalOutputPath = outputDir.canonicalPath
                val canonicalEntryPath = newFile.canonicalPath
                if (!canonicalEntryPath.startsWith(canonicalOutputPath + File.separator)) {
                    throw SecurityException("ورودی نامعتبر در فایل ZIP (Zip Slip): ${entry.name}")
                }

                if (entry.isDirectory) {
                    // اگر ورودی یک پوشه است، آن را ایجاد کن (اگر وجود نداشته باشد)
                    if (!newFile.exists() && !newFile.mkdirs()) {
                        throw IOException("امکان ایجاد پوشه '${newFile.absolutePath}' از فایل ZIP وجود ندارد.")
                    }
                } else {
                    // اگر ورودی یک فایل است، ابتدا پوشه‌های والد آن را ایجاد کن
                    val parent = newFile.parentFile
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw IOException("امکان ایجاد پوشه والد '${parent.absolutePath}' برای فایل وجود ندارد.")
                    }
                    // سپس فایل را بنویس
                    FileOutputStream(newFile).use { fos -> zipInput.copyTo(fos) }
                }
                zipInput.closeEntry()
                entry = zipInput.nextEntry
            }
        }
        Log.i(TAG, "Zip file extracted successfully to '$outputDirString'.")
    }
}


