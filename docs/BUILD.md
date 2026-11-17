# Build

## Environment (verified)

- JAVA_HOME: `C:\Users\dzulf\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2` (Temurin 21.0.7)
  - Android Studio's JBR (`C:\Program Files\Android\Android Studio\jbr`) is Java 25 and too new for Gradle 8.14; don't use it.
- Android SDK: `C:\Users\dzulf\AppData\Local\Android\Sdk` (platform `android-35`), set in `local.properties` (gitignored).
- Gradle 8.14 via wrapper, AGP 8.13.1, Kotlin 2.2.20, Ktor 3.2.3 (see `gradle/libs.versions.toml`).

## Commands (Windows cmd / PowerShell)

```
set JAVA_HOME=C:\Users\dzulf\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2
gradlew.bat assembleDebug
gradlew.bat testDebugUnitTest
gradlew.bat assembleRelease      # R8 minify + shrink (~2.1 MB); unsigned unless the env vars below are set
```

Outputs: `app/build/outputs/apk/{debug,release}/`.

## Release signing

`app/build.gradle.kts` creates `signingConfigs.release` only when `KEYSTORE_PATH` is set, reading
`KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` from the environment (this is what
`.github/workflows/release.yml` provides). Without them `assembleRelease` produces
`app-release-unsigned.apk`; with them it produces a signed `app-release.apk`.

The in-process bash tool cannot run `cmd`/`.bat` reliably; from Python use
`subprocess.run([r"<root>\gradlew.bat", *args], env={**os.environ, "JAVA_HOME": ...})`.
The first build downloads Gradle and dependencies (~5 min); later builds are much faster.
