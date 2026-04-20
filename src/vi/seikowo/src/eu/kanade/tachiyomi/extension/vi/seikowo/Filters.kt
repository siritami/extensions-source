package eu.kanade.tachiyomi.extension.vi.seikowo

import eu.kanade.tachiyomi.source.model.Filter

private class Option(
    val displayName: String,
    val value: String?,
)

private val statusOptions = arrayOf(
    Option("All Status", null),
    Option("Completed", "Status_Completed"),
    Option("Ongoing", "Status_Ongoing"),
)

private val sortOptions = arrayOf(
    Option("Latest Updates", "updated"),
    Option("Recently Added", "published"),
    Option("Title A-Z", "title"),
    Option("Most Comments", "popular"),
)

private val genreOptions = arrayOf(
    Option("All Genres", null),
    Option("Adaptation", "Adaptation"),
    Option("Adventure", "Adventure"),
    Option("Animals", "Animals"),
    Option("Cooking", "Cooking"),
    Option("Crossdressing", "Crossdressing"),
    Option("Delinquents", "Delinquents"),
    Option("Demons", "Demons"),
    Option("Fan Colored", "Fan Colored"),
    Option("Full Color", "Full Color"),
    Option("Genderswap", "Genderswap"),
    Option("Ghosts", "Ghosts"),
    Option("Girls' Love", "Girls' Love"),
    Option("Gore", "Gore"),
    Option("Gyaru", "Gyaru"),
    Option("Historical", "Historical"),
    Option("Loli", "Loli"),
    Option("Long Strip", "Long Strip"),
    Option("Magic", "Magic"),
    Option("Monster Girls", "Monster Girls"),
    Option("Ninja", "Ninja"),
    Option("Office Workers", "Office Workers"),
    Option("Post-Apocalyptic", "Post-Apocalyptic"),
    Option("Psychological", "Psychological"),
    Option("Reincarnation", "Reincarnation"),
    Option("Romance", "Romance"),
    Option("Sci-Fi", "Sci-Fi"),
    Option("Shota", "Shota"),
    Option("Sports", "Sports"),
    Option("Survival", "Survival"),
    Option("Thriller", "Thriller"),
    Option("Time Travel", "Time Travel"),
    Option("Vampires", "Vampires"),
    Option("Video Games", "Video Games"),
    Option("Villainess", "Villainess"),
    Option("Virtual Reality", "Virtual Reality"),
    Option("Zombies", "Zombies"),
    Option("action", "action"),
    Option("aliens", "aliens"),
    Option("award winning", "award winning"),
    Option("comedy", "comedy"),
    Option("drama", "drama"),
    Option("fantasy", "fantasy"),
    Option("harem", "harem"),
    Option("horror", "horror"),
    Option("isekai", "isekai"),
    Option("manga", "manga"),
    Option("manhua", "manhua"),
    Option("manhwa", "manhwa"),
    Option("martial arts", "martial arts"),
    Option("mecha", "mecha"),
    Option("military", "military"),
    Option("monsters", "monsters"),
    Option("mystery", "mystery"),
    Option("oneshot", "oneshot"),
    Option("romcom", "romcom"),
    Option("school life", "school life"),
    Option("sci fi", "sci fi"),
    Option("seinen", "seinen"),
    Option("shoujo", "shoujo"),
    Option("shounen", "shounen"),
    Option("slice of life", "slice of life"),
    Option("supernatural", "supernatural"),
    Option("tragedy", "tragedy"),
    Option("web comic", "web comic"),
    Option("webtoon", "webtoon"),
)

class StatusFilter : Filter.Select<String>(
    "Status",
    statusOptions.map { it.displayName }.toTypedArray(),
) {
    val selectedValue: String?
        get() = statusOptions[state].value
}

class SortByFilter : Filter.Select<String>(
    "Sort By",
    sortOptions.map { it.displayName }.toTypedArray(),
) {
    val selectedValue: String
        get() = sortOptions[state].value ?: "updated"
}

class GenreFilter : Filter.Select<String>(
    "Genres",
    genreOptions.map { it.displayName }.toTypedArray(),
) {
    val selectedValue: String?
        get() = genreOptions[state].value
}
