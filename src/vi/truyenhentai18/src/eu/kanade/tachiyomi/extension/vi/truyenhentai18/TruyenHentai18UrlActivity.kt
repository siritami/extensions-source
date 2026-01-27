package eu.kanade.tachiyomi.extension.vi.truyenhentai18

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

class TruyenHentai18UrlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pathSegments = intent?.data?.pathSegments

        if (pathSegments != null && pathSegments.size > 0) {
            val slug = pathSegments.last().removeSuffix(".html")
            val intent = Intent().apply {
                action = "eu.kanade.tachiyomi.SEARCH"
                putExtra("query", "${TruyenHentai18.PREFIX_SLUG_SEARCH}$slug")
                putExtra("filter", packageName)
            }

            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Log.e("TruyenHentai18UrlAct", "Could not start activity", e)
            }
        } else {
            Log.e("TruyenHentai18UrlAct", "Could not parse URI from intent $intent")
        }

        finish()
        exitProcess(0)
    }
}
