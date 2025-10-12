package psp.fazli.efootball.viewmodel

import psp.fazli.efootball.worker.DownloadWorkerState

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