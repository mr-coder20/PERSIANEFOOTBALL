package shin.a.pes

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.widget.TextView
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import shin.a.pes.data.BackgroundPreferences
import shin.a.pes.data.ThemePreferences
import shin.a.pes.ui.theme.AppNavHost
import shin.a.pes.ui.theme.DownloadDialog
import shin.a.pes.ui.theme.NavRoutes
import shin.a.pes.viewmodel.DownloadViewModel
import shin.a.pes.viewmodel.DownloadViewModelFactory

class MainActivity : ComponentActivity() {


    
    private val defaultDownloadUrl =
        "https://s27.uupload.ir/files/irangamepespsp/IranGame.zip"
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
                        onOpenRubika = { openDeveloperContact() },
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
    @SuppressLint("QueryPermissionsNeeded")
    private fun launchApp(context: Context) {
        val packageName = "ir.gtawire.pspp"
        val pm = context.packageManager

        try {
            // 1️⃣ تلاش برای پیدا کردن intent اصلی اپ
            var intent: Intent? = pm.getLaunchIntentForPackage(packageName)

            // 2️⃣ بررسی محدودیت Package Visibility در Android 11+
            if (intent == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(packageName)
                }
                val resolveInfo = pm.queryIntentActivities(mainIntent, 0)
                if (resolveInfo.isNotEmpty()) {
                    val info = resolveInfo[0].activityInfo
                    intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        setClassName(info.packageName, info.name)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                }
            }

            // 3️⃣ اگر اپ نصب بود → اجرا کن
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(intent)
                return
            }

            // 4️⃣ اپ نصب نیست → Toast نصب پلاگین
            showProgrammaticToast(context, "برای اجرای بازی، ابتدا شبیه‌ساز PSP را نصب کنید 🎮")

            // 5️⃣ تلاش برای باز کردن مایکت
            try {
                val bazaarIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("bazaar://details?id=$packageName")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

                if (bazaarIntent.resolveActivity(pm) != null) {
                    context.startActivity(bazaarIntent)
                    return
                }
            } catch (ignored: Exception) {}

            // 6️⃣ اگر مایکت باز نشد → مرورگر
            try {
                val webIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://cafebazaar.ir/app/$packageName")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

                if (webIntent.resolveActivity(pm) != null) {
                    context.startActivity(webIntent)
                } else {
                    // مرورگر هم نبود → Toast خطا
                    showProgrammaticToast(context, "مرورگر در دستگاه شما یافت نشد 🌐")
                }
            } catch (e: Exception) {
                showProgrammaticToast(context, "خطا در باز کردن لینک نصب شبیه‌ساز ❌")
                Log.e("MainActivity", "LaunchApp Error: ", e)
            }

        } catch (e: Exception) {
            showProgrammaticToast(context, "خطا در اجرای شبیه‌ساز eFootball ⚠️")
            Log.e("MainActivity", "LaunchApp Fatal Error: ", e)
        }
    }

    // ================================
// Toast حرفه‌ای بدون XML
// ================================
    private fun showProgrammaticToast(context: Context, message: String) {
        try {
            val density = context.resources.displayMetrics.density

            val textView = TextView(context).apply {
                text = message
                setTextColor(ColorStateList.valueOf(0xFFFFFFFF.toInt())) // سفید
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setTypeface(null, Typeface.BOLD)
                setPadding(
                    (24 * density).toInt(),
                    (16 * density).toInt(),
                    (24 * density).toInt(),
                    (16 * density).toInt()
                )

                background = GradientDrawable().apply {
                    cornerRadius = 16 * density
                    setColor(0xCC202020.toInt()) // نیمه شفاف
                }
            }

            val toast = Toast(context.applicationContext)
            toast.duration = Toast.LENGTH_LONG
            toast.view = textView
            toast.setGravity(
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                0,
                (120 * density).toInt()
            )
            toast.show()

        } catch (e: Exception) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }


    private fun openDeveloperContact() {
        val telegramId = "shina_ashin"

        try {
            // تلاش برای باز کردن مستقیم در اپلیکیشن تلگرام
            val telegramUri = Uri.parse("tg://resolve?domain=$telegramId")
            val intent = Intent(Intent.ACTION_VIEW, telegramUri)
            startActivity(intent)
        } catch (e: Exception) {
            // اگر تلگرام نصب نبود یا باز نشد، لینک را در مرورگر باز کن
            try {
                val webUri = Uri.parse("https://t.me/$telegramId")
                val webIntent = Intent(Intent.ACTION_VIEW, webUri)
                startActivity(webIntent)
            } catch (ex: Exception) {
                // اگر مرورگر هم باز نشد
                Toast.makeText(this, "برنامه‌ای برای باز کردن تلگرام یافت نشد.", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }


    // https://cafebazaar.ir/app/ir.gtawire.pspp

    private fun openInstallSimulator(context: Context) {
        val packageName = "ir.gtawire.pspp"
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            try {
                // اول تلاش می‌کند با دیپ لینک خود مایکت باز کند
                val bazaarIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("bazaar://details?id=$packageName")
                )
                context.startActivity(bazaarIntent)
            } catch (anfe: android.content.ActivityNotFoundException) {
                try {
                    // اگر مایکت نصب نبود، مرورگر را باز کن و به لینک مایکت ببر
                    val webIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://cafebazaar.ir/app/$packageName")
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