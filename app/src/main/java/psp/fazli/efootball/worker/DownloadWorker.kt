package psp.fazli.efootball.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import psp.fazli.efootball.R
import psp.fazli.efootball.utils.DownloadPrefs
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
        const val KEY_RESUME_OFFSET = "KEY_RESUME_OFFSET" // This will still be used to trigger resume logic
        const val KEY_PROGRESS = "KEY_PROGRESS"
        const val KEY_DOWNLOAD_STATE = "KEY_DOWNLOAD_STATE"

        const val WORK_NAME = "efootballDataDownload" // The unique name for the work
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "efootball_download_channel"
        private const val TAG = "DownloadWorker"

        // **تنظیمات جدید برای دانلود چندبخشی**
        private const val NUMBER_OF_CHUNKS = 4
        private fun getChunkPartFile(outputDir: File, workName: String, index: Int) = File(outputDir, "$workName.part$index")
    }

    // دیتا کلاس برای نگهداری اطلاعات هر بخش (chunk)
    private data class Chunk(
        val index: Int,
        val startByte: Long,
        val endByte: Long,
        var bytesDownloaded: Long = 0L // بایت‌های دانلود شده برای این بخش
    )

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork started with multi-part logic. Worker ID: $id")

        val downloadUrl = inputData.getString(KEY_INPUT_URL)
            ?: return reportFailure("URL دانلود موجود نیست.", 0, 0)
        val zipFileName = inputData.getString(KEY_OUTPUT_FILE_NAME)
            ?: return reportFailure("نام فایل خروجی موجود نیست.", 0, 0)
        val resumeOffset = inputData.getLong(KEY_RESUME_OFFSET, 0L)

        // ۱. فراخوانی setForeground در ابتدای کار
        try {
            setForeground(createInitialForegroundInfo())
            Log.i(TAG, "Initial setForeground called successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error calling setForeground.", e)
            return reportFailure("خطا در اجرای سرویس پیش‌زمینه: ${e.message}", 0, 0)
        }

        val totalBytesKey = WORK_NAME + "_total_bytes" // کلید مطابق با منطق قدیمی شما

        try {
            // ۲. دریافت حجم کل فایل
            val totalBytes = getTotalFileSize(downloadUrl, totalBytesKey)
            if (totalBytes <= 0) {
                return reportFailure("امکان دریافت حجم فایل وجود ندارد.", 0, 0)
            }

            val appSpecificDir = getAppSpecificDownloadsDir()
                ?: return reportFailure("پوشه دانلود در دسترس نیست.", 0, 0)

            // ۳. آماده‌سازی بخش‌ها (Chunks) برای دانلود
            val chunks = prepareChunks(totalBytes, appSpecificDir, WORK_NAME, resumeOffset > 0)
            val totalBytesDownloadedSoFar = chunks.sumOf { it.bytesDownloaded }

            // ۴. گزارش پیشرفت اولیه
            reportProgress(calculateProgress(totalBytesDownloadedSoFar, totalBytes), DownloadWorkerState.DOWNLOADING)

            // ۵. شروع دانلود موازی بخش‌ها
            return downloadInChunks(chunks, downloadUrl, zipFileName, totalBytes, appSpecificDir)

        } catch (e: CancellationException) {
            Log.i(TAG, "Download was cancelled for $WORK_NAME.", e)
            // وضعیت فعلی در خود حلقه دانلود ذخیره می‌شود
            val finalBytes = DownloadPrefs.getBytesRead(appContext, WORK_NAME)
            val totalBytes = DownloadPrefs.getTotalBytes(appContext, WORK_NAME)
            reportProgress(calculateProgress(finalBytes, totalBytes), DownloadWorkerState.CANCELLED, "دانلود لغو شد")
            return Result.failure() // لغو شدن یک شکست محسوب می‌شود تا دوباره زمان‌بندی نشود
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled exception in doWork for $WORK_NAME: ${e.message}", e)
            val lastKnownBytes = DownloadPrefs.getBytesRead(appContext, WORK_NAME)
            val totalBytes = DownloadPrefs.getTotalBytes(appContext, WORK_NAME)
            return reportFailure("خطای پیش‌بینی نشده: ${e.message}", calculateProgress(lastKnownBytes, totalBytes), lastKnownBytes)
        }
    }

    /**
     * دانلود موازی بخش‌ها.
     */
    private suspend fun downloadInChunks(
        chunks: List<Chunk>,
        url: String,
        zipFileName: String,
        totalBytes: Long,
        outputDir: File
    ): Result {
        val client = OkHttpClient()
        var totalDownloadedBytes = chunks.sumOf { it.bytesDownloaded }
        var lastLoggedProgress = -1
        var lastSaveTime = System.currentTimeMillis()

        try {
            coroutineScope {
                chunks.map { chunk ->
                    launch(Dispatchers.IO) {
                        // اگر بخش کامل شده، از آن عبور کن
                        if (chunk.bytesDownloaded >= (chunk.endByte - chunk.startByte + 1)) {
                            Log.i(TAG, "Chunk ${chunk.index} for $WORK_NAME already completed.")
                            return@launch
                        }

                        val downloadOffset = chunk.startByte + chunk.bytesDownloaded
                        val rangeHeader = "bytes=$downloadOffset-${chunk.endByte}"
                        val request = Request.Builder().url(url).header("Range", rangeHeader).build()
                        val response = client.newCall(request).execute()

                        if (!response.isSuccessful) throw IOException("خطای سرور (${response.code}) برای بخش ${chunk.index}")

                        val body = response.body ?: throw IOException("پاسخ سرور برای بخش ${chunk.index} خالی است")
                        val partFile = getChunkPartFile(outputDir, WORK_NAME, chunk.index)

                        RandomAccessFile(partFile, "rw").use { raf ->
                            raf.seek(chunk.bytesDownloaded) // پرش به نقطه ادامه دانلود
                            body.byteStream().use { input ->
                                val buffer = ByteArray(8 * 1024)
                                var len: Int
                                while (input.read(buffer).also { len = it } != -1) {
                                    ensureActive()
                                    if (isStopped || DownloadPrefs.shouldPause(appContext, WORK_NAME)) {
                                        throw CancellationException("دانلود متوقف شد.")
                                    }

                                    raf.write(buffer, 0, len)
                                    chunk.bytesDownloaded += len

                                    synchronized(this) {
                                        totalDownloadedBytes += len
                                    }

                                    // آپدیت پیشرفت و نوتیفیکیشن
                                    val progressPercent = calculateProgress(totalDownloadedBytes, totalBytes)
                                    if (progressPercent > lastLoggedProgress) {
                                        reportProgress(progressPercent, DownloadWorkerState.DOWNLOADING)
                                        lastLoggedProgress = progressPercent
                                    }

                                    // ذخیره پیشرفت به صورت دوره‌ای (مثلا هر ۲ ثانیه)
                                    if (System.currentTimeMillis() - lastSaveTime > 2000) {
                                        withContext(NonCancellable) {
                                            DownloadPrefs.setBytesRead(appContext, WORK_NAME, totalDownloadedBytes)
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
            Log.i(TAG, "All chunks downloaded. Merging files...")
            val finalOutputFile = File(outputDir, zipFileName)
            mergeFiles(chunks.map { getChunkPartFile(outputDir, WORK_NAME, it.index) }, finalOutputFile)

            // ۷. استخراج فایل
            Log.i(TAG, "File merged. Starting extraction.")
            val pspDir = File(Environment.getExternalStorageDirectory(), "PSP")
            extractZipFile(finalOutputFile.absolutePath, pspDir.absolutePath)

            // ۸. پاک‌سازی و گزارش موفقیت
            withContext(NonCancellable) {
                finalOutputFile.delete() // حذف فایل zip
                chunks.forEach { getChunkPartFile(outputDir, WORK_NAME, it.index).delete() } // حذف فایل‌های part
                DownloadPrefs.clearDownloadState(appContext, WORK_NAME) // پاک کردن وضعیت از Prefs
            }
            reportCompletion()
            return Result.success()

        } catch (e: CancellationException) {
            Log.i(TAG, "Download paused or cancelled for $WORK_NAME.")
            withContext(NonCancellable) {
                // ذخیره آخرین وضعیت کل بایت‌های دانلود شده
                DownloadPrefs.setBytesRead(appContext, WORK_NAME, totalDownloadedBytes)
            }
            val state = if (DownloadPrefs.shouldPause(appContext, WORK_NAME)) DownloadWorkerState.PAUSED else DownloadWorkerState.CANCELLED
            reportProgress(calculateProgress(totalDownloadedBytes, totalBytes), state, if (state == DownloadWorkerState.PAUSED) "دانلود متوقف شد" else "دانلود لغو شد")
            return Result.success() // برای توقف، success برمیگردانیم تا Worker دوباره اجرا نشود
        } catch (e: Exception) {
            Log.e(TAG, "Error during chunked download for $WORK_NAME: ${e.message}", e)
            withContext(NonCancellable) {
                DownloadPrefs.setBytesRead(appContext, WORK_NAME, totalDownloadedBytes)
            }
            return reportFailure("خطا در دانلود: ${e.message}", calculateProgress(totalDownloadedBytes, totalBytes), totalDownloadedBytes)
        }
    }

    private suspend fun getTotalFileSize(url: String, totalBytesKey: String): Long {
        // از منطق `getTotalBytes` در `DownloadPrefs` شما استفاده می‌کند.
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
                    // از `setTotalBytes` شما استفاده می‌کنیم
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

    private fun prepareChunks(totalBytes: Long, outputDir: File, workName: String, isResuming: Boolean): List<Chunk> {
        val chunkSize = totalBytes / NUMBER_OF_CHUNKS
        val chunks = (0 until NUMBER_OF_CHUNKS).map { i ->
            val startByte = i * chunkSize
            val endByte = if (i == NUMBER_OF_CHUNKS - 1) totalBytes - 1 else (startByte + chunkSize) - 1
            Chunk(index = i, startByte = startByte, endByte = endByte)
        }

        // --- شروع بخش اصلاح شده ---

        // بررسی می‌کنیم آیا حداقل یکی از فایل‌های part از قبل وجود دارد یا خیر.
        val isAnyPartFilePresent = chunks.any { getChunkPartFile(outputDir, workName, it.index).exists() }
        val shouldResumeFromParts = isResuming || isAnyPartFilePresent

        if (shouldResumeFromParts) {
            // اگر باید ادامه دهیم، حجم فایل‌های part موجود را می‌خوانیم
            Log.d(TAG, "Resuming download, calculating downloaded size from existing part files...")
            chunks.forEach { chunk ->
                val partFile = getChunkPartFile(outputDir, workName, chunk.index)
                if (partFile.exists()) {
                    chunk.bytesDownloaded = partFile.length()
                }
            }
            // اطمینان حاصل می‌کنیم که مقدار Prefs با مقدار واقعی روی دیسک همگام است
            val totalBytesOnDisk = chunks.sumOf { it.bytesDownloaded }
            DownloadPrefs.setBytesRead(appContext, workName, totalBytesOnDisk)

        } else {
            // اگر دانلود جدید است، فایل‌های part قدیمی را پاک می‌کنیم
            Log.d(TAG, "Starting new download, deleting old part files...")
            chunks.forEach { chunk ->
                getChunkPartFile(outputDir, workName, chunk.index).delete()
            }
            // همچنین بایت‌های خوانده شده در Prefs را صفر می‌کنیم
            DownloadPrefs.setBytesRead(appContext, workName, 0L)
        }

        // --- پایان بخش اصلاح شده ---

        Log.d(TAG, "Chunks prepared for $workName. Details: $chunks")
        return chunks
    }


    private fun mergeFiles(parts: List<File>, outputFile: File) {
        // اطمینان از حذف فایل خروجی قدیمی قبل از ادغام
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

    // --- توابع کمکی (Helper Functions) ---

    private fun getAppSpecificDownloadsDir(): File? {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val appSpecificDir = File(downloadsDir, "PersianEFootball")
        if (!appSpecificDir.exists()) {
            if (!appSpecificDir.mkdirs()) {
                Log.e(TAG, "Failed to create directory: ${appSpecificDir.path}")
                return null
            }
        }
        return appSpecificDir
    }

    // --- توابع گزارش وضعیت (مشابه قبل) ---
    private suspend fun reportProgress(progress: Int, state: DownloadWorkerState, message: String? = null) {
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
            KEY_PROGRESS to actualProgress,
            KEY_DOWNLOAD_STATE to state.name
        )
        setProgress(progressDataOut)
    }

    private suspend fun reportFailure(errorMessage: String, progressPercent: Int, bytesSoFar: Long): Result {
        Log.e(TAG, "Download FAILED: $errorMessage, Progress: $progressPercent%, Bytes: $bytesSoFar")
        val notification = createNotification("دانلود ناموفق: $errorMessage", progressPercent, false)
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
            KEY_PROGRESS to 100,
            KEY_DOWNLOAD_STATE to DownloadWorkerState.COMPLETED.name
        )
        setProgress(finalData)
    }

    private fun createInitialForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = createNotification("در حال آماده سازی برای دانلود...", 0, true, true)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(contentText: String, progress: Int, ongoing: Boolean, indeterminate: Boolean = false) =
        NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle("دانلود دیتای بازی")
            .setTicker("دانلود دیتای بازی")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (ongoing && progress >= 0) {
                    setProgress(100, progress, indeterminate)
                }
            }
            .build()

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

    // تابع استخراج فایل ZIP (بدون تغییر)
    private fun extractZipFile(zipFilePath: String, outputDirString: String) {
        Log.i(TAG, "Attempting to extract '$zipFilePath' to '$outputDirString'.")
        val outputDir = File(outputDirString)
        if (!outputDir.exists()) {
            if (!outputDir.mkdirs()) throw IOException("امکان ایجاد پوشه مقصد برای استخراج وجود ندارد: $outputDirString")
        }

        ZipInputStream(FileInputStream(zipFilePath)).use { zipInput ->
            var entry: ZipEntry? = zipInput.nextEntry
            while (entry != null) {
                val newFile = File(outputDir, entry.name)
                val canonicalOutputPath = outputDir.canonicalPath
                val canonicalEntryPath = newFile.canonicalPath
                if (!canonicalEntryPath.startsWith(canonicalOutputPath + File.separator)) {
                    throw SecurityException("ورودی نامعتبر در فایل ZIP (Zip Slip): ${entry.name}")
                }

                if (entry.isDirectory) {
                    if (!newFile.mkdirs() && !newFile.isDirectory) {
                        throw IOException("امکان ایجاد پوشه '${newFile.absolutePath}' از فایل ZIP وجود ندارد.")
                    }
                } else {
                    val parent = newFile.parentFile
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs()
                    }
                    FileOutputStream(newFile).use { fos -> zipInput.copyTo(fos) }
                }
                zipInput.closeEntry()
                entry = zipInput.nextEntry
            }
        }
        Log.i(TAG, "Zip file extracted successfully to '$outputDirString'.")
    }
}


