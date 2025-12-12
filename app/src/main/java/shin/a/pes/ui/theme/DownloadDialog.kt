package shin.a.pes.ui.theme

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import shin.a.pes.viewmodel.DownloadUiState
import shin.a.pes.worker.DownloadWorkerState

@Composable
fun DownloadDialog(
    uiState: DownloadUiState,
    onDismissRequestClose: () -> Unit,
    onDismissDialogAndContinueInBackground: () -> Unit,
    onCancelDownload: () -> Unit,
    onRetryFailedDownload: () -> Unit // <<-- کال بک جدید برای تلاش مجدد
) {
    if (uiState.showDialog) {
        AlertDialog(
            onDismissRequest = onDismissRequestClose,
            title = { Text(text = "دانلود دیتا") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when (uiState.status) {
                        DownloadWorkerState.DOWNLOADING -> {
                            Text(text = "در حال دانلود... ${uiState.progress}%")
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { uiState.progress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        DownloadWorkerState.PAUSED -> {
                            Text(text = "دانلود متوقف شد. ${uiState.progress}%")
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { uiState.progress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        DownloadWorkerState.COMPLETED -> {
                            Text(text = "دانلود و نصب دیتا کامل شد.")
                        }
                        DownloadWorkerState.FAILED -> {
                            Text(text = "دانلود ناموفق بود: ${uiState.errorMessage ?: "خطای ناشناخته"}")
                        }
                        DownloadWorkerState.CANCELLED -> {
                            Text(text = "دانلود لغو شد: ${uiState.errorMessage ?: ""}")
                        }
                    }
                }
            },
            confirmButton = {
                when (uiState.status) {
                    DownloadWorkerState.DOWNLOADING, DownloadWorkerState.PAUSED -> {
                        TextButton(onClick = onDismissDialogAndContinueInBackground) {
                            Text(text = "دانلود در پس زمینه")
                        }
                    }
                    DownloadWorkerState.FAILED -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onRetryFailedDownload) { // <<-- استفاده از کال بک جدید
                                Text(text = "تلاش مجدد")
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = onDismissRequestClose) {
                                Text(text = "بستن")
                            }
                        }
                    }
                    DownloadWorkerState.COMPLETED, DownloadWorkerState.CANCELLED -> {
                        TextButton(onClick = onDismissRequestClose) {
                            Text(text = "بستن")
                        }
                    }
                }
            },
            dismissButton = {
                if (uiState.status == DownloadWorkerState.DOWNLOADING || uiState.status == DownloadWorkerState.PAUSED) {
                    TextButton(onClick = onCancelDownload) {
                        Text(text = "لغو کامل")
                    }
                }
            }
        )
    }
}

