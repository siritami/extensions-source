package eu.kanade.tachiyomi.extension.vi.ariverse

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Locale

class Ariverse :
    Madara(
        "Ariverse",
        "https://arigl.com",
        "vi",
        SimpleDateFormat("dd/MM/yyyy", Locale("vi")),
    ) {

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(5)
        .build()
}
