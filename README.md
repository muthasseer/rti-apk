# AB Securitas Real Time Inspector — Android V13.1

- Device ID is generated automatically from Android Settings.Secure.ANDROID_ID and is not editable.
- The inspector logs in with User ID + Password; the server binds the Device ID on first login. One phone can be shared by several inspectors of the same company (use **Switch Inspector**).
- **Start Inspection** creates a server-side session and opens the camera with a scan box.
- Total / Scanned / Pending points are shown, with the point names on the home screen.
- First scan = ACCEPTED, repeats 1–2 = REPEAT, third repeat = REPEAT_ALERT (beep + blocking popup, not stored).
- Inspection ends automatically when every active point is accepted, or manually with **END INSPECTION**.

Server: set in `app/src/main/java/com/absecuritas/realtimeinspector/AppConfig.kt`.
Build: GitHub Actions workflow `.github/workflows/build-apk.yml` (Java 17, Gradle 9.4.1, AGP 9.2.0).
