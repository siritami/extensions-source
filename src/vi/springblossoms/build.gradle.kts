import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "SpringBlossoms"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "vi"
        baseUrl {
            custom("https://owibusb.com")
        }
        id = 4413681066613655891L
    }

    deeplink {
        path("/manga/..*")
    }
}
