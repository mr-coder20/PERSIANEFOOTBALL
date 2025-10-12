package psp.fazli.efootball.utils // یا هر پکیج دیگری

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object DownloadPrefs {
    private const val PREFS_NAME = "download_prefs"
    private const val KEY_SHOULD_PAUSE_PREFIX = "should_pause_"
    private const val KEY_BYTES_READ_PREFIX = "bytes_read_"
    private const val KEY_COMPLETION_MESSAGE_SHOWN_PREFIX = "completion_message_shown_" // <<-- جدید
    private const val KEY_TOTAL_BYTES_SUFFIX_FOR_PREFS = "_total_bytes" // برای سازگاری با DownloadWorker

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun shouldPause(context: Context, workName: String): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOULD_PAUSE_PREFIX + workName, false)
    }

    fun setShouldPause(context: Context, workName: String, pause: Boolean) {
        getPrefs(context).edit { putBoolean(KEY_SHOULD_PAUSE_PREFIX + workName, pause) }
    }

    fun getBytesRead(context: Context, workName: String): Long {
        return getPrefs(context).getLong(KEY_BYTES_READ_PREFIX + workName, 0L)
    }
    // در DownloadPrefs.kt (اضافه یا اصلاح شود)
    fun getTotalBytesForWorker(context: Context, fullKey: String): Long {
        return getPrefs(context).getLong(fullKey, 0L)
    }

    fun setTotalBytesForWorker(context: Context, fullKey: String, bytes: Long) {
        getPrefs(context).edit { putLong(fullKey, bytes) }
    }


    fun setBytesRead(context: Context, workName: String, bytes: Long) {
        getPrefs(context).edit { putLong(KEY_BYTES_READ_PREFIX + workName, bytes) }
    }

    // تابع برای total bytes که در Worker استفاده می شود
    fun getTotalBytes(context: Context, workName: String): Long {
        return getPrefs(context).getLong(KEY_BYTES_READ_PREFIX + workName + KEY_TOTAL_BYTES_SUFFIX_FOR_PREFS, 0L)
    }

    fun setTotalBytes(context: Context, workName: String, bytes: Long) {
        getPrefs(context).edit { putLong(KEY_BYTES_READ_PREFIX + workName + KEY_TOTAL_BYTES_SUFFIX_FOR_PREFS, bytes) }
    }

    fun clearDownloadState(context: Context, workName: String) {
        getPrefs(context).edit {
            remove(KEY_SHOULD_PAUSE_PREFIX + workName)
            remove(KEY_BYTES_READ_PREFIX + workName)
            // remove(KEY_BYTES_READ_PREFIX + workName + KEY_TOTAL_BYTES_SUFFIX_FOR_PREFS) // این را هم اگر لازم است پاک کنید
            // remove(KEY_COMPLETION_MESSAGE_SHOWN_PREFIX + workName) // این را هم
        }
        // بهتر است total bytes و completion message جداگانه مدیریت شوند
    }

    // توابع جدید برای completion message
    fun setCompletionMessageShown(context: Context, workName: String, shown: Boolean) {
        getPrefs(context).edit { putBoolean(KEY_COMPLETION_MESSAGE_SHOWN_PREFIX + workName, shown) }
    }

    fun hasCompletionMessageBeenShown(context: Context, workName: String): Boolean {
        return getPrefs(context).getBoolean(KEY_COMPLETION_MESSAGE_SHOWN_PREFIX + workName, false)
    }

    fun resetCompletionMessageShown(context: Context, workName: String) {
        setCompletionMessageShown(context, workName, false)
    }
}
   