package shin.a.pes.worker

// کلیدهای داده ورودی و خروجی برای Worker
const val KEY_INPUT_URL = "KEY_INPUT_URL"
const val KEY_OUTPUT_FILE_NAME = "KEY_OUTPUT_FILE_NAME"
const val KEY_PROGRESS = "KEY_PROGRESS"
const val KEY_DOWNLOAD_STATE = "KEY_DOWNLOAD_STATE"
const val KEY_RESUME_OFFSET = "KEY_RESUME_OFFSET"

enum class DownloadWorkerState {
    DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
}

