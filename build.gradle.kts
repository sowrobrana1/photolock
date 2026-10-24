// PhotoLock — root build script.
// Plugin versions are declared here and applied in the :app module.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    // KSP — used by Room's annotation processor (version tracks Kotlin 2.0.20).
    id("com.google.devtools.ksp") version "2.0.20-1.0.25" apply false
}
