import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
<<<<<<<< HEAD:src/th/blackmanga/build.gradle.kts
    name = "Black Manga"
    theme = "mangathemesia"
    versionCode = 1
========
    name = "Manga-Neko"
    theme = "mangathemesia"
    versionCode = 0
>>>>>>>> upstream/main:src/th/manganeko/build.gradle.kts
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
<<<<<<<< HEAD:src/th/blackmanga/build.gradle.kts
        baseUrl = "https://www.black-manga.com"
========
        baseUrl = "https://manga-neko.com"
>>>>>>>> upstream/main:src/th/manganeko/build.gradle.kts
        lang = "th"
    }
}
