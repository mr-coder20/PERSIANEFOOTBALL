package persian.alimsmi.efootball.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment // به استفاده از getExternalFilesDir در آینده فکر کنید
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import persian.alimsmi.efootball.R // مطمئن شوید رشته های لازم در اینجا تعریف شده اند
import persian.alimsmi.efootball.utils.DownloadPrefs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

// کلیدهای داده ورودی و خروجی برای Worker
const val KEY_INPUT_URL = "KEY_INPUT_URL"
const val KEY_OUTPUT_FILE_NAME = "KEY_OUTPUT_FILE_NAME"
const val KEY_PROGRESS = "KEY_PROGRESS"
const val KEY_DOWNLOAD_STATE = "KEY_DOWNLOAD_STATE"
const val KEY_RESUME_OFFSET = "KEY_RESUME_OFFSET"

enum class DownloadWorkerState {
    DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
}

class DownloadWorker(private val appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val WORK_NAME = "efootballDataDownload"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "efootball_download_channel"
        private const val TAG = "DownloadWorker"
        const val PREFS_TOTAL_BYTES_KEY_SUFFIX = "_total_bytes"
        fun getTotalBytesKey(workName: String) = workName + PREFS_TOTAL_BYTES_KEY_SUFFIX
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork started. Worker ID: $id")

        // 1. فراخوانی setForeground در همان ابتدای کار
        try {
            createNotificationChannel()
            val simpleNotification = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setContentTitle("Test Foreground")
                .setContentText("Worker is running...")
                .setSmallIcon(R.drawable.ic_download) // مطمئن شوید این آیکون وجود دارد و ساده است
                .build()
            setForeground(
                ForegroundInfo(
                    NOTIFICATION_ID,
                    simpleNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC // ✅
                )
            )
            Log.i(TAG, "setForeground (simple) called successfully at the beginning of doWork.")
        } catch (e: Exception) {
            Log.e(TAG, "IllegalStateException calling initial setForeground in doWork. This might be the core issue if WorkManager tried to start FGS before this worker called setForeground.", e)
            return Result.failure(
                workDataOf(
                    KEY_DOWNLOAD_STATE to DownloadWorkerState.FAILED.name,
                    "error" to "Failed to set foreground state timely: ${e.message}"
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Generic exception calling initial setForeground in doWork.", e)
            return Result.failure(
                workDataOf(
                    KEY_DOWNLOAD_STATE to DownloadWorkerState.FAILED.name,
                    "error" to "Generic error setting foreground state: ${e.message}"
                )
            )
        }

        val downloadUrl = inputData.getString(KEY_INPUT_URL)
            ?: return reportFailure(
                errorMessage = "URL دانلود موجود نیست. Worker ID: $id",
                bytesSoFar = 0L // پاس دادن مقدار پیش فرض برای بایت ها
            )
        val zipFileName = inputData.getString(KEY_OUTPUT_FILE_NAME)
            ?: return reportFailure(
                errorMessage = "نام فایل خروجی موجود نیست. Worker ID: $id",
                bytesSoFar = 0L // پاس دادن مقدار پیش فرض برای بایت ها
            )
        var resumeOffset = inputData.getLong(KEY_RESUME_OFFSET, 0L)
        // منطق resumeOffset شما به نظر خوب می آید
        if (resumeOffset == 0L) {
            val prefsOffset = DownloadPrefs.getBytesRead(appContext, WORK_NAME)
            if (prefsOffset > 0 && DownloadPrefs.shouldPause(appContext, WORK_NAME)) {
                resumeOffset = prefsOffset
                Log.d(TAG, "Resuming from Prefs offset (shouldPause was true): $resumeOffset for $WORK_NAME")
            } else if (prefsOffset > 0 && !DownloadPrefs.shouldPause(appContext, WORK_NAME)) {
                Log.d(TAG, "InputData offset is 0 for $WORK_NAME, shouldPause is false, but Prefs offset exists ($prefsOffset). Starting from 0 as per inputData (ViewModel should clear old offsets).")
            }
        }

        // 2. reportInitialProgress دیگر setForeground را فراخوانی نمی کند.
        // فقط وضعیت و پیشرفت اولیه را برای UI ارسال می کند و نوتیفیکیشن را آپدیت می کند.
        val totalBytesKey = getTotalBytesKey(WORK_NAME)
        reportInitialProgressAndUpdateNotification(resumeOffset, DownloadPrefs.getTotalBytesForWorker(appContext, totalBytesKey))

        // مسیردهی فایل ها (به استفاده از getExternalFilesDir برای سازگاری بهتر فکر کنید)
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val appSpecificDir = File(downloadsDir, "PersianEFootball")
        if (!appSpecificDir.exists()) {
            if (!appSpecificDir.mkdirs()) {
                return reportFailure("امکان ایجاد پوشه دانلود وجود ندارد: ${appSpecificDir.path}", calculateProgress(resumeOffset, DownloadPrefs.getTotalBytesForWorker(appContext, totalBytesKey)), resumeOffset)
            }
        }
        val outputFile = File(appSpecificDir, zipFileName)
        val client = OkHttpClient.Builder().build()

        try {
            Log.i(TAG, "Preparing download request. URL: $downloadUrl, Output: ${outputFile.path}, ResumeOffset: $resumeOffset")
            val requestBuilder = Request.Builder().url(downloadUrl)
            if (resumeOffset > 0) {
                requestBuilder.addHeader("Range", "bytes=$resumeOffset-")
                Log.i(TAG, "Attempting to resume download from byte: $resumeOffset for $downloadUrl")
            } else {
                Log.i(TAG, "Starting download from beginning for $downloadUrl")
            }

            val response = client.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful) {
                if (response.code == 416 && resumeOffset > 0) { // Range Not Satisfiable
                    Log.w(TAG, "Range not satisfiable (416) for $downloadUrl. Server may not support resume or file changed. Restarting download from beginning.")
                    // مهم: ریست کردن بایت های خوانده شده و توتال بایت ها
                    DownloadPrefs.setBytesRead(appContext, WORK_NAME, 0L)
                    DownloadPrefs.setTotalBytesForWorker(appContext, totalBytesKey, 0L)
                    val newRequest = Request.Builder().url(downloadUrl).build()
                    val newResponse = client.newCall(newRequest).execute()
                    if (!newResponse.isSuccessful) {
                        return reportFailure("دانلود از ابتدا هم ناموفق بود: ${newResponse.code} ${newResponse.message}", 0, 0L)
                    }
                    return processDownloadStream(newResponse, outputFile, 0L, totalBytesKey)
                }
                val currentBytesForFailure = if (resumeOffset > 0) resumeOffset else DownloadPrefs.getBytesRead(appContext, WORK_NAME)
                return reportFailure("دانلود ناموفق: ${response.code} ${response.message}", calculateProgress(currentBytesForFailure, DownloadPrefs.getTotalBytesForWorker(appContext, totalBytesKey)), currentBytesForFailure)
            }
            return processDownloadStream(response, outputFile, resumeOffset, totalBytesKey)

        } catch (e: CancellationException) {
            Log.i(TAG, "Download job was cancelled. Worker ID: $id", e)
            // مهم است که اینجا Result.failure یا success برگردانیم تا WorkManager وضعیت را بداند
            // اگر می خواهید وضعیت PAUSED را نشان دهید، باید در reportProgress انجام شود
            // و از اینجا success برگردانید. اما CancellationException معمولا به معنی لغو کامل است.
            withContext(NonCancellable) { // برای اطمینان از ذخیره وضعیت
                DownloadPrefs.setBytesRead(appContext, WORK_NAME, DownloadPrefs.getBytesRead(appContext, WORK_NAME)) // ذخیره آخرین بایت خوانده شده
                reportProgress(
                    calculateProgress(DownloadPrefs.getBytesRead(appContext, WORK_NAME), DownloadPrefs.getTotalBytesForWorker(appContext, totalBytesKey)),
                    DownloadWorkerState.CANCELLED,
                    appContext.getString(R.string.download_state_cancelled_by_system),
                    initial = false, // دیگر initial نیست
                    isCancellation = true
                )
            }
            return Result.failure() // یا success() اگر لغو را یک پایان موفقیت آمیز کار در نظر می گیرید
        } catch (e: IOException) {
            Log.e(TAG, "IOException during download setup or execution for $downloadUrl. Worker ID: $id", e)
            val currentBytes = DownloadPrefs.getBytesRead(appContext, WORK_NAME) // اطمینان از خواندن آخرین مقدار ذخیره شده
            return reportFailure("خطا در فایل یا شبکه: ${e.message}", calculateProgress(currentBytes, DownloadPrefs.getTotalBytesForWorker(appContext, totalBytesKey)), currentBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Generic exception during download setup or execution for $downloadUrl. Worker ID: $id", e)
            val currentBytes = DownloadPrefs.getBytesRead(appContext, WORK_NAME)
            return reportFailure("خطای ناشناخته: ${e.message}", calculateProgress(currentBytes, DownloadPrefs.getTotalBytesForWorker(appContext, totalBytesKey)), currentBytes)
        }
    }

    private suspend fun processDownloadStream(
        response: Response,
        outputFile: File,
        initialOffset: Long,
        totalBytesKey: String // پاس دادن کلید برای استفاده مداوم
    ): Result {
        val body = response.body ?: return reportFailure("پاسخ دانلود خالی است.", calculateProgress(initialOffset, DownloadPrefs.getTotalBytesForWorker(appContext, totalBytesKey)), initialOffset)

        // محاسبه totalBytesForProgressCalculation (منطق قبلی شما خوب بود)
        val totalBytesForProgressCalculation = when {
            response.code == 206 -> { // Partial Content
                val contentRange = response.header("Content-Range")
                val totalFromServer = contentRange?.substringAfterLast('/')?.toLongOrNull()
                if (totalFromServer != null) {
                    Log.d(TAG, "Partial content. Total size from Content-Range: $totalFromServer")
                    totalFromServer
                } else {
                    Log.w(TAG, "Partial content but Content-Range format for total size not found or invalid: $contentRange. Using body.contentLength() + initialOffset.")
                    body.contentLength() + initialOffset // این ممکن است دقیق نباشد اگر سرور Content-Length را برای ۲0۶ نفرستد
                }
            }
            else -> body.contentLength()
        }

        if (totalBytesForProgressCalculation <= 0 && initialOffset == 0L) {
            Log.w(TAG, "Total file size is unknown for ${response.request.url}, progress will not be accurate. Body.contentLength=${body.contentLength()}")
        }
        // ذخیره totalBytes فقط اگر بزرگتر از صفر باشد و یا مقدار قبلی صفر بوده باشد
        val currentKnownTotal = DownloadPrefs.getTotalBytesForWorker(appContext, totalBytesKey)
        if (totalBytesForProgressCalculation > 0) {
            if (currentKnownTotal <=0 || totalBytesForProgressCalculation != currentKnownTotal) {
                DownloadPrefs.setTotalBytesForWorker(appContext, totalBytesKey, totalBytesForProgressCalculation)
                Log.i(TAG, "Set total bytes for $WORK_NAME to: $totalBytesForProgressCalculation")
            }
        } else if (currentKnownTotal <= 0) {
            // اگر قبلا هم صفر بوده و الان هم صفر است، چیزی تغییر نکرده
            DownloadPrefs.setTotalBytesForWorker(appContext, totalBytesKey, -1L) // برای نشان دادن نامشخص بودن
            Log.w(TAG, "Total bytes remains unknown for $WORK_NAME. Setting to -1 in prefs.")
        }
        // اگر initialOffset صفر است و فایل وجود دارد، آن را پاک کن (برای شروع دانلود جدید)
        if (initialOffset == 0L && outputFile.exists()) {
            Log.i(TAG, "Initial offset is 0, deleting existing file: ${outputFile.path}")
            outputFile.delete()
        }

        var currentTotalBytesRead = initialOffset
        // اطمینان از اینکه currentTotalBytesRead از مقدار ذخیره شده در Prefs شروع می شود اگر initialOffset از ورودی 0 باشد
        // این حالت زمانی است که ViewModel ممکن است آفست را 0 پاس دهد اما Prefs مقدار داشته باشد
        if (initialOffset == 0L && DownloadPrefs.getBytesRead(appContext, WORK_NAME) > 0 && DownloadPrefs.shouldPause(appContext, WORK_NAME)) {
            currentTotalBytesRead = DownloadPrefs.getBytesRead(appContext, WORK_NAME)
            Log.i(TAG, "Corrected currentTotalBytesRead to $currentTotalBytesRead from Prefs as initialOffset was 0 but shouldPause was true.")
        }


        try {
            // استفاده از FileOutputStream با قابلیت append اگر initialOffset > 0 باشد
            FileOutputStream(outputFile, initialOffset > 0 && outputFile.exists()).use { out ->
                body.byteStream().use { inputStream ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesReadThisChunk: Int
                    var lastLoggedProgress = -1

                    while (inputStream.read(buffer).also { bytesReadThisChunk = it } != -1) {
                        if (isStopped) { // بررسی isStopped از CoroutineWorker
                            Log.i(TAG, "Work is stopped by WorkManager (isStopped is true). Progress: $currentTotalBytesRead bytes.")
                            withContext(NonCancellable) {
                                DownloadPrefs.setBytesRead(appContext, WORK_NAME, currentTotalBytesRead)
                                reportProgress(calculateProgress(currentTotalBytesRead, DownloadPrefs.getTotalBytesForWorker(appContext, totalBytesKey)), DownloadWorkerState.PAUSED, appContext.getString(R.string.download_state_paused_by_system), false, true)
                            }
                            return Result.success() // کار با موفقیت (متوقف شده) پایان می یابد
                        }

                        if (DownloadPrefs.shouldPause(appContext, WORK_NAME)) {
                            Log.i(TAG, "Work is paused by user command via Prefs. Progress: $currentTotalBytesRead bytes.")
                            withContext(NonCancellable) {
                                DownloadPrefs.setBytesRead(appContext, WORK_NAME, currentTotalBytesRead)
                                reportProgress(calculateProgress(currentTotalBytesRead, DownloadPrefs.getTotalBytesForWorker(appContext, totalBytesKey)), DownloadWorkerState.PAUSED, appContext.getString(R.string.download_state_paused_by_user), false, false)
                            }
                            return Result.success() // کار با موفقیت (متوقف شده توسط کاربر) پایان می یابد
                        }

                        out.write(buffer, 0, bytesReadThisChunk)
                        currentTotalBytesRead += bytesReadThisChunk

                        // ذخیره بایت های خوانده شده به صورت دوره ای (مثلا هر 256KB یا هر 1 ثانیه)
                        // این کار را در reportProgress انجام می دهیم.

                        val actualTotalBytesForProgress = DownloadPrefs.getTotalBytesForWorker(appContext, totalBytesKey)
                        val progressPercent = calculateProgress(currentTotalBytesRead, actualTotalBytesForProgress)

                        // ارسال پیشرفت - initial=false چون دیگر اولیه نیست
                        reportProgress(progressPercent, DownloadWorkerState.DOWNLOADING, initial = false)

                        if (actualTotalBytesForProgress > 0) {
                            if (progressPercent % 5 == 0 && progressPercent != lastLoggedProgress) {
                                Log.d(TAG, "Download Progress for $WORK_NAME: $progressPercent% ($currentTotalBytesRead / $actualTotalBytesForProgress)")
                                lastLoggedProgress = progressPercent
                            }
                        } else {
                            // لاگ کردن پیشرفت بر اساس بایت های خوانده شده اگر حجم کل نامشخص است
                            if (currentTotalBytesRead % (1024 * 1024) == 0L && currentTotalBytesRead > 0) { // هر 1MB
                                Log.d(TAG, "Download Progress for $WORK_NAME: $currentTotalBytesRead bytes (total size unknown)")
                            }
                        }
                    }
                }
            }

            // بررسی نهایی پس از اتمام حلقه دانلود
            val finalTotalBytesFromPrefs = DownloadPrefs.getTotalBytesForWorker(appContext, totalBytesKey)
            if (finalTotalBytesFromPrefs > 0 && currentTotalBytesRead < finalTotalBytesFromPrefs) {
                Log.w(TAG, "Download loop finished but read bytes ($currentTotalBytesRead) is less than total bytes ($finalTotalBytesFromPrefs). File might be incomplete for $WORK_NAME.")
                DownloadPrefs.setBytesRead(appContext, WORK_NAME, currentTotalBytesRead) // ذخیره بایت های فعلی
                return reportFailure("فایل به طور کامل دانلود نشد. ($currentTotalBytesRead/$finalTotalBytesFromPrefs)", calculateProgress(currentTotalBytesRead, finalTotalBytesFromPrefs), currentTotalBytesRead)
            }

            Log.i(TAG, "Download completed for $WORK_NAME. Total bytes read: $currentTotalBytesRead. Output: ${outputFile.absolutePath}. Starting extraction.")
            withContext(NonCancellable) {
                DownloadPrefs.setBytesRead(appContext, WORK_NAME, 0L) // ریست بایت های خوانده شده پس از دانلود موفق
                DownloadPrefs.setShouldPause(appContext, WORK_NAME, false)
                // DownloadPrefs.setTotalBytesForWorker(appContext, totalBytesKey, 0L) // توتال بایت را اینجا ریست نکنید، شاید برای نمایش لازم باشد
            }

            // مسیر استخراج (به استفاده از getExternalFilesDir فکر کنید)
            val pspDir = File(Environment.getExternalStorageDirectory(), "PSP")
            extractZipFile(outputFile.absolutePath, pspDir.absolutePath)

            Log.i(TAG, "Extraction completed for $WORK_NAME.")
            reportCompletion()
            return Result.success()

        } catch (e: CancellationException) {
            Log.i(TAG, "Download stream processing was cancelled. Worker ID: $id", e)
            withContext(NonCancellable) {
                DownloadPrefs.setBytesRead(appContext, WORK_NAME, currentTotalBytesRead)
                reportProgress(calculateProgress(currentTotalBytesRead, DownloadPrefs.getTotalBytesForWorker(appContext, totalBytesKey)), DownloadWorkerState.CANCELLED, appContext.getString(R.string.download_state_cancelled_by_system), false, true)
            }
            return Result.failure()
        } catch (e: IOException) {
            Log.e(TAG, "IOException during file operations in processDownloadStream for $WORK_NAME. Worker ID: $id", e)
            withContext(NonCancellable) {
                DownloadPrefs.setBytesRead(appContext, WORK_NAME, currentTotalBytesRead)
            }
            return reportFailure("خطا در فایل یا شبکه (جریان): ${e.message}", calculateProgress(currentTotalBytesRead, DownloadPrefs.getTotalBytesForWorker(appContext, totalBytesKey)), currentTotalBytesRead)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during file operations in processDownloadStream (possibly from extraction) for $WORK_NAME. Worker ID: $id", e)
            withContext(NonCancellable) {
                DownloadPrefs.setBytesRead(appContext, WORK_NAME, currentTotalBytesRead)
            }
            return reportFailure("خطا در پردازش فایل (جریان): ${e.message}", calculateProgress(currentTotalBytesRead, DownloadPrefs.getTotalBytesForWorker(appContext, totalBytesKey)), currentTotalBytesRead)
        }
    }

    // تغییر نام و منطق برای آپدیت نوتیفیکیشن بدون فراخوانی setForeground
    private suspend fun reportInitialProgressAndUpdateNotification(offset: Long, totalBytes: Long) {
        val progress = calculateProgress(offset, totalBytes)
        val message = if (offset > 0) appContext.getString(R.string.download_resuming) // مثال: "در حال ادامه دانلود..."
        else appContext.getString(R.string.download_preparing) // مثال: "در حال آماده سازی..."

        // فقط نوتیفیکیشن را آپدیت می کنیم (setForeground قبلا در doWork فراخوانی شده)
        val notification = createNotification(message, progress.coerceIn(0,100), true, progress < 0)
        notificationManager.notify(NOTIFICATION_ID, notification)

        // ارسال پیشرفت اولیه به ViewModel
        val progressDataOut = workDataOf(
            KEY_PROGRESS to progress.coerceIn(0,100),
            KEY_DOWNLOAD_STATE to DownloadWorkerState.DOWNLOADING.name // وضعیت اولیه دانلودینگ است
        )
        setProgress(progressDataOut)
        Log.d(TAG, "Initial progress reported and notification updated: Progress $progress%, Offset $offset, TotalBytes $totalBytes")
    }

    // isCancellation برای مدیریت پیام لغو خاص
    private suspend fun reportProgress(
        progressPercentCalculated: Int,
        state: DownloadWorkerState,
        contentTextOverride: String? = null,
        initial: Boolean, // دیگر استفاده نمی شود چون setForeground جدا شده
        isCancellation: Boolean = false // برای تشخیص پیام لغو
    ) {
        val actualProgress = if (progressPercentCalculated < 0 && state == DownloadWorkerState.DOWNLOADING) 0 else progressPercentCalculated.coerceIn(0, 100)
        val isIndeterminate = progressPercentCalculated < 0 && state == DownloadWorkerState.DOWNLOADING && !isCancellation

        // ذخیره بایت های خوانده شده در اینجا، قبل از آپدیت نوتیفیکیشن و setProgress
        // این کار باید در processDownloadStream انجام شود چون بایت های خوانده شده آنجا در دسترس است
        // اما اگر می خواهید اینجا هم بر اساس پیشرفت تخمین بزنید، باید totalBytes را داشته باشید.
        // فعلا این بخش را ساده نگه می داریم و به ذخیره در processDownloadStream اتکا می کنیم.

        val defaultMessageKey = when (state) {
            DownloadWorkerState.DOWNLOADING -> if (!isIndeterminate) R.string.download_state_downloading else R.string.download_state_downloading_indeterminate
            DownloadWorkerState.PAUSED -> if (!isIndeterminate) R.string.download_state_paused else R.string.download_state_paused_indeterminate
            DownloadWorkerState.CANCELLED -> R.string.download_state_cancelled // پیام عمومی لغو
            DownloadWorkerState.FAILED -> R.string.download_state_failed // پیام عمومی ناموفق
            DownloadWorkerState.COMPLETED -> R.string.download_state_completed // پیام عمومی کامل شد
        }

        val messageArg = if (!isIndeterminate && state != DownloadWorkerState.CANCELLED && state != DownloadWorkerState.FAILED && state != DownloadWorkerState.COMPLETED) actualProgress else ""
        val defaultMessage = if (messageArg is Int) appContext.getString(defaultMessageKey, messageArg) else appContext.getString(defaultMessageKey)

        val currentContentText = contentTextOverride ?: defaultMessage

        // setForeground اینجا فراخوانی نمی شود. فقط نوتیفیکیشن آپدیت می شود.
        val notification = createNotification(currentContentText, actualProgress, (state == DownloadWorkerState.DOWNLOADING || state == DownloadWorkerState.PAUSED), isIndeterminate)
        notificationManager.notify(NOTIFICATION_ID, notification)

        val progressDataOut = workDataOf(
            KEY_PROGRESS to actualProgress,
            KEY_DOWNLOAD_STATE to state.name
        )
        setProgress(progressDataOut)
        // Log.v(TAG, "Progress reported: State=$state, Percent=$actualProgress, Indeterminate=$isIndeterminate, Text='$currentContentText'") // لاگ دقیق تر
    }


    private suspend fun reportFailure(errorMessage: String, progressPercent: Int = 0, bytesSoFar: Long): Result {
        Log.e(TAG, "Download FAILED for $WORK_NAME: $errorMessage, Progress: $progressPercent%, Bytes: $bytesSoFar. Worker ID: $id")
        withContext(NonCancellable) {
            DownloadPrefs.setBytesRead(appContext, WORK_NAME, bytesSoFar) // ذخیره بایت ها در صورت شکست
        }
        val actualProgress = progressPercent.coerceIn(0, 100)
        val notification = createNotification(
            appContext.getString(R.string.download_failed_message, errorMessage), // مثال: "دانلود ناموفق: %s"
            actualProgress,
            ongoing = false
        )
        notificationManager.notify(NOTIFICATION_ID, notification)

        val outputData = workDataOf(
            KEY_PROGRESS to actualProgress,
            KEY_DOWNLOAD_STATE to DownloadWorkerState.FAILED.name,
            "error" to errorMessage
        )
        // setProgress(outputData) // در Result.failure به صورت خودکار انجام می شود اگر data داشته باشد
        return Result.failure(outputData)
    }

    private suspend fun reportCompletion() {
        Log.i(TAG, "Download and extraction COMPLETED for $WORK_NAME. Worker ID: $id")
        val finalNotification = createNotification(
            appContext.getString(R.string.download_completed_message), // مثال: "دانلود و نصب کامل شد"
            100,
            ongoing = false
        )
        notificationManager.notify(NOTIFICATION_ID, finalNotification)
        val finalData = workDataOf(
            KEY_PROGRESS to 100,
            KEY_DOWNLOAD_STATE to DownloadWorkerState.COMPLETED.name
        )
        setProgress(finalData) // ارسال وضعیت نهایی
        // ریست کردن total bytes بعد از تکمیل موفقیت آمیز (اختیاری، اگر می خواهید برای اجرای بعدی از نو شروع شود)
        // DownloadPrefs.setTotalBytesForWorker(appContext, getTotalBytesKey(WORK_NAME), 0L)
    }

    private fun calculateProgress(bytesRead: Long, totalBytes: Long): Int {
        return if (totalBytes > 0) {
            ((bytesRead * 100.0) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            -1 // نشان دهنده نامشخص بودن پیشرفت
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                val name = appContext.getString(R.string.download_channel_name) // مثال: "کانال دانلود"
                val descriptionText = appContext.getString(R.string.download_channel_description) // مثال: "نوتیفیکیشن های مربوط به دانلود دیتا"
                val importance = NotificationManager.IMPORTANCE_LOW // یا IMPORTANCE_DEFAULT اگر می خواهید بیشتر قابل مشاهده باشد
                val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                    description = descriptionText
                    // setSound(null, null) // برای جلوگیری از صدا در هر آپدیت نوتیفیکیشن
                    setShowBadge(false) // عدم نمایش نقطه روی آیکون برنامه
                }
                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "Notification channel $CHANNEL_ID created.")
            } else {
                Log.d(TAG, "Notification channel $CHANNEL_ID already exists.")
            }
        }
    }

    private fun createNotification(contentText: String, progress: Int, ongoing: Boolean, indeterminate: Boolean = false) =
        NotificationCompat.Builder(appContext, CHANNEL_ID) // استفاده از appContext به جای applicationContext
            .setContentTitle(appContext.getString(R.string.download_notification_title)) // مثال: "دانلود دیتای بازی"
            .setTicker(appContext.getString(R.string.download_notification_ticker)) // متنی که به صورت خلاصه در status bar نمایش داده می شود
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_download) // مطمئن شوید این آیکون وجود دارد
            .setOngoing(ongoing) // اگر true باشد، کاربر نمی تواند نوتیفیکیشن را با swipe ببندد
            .setAutoCancel(!ongoing) // اگر true باشد، با کلیک روی نوتیفیکیشن بسته می شود (وقتی ongoing false است)
            .setOnlyAlertOnce(true) // فقط برای اولین بار صدا/ویبره داشته باشد (اگر کانال این اجازه را بدهد)
            .setPriority(NotificationCompat.PRIORITY_LOW) // یا PRIORITY_DEFAULT
            .apply {
                if (ongoing && progress in 0..100) { // فقط اگر ongoing و پیشرفت معتبر است، نوار پیشرفت را نشان بده
                    setProgress(100, progress, indeterminate)
                }
            }
            .build()

    // تابع استخراج فایل ZIP (منطق قبلی شما خوب بود، فقط چند لاگ اضافه شده)
    private fun extractZipFile(zipFilePath: String, outputDirString: String) {
        Log.i(TAG, "Attempting to extract '$zipFilePath' to '$outputDirString'.")
        val outputDir = File(outputDirString)
        if (!outputDir.exists()) {
            Log.d(TAG, "Output directory for extraction does not exist. Creating: $outputDirString")
            if (!outputDir.mkdirs()) {
                Log.e(TAG, "Failed to create output directory for extraction: $outputDirString")
                throw IOException("امکان ایجاد پوشه مقصد برای استخراج وجود ندارد: $outputDirString")
            }
        } else if (!outputDir.isDirectory) {
            Log.e(TAG, "Output path for extraction exists but is not a directory: $outputDirString")
            throw IOException("مسیر مقصد برای استخراج یک پوشه نیست: $outputDirString")
        }

        val zipFile = File(zipFilePath)
        if (!zipFile.exists() || !zipFile.isFile) {
            Log.e(TAG, "Zip file not found or is not a file for extraction: $zipFilePath")
            throw IOException("فایل ZIP برای استخراج یافت نشد: $zipFilePath")
        }

        var entriesExtractedCount = 0
        try {
            ZipInputStream(FileInputStream(zipFile)).use { zipInput ->
                var entry: ZipEntry? = zipInput.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    val newFile = File(outputDir, entryName)
                    Log.d(TAG, "Processing ZipEntry: $entryName")

                    // بررسی امنیتی Zip Slip
                    val canonicalOutputPath = outputDir.canonicalPath
                    val canonicalEntryPath = newFile.canonicalPath
                    if (!canonicalEntryPath.startsWith(canonicalOutputPath + File.separator)) {
                        Log.e(TAG, "Zip Slip Vulnerability detected for entry: $entryName. Path: $canonicalEntryPath, Expected Prefix: $canonicalOutputPath")
                        throw SecurityException("ورودی نامعتبر در فایل ZIP (Zip Slip): $entryName")
                    }

                    if (entry.isDirectory) {
                        if (!newFile.exists()) { // فقط اگر پوشه وجود ندارد ایجاد کن
                            Log.d(TAG, "Creating directory from zip: ${newFile.absolutePath}")
                            if (!newFile.mkdirs()) {
                                throw IOException("امکان ایجاد پوشه '${newFile.absolutePath}' از فایل ZIP وجود ندارد.")
                            }
                        } else if (!newFile.isDirectory) {
                            throw IOException("مسیر '${newFile.absolutePath}' وجود دارد اما پوشه نیست.")
                        }
                    } else {
                        val parentDir = newFile.parentFile
                        if (parentDir != null && !parentDir.exists()) { // اگر پوشه والد وجود ندارد ایجاد کن
                            Log.d(TAG, "Creating parent directory for file: ${parentDir.absolutePath}")
                            if (!parentDir.mkdirs()) {
                                throw IOException("امکان ایجاد پوشه والد '${parentDir.absolutePath}' برای فایل '${newFile.name}' وجود ندارد.")
                            }
                        } else if (parentDir != null && !parentDir.isDirectory) {
                            throw IOException("مسیر والد '${parentDir.absolutePath}' وجود دارد اما پوشه نیست.")
                        }


                        Log.d(TAG, "Extracting file: ${newFile.absolutePath} (Size: ${entry.size} bytes)")
                        FileOutputStream(newFile).use { fos ->
                            val buffer = ByteArray(8 * 1024)
                            var len: Int
                            while (zipInput.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                            }
                        }
                        entriesExtractedCount++
                    }
                    zipInput.closeEntry()
                    entry = zipInput.nextEntry
                }
            }
            Log.i(TAG, "Zip file extracted successfully. Total files/dirs processed: $entriesExtractedCount to '$outputDirString'.")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security error extracting zip file '$zipFilePath': ${e.message}", e)
            throw IOException("خطای امنیتی در استخراج فایل ZIP: ${e.message}", e) // Re-throw as IOException for consistent error handling
        } catch (e: IOException) {
            Log.e(TAG, "IO error extracting zip file '$zipFilePath': ${e.message}", e)
            throw e // Re-throw
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error extracting zip file '$zipFilePath': ${e.message}", e)
            throw IOException("خطای ناشناخته هنگام استخراج فایل ZIP: ${e.message}", e) // Re-throw as IOException
        }
    }
}
