import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Học Viện 2Ten"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "vi"
        baseUrl = "https://hv2tcomics.net"
    }
}

dependencies {
    implementation("com.github.penfeizhou.android.animation:avif:3.0.5")
    implementation("com.github.penfeizhou.android.animation:awebpencoder:3.0.5")
}
