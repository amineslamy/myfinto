# MyFinto — Time Tracking / ثبت کارکرد

این مخزن شامل یک اپلیکیشن اندروید برای ثبت ساعت کارکرد، محاسبه حقوق بر اساس ساعت موظفی و خروجی گزارش (با پشتیبانی از تقویم جلالی) است. اپ با Jetpack Compose نوشته شده و داده‌ها در دیتابیس محلی نگهداری می‌شوند.

---

## درباره (فارسی)

- **نام:** ثبت کارکرد (MyFinto)
- **توضیح:** برنامه‌ای برای ثبت و مدیریت ساعت‌های کاری، محاسبه حقوق و تولید گزارش.
- **نسخه APK:** فایل `myfinto.apk` در ریشهٔ این مخزن قرار دارد و برای نصب مستقیم روی دستگاه آماده است.

### پیش‌نیازها

- JDK 11 یا جدیدتر
- اگر می‌خواهید محلی بیلد کنید بدون Android Studio: Android SDK command-line tools (sdkmanager, platform-tools)

### اجرای محلی (فارسی)

1. از ریشهٔ پروژه این دستور را اجرا کنید:

```bash
./gradlew assembleDebug
```

2. یا پروژه را در Android Studio باز کنید (`File → Open`) و اجرا کنید.
3. برای نصب APK روی دستگاه:

```bash
adb install -r myfinto.apk
```

### نکات مربوط به امضا و بیلد

- پروژه از امضای debug پیش‌فرض استفاده می‌کند؛ اگر برای release نیاز به keystore دارید، مقادیر لازم را در `app/build.gradle.kts` و Secrets در GitHub تنظیم کنید.
- اگر هنگام بیلد محلی با خطاهای مربوط به keystore مواجه شدید، بخش `signingConfigs` در `app/build.gradle.kts` را بررسی کنید.

### CI / GitHub Actions

- این مخزن شامل یک workflow در مسیر `.github/workflows/build.yml` است که APK را می‌سازد و آن را به عنوان artifact آپلود می‌کند.
- برای دانلود APK بدون اجرا کردن workflow، فایل `myfinto.apk` در ریپو قرار گرفته است.

---

## About (English)

- **Name:** MyFinto — Time Tracking
- **Description:** An Android app to log working hours, calculate pay using the Jalali calendar, and export reports. Built with Jetpack Compose and local persistence.
- **APK:** The built APK `myfinto.apk` is included at the repository root for convenience.

### Requirements

- JDK 11 or newer
- Android SDK command-line tools (if building locally without Android Studio)

### Run locally

1. From project root run:

```bash
./gradlew assembleDebug
```

2. Or open the project in Android Studio (`File → Open`) and run on an emulator or device.

3. To install the APK on a device:

```bash
adb install -r myfinto.apk
```

### Signing & build notes

- The project uses default debug signing for debug builds. For a release build, provide a keystore and configure `app/build.gradle.kts` accordingly and store secrets in GitHub.
- If you see keystore-related errors when building locally, check the `signingConfigs` section in `app/build.gradle.kts`.

### CI / GitHub Actions

- See `.github/workflows/build.yml` for the workflow that builds the APK and uploads artifacts.
- The repository already contains the `myfinto.apk` file for direct download if you prefer not to use Actions.

---

If you want, I can:

- Create a GitHub Release and attach `myfinto.apk` to it, and add release notes.
- Move the APK to a `releases/` folder and add checksums in this README.

Which option do you prefer?