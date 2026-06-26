# Changelog

## V1.0 Release Candidate

### Backend Fixes
* **Heartbeat Timeout Fix**: Increased `MISSED_THRESHOLD_MS` in `server/heartbeat-checker.js` from 10 minutes to 30 minutes to account for Android's system-enforced 15-minute `WorkManager` background interval limitation. This entirely resolves the false "protection lost" notifications when the Android system suspends the AccessibilityService during Doze mode.
* **FCM Payload Enhancement**: Added the specific `reason` string to the FCM data payload in `heartbeat-checker.js` so that the Android client can accurately display why protection was lost rather than a generic message.
* **State Machine Hardening (Previous Phase)**: Transitioned all heartbeat and protection updates to atomic Firestore transactions in `store.js` to mathematically prevent race conditions and duplicate state transitions.

### Android Fixes
* **Dynamic Notifications**: Updated `ScrollBuddyFCMService.kt` to extract the `reason` string from the FCM push payload instead of hardcoding a generic string.
* **Room Database Migration**: Fixed a critical build warning by updating the deprecated `fallbackToDestructiveMigration()` call in `AppDatabase.kt` to `fallbackToDestructiveMigration(dropAllTables = true)`.
* **Immediate Protection Drop Check (Previous Phase)**: Overrode `onUnbind` and `onDestroy` in `ScrollSentryAccessibilityService.kt` to immediately send an `active=false` heartbeat when Android forcibly closes the accessibility service (e.g. during uninstall or manual disable).
