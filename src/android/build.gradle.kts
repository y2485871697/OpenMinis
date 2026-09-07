plugins {
    // [toolchain-2026] Latest stable toolchain line (checked 2026-09):
    // AGP 9.4.0 + Kotlin 2.3.21 + KSP2 2.3.11 + Gradle 9.7.1 (wrapper).
    // AGP 9 requires JDK 17+ to RUN Gradle; still compiles to Java 17
    // bytecode. KSP2 uses standalone versioning since 2.3 (no longer
    // kotlinVersion-kspVersion pairs).
    id("com.android.application") version "9.4.0" apply false
    // [toolchain-2026] AGP 9.0+ has Kotlin support BUILT IN (no separate
    // org.jetbrains.kotlin.android plugin — applying it is a hard error).
    // Only the compiler-helper KGP plugins are declared here.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
}
