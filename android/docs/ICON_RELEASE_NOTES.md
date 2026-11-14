# AstroCare 1.2-standalone — icon release

This release packages an original space-and-health icon: a white heart and cyan ECG pulse with an orbital ring and star, on deep navy. The colors and concept match AstroCare's astronaut-health theme. Adaptive launcher resources and all five legacy Android launcher densities are included.

The deliverable is one complete signed APK, versionCode 3, versionName `1.2-standalone`, package `com.yasmin.astrocare`. It is not an update patch or split APK. No earlier AstroCare version is required for a fresh installation. Minimum Android API is 26.

The backend has not been changed. Emulator testing was stopped at the user's request. The reported Android 14 fresh-install startup crash has not been reproduced or confirmed fixed. Do not interpret the new icon or successful APK assembly as a verified crash fix.

A new Robolectric launcher construction/recreation test passed. An exploratory real-activity navigation test timed out waiting for the synthetic seed banner; its source is retained as a diagnostic and may still fail when the full unit test suite is run. The previously reported 213 passing tests belong to the preceding 1.1 release, not a new full-suite certification of this icon release.

Build an APK with `./gradlew assembleDebug`. Create your own machine-specific `local.properties` SDK path before building the source project. Generated build/cache directories and machine-specific SDK settings are excluded from the delivered source archive.
