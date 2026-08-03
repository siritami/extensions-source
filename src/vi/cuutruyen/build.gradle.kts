import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "CuuTruyen"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "vi"
        baseUrl {
            custom("https://cuutruyen.net")
        }
        id = 4989357807406366033
    }

    deeplink {
        path("/mangas/.*")
    }
}
