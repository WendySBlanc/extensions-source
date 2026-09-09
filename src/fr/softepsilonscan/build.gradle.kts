import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Soft Epsilon Scan"
    versionCode = 54
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"
    theme = "pam"

    source {
        lang = "fr"
        baseUrl = "https://epsilonsoft.to"
    }
}
