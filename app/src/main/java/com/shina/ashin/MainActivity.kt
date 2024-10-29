package com.shina.ashin

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.shina.ashin.SettingFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private val REQUEST_CODE_STORAGE_PERMISSION = 1001
    private var isDownloading = false // متغیر برای کنترل وضعیت دانلود

    private fun showDownloadDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.download_dialog)


        // دکمه شروع دانلود
        val startDownloadButton = dialog.findViewById<View>(R.id.start_download)
        startDownloadButton.setOnClickListener {
            isDownloading = true // وضعیت دانلود را فعال کنید
            dialog.dismiss() // دیالوگ را ببندید
            downloadLargeFile(
                "https://s5.uupload.ir/files/irangamepespsp/Patch.zip",
                "Patch.zip"
            )
        }

        // دکمه خروج
        val exitButton =
            dialog.findViewById<View>(R.id.exit_button) // فرض کنید این ID برای دکمه خروج است
        exitButton.setOnClickListener {
            isDownloading = false // وضعیت دانلود را غیرفعال کنید
            dialog.dismiss() // دیالوگ را ببندید
        }

        dialog.setCancelable(false)
        dialog.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
//        window.navigationBarColor = resources.getColor(R.color.md_green_100)


        val info = findViewById<Button>(R.id.info)
        val install_Simulator = findViewById<Button>(R.id.install_Simulator)
        val setting = findViewById<Button>(R.id.setting)
        val start_game = findViewById<Button>(R.id.start_game)
        val tell_programer = findViewById<Button>(R.id.tell_programer)

        tell_programer.setOnClickListener {
            try {
                val channelUrl = "https://t.me/a_god_3_6_9"
                val openChannelIntent = Intent(Intent.ACTION_VIEW, Uri.parse(channelUrl))
                openChannelIntent.setPackage("org.telegram.messenger")
                startActivity(openChannelIntent)
            } catch (_: Exception) {
                Toast.makeText(this, "تلگرام ندارید", Toast.LENGTH_SHORT).show()
            }

        }

        info.setOnClickListener {
            val infoFragment = InfoFragment()
            val fragmentManager = supportFragmentManager
            val fragmentTransaction = fragmentManager.beginTransaction()
            fragmentTransaction.add(R.id.main, infoFragment)
            fragmentTransaction.addToBackStack(null)
            fragmentTransaction.commit()
        }
        install_Simulator.setOnClickListener {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://myket.ir/app/com.Dategamer.pspplugin")
            )
            startActivity(intent)
        }
        setting.setOnClickListener {
            val settingFragment = SettingFragment()
            val fragmentManager = supportFragmentManager
            val fragmentTransaction = fragmentManager.beginTransaction()
            fragmentTransaction.add(R.id.main, settingFragment)
            fragmentTransaction.addToBackStack(null)
            fragmentTransaction.commit()
        }
        start_game.setOnClickListener {
            launchApp(this)

        }
        // دکمه شروع دانلود
        val startDownloadButton = findViewById<View>(R.id.download_data)
        startDownloadButton.setOnClickListener {
            showDownloadDialog()
        }

    }



    private fun downloadLargeFile(url: String, fileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                requestStoragePermission()
                return
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_CODE_STORAGE_PERMISSION
                )
                return
            }
        }

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.download_dialog) // از layout دیالوگ دانلود استفاده کنید
        val progressBar = dialog.findViewById<ProgressBar>(R.id.progress_bar)
        val textView = dialog.findViewById<TextView>(R.id.text_view)
        val startDownloadButton = dialog.findViewById<View>(R.id.start_download)
        startDownloadButton.visibility = View.INVISIBLE
        val exitButton =
            dialog.findViewById<View>(R.id.exit_button) // فرض کنید این ID برای دکمه خروج است
        exitButton.setOnClickListener {
            isDownloading = false // وضعیت دانلود را غیرفعال کنید
            dialog.dismiss() // دیالوگ را ببندید
        }
        dialog.setCancelable(false)
        dialog.show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) throw IOException("Unexpected code $response")

                val outputFile = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    fileName
                )
                val outputStream = FileOutputStream(outputFile)

                val totalBytes = response.body!!.contentLength()
                var bytesRead: Long = 0

                response.body!!.byteStream().use { inputStream ->
                    val buffer = ByteArray(4096)
                    var bytes: Int
                    while (inputStream.read(buffer).also { bytes = it } != -1) {
                        outputStream.write(buffer, 0, bytes)
                        bytesRead += bytes
                        val progress = (bytesRead * 100) / totalBytes
                        withContext(Dispatchers.Main) {
                            progressBar.progress = progress.toInt()
                            textView.text = "در حال دانلود... $progress%"
                        }
                    }
                }

                outputStream.close()

                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    Toast.makeText(
                        this@MainActivity,
                        "دانلود دیتا با موفقیت انجام شد",
                        Toast.LENGTH_SHORT
                    ).show()
                    extractZipFileWithProgress(
                        outputFile.absolutePath,
                        "${Environment.getExternalStorageDirectory()}"
                    )
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    Toast.makeText(
                        this@MainActivity,
                        "خطا در دانلود: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun extractZipFileWithProgress(zipFilePath: String, outputDir: String) {
        // نمایش دیالوگ برای استخراج
        val extractDialog = Dialog(this)
        extractDialog.setContentView(R.layout.extract_dialog) // از layout مشابه دیالوگ دانلود استفاده کنید
        val progressBar = extractDialog.findViewById<ProgressBar>(R.id.progress_bar)
        val textView = extractDialog.findViewById<TextView>(R.id.text_view)
        extractDialog.setCancelable(false)
        extractDialog.show()

        CoroutineScope(Dispatchers.IO).launch {
            val zipFile = File(zipFilePath)

            if (!zipFile.exists()) {
                withContext(Dispatchers.Main) {
                    extractDialog.dismiss()
                    Toast.makeText(
                        this@MainActivity,
                        "برای نصب، ابتدا باید فایل زیپ دانلود شود",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@withContext
                }
            }

            try {
                val extractDir = File(outputDir)
                if (!extractDir.exists()) {
                    extractDir.mkdirs()
                }

                var zipInputStream = ZipInputStream(FileInputStream(zipFile))
                var entry: ZipEntry?
                var totalEntries = 0
                var extractedEntries = 0

                // محاسبه تعداد کل ورودی‌ها
                while (zipInputStream.nextEntry.also { entry = it } != null) {
                    totalEntries++
                }

                zipInputStream.close() // بستن ورودی ZIP

                zipInputStream =
                    ZipInputStream(FileInputStream(zipFile)) // باز کردن دوباره برای استخراج

                while (zipInputStream.nextEntry.also { entry = it } != null) {
                    val newFile = File(extractDir, entry!!.name)

                    if (entry!!.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        newFile.parentFile?.mkdirs()
                        FileOutputStream(newFile).use { output ->
                            zipInputStream.copyTo(output)
                        }
                    }
                    zipInputStream.closeEntry()

                    extractedEntries++
                    val progress = (extractedEntries * 100) / totalEntries
                    withContext(Dispatchers.Main) {
                        progressBar.progress = progress
                        textView.text = "در حال استخراج... $progress%"
                    }
                }

                withContext(Dispatchers.Main) {
                    extractDialog.dismiss()
                    Toast.makeText(
                        this@MainActivity,
                        "دیتا با موفقیت استخراج شد",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    extractDialog.dismiss()
                    Toast.makeText(
                        this@MainActivity,
                        "خطا در استخراج: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // برای اندروید 11 و بالاتر، به صفحه تنظیمات هدایت کنید
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.addCategory("android.intent.category.DEFAULT")
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_CODE_STORAGE_PERMISSION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_STORAGE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // مجوز داده شده است
                downloadLargeFile(
                    "https://s5.uupload.ir/files/irangamepespsp/Patch.zip",
                    "Patch.zip"
                )
            } else {
                Toast.makeText(this, "مجوز دسترسی به حافظه داده نشده است", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }
    fun launchApp(context: Context) {
        val packageName = "com.Dategamer.pspplugin"
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)

        // Fix for Android 13 and later
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            intent?.addCategory(Intent.CATEGORY_LAUNCHER)
        }

        if (intent == null) {
            // If the app is not installed, redirect to the Play Store
            val marketIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("market://details?id=$packageName")
            }
            context.startActivity(marketIntent)
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}