# Step 20 owner validation

Time: 2026-10-16T05:33:27.4282513Z
Device acceptance: PENDING; this script does not execute physical tests.
HEAD: 4fb3bd451abe0442519d149a73e0d3fccaefdbb5
Source portability/manifest/whitespace: PASS
Command: gradlew.bat clean :core:jvmTest :android:assembleDebug :core:assembleDebugAndroidTest --console=plain --no-daemon
Merged debug manifest: PASS
JVM: 31 suites; 247 tests; failures=0; errors=0; skipped=0
APK: android/build/outputs/apk/debug/android-debug.apk; SHA256=53A0222AD8862D1008B410E33165EEEC02CBB24419E41A46D48DFA7138ECAC09
APK: core/build/outputs/apk/androidTest/debug/core-debug-androidTest.apk; SHA256=D58770DC32ED237251425EB2531E3A3D20687D240B18B3C7E695AEA96ED6180C

BUILD AND JVM CHECKS: PASS. PHYSICAL ACCEPTANCE: PENDING.

```text
> Task :core:compileDebugAndroidTestShaders NO-SOURCE
> Task :core:generateDebugAndroidTestAssets UP-TO-DATE
> Task :core:mergeDebugAndroidTestAssets
> Task :core:compressDebugAndroidTestAssets
> Task :core:processDebugAndroidTestJavaRes
> Task :core:mergeDebugAndroidTestJavaResource
> Task :core:checkDebugAndroidTestDuplicateClasses
> Task :core:desugarDebugAndroidTestFileDependencies
> Task :core:mergeExtDexDebugAndroidTest
> Task :android:mergeLibDexDebug
> Task :android:packageDebug
> Task :android:createDebugApkListingFileRedirect
> Task :android:assembleDebug
> Task :core:dexBuilderDebugAndroidTest
> Task :core:mergeProjectDexDebugAndroidTest
> Task :core:mergeDebugAndroidTestJniLibFolders
> Task :core:mergeDebugAndroidTestNativeLibs NO-SOURCE
> Task :core:stripDebugAndroidTestDebugSymbols NO-SOURCE
> Task :core:validateSigningDebugAndroidTest
> Task :core:writeDebugAndroidTestSigningConfigVersions
> Task :core:mergeLibDexDebugAndroidTest
> Task :core:packageDebugAndroidTest
> Task :core:createDebugAndroidTestApkListingFileRedirect
> Task :core:assembleDebugAndroidTest

[Incubating] Problems report is available at: file:///D:/YoDrop/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.14.3/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 2m 19s
86 actionable tasks: 86 executed
```
