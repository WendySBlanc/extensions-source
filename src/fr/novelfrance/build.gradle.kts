import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NovelFrance"
    versionCode = 4
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "fr"
        baseUrl = "https://novelfrance.fr"
    }

    deeplink {
        host("novelfrance.fr")
        path("/novel/..*")
    }
}
