import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Lava Scans"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"
    theme = "mangathemesia"

    source {
        lang = "ar"
        baseUrl = "https://lavascans.com"
        id = 3209001028102012989L
    }
}
