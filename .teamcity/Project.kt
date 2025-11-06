import jetbrains.buildServer.configs.kotlin.v2019_2.*
import jetbrains.buildServer.configs.kotlin.v2019_2.Project
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.gradle
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.script
import jetbrains.buildServer.configs.kotlin.v2019_2.triggers.vcs
import jetbrains.buildServer.configs.kotlin.v2019_2.ui.*
import jetbrains.buildServer.configs.kotlin.v2019_2.vcs.GitVcsRoot

// ---- VCS Root ----
object GitHubVcs : GitVcsRoot({
    name = "GitHub KMM Repo"
    url = "%env.GITHUB_REPO_URL%"
    branch = "%env.DEFAULT_BRANCH%"
    branchSpec = "+:refs/heads/*"
    authMethod = password {
        userName = "%env.GITHUB_USERNAME%"
        password = "credentialsJSON:%env.GITHUB_TOKEN_ID%"
    }
    useMirrors = true
})

// ---- Build Configs ----

// Android CI: build + unit tests + assembleDebug
object AndroidCI : BuildType({
    id("Android_CI")
    name = "Android CI (debug)"
    vcs {
        root(GitHubVcs)
        cleanCheckout = true
    }
    params {
        // Gradle JVM / caching
        param("env.JAVA_HOME", "%{CONF_JAVA_HOME}%")
        param("env.ANDROID_SDK_ROOT", "%{CONF_ANDROID_SDK}%")
    }
    steps {
        gradle {
            name = "Gradle build (debug)"
            useGradleWrapper = true
            tasks = "clean :app:assembleDebug test"
            jdkHome = "%env.JAVA_HOME%"
            gradleParams = "-Dorg.gradle.jvmargs='-Xmx3g' --stacktrace --build-cache"
        }
    }
    artifacts {
        artifactRules = "app/build/outputs/**/*.apk => android-artifacts"
    }
    triggers {
        vcs {
            branchFilter = """
                +:refs/heads/*
                -:refs/heads/release/*
            """.trimIndent()
        }
    }
    requirements {
        // Any agent with Android SDK
        contains("env.ANDROID_SDK_ROOT", "/") // simplistic presence check
        doesNotContain("teamcity.agent.jvm.os.name", "Mac") // prefer non-mac for Android to free macOS for iOS
    }
})

// Android Release -> Firebase App Distribution
object AndroidDistribute : BuildType({
    id("Android_Distribute_Firebase")
    name = "Android Distribute (Firebase App Distribution)"
    vcs {
        root(GitHubVcs)
        cleanCheckout = true
    }
    params {
        param("env.JAVA_HOME", "%{CONF_JAVA_HOME}%")
        param("env.ANDROID_SDK_ROOT", "%{CONF_ANDROID_SDK}%")
        // Firebase
        param("env.FIREBASE_TOKEN", "credentialsJSON:%FIREBASE_TOKEN_ID%")
        param("env.FIREBASE_APP_ID", "%ANDROID_FIREBASE_APP_ID%")
        param("env.RELEASE_NOTES", "%RELEASE_NOTES%")
        // Keystore (mount your secure file to this path or set gradle signing to use env vars)
        param("env.ANDROID_KEYSTORE_PATH", "%ANDROID_KEYSTORE_PATH%")
        param("env.ANDROID_KEYSTORE_PASSWORD", "%ANDROID_KEYSTORE_PASSWORD%")
        param("env.ANDROID_KEY_ALIAS", "%ANDROID_KEY_ALIAS%")
        param("env.ANDROID_KEY_PASSWORD", "%ANDROID_KEY_PASSWORD%")
    }
    steps {
        gradle {
            name = "Assemble & Upload to Firebase"
            useGradleWrapper = true
            tasks = ":app:assembleRelease :app:appDistributionUploadRelease"
            jdkHome = "%env.JAVA_HOME%"
            gradleParams = "-Dorg.gradle.jvmargs='-Xmx3g' --stacktrace --build-cache -PfirebaseAppId=%env.FIREBASE_APP_ID% -PreleaseNotes='%env.RELEASE_NOTES%'"
        }
    }
    artifacts {
        artifactRules = "app/build/outputs/**/*.apk => android-artifacts"
    }
    triggers {
        vcs {
            branchFilter = """
                +:refs/heads/release/*
                +:refs/tags/v*
            """.trimIndent()
        }
    }
    requirements {
        contains("env.ANDROID_SDK_ROOT", "/")
    }
})

// iOS using Fastlane lanes
object IosTestFlight : BuildType({
    id("iOS_TestFlight")
    name = "iOS Beta (TestFlight via fastlane)"
    vcs {
        root(GitHubVcs)
        cleanCheckout = true
    }
    params {
        // Fastlane / Xcode
        param("env.APPLE_TEAM_ID", "%APPLE_TEAM_ID%")
        param("env.APP_IDENTIFIER", "%IOS_BUNDLE_ID%")
        param("env.MATCH_GIT_URL", "%MATCH_GIT_URL%")
        // App Store Connect API key (p8 material stored as secure value; lane writes it to a temp file)
        param("env.ASC_KEY_ID", "%APP_STORE_CONNECT_API_KEY_ID%")
        param("env.ASC_ISSUER_ID", "%APP_STORE_CONNECT_ISSUER_ID%")
        param("env.ASC_KEY_P8_BASE64", "credentialsJSON:%APP_STORE_CONNECT_API_KEY_P8_BASE64_ID%")
        param("env.TESTFLIGHT_GROUPS", "%TESTFLIGHT_GROUPS%")
        // Optional build number override
        param("env.BUILD_NUMBER", "%build.counter%")
    }
    steps {
        script {
            name = "Bundle install (if Gemfile present)"
            scriptContent = "if [ -f Gemfile ]; then bundle install --path vendor/bundle; fi"
        }
        script {
            name = "fastlane beta"
            scriptContent = "export LC_ALL=en_US.UTF-8; export LANG=en_US.UTF-8; bundle exec fastlane beta || fastlane beta"
        }
    }
    artifacts {
        artifactRules = "fastlane/build/**/*.ipa => ios-artifacts"
    }
    requirements {
        contains("teamcity.agent.jvm.os.name", "Mac")
        contains("env.XCODE_VERSION", "") // any Xcode
    }
    triggers {
        vcs {
            branchFilter = """
                +:refs/heads/release/*
                +:refs/tags/ios-v*
            """.trimIndent()
        }
    }
})

// iOS optional Firebase Distribution via fastlane
object IosFirebase : BuildType({
    id("iOS_Firebase_Distribution")
    name = "iOS (Firebase App Distribution via fastlane)"
    vcs {
        root(GitHubVcs)
        cleanCheckout = true
    }
    params {
        param("env.APP_IDENTIFIER", "%IOS_BUNDLE_ID%")
        param("env.FIREBASE_APP_ID_IOS", "%IOS_FIREBASE_APP_ID%")
        param("env.FIREBASE_TOKEN", "credentialsJSON:%FIREBASE_TOKEN_ID%")
        param("env.FIREBASE_GROUPS", "%FIREBASE_GROUPS%")
        // Signing via match or manual
        param("env.MATCH_GIT_URL", "%MATCH_GIT_URL%")
        param("env.ASC_KEY_ID", "%APP_STORE_CONNECT_API_KEY_ID%")
        param("env.ASC_ISSUER_ID", "%APP_STORE_CONNECT_ISSUER_ID%")
        param("env.ASC_KEY_P8_BASE64", "credentialsJSON:%APP_STORE_CONNECT_API_KEY_P8_BASE64_ID%")
    }
    steps {
        script {
            name = "Bundle install (if Gemfile present)"
            scriptContent = "if [ -f Gemfile ]; then bundle install --path vendor/bundle; fi"
        }
        script {
            name = "fastlane firebase_beta"
            scriptContent = "export LC_ALL=en_US.UTF-8; export LANG=en_US.UTF-8; bundle exec fastlane firebase_beta || fastlane firebase_beta"
        }
    }
    artifacts {
        artifactRules = "fastlane/build/**/*.ipa => ios-artifacts"
    }
    requirements {
        contains("teamcity.agent.jvm.os.name", "Mac")
    }
})

// ---- Project ----
object ProjectRoot : Project({
    name = "KMM (Android + iOS)"
    description = "TeamCity Kotlin DSL for Kotlin Multiplatform with Android Firebase Distribution and iOS TestFlight/Firebase"

    params {
        // Change these defaults in TeamCity UI -> Project Parameters
        param("env.GITHUB_REPO_URL", "git@github.com:your-org/your-repo.git")
        param("env.GITHUB_USERNAME", "git")
        param("env.GITHUB_TOKEN_ID", "GITHUB_TOKEN") // reference to a TeamCity stored token/credential
        param("env.DEFAULT_BRANCH", "refs/heads/main")

        // Android
        param("ANDROID_FIREBASE_APP_ID", "1:1234567890:android:abc123")
        param("ANDROID_KEYSTORE_PATH", "%teamcity.build.checkoutDir%/keystore.jks")
        param("ANDROID_KEYSTORE_PASSWORD", "")
        param("ANDROID_KEY_ALIAS", "")
        param("ANDROID_KEY_PASSWORD", "")
        param("RELEASE_NOTES", "Automated build from TeamCity")

        // iOS
        param("IOS_BUNDLE_ID", "com.example.app")
        param("IOS_FIREBASE_APP_ID", "1:1234567890:ios:def456")
        param("APPLE_TEAM_ID", "ABCDE12345")
        param("TESTFLIGHT_GROUPS", "internal testers")
        param("FIREBASE_GROUPS", "internal-testers")

        // App Store Connect
        param("APP_STORE_CONNECT_API_KEY_ID", "")
        param("APP_STORE_CONNECT_ISSUER_ID", "")
        param("APP_STORE_CONNECT_API_KEY_P8_BASE64_ID", "ASC_P8_B64")
        param("FIREBASE_TOKEN_ID", "FIREBASE_TOKEN")

        // Tools on agents
        param("CONF_JAVA_HOME", "/usr/lib/jvm/temurin-17-jdk")
        param("CONF_ANDROID_SDK", "/opt/android-sdk")
    })

    vcsRoot(GitHubVcs)
    buildType(AndroidCI)
    buildType(AndroidDistribute)
    buildType(IosTestFlight)
    buildType(IosFirebase)
})
