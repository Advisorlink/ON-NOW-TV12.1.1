pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // v2.11.8 — JitPack for NewPipeExtractor (com.github.teamnewpipe:
        // NewPipeExtractor).  Powers native in-app YouTube trailer
        // playback — extracts direct video URLs on the device using
        // the operator's residential IP, so YouTube treats us as a
        // regular home user (no bot flag, no Error 153, no embed
        // restrictions).
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "Vesper"
include(":app")
