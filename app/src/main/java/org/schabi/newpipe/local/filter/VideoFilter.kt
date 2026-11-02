package org.schabi.newpipe.local.filter

import java.time.Duration
import java.time.OffsetDateTime
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/** What a filter needs to know about a video, whatever list it comes from. */
data class VideoFacts(
    val title: String,
    val uploaderUrl: String?,
    val uploadDate: OffsetDateTime?,
    /** seconds; 0 or less when unknown */
    val durationSeconds: Long,
    /** -1 when unknown */
    val viewCount: Long,
    val isLiveOrUpcoming: Boolean,
    val isShort: Boolean
) {
    companion object {
        /** The feed does not store the Shorts flag: clips up to this length are treated as Shorts. */
        private const val FEED_SHORTS_MAX_SECONDS = 60

        fun of(item: StreamInfoItem) = VideoFacts(
            title = item.name.orEmpty(),
            uploaderUrl = item.uploaderUrl,
            uploadDate = item.uploadDate?.offsetDateTime(),
            durationSeconds = item.duration,
            viewCount = item.viewCount,
            isLiveOrUpcoming = isLive(item.streamType),
            isShort = item.isShortFormContent
        )

        fun of(stream: StreamEntity) = VideoFacts(
            title = stream.title,
            uploaderUrl = stream.uploaderUrl,
            uploadDate = stream.uploadDate,
            durationSeconds = stream.duration,
            viewCount = stream.viewCount ?: -1,
            isLiveOrUpcoming = isLive(stream.streamType) ||
                (stream.uploadDate?.isAfter(OffsetDateTime.now()) == true),
            isShort = stream.duration in 1..FEED_SHORTS_MAX_SECONDS
        )

        private fun isLive(type: StreamType) = type == StreamType.LIVE_STREAM || type == StreamType.AUDIO_LIVE_STREAM
    }
}

/**
 * Filters and order for the Suggestions and Subscriptions lists, computed on the device.
 * A video whose date or length is unknown is kept: the filter cannot tell, so it does not hide it.
 */
data class VideoFilter(
    val period: Period = Period.ANY,
    val length: Length = Length.ANY,
    val sort: Sort = Sort.DEFAULT,
    val hideShorts: Boolean = false,
    val hideLive: Boolean = false,
    val onlySubscribed: Boolean = false,
    /** case-insensitive words; a video whose title contains any of them is hidden */
    val hiddenWords: List<String> = emptyList()
) {
    enum class Period(val maxAge: Duration?) {
        ANY(null),
        DAY(Duration.ofDays(1)),
        WEEK(Duration.ofDays(7)),
        MONTH(Duration.ofDays(31)),
        YEAR(Duration.ofDays(366))
    }

    enum class Length(val minSeconds: Long, val maxSeconds: Long) {
        ANY(0, Long.MAX_VALUE),
        SHORT(1, 4 * 60 - 1),
        MEDIUM(4 * 60, 20 * 60),
        LONG(20 * 60 + 1, Long.MAX_VALUE)
    }

    enum class Sort { DEFAULT, NEWEST, MOST_VIEWED, SHORTEST, LONGEST }

    val isDefault: Boolean get() = this == VideoFilter()

    /** Number of active settings, for the menu badge. */
    val activeCount: Int
        get() = listOf(
            period != Period.ANY,
            length != Length.ANY,
            sort != Sort.DEFAULT,
            hideShorts,
            hideLive,
            onlySubscribed,
            hiddenWords.isNotEmpty()
        ).count { it }

    fun <T> apply(
        items: List<T>,
        subscribedChannels: Set<String> = emptySet(),
        now: OffsetDateTime = OffsetDateTime.now(),
        facts: (T) -> VideoFacts
    ): List<T> {
        if (isDefault) {
            return items
        }
        val words = hiddenWords.map { it.lowercase() }
        val kept = items.filter { item ->
            val f = facts(item)
            matchesPeriod(f, now) &&
                matchesLength(f) &&
                !(hideShorts && f.isShort) &&
                !(hideLive && f.isLiveOrUpcoming) &&
                !(onlySubscribed && f.uploaderUrl !in subscribedChannels) &&
                words.none { f.title.lowercase().contains(it) }
        }
        return when (sort) {
            Sort.DEFAULT -> kept

            // unknown values go last
            Sort.NEWEST -> kept.sortedByDescending { facts(it).uploadDate?.toEpochSecond() ?: Long.MIN_VALUE }

            Sort.MOST_VIEWED -> kept.sortedByDescending { facts(it).viewCount }

            Sort.SHORTEST -> kept.sortedBy { facts(it).durationSeconds.takeIf { d -> d > 0 } ?: Long.MAX_VALUE }

            Sort.LONGEST -> kept.sortedByDescending { facts(it).durationSeconds }
        }
    }

    private fun matchesPeriod(f: VideoFacts, now: OffsetDateTime): Boolean {
        val maxAge = period.maxAge ?: return true
        val date = f.uploadDate ?: return true
        return !date.isBefore(now.minus(maxAge))
    }

    private fun matchesLength(f: VideoFacts): Boolean = length == Length.ANY ||
        f.durationSeconds <= 0 ||
        f.durationSeconds in length.minSeconds..length.maxSeconds

    /** Compact form for the preferences. */
    fun encode(): String = listOf(
        "period=${period.name}",
        "length=${length.name}",
        "sort=${sort.name}",
        "shorts=${if (hideShorts) 1 else 0}",
        "live=${if (hideLive) 1 else 0}",
        "subs=${if (onlySubscribed) 1 else 0}",
        "words=${hiddenWords.joinToString(",") { it.replace(",", " ").replace(";", " ") }}"
    ).joinToString(";")

    companion object {
        fun decode(text: String?): VideoFilter {
            if (text.isNullOrBlank()) {
                return VideoFilter()
            }
            val values = text.split(";").mapNotNull {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap()
            return VideoFilter(
                period = enumOr(values["period"], Period.ANY),
                length = enumOr(values["length"], Length.ANY),
                sort = enumOr(values["sort"], Sort.DEFAULT),
                hideShorts = values["shorts"] == "1",
                hideLive = values["live"] == "1",
                onlySubscribed = values["subs"] == "1",
                hiddenWords = parseWords(values["words"])
            )
        }

        fun parseWords(text: String?): List<String> = text.orEmpty().split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E = enumValues<E>().firstOrNull { it.name == name } ?: default
    }
}
