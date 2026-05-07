# BJTU MIS Android

This module is the native Android migration target for the existing local
`FastAPI + Vue` BJTU MIS collector.

The Android app does not run a local HTTP server or require a PC/cloud backend.
It talks directly to BJTU CAS/MIS, AA, and VE systems, stores encrypted cookies
locally, and keeps module snapshots in Room for offline reading.

Open `android/` with Android Studio, sync Gradle, then run the `app` target.
