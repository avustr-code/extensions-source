import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NewXToon"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        baseUrl {
            custom("https://newxtoon1.com")
        }
        lang = "ko"
    }

    deeplink {
        path("/comics/..*")
    }
}
