# 🏆 Persian eFootball Downloader

*اپلیکیشن مدیریت و دانلود دیتای بازی eFootball برای اندروید*

![Kotlin](https://img.shields.io/badge/Kotlin-1.8-blue) ![Android](https://img.shields.io/badge/Android-13+-green) ![WorkManager](https://img.shields.io/badge/WorkManager-Coroutines-orange) ![License](https://img.shields.io/badge/License-MIT-blue)

---

## 🌟 Overview | معرفی اپلیکیشن

**English:**
Persian eFootball Downloader is an Android app that enables downloading eFootball game data with resume support, automatic ZIP extraction, and progress notifications.

**فارسی:**
Persian eFootball Downloader یک اپلیکیشن اندرویدی است که امکان دانلود دیتای بازی eFootball با قابلیت ادامه دانلود، استخراج خودکار فایل ZIP و نمایش پیشرفت در نوتیفیکیشن‌ها را فراهم می‌کند.

---

## 🎯 Features | ویژگی‌ها

| Feature                      | Description (EN)                                                         | توضیح فارسی                                         |
| ---------------------------- | ------------------------------------------------------------------------ | --------------------------------------------------- |
| ⚡ Chunked Download           | Download files in multiple parts for faster downloads and resume support | دانلود چندتکه برای سرعت بالاتر و امکان ادامه دانلود |
| 🗜️ Automatic ZIP Extraction | Extracts ZIP files to device storage automatically                       | استخراج خودکار فایل‌های ZIP                         |
| 🔔 Foreground Notifications  | Shows download progress using notifications                              | نمایش پیشرفت دانلود با نوتیفیکیشن                   |
| ⏸️ Pause & Resume            | Pause or continue download anytime                                       | توقف و ادامه دانلود در هر زمان                      |
| 🔑 Dynamic Permissions       | Requests storage & notification permissions at runtime                   | مدیریت پویا دسترسی حافظه و نوتیفیکیشن               |
| 📱 Android Version Support   | Works on Android 6 (API 23) up to Android 13+ (API 36)                   | سازگار با نسخه‌های مختلف اندروید                    |

---

## 🛠️ Technologies | تکنولوژی‌ها و روش‌های مدرن

* **Kotlin & Coroutines** – مدیریت همزمانی دانلود چندتکه بدون بلاک کردن UI
* **WorkManager + CoroutineWorker** – اجرای امن دانلود در پس‌زمینه با امکان Resume و مدیریت Lifecycle
* **OkHttp** – انجام درخواست‌های HTTP با قابلیت Range و Resume
* **Jetpack Compose** – رابط کاربری مدرن و واکنش‌گرا
* **ViewModel + StateFlow** – مدیریت حالت دانلود به صورت reactive
* **DataStore (Preferences)** – ذخیره پایدار وضعیت دانلود و پیشرفت
* **Foreground Service** – نمایش نوتیفیکیشن پیشرفت دانلود حتی هنگام بسته بودن اپ
* **Scoped Storage & MANAGE_EXTERNAL_STORAGE** – مدیریت امن فایل‌ها در همه نسخه‌های اندروید
* **Zip Extraction with Security Checks** – جلوگیری از حمله‌های Zip Slip

---

## 📲 Screenshots | تصاویر اپلیکیشن

<p float="left">
  <img src="https://user-images.githubusercontent.com/0000000/your-screenshot1.png" width="250" />
  <img src="https://user-images.githubusercontent.com/0000000/your-screenshot2.png" width="250" />
  <img src="https://user-images.githubusercontent.com/0000000/your-screenshot3.png" width="250" />
</p>

---

## ⚙️ How to Use | نحوه استفاده

1. نصب اپلیکیشن و اجرا
2. اجازه دسترسی حافظه و نوتیفیکیشن را بدهید
3. شروع دانلود دیتای بازی
4. دانلود را می‌توانید متوقف، ادامه دهید یا پس از اتمام، بازی را اجرا کنید

**Pro Tip:** دانلود چندتکه باعث می‌شود فایل‌ها سریع‌تر و با قابلیت Resume دانلود شوند، حتی اگر اتصال اینترنت قطع شود.

---

## 📦 Installation | نصب

Clone the repository:

```bash
git clone https://github.com/mr-coder20/PERSIANEFOOTBALL.git
```

Open in Android Studio and run the app on your device (Android 6+ recommended).

---

## 💡 Notes | نکات مهم

* اپلیکیشن از Foreground Service استفاده می‌کند تا دانلود حتی در پس‌زمینه ادامه یابد.
* اگر اندروید 11+ دارید، نیاز به دسترسی کامل به فایل‌ها (MANAGE_EXTERNAL_STORAGE) است.
* نوتیفیکیشن پیشرفت دانلود برای API 33+ نیاز به دسترسی POST_NOTIFICATIONS دارد.

---

## 📜 License | مجوز

This project is licensed under the **MIT License** – see the [LICENSE](LICENSE) file for details.

---

## 📬 Contact | تماس با من


* GitHub: [yourusername](https://github.com/mr-coder20)

---

✨ Made with ❤️ and Jetpack Compose
