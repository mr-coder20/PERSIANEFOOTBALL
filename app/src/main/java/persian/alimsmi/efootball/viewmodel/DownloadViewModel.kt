package persian.alimsmi.efootball.viewmodel

import android.app.Application
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.work.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import persian.alimsmi.efootball.utils.DownloadPrefs
import persian.alimsmi.efootball.worker.*

data class DownloadUiState(
    val progress: Int = 0,
    val status: DownloadWorkerState = DownloadWorkerState.CANCELLED, // وضعیت اولیه بهتر است چیزی باشد که نیاز به دانلود را نشان دهد
    val isDownloading: Boolean = false,
    val showDialog: Boolean = false,
    val errorMessage: String? = null,
    val downloadUrl: String? = null,
    val outputFileName: String? = null,
    val userDismissedDialog: Boolean = false
)

class DownloadViewModel(private val app: Application) : AndroidViewModel(app) {

    private val workManager = WorkManager.getInstance(app)
    private val _downloadUiState = mutableStateOf(DownloadUiState())
    val downloadUiState: State<DownloadUiState> = _downloadUiState

    companion object {
        private const val TAG = "DownloadViewModel"
    }

    init {
        // بررسی اولیه وضعیت دانلود هنگام شروع ViewModel
        // اگر کاری در حال اجرا یا متوقف شده وجود دارد، UI را با آن همگام کن
        // این کمک می کند اگر برنامه بسته و دوباره باز شود.
        val existingWorkInfo = workManager.getWorkInfosForUniqueWork(DownloadWorker.WORK_NAME).get()?.firstOrNull()
        if (existingWorkInfo != null) {
            Log.d(TAG, "Found existing work on init: ${existingWorkInfo.state}")
            handleWorkInfo(existingWorkInfo)
            // اگر کار قبلا کامل شده و پیامش نشان داده شده، دیالوگ را باز نکن
            if (existingWorkInfo.state == WorkInfo.State.SUCCEEDED &&
                (try { DownloadWorkerState.valueOf(existingWorkInfo.progress.getString(KEY_DOWNLOAD_STATE) ?: "") } catch (e: Exception) { null }) == DownloadWorkerState.COMPLETED &&
                DownloadPrefs.hasCompletionMessageBeenShown(app, DownloadWorker.WORK_NAME)) {
                _downloadUiState.value = _downloadUiState.value.copy(showDialog = false)
            } else if (existingWorkInfo.state == WorkInfo.State.RUNNING || existingWorkInfo.state == WorkInfo.State.ENQUEUED) {
                // اگر در حال اجراست و کاربر قبلا دیالوگ را نبسته، دیالوگ را نشان بده
                if (!_downloadUiState.value.userDismissedDialog) {
                    _downloadUiState.value = _downloadUiState.value.copy(showDialog = true)
                }
            }
        } else {
            // اگر هیچ کار فعالی نیست، بررسی کن آیا پیام کامل شدن قبلا نشان داده شده
            if (DownloadPrefs.hasCompletionMessageBeenShown(app, DownloadWorker.WORK_NAME)) {
                // اگر نشان داده شده، یعنی دانلود قبلا کامل شده، پس UiState را به COMPLETED بدون دیالوگ تنظیم کن
                _downloadUiState.value = DownloadUiState(status = DownloadWorkerState.COMPLETED, progress = 100, showDialog = false)
            }
        }

        workManager.getWorkInfosForUniqueWorkLiveData(DownloadWorker.WORK_NAME)
            .asFlow()
            .mapNotNull { it.firstOrNull() }
            .onEach { workInfo ->
                handleWorkInfo(workInfo)
            }
            .launchIn(viewModelScope)
    }

    private fun handleWorkInfo(workInfo: WorkInfo?) {
        if (workInfo == null) {
            val currentUiState = _downloadUiState.value
            if (!DownloadPrefs.hasCompletionMessageBeenShown(app, DownloadWorker.WORK_NAME)) { // اگر پیام کامل شدن نشان داده نشده
                if (currentUiState.showDialog && !currentUiState.userDismissedDialog &&
                    (currentUiState.status == DownloadWorkerState.FAILED ||
                            currentUiState.status == DownloadWorkerState.CANCELLED ||
                            currentUiState.status == DownloadWorkerState.PAUSED)
                ) {
                    _downloadUiState.value = currentUiState.copy(
                        isDownloading = false,
                        errorMessage = currentUiState.errorMessage ?: "فرایند دانلود یافت نشد."
                    )
                } else if (!currentUiState.showDialog && !currentUiState.userDismissedDialog && currentUiState.status != DownloadWorkerState.COMPLETED) {
                    if (_downloadUiState.value != DownloadUiState()) {
                        _downloadUiState.value = DownloadUiState()
                    }
                }
            } else {
                // اگر پیام کامل شدن نشان داده شده، دیالوگ را برای وضعیت های غیرفعال باز نکن
                if (currentUiState.status != DownloadWorkerState.DOWNLOADING && currentUiState.status != DownloadWorkerState.PAUSED) {
                    _downloadUiState.value = currentUiState.copy(showDialog = false)
                }
            }
            Log.d(TAG, "No active or relevant past work found or work is finished: ${DownloadWorker.WORK_NAME}")
            return
        }

        Log.d(TAG, "Handling WorkInfo: ID: ${workInfo.id} State: ${workInfo.state}, Progress: ${workInfo.progress}")

        val progress = workInfo.progress.getInt(KEY_PROGRESS, 0)
        val workerStateString = workInfo.progress.getString(KEY_DOWNLOAD_STATE)
        var effectiveWorkerState = try {
            workerStateString?.let { DownloadWorkerState.valueOf(it) }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid DownloadWorkerState from progress: $workerStateString")
            null
        }

        if (effectiveWorkerState == null || (workInfo.state == WorkInfo.State.SUCCEEDED && effectiveWorkerState != DownloadWorkerState.PAUSED)) {
            effectiveWorkerState = when (workInfo.state) {
                WorkInfo.State.RUNNING -> DownloadWorkerState.DOWNLOADING
                WorkInfo.State.SUCCEEDED -> {
                    val stateFromProgressOnSuccess = try {
                        workInfo.progress.getString(KEY_DOWNLOAD_STATE)?.let { DownloadWorkerState.valueOf(it) }
                    } catch (e: Exception) { null }
                    if (stateFromProgressOnSuccess == DownloadWorkerState.PAUSED) DownloadWorkerState.PAUSED
                    else DownloadWorkerState.COMPLETED
                }
                WorkInfo.State.FAILED -> DownloadWorkerState.FAILED
                WorkInfo.State.CANCELLED -> DownloadWorkerState.CANCELLED
                WorkInfo.State.BLOCKED, WorkInfo.State.ENQUEUED -> DownloadWorkerState.PAUSED // یا DOWNLOADING اگر بلافاصله شروع می شود
            }
        }

        val isDownloadingRealTime = workInfo.state == WorkInfo.State.RUNNING && effectiveWorkerState == DownloadWorkerState.DOWNLOADING
        val errorMessageFromWork = if (effectiveWorkerState == DownloadWorkerState.FAILED) {
            workInfo.outputData.getString("error") ?: workInfo.progress.getString("error") ?: "خطای ناشناخته"
        } else if (effectiveWorkerState == DownloadWorkerState.CANCELLED) {
            _downloadUiState.value.errorMessage // حفظ پیام لغو قبلی
        } else null

        val userHasManuallyDismissed = _downloadUiState.value.userDismissedDialog
        var shouldShowDialogNew = _downloadUiState.value.showDialog
        var resetUserDismissedFlag = userHasManuallyDismissed

        if (effectiveWorkerState == DownloadWorkerState.COMPLETED) {
            if (!DownloadPrefs.hasCompletionMessageBeenShown(app, DownloadWorker.WORK_NAME)) {
                shouldShowDialogNew = true
                resetUserDismissedFlag = false // چون دیالوگ را فعالانه نشان می دهیم
            } else {
                shouldShowDialogNew = false // قبلا نشان داده شده، دیگر نشان نده
            }
        } else if (userHasManuallyDismissed) {
            if ((effectiveWorkerState == DownloadWorkerState.FAILED && errorMessageFromWork != null && errorMessageFromWork != _downloadUiState.value.errorMessage) ||
                (effectiveWorkerState == DownloadWorkerState.CANCELLED && _downloadUiState.value.status != DownloadWorkerState.CANCELLED)
            ) {
                shouldShowDialogNew = true
                resetUserDismissedFlag = false
            } else {
                shouldShowDialogNew = false
            }
        } else { // اگر کاربر نبسته و وضعیت هم COMPLETED نیست
            shouldShowDialogNew = isDownloadingRealTime ||
                    effectiveWorkerState == DownloadWorkerState.PAUSED ||
                    effectiveWorkerState == DownloadWorkerState.FAILED ||
                    effectiveWorkerState == DownloadWorkerState.CANCELLED ||
                    _downloadUiState.value.showDialog // اگر به هر دلیلی از قبل باز بوده (مثلا کاربر تازه startDownload زده)
        }

        _downloadUiState.value = _downloadUiState.value.copy(
            progress = progress,
            status = effectiveWorkerState,
            isDownloading = isDownloadingRealTime,
            showDialog = shouldShowDialogNew,
            errorMessage = errorMessageFromWork ?: if (effectiveWorkerState != DownloadWorkerState.FAILED && effectiveWorkerState != DownloadWorkerState.CANCELLED) null else _downloadUiState.value.errorMessage,
            userDismissedDialog = resetUserDismissedFlag,
            // اطمینان از اینکه URL و نام فایل در UiState باقی می مانند اگر دانلود فعال است
            downloadUrl = if(effectiveWorkerState == DownloadWorkerState.DOWNLOADING || effectiveWorkerState == DownloadWorkerState.PAUSED) _downloadUiState.value.downloadUrl else null,
            outputFileName = if(effectiveWorkerState == DownloadWorkerState.DOWNLOADING || effectiveWorkerState == DownloadWorkerState.PAUSED) _downloadUiState.value.outputFileName else null
        )
        Log.d(TAG, "UI State Updated: Status: $effectiveWorkerState, Progress: $progress, ShowDialog: $shouldShowDialogNew, UserDismissed: $resetUserDismissedFlag, IsDownloading: $isDownloadingRealTime")
    }

    fun startDownload(url: String, fileName: String) {
        val currentUiState = _downloadUiState.value
        val isTryingToContinueOrRetry = currentUiState.status == DownloadWorkerState.PAUSED ||
                currentUiState.status == DownloadWorkerState.FAILED ||
                currentUiState.status == DownloadWorkerState.CANCELLED

        Log.d(TAG, "Request to start/continue/retry download: URL=$url, FileName=$fileName, IsTryingToContinueOrRetry=$isTryingToContinueOrRetry, CurrentStatus=${currentUiState.status}")

        _downloadUiState.value = currentUiState.copy(
            downloadUrl = url,
            outputFileName = fileName,
            showDialog = true,
            userDismissedDialog = false,
            errorMessage = if (!isTryingToContinueOrRetry || currentUiState.status == DownloadWorkerState.PAUSED) null else currentUiState.errorMessage,
            status = if (currentUiState.status == DownloadWorkerState.PAUSED && isTryingToContinueOrRetry) DownloadWorkerState.PAUSED else DownloadWorkerState.PAUSED,
            progress = if (currentUiState.status == DownloadWorkerState.PAUSED && isTryingToContinueOrRetry) currentUiState.progress else 0,
            isDownloading = false
        )

        if (!isTryingToContinueOrRetry || currentUiState.status == DownloadWorkerState.FAILED || currentUiState.status == DownloadWorkerState.CANCELLED) {
            Log.d(TAG, "Fresh start or Retry from FAILED/CANCELLED: Clearing download state & completion flag for ${DownloadWorker.WORK_NAME}.")
            DownloadPrefs.clearDownloadState(app, DownloadWorker.WORK_NAME) // این باید آفست و shouldPause را پاک کند
            DownloadPrefs.setTotalBytes(app, DownloadWorker.WORK_NAME, 0L) // ریست توتال بایت هم مهم است
            DownloadPrefs.resetCompletionMessageShown(app, DownloadWorker.WORK_NAME) // <<-- ریست فلگ
        }
        DownloadPrefs.setShouldPause(app, DownloadWorker.WORK_NAME, false)

        val resumeOffsetCalculated: Long = if (currentUiState.status == DownloadWorkerState.PAUSED && isTryingToContinueOrRetry) {
            val offset = DownloadPrefs.getBytesRead(app, DownloadWorker.WORK_NAME)
            Log.d(TAG, "Resuming from PAUSED with offset: $offset for ${DownloadWorker.WORK_NAME}")
            offset
        } else {
            Log.d(TAG, "Starting new or retrying from FAILED/CANCELLED from offset 0 for ${DownloadWorker.WORK_NAME}.")
            0L
        }

        val inputData = workDataOf(
            KEY_INPUT_URL to url,
            KEY_OUTPUT_FILE_NAME to fileName,
            KEY_RESUME_OFFSET to resumeOffsetCalculated
        )

        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val downloadWorkRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniqueWork(
            DownloadWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            downloadWorkRequest
        )
        Log.d(TAG, "Download work for ${DownloadWorker.WORK_NAME} enqueued. Offset: $resumeOffsetCalculated")
    }

    fun cancelDownload() {
        Log.d(TAG, "User requested cancel download for ${DownloadWorker.WORK_NAME}.")
        workManager.cancelUniqueWork(DownloadWorker.WORK_NAME)
        DownloadPrefs.clearDownloadState(app, DownloadWorker.WORK_NAME)
        DownloadPrefs.setTotalBytes(app, DownloadWorker.WORK_NAME, 0L)
        DownloadPrefs.resetCompletionMessageShown(app, DownloadWorker.WORK_NAME) // <<-- ریست فلگ

        _downloadUiState.value = _downloadUiState.value.copy(
            status = DownloadWorkerState.CANCELLED,
            isDownloading = false,
            progress = 0,
            showDialog = false, // <<-- بستن فوری دیالوگ
            userDismissedDialog = false, // یا true برای "به خاطر سپردن" لغو
            errorMessage = "دانلود توسط کاربر لغو شد.",
            downloadUrl = null, // پاک کردن اطلاعات دانلود از وضعیت فعلی
            outputFileName = null
        )
        Log.i(TAG, "Download cancelled by user. Dialog closed.")
    }

    fun requestPauseDownload() {
        if (_downloadUiState.value.status == DownloadWorkerState.DOWNLOADING) {
            Log.d(TAG, "Requesting PAUSE for download via Prefs for ${DownloadWorker.WORK_NAME}.")
            DownloadPrefs.setShouldPause(app, DownloadWorker.WORK_NAME, true)
        }
    }

    fun dismissDialogAndContinueInBackground() {
        Log.d(TAG, "User dismissed dialog, download will continue in background.")
        if (_downloadUiState.value.status == DownloadWorkerState.DOWNLOADING || _downloadUiState.value.status == DownloadWorkerState.PAUSED) {
            _downloadUiState.value = _downloadUiState.value.copy(
                showDialog = false,
                userDismissedDialog = true
            )
        } else {
            _downloadUiState.value = _downloadUiState.value.copy(showDialog = false)
        }
    }

    fun dismissRequestClose() {
        val currentUiStateValue = _downloadUiState.value
        Log.d(TAG, "dismissRequestClose called. Current status: ${currentUiStateValue.status}")

        when (currentUiStateValue.status) {
            DownloadWorkerState.COMPLETED -> {
                if (!DownloadPrefs.hasCompletionMessageBeenShown(app, DownloadWorker.WORK_NAME)) {
                    DownloadPrefs.setCompletionMessageShown(app, DownloadWorker.WORK_NAME, true)
                    Log.d(TAG, "Completion message for ${DownloadWorker.WORK_NAME} marked as shown.")
                }
                _downloadUiState.value = currentUiStateValue.copy(showDialog = false, userDismissedDialog = false)
            }
            DownloadWorkerState.FAILED, DownloadWorkerState.CANCELLED -> {
                _downloadUiState.value = currentUiStateValue.copy(showDialog = false, userDismissedDialog = false)
            }
            DownloadWorkerState.DOWNLOADING, DownloadWorkerState.PAUSED -> {
                // اگر کاربر بیرون از دیالوگ کلیک کند در حالی که دانلود فعال است
                Log.d(TAG, "Dismiss request during active download/pause. Treating as 'continue in background'.")
                dismissDialogAndContinueInBackground()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel Cleared.")
    }
}

class DownloadViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DownloadViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DownloadViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
   