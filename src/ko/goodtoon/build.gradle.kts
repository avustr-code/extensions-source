import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "GoodToon"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "ko"
        baseUrl {
            custom("https://www.goodtoon004.com")
        }
    }

    deeplink {
        path("/manga/..*")
    }
}
