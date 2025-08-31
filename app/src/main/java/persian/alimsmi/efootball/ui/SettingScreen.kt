package persian.alimsmi.efootball.ui

import android.Manifest
// import android.app.Activity // دیگر نیازی نیست اگر مستقیم استفاده نشود
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import persian.alimsmi.efootball.R
import java.io.File // اضافه کردن این
import java.io.FileOutputStream // اضافه کردن این
import java.io.IOException // اضافه کردن این

// تابع کپی کردن فایل از assets به مسیر مشخص شده
private fun copyAssetToFile(context: Context, settingId: String, showToast: Boolean = true) {
    val assetManager = context.assets
    val sourceAssetPath = "$settingId/EBOOT.OLD" // مسیر فایل در پوشه assets

    // مسیر مقصد
    val baseDir = Environment.getExternalStorageDirectory() // توجه به منسوخ شدن در API های جدید
    val targetDirPath = File(baseDir, "PSP/PSP_GAME/SYSDIR")
    val targetFile = File(targetDirPath, "EBOOT.OLD") // همیشه با همین نام ذخیره می شود

    try {
        if (!targetDirPath.exists()) {
            if (!targetDirPath.mkdirs()) {
                if (showToast) Toast.makeText(context, "خطا در ایجاد مسیر: ${targetDirPath.absolutePath}", Toast.LENGTH_LONG).show()
                return
            }
        }

        assetManager.open(sourceAssetPath).use { inputStream ->
            FileOutputStream(targetFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }

    } catch (e: IOException) {
        e.printStackTrace()
        var errorMessage = "خطا در کپی کردن فایل: ${e.localizedMessage}"
        if (e is java.io.FileNotFoundException && e.message?.contains(sourceAssetPath, ignoreCase = true) == true) {
            errorMessage = "خطا: فایل $sourceAssetPath در assets پیدا نشد."
        } else if (e.message?.contains("Permission denied", ignoreCase = true) == true) {
            errorMessage = "خطا: دسترسی برای نوشتن در مسیر ${targetFile.absolutePath} وجود ندارد."
        }
        if (showToast) Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        e.printStackTrace()
        if (showToast) Toast.makeText(context, "خطای ناشناخته: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingScreen(
    // onChoose: (String) -> Unit, // این دیگر ممکن است لازم نباشد اگر عملیات همینجا انجام شود
    // یا اینکه پس از کپی فایل، همچنان یک id را به بیرون پاس دهیم
    onSettingApplied: (String) -> Unit, // یک کال بک جدید برای اطلاع از اعمال تنظیمات
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var initialPermissionCheckDone by remember { mutableStateOf(false) }
    var hasPermissions by remember { mutableStateOf(false) }

    val images = listOf(
        "1" to R.drawable.a1,
        "2" to R.drawable.b2,
        "3" to R.drawable.c3,
        "4" to R.drawable.d4
    )

    val checkCurrentPermissions = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager() // برای اندروید 11+ فقط این را چک می کنیم چون MANAGE_EXTERNAL_STORAGE را می خواهیم
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    SideEffect {
        if (!initialPermissionCheckDone) {
            hasPermissions = checkCurrentPermissions()
            // اگر دسترسی از قبل وجود داشت و بررسی اولیه کامل شد، initialPermissionCheckDone را true می کنیم
            // تا LaunchedEffect بی دلیل اجرا نشود اگر کاربر به صفحه برگردد و دسترسی هنوز باشد.
            if (hasPermissions) {
                initialPermissionCheckDone = true
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        initialPermissionCheckDone = true
        val allGranted = result.values.all { it }
        if (!allGranted) {
            Toast.makeText(context, "دسترسی برای انتخاب تنظیمات لازم است. به صفحه اصلی بازگشت.", Toast.LENGTH_LONG).show()
            onBack()
        } else {
            hasPermissions = true
            // نیازی به Snackbar نیست، کاربر می تواند مستقیما انتخاب کند
        }
    }

    val manageFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        initialPermissionCheckDone = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                hasPermissions = true
            } else {
                Toast.makeText(context, "دسترسی کامل به فایل‌ها برای انتخاب تنظیمات لازم است. به صفحه اصلی بازگشت.", Toast.LENGTH_LONG).show()
                onBack()
            }
        }
    }

    LaunchedEffect(key1 = hasPermissions, key2 = initialPermissionCheckDone) {
        // این LaunchedEffect فقط زمانی اجرا می شود که hasPermissions تغییر کند
        // یا initialPermissionCheckDone تغییر کند (که فقط یکبار از false به true می رود).
        // هدف این است که فقط یک بار در ابتدا یا اگر دسترسی ها از دست رفتند، درخواست کنیم.
        if (!hasPermissions && !initialPermissionCheckDone) { // فقط اگر دسترسی نداریم و بررسی اولیه هنوز انجام نشده
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    Toast.makeText(context, "برای ادامه، دسترسی کامل به فایل‌ها نیاز است.", Toast.LENGTH_SHORT).show()
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:${context.packageName}")
                    manageFilesLauncher.launch(intent)
                } else {
                    // این حالت نباید رخ دهد اگر hasPermissions از قبل false بوده
                    // اما برای اطمینان، وضعیت را به روز می کنیم
                    hasPermissions = true
                    initialPermissionCheckDone = true
                }
            } else {
                val permissionsArray = arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                val notGrantedPermissions = permissionsArray.filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                if (notGrantedPermissions.isNotEmpty()) {
                    permissionLauncher.launch(notGrantedPermissions.toTypedArray())
                } else {
                    // این حالت نباید رخ دهد اگر hasPermissions از قبل false بوده
                    hasPermissions = true
                    initialPermissionCheckDone = true
                }
            }
        } else if (hasPermissions && !initialPermissionCheckDone) {
            // اگر دسترسی داریم ولی بررسی اولیه هنوز کامل نشده (مثلا در اولین ورود)، آن را کامل می کنیم
            initialPermissionCheckDone = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("تنظیمات", color = Color.Black) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_back),
                            contentDescription = "بازگشت",
                            tint = Color.Black
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White,
                    titleContentColor = Color.Black
                ),
                modifier = Modifier.statusBarsPadding()
            )
        },
        containerColor = Color.White,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = Color(0xFF323232),
                    contentColor = Color.White
                )
            }
        }
    ) { paddingValues ->
        if (hasPermissions) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                items(images) { (id, resId) ->
                    Image(
                        painter = painterResource(id = resId),
                        contentDescription = "گزینه $id",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                            .clickable {
                                // وقتی روی عکس کلیک می شود:
                                // ۱. فایل مربوطه را کپی کن
                                copyAssetToFile(context, id, showToast = true)
                                // ۲. به вызывающий کد اطلاع بده که تنظیمات اعمال شد (اختیاری)
                                onSettingApplied(id)
                                // ۳. (اختیاری) به صفحه قبلی بازگرد
                                // onBack()
                            }
                            .background(Color.LightGray, RoundedCornerShape(16.dp))
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                if (initialPermissionCheckDone) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("برای مشاهده و انتخاب تنظیمات، نیاز به دسترسی فایل‌ها است.")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            initialPermissionCheckDone = false // اجازه می دهد LaunchedEffect دوباره اجرا شود
                            hasPermissions = false
                        }) {
                            Text("تلاش مجدد برای دسترسی")
                        }
                    }
                } else {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
