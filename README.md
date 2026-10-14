# Watch Vitals (Android)

Connects to a Bluetooth LE watch and continuously shows heart rate and SpO2, using the
standard Bluetooth Heart Rate (0x180D) and Pulse Oximeter (0x1822) services. A foreground
service keeps the connection alive with the screen off and reconnects automatically.

## Get the APK without installing anything (GitHub Actions)
1. Create a new empty GitHub repository.
2. Upload every file from this folder (keep the `.github/workflows/build.yml` path).
3. Open the **Actions** tab, run **Build APK** (it also runs on every push).
4. When it finishes, download the **WatchVitals-debug-apk** artifact, unzip it, and install `app-debug.apk` on your phone (allow "install unknown apps").

## Or build in Android Studio
Open this folder, let it sync, then Build > Build Bundle(s) / APK(s) > Build APK(s).

## Notes
- Use the app's scan list to pick the watch. Disconnect it from other apps first.
- If the watch only uses a private protocol, the app will connect but show "no standard vitals
  service"; the log lists every service and characteristic it found.
- Not a medical device.
