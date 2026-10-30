import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
<<<<<<<< HEAD:src/th/pedmanga/build.gradle.kts
    name = "Ped Manga"
    theme = "mangathemesia"
    versionCode = 1
========
    name = "1668Manga"
    theme = "mangathemesia"
    versionCode = 0
>>>>>>>> upstream/main:src/th/one668manga/build.gradle.kts
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
<<<<<<<< HEAD:src/th/pedmanga/build.gradle.kts
        baseUrl = "https://ped-manga.com"
========
        baseUrl = "https://1668manga.com"
>>>>>>>> upstream/main:src/th/one668manga/build.gradle.kts
        lang = "th"
    }
}
