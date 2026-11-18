pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    // fork: libadb-android (and its pairing library) are on JitPack only (home/SelfAdb.kt)
    repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } }
}
rootProject.name = "tvhome"
include(":app")
