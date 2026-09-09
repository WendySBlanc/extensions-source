import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hentai Origines"
    versionCode = 55
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"
    theme = "origines"

    source {
        lang = "fr"
        baseUrl = "https://hentai-origines.com"
    }
}
