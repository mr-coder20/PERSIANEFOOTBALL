package psp.fazli.efootball

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import psp.fazli.efootball.data.BackgroundPreferences
import psp.fazli.efootball.data.ThemePreferences
import psp.fazli.efootball.ui.theme.AppNavHost
import psp.fazli.efootball.ui.theme.DownloadDialog
import psp.fazli.efootball.ui.theme.NavRoutes
import psp.fazli.efootball.viewmodel.DownloadViewModel
import psp.fazli.efootball.viewmodel.DownloadViewModelFactory

class MainActivity : ComponentActivity() {

    private val defaultDownloadUrl =
        "https://s27.uupload.ir/files/irangamepespsp/IranEdit.zip"  //"https://www.learningcontainer.com/wp-content/uploads/2020/05/sample-large-zip-file.zip"  // "https://s27.uupload.ir/files/irangamepespsp/IranEdit.zip"
    private val defaultZipFileName = "PSP.zip"

    private val themePreferences by lazy { ThemePreferences(this) }
    private val backgroundPreferences by lazy { BackgroundPreferences(this) }

    private var onAllPermissionsGrantedAction: (() -> Unit)? = null // تغییر نام برای وضوح
    private lateinit var navController: NavHostController

    private val downloadViewModel: DownloadViewModel by viewModels {
        DownloadViewModelFactory(application)
    }


    // لانچر برای دسترسی‌های حافظه (چند دسترسی)
    private val requestStoragePermissionsLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allStorageGranted = permissions.entries.all { it.value }
            if (allStorageGranted) {
                // پس از دسترسی حافظه، حالا دسترسی نوتیفیکیشن را بررسی/درخواست کن
                requestNotificationPermissionIfNeeded()
            } else {
                Toast.makeText(this, "دسترسی به فایل‌ها برای ادامه لازم است.", Toast.LENGTH_LONG)
                    .show()
                onAllPermissionsGrantedAction = null // ریست اکشن چون یک دسترسی رد شد
            }
        }

    // لانچر برای دسترسی مدیریت تمام فایل‌ها (MANAGE_EXTERNAL_STORAGE)
    private val manageStoragePermissionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    // پس از دسترسی مدیریت فایل، حالا دسترسی نوتیفیکیشن را بررسی/درخواست کن
                    requestNotificationPermissionIfNeeded()
                } else {
                    Toast.makeText(this, "دسترسی کامل به فایل‌ها اعطا نشد.", Toast.LENGTH_LONG)
                        .show()
                    onAllPermissionsGrantedAction = null // ریست اکشن
                }
            }
        }

    // --- جدید: لانچر برای دسترسی نوتیفیکیشن (یک دسترسی) ---
    private val requestNotificationPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("MainActivity", "Notification permission granted.")
                onAllPermissionsGrantedAction?.invoke() // تمام دسترسی‌ها (حافظه و نوتیفیکیشن) داده شده‌اند
            } else {
                Toast.makeText(
                    this,
                    "دسترسی به نوتیفیکیشن برای نمایش پیشرفت دانلود لازم است.",
                    Toast.LENGTH_LONG
                ).show()
                // حتی اگر نوتیفیکیشن رد شود، شاید بخواهید دانلود ادامه یابد (ولی بدون نوتیفیکیشن)
                // یا اینکه کاربر را مجبور به دادن دسترسی کنید. فعلا اجازه ادامه می‌دهیم.
                // اگر می‌خواهید دانلود را متوقف کنید، اینجا onAllPermissionsGrantedAction را null کنید یا UI مناسبی نشان دهید.
                onAllPermissionsGrantedAction?.invoke() // <<-- تصمیم با شما: آیا بدون نوتیفیکیشن هم دانلود شروع شود؟
                // اگر نه، این خط را حذف کنید و onAllPermissionsGrantedAction را null کنید.
            }
            if (!isGranted) { // فقط اگر رد شده، null کن که دوباره فراخوانی نشود مگر با درخواست جدید
                onAllPermissionsGrantedAction = null
            }
        }
    // ---------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialDarkMode = runBlocking { themePreferences.isDarkMode.first() }
        val initialBackground = runBlocking { backgroundPreferences.backgroundChoice.first() }

        setContent {
            var isDarkMode by remember { mutableStateOf(initialDarkMode) }
            var backgroundChoice by remember { mutableStateOf(initialBackground) }
            navController = rememberNavController()

            val downloadUiState by downloadViewModel.downloadUiState

            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    AppNavHost(
                        navController = navController,
                        onDownloadRequest = {
                            // --- تغییر اینجا: ابتدا تمام دسترسی‌های لازم را درخواست کن ---
                            requestAllPermissionsIfNeeded {
                                downloadViewModel.startDownload(
                                    defaultDownloadUrl,
                                    defaultZipFileName
                                )
                            }
                        },
                        onStartGame = { launchApp(this@MainActivity) },
                        onOpenSettingsPage = {
                            // برای باز کردن صفحه تنظیمات هم ممکن است به دسترسی حافظه نیاز باشد
                            requestStoragePermissionsIfNeededOnly { // یک تابع جدید برای این منظور
                                navController.navigate(NavRoutes.SETTINGS)
                            }
                        },
                        // ... بقیه پارامترهای AppNavHost ...
                        onSettingsChosen = { settingId ->
                            lifecycleScope.launch {
                                Toast.makeText(
                                    this@MainActivity,
                                    "تنظیمات $settingId اعمال شد.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                navController.popBackStack(NavRoutes.MAIN, inclusive = false)
                            }
                        },
                        onOpenRubika = { openRubika() },
                        onInstallSimulator = { openInstallSimulator(this@MainActivity) },
                        isDarkMode = isDarkMode,
                        onToggleDarkMode = {
                            isDarkMode = !isDarkMode
                            lifecycleScope.launch { themePreferences.setDarkMode(isDarkMode) }
                            backgroundChoice =
                                if (backgroundChoice == "bg2") "bg1" else "bg2" // ساده شده
                            lifecycleScope.launch {
                                backgroundPreferences.setBackground(
                                    backgroundChoice
                                )
                            }
                        },
                        backgroundChoice = backgroundChoice
                    )

                    DownloadDialog(
                        uiState = downloadUiState,
                        onDismissRequestClose = { downloadViewModel.dismissRequestClose() },
                        onDismissDialogAndContinueInBackground = { downloadViewModel.dismissDialogAndContinueInBackground() },
                        onCancelDownload = { downloadViewModel.cancelDownload() },
                        onRetryFailedDownload = {
                            val urlToRetry = downloadUiState.downloadUrl ?: defaultDownloadUrl
                            val fileToRetry = downloadUiState.outputFileName ?: defaultZipFileName
                            // --- تغییر اینجا: برای تلاش مجدد هم دسترسی‌ها را بررسی کن ---
                            requestAllPermissionsIfNeeded {
                                downloadViewModel.startDownload(urlToRetry, fileToRetry)
                            }
                        }
                    )
                }
            }
        }
    }

    // --- جدید: تابعی که ابتدا دسترسی حافظه و سپس نوتیفیکیشن را درخواست می‌کند ---
    private fun requestAllPermissionsIfNeeded(onGranted: () -> Unit) {
        this.onAllPermissionsGrantedAction = onGranted
        requestStoragePermissionsIfNeededOnly() // شروع با دسترسی حافظه
    }
    // -----------------------------------------------------------------------

    // تابع اصلی درخواست دسترسی حافظه (بدون تغییر زیاد، فقط فراخوانی requestNotificationPermissionIfNeeded در صورت موفقیت)
    private fun requestStoragePermissionsIfNeededOnly(onStorageGrantedCustomAction: (() -> Unit)? = null) {
        val actionAfterStorage =
            onStorageGrantedCustomAction ?: { requestNotificationPermissionIfNeeded() }

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> { // Android 11+ (API 30+)
                if (Environment.isExternalStorageManager()) {
                    actionAfterStorage() // دسترسی مدیریت فایل از قبل وجود دارد
                } else {
                    Toast.makeText(
                        this,
                        "برای ادامه، دسترسی کامل به فایل‌ها نیاز است. لطفاً از صفحه بعد دسترسی را فعال کنید.",
                        Toast.LENGTH_LONG
                    ).show()
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = Uri.parse("package:${applicationContext.packageName}")
                        manageStoragePermissionLauncher.launch(intent) // نتیجه در لانچر مدیریت می‌شود
                    } catch (e: Exception) {
                        try {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            intent.data = Uri.parse("package:${applicationContext.packageName}")
                            manageStoragePermissionLauncher.launch(intent)
                        } catch (ex: Exception) {
                            Toast.makeText(
                                this,
                                "خطا در باز کردن صفحه دسترسی. لطفاً دسترسی به فایل‌ها را از تنظیمات اپلیکیشن فعال کنید.",
                                Toast.LENGTH_LONG
                            ).show()
                            onAllPermissionsGrantedAction = null // ریست اکشن چون خطا رخ داد
                        }
                    }
                }
            }
            // برای Android 10 (API 29) و پایین‌تر (READ/WRITE_EXTERNAL_STORAGE)
            // توجه: WRITE_EXTERNAL_STORAGE تا maxSdkVersion="29" در مانیفست است.
            // READ_EXTERNAL_STORAGE تا maxSdkVersion="32" در مانیفست است.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> { // Android 6+ (API 23+)
                val permissionsToRequest = mutableListOf<String>()
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    if (Build.VERSION.SDK_INT <= 32) { // فقط تا API 32 لازم است
                        permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                }
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    if (Build.VERSION.SDK_INT <= 29) { // فقط تا API 29 لازم است
                        permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }

                if (permissionsToRequest.isNotEmpty()) {
                    requestStoragePermissionsLauncher.launch(permissionsToRequest.toTypedArray()) // نتیجه در لانچر مدیریت می‌شود
                } else {
                    actionAfterStorage() // دسترسی‌های حافظه از قبل وجود دارند
                }
            }

            else -> { // پایین‌تر از Android 6 (API 23)
                actionAfterStorage() // دسترسی‌ها در زمان نصب داده شده‌اند
            }
        }
    }

    // --- جدید: تابع درخواست دسترسی نوتیفیکیشن ---
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+ (API 33+)
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d("MainActivity", "Notification permission already granted.")
                    onAllPermissionsGrantedAction?.invoke() // اجرای اکشن نهایی
                    onAllPermissionsGrantedAction = null // ریست اکشن
                }

                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // TODO: نمایش UI توضیحی به کاربر که چرا این دسترسی لازم است
                    Log.i("MainActivity", "Showing rationale for notification permission.")
                    // پس از نمایش UI، دوباره درخواست کنید:
                    Toast.makeText(
                        this,
                        "برای نمایش پیشرفت دانلود، نیاز به دسترسی نوتیفیکیشن است.",
                        Toast.LENGTH_LONG
                    ).show()
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                else -> {
                    Log.d("MainActivity", "Requesting notification permission.")
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            // برای APIهای پایین‌تر از 33، نیازی به این دسترسی نیست
            Log.d("MainActivity", "Notification permission not required for this API level.")
            onAllPermissionsGrantedAction?.invoke() // اجرای اکشن نهایی
            onAllPermissionsGrantedAction = null // ریست اکشن
        }
    }
    // ---------------------------------------------------

    // ... (بقیه توابع MainActivity بدون تغییر: launchApp, openRubika, openInstallSimulator)
    private fun launchApp(context: Context) {
        val packageName = "com.parian.pspplugin"
        val activityName = "com.parian.pspplugin.MainActivity" // در صورت تفاوت، اصلاح کن

        try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else {
                // fallback برای Android 14+ که getLaunchIntent ممکن است null بدهد
                val directIntent = Intent()
                directIntent.setClassName(packageName, activityName)
                directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(directIntent)
            }
        } catch (e: Exception) {
            try {
                val myketIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("myket://details?id=$packageName")
                )
                context.startActivity(myketIntent)
            } catch (e2: Exception) {
                val webIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://myket.ir/app/$packageName")
                )
                context.startActivity(webIntent)
            }
        }
    }





    @SuppressLint("UseKtx")
    private fun openRubika() {
        val channelId = "@iran__editt" // آی‌دی کانال یا کاربر مورد نظر
        val rubikaUri = Uri.parse("rubika://channel/$channelId") // دیپ لینک واقعی روبیکا

        val intent = Intent(Intent.ACTION_VIEW, rubikaUri)
        // بررسی اینکه آیا اپ نصب است
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            // اگر نصب نبود، لینک وب را باز کن
            try {
                val webIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://rubika.ir/$channelId")
                )
                startActivity(webIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "روبیکا نصب نیست و لینک وب هم باز نشد.", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }



    // https://myket.ir/app/com.parian.pspplugin

    private fun openInstallSimulator(context: Context) {
        val packageName = "com.parian.pspplugin"
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            try {
                // اول تلاش می‌کند با دیپ لینک خود مایکت باز کند
                val myketIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("myket://details?id=$packageName")
                )
                context.startActivity(myketIntent)
            } catch (anfe: android.content.ActivityNotFoundException) {
                try {
                    // اگر مایکت نصب نبود، مرورگر را باز کن و به لینک مایکت ببر
                    val webIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://myket.ir/app/$packageName")
                    )
                    context.startActivity(webIntent)
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        "نصب شبیه‌ساز eFootball2026 از مایکت ممکن نشد.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }


}