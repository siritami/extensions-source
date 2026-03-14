package eu.kanade.tachiyomi.extension.vi.teamlanhlung

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

class TeamLanhLungUrlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments
        val slug = if (pathSegments != null && pathSegments.isNotEmpty()) {
            extractSlug(pathSegments)
        } else {
            null
        }

        if (!slug.isNullOrBlank()) {
            try {
                startActivity(
                    Intent().apply {
                        action = "eu.kanade.tachiyomi.SEARCH"
                        putExtra("query", "id:$slug")
                        putExtra("filter", packageName)
                    },
                )
            } catch (e: ActivityNotFoundException) {
                Log.e("TeamLanhLungUrlActivity", e.toString())
            }
        } else {
            Log.e("TeamLanhLungUrlActivity", "Could not parse URI from intent $intent")
        }

        finish()
        exitProcess(0)
    }

    private fun extractSlug(pathSegments: List<String>): String? {
        if (pathSegments.isEmpty()) {
            return null
        }

        val firstSegment = pathSegments[0]
        if ("truyen-tranh".equals(firstSegment) && pathSegments.size > 1) {
            return pathSegments[1]
        }

        val chapterMatch = CHAPTER_URL_REGEX.find(firstSegment)
        if (chapterMatch != null) {
            return chapterMatch.groupValues[1]
        }

        return firstSegment
    }

    companion object {
        private val CHAPTER_URL_REGEX = Regex("(.+)-chap-\\d+(?:\\.\\d+)?", RegexOption.IGNORE_CASE)
    }
}
