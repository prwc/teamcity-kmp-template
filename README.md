# TeamCity + Kotlin Multiplatform (Android & iOS) CI/CD

This template gives you a **ready-to-import TeamCity Kotlin DSL** plus **Fastlane lanes** for iOS TestFlight and optional Firebase App Distribution.
It assumes:
- Android builds run on a Linux agent with JDK 17 and Android SDK.
- iOS builds run on a macOS agent with Xcode and Ruby (fastlane).
- Your code is in a GitHub repo.

## How to use

1. Copy the `.teamcity` folder to the **root of your KMM repo** and push.
2. In TeamCity, create a **project from URL / VCS** pointing to your repo. TeamCity will detect the Kotlin DSL.
3. Configure **Project Parameters** in TeamCity (Project → Edit → Parameters):
   - `env.GITHUB_REPO_URL` (e.g. `git@github.com:your-org/your-repo.git`)
   - `env.GITHUB_USERNAME` (`git` for SSH, or your username for https)
   - `env.GITHUB_TOKEN_ID` (ID of a TeamCity credential for GitHub)
   - `env.DEFAULT_BRANCH` (e.g. `refs/heads/main`)
   - Android:
     - `ANDROID_FIREBASE_APP_ID` (from Firebase Console)
     - `FIREBASE_TOKEN_ID` (TeamCity credential ID for a Firebase CLI token)
     - Signing: `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`
   - iOS:
     - `IOS_BUNDLE_ID`, `APPLE_TEAM_ID`
     - `MATCH_GIT_URL` (if using `match`), or configure manual signing in your Xcode project
     - App Store Connect:
       - `APP_STORE_CONNECT_API_KEY_ID`
       - `APP_STORE_CONNECT_ISSUER_ID`
       - `APP_STORE_CONNECT_API_KEY_P8_BASE64_ID` (TeamCity secure value ID holding **base64** of your `.p8`)

4. On your **Android module** `app/build.gradle` add the Firebase App Distribution plugin:
   ```kotlin
   plugins {
       id("com.android.application")
       kotlin("android")
       id("com.google.firebase.appdistribution") version "5.0.0"
   }

   firebaseAppDistribution {
       artifact = file("build/outputs/apk/release/app-release.apk")
       appId = System.getProperty("firebaseAppId") ?: System.getenv("FIREBASE_APP_ID")
       releaseNotes = System.getProperty("releaseNotes") ?: System.getenv("RELEASE_NOTES")
       testers = System.getenv("FIREBASE_TESTERS") // optional
       groups = System.getenv("FIREBASE_GROUPS") // optional
   }
   ```

5. For **iOS**, copy the provided `fastlane/` folder to your iOS app directory (same level as your `.xcodeproj` / `.xcworkspace`). Ensure a scheme named `App` (or set `XCODE_SCHEME` parameter).

6. On macOS agents, install dependencies:
   ```bash
   gem install fastlane
   fastlane add_plugin firebase_app_distribution
   # or bundle install if using the provided Gemfile
   ```

## Pipelines

- **Android CI (debug)**: builds and tests every push except `release/*` branches.
- **Android Distribute (Firebase)**: triggered on `release/*` branches or tags `v*`; assembles release and uploads to Firebase.
- **iOS Beta (TestFlight)**: triggered on `release/*` branches or tags `ios-v*`; runs `fastlane beta` to build and upload to TestFlight.
- **iOS (Firebase)**: manual or add triggers as desired; runs `fastlane firebase_beta` to distribute to Firebase App Distribution.

## Agent requirements

- Android agents should set `CONF_JAVA_HOME` and `CONF_ANDROID_SDK` parameters (defaults provided).
- iOS agents must be macOS with Xcode, and have Ruby/fastlane available.

## Secrets & secure values

Create TeamCity credentials / secure parameters for:
- `GITHUB_TOKEN` (if using https) or SSH keys on agent.
- `FIREBASE_TOKEN`
- `APP_STORE_CONNECT_API_KEY_P8_BASE64` (contents of `.p8`, base64-encoded). Example:
  ```bash
  base64 -i AuthKey_ABC123.p8 | tr -d '\n'
  ```

## Notes

- Adjust Gradle tasks, scheme name, and triggers to match your repo.
- If you prefer Fastlane for Android too, add lanes similarly and call them from TeamCity.
