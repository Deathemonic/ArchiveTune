package moe.rukamori.archivetune.sources

import java.io.InterruptedIOException
import kotlin.math.abs
import moe.rukamori.archivetune.utils.FuzzyMatcher

internal object SourceTrackMatcher {
    private val artistCreditsRegex = Regex("(?i)\\s*(?:,|&|\\bfeat\\.?|\\bft\\.?|\\bwith\\b)\\s*")
    private val titleAnnotationsRegex = Regex("""\(([^)]*)\)|\[([^\]]*)\]""")
    private val featuredArtistRegex = Regex("(?i)\\s+(?:feat\\.?|ft\\.?)\\s+.*$")
    private val editorialSuffixRegex =
        Regex("(?i)\\s*[-–—]\\s*(?:official\\s+)?(?:audio|video|lyrics?|visuali[sz]er)(?:\\s+.*)?$")
    private val soundtrackAnnotationRegex = Regex("""(?i)\(\s*from\s+[^)]*\)|\[\s*from\s+[^\]]*\]""")

    fun match(query: SourceSongQuery, tracks: List<SourceTrack>): SourceTrack? {
        if (query.duration <= 0 || query.artists.isEmpty()) return null
        val title = canonicalTitle(query.title)
        val baseTitle = canonicalTitle(query.title, stripContext = true)
        val versions = versionTags(query.title)
        val remix = remixName(query.title)
        val tolerance = maxOf(6, minOf(10, query.duration / 50))

        return tracks.distinctBy { it.id }.mapNotNull { track ->
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Source matching interrupted")

            if (!track.streamable ||
                abs(track.duration - query.duration) > tolerance ||
                versionTags("${track.title} ${track.version}") != versions ||
                !artistMatches(query.artists, track.artist)
            ) {
                return@mapNotNull null
            }

            val candidateRemix = remixName(track.title, track.version)

            if (remix.isNotBlank() && candidateRemix.isNotBlank() && remix != candidateRemix) return@mapNotNull null

            val candidateTitle = canonicalTitle(track.title)
            val candidateBaseTitle = canonicalTitle(track.title, stripContext = true)
            val contextMatch = (title.isNotBlank() && title == candidateBaseTitle) ||
                (candidateTitle.isNotBlank() && baseTitle == candidateTitle)
            val similarity = maxOf(titleSimilarity(title, candidateTitle), if (contextMatch) 0.95 else 0.0)

            if (similarity < 0.90) return@mapNotNull null
            val albumBonus = if (!query.album.isNullOrBlank() &&
                normalized(query.album) == normalized(track.album)
            ) {
                8.0
            } else {
                0.0
            }
            val explicitBonus = if (track.explicit == query.explicit) 4.0 else 0.0

            track to (similarity * 100 + albumBonus + explicitBonus - abs(track.duration - query.duration))
        }.maxByOrNull { it.second }?.first
    }

    fun searchQueries(query: SourceSongQuery): List<String> = listOf(
        "${query.artists.firstOrNull().orEmpty()} ${query.title}",
        "${query.artists.firstOrNull().orEmpty()} ${canonicalTitle(query.title, stripContext = true)}",
        canonicalTitle(query.title, stripContext = true),
    ).map(String::trim).filter(String::isNotBlank).distinctBy(::normalized)

    private fun remixName(title: String, version: String = ""): String {
        val label = (listOf(version) + titleAnnotationsRegex.findAll(title).map { it.value }.toList() + title.substringAfterLast(" - ", ""))
            .firstOrNull { "remix" in versionTags(it) }.orEmpty()

        return normalized(label).split(' ').filterNot { it in setOf("remix", "radio", "edit", "extended", "version") }.joinToString(" ")
    }

    private fun artistMatches(artists: List<String>, performer: String): Boolean {
        val full = normalized(performer)
        val credits = performer.split(artistCreditsRegex).map(::normalized)

        return artists.any { normalized(it) == full || normalized(it) in credits }
    }

    private fun canonicalTitle(value: String, stripContext: Boolean = false): String {
        val withoutAnnotations = value.replace(soundtrackAnnotationRegex, " ").replace(titleAnnotationsRegex) { match ->
            val content =
                normalized(match.groupValues.drop(1).firstOrNull(String::isNotBlank).orEmpty())

            val words = content.split(' ')

            if ((stripContext && words.none { it in editionWords }) || editorialWords.any {
                    it in words
                } || versionTags(content).isNotEmpty() ||
                content.startsWith("feat ") ||
                content.startsWith("ft ")
            ) {
                " "
            } else {
                match.value
            }
        }.replace(featuredArtistRegex, " ")
            .replace(editorialSuffixRegex, " ")

        return normalized(withoutAnnotations)
    }

    private fun versionTags(value: String): Set<String> {
        val words = normalized(value.replace(soundtrackAnnotationRegex, " ")).split(' ').toSet()

        return buildSet {
            for (tag in listOf(
                "live",
                "acoustic",
                "remix",
                "instrumental",
                "karaoke",
                "demo",
                "nightcore",
                "slowed",
            )) {
                if (tag in words) add(tag)
            }

            if (words.any { it.startsWith("remaster") }) add("remaster")

            if ("sped" in words) add("sped")
        }
    }

    private fun titleSimilarity(first: String, second: String): Double {
        if (first.isBlank() || second.isBlank()) return 0.0

        if (first == second || first.replace(" ", "") == second.replace(" ", "")) return 1.0

        if (abs(first.length - second.length) > 2) return 0.0
        var previous = IntArray(second.length + 1) { it }
        var next = IntArray(second.length + 1)

        for (i in first.indices) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Source matching interrupted")

            next[0] = i + 1

            for (j in second.indices) {
                next[j + 1] =
                    minOf(
                        next[j] + 1,
                        previous[j + 1] + 1,
                        previous[j] + if (first[i] == second[j]) 0 else 1,
                    )
            }
            val reusable = previous
            previous = next
            next = reusable
        }

        return 1.0 - previous.last().toDouble() / maxOf(first.length, second.length)
    }

    private fun normalized(value: String): String = FuzzyMatcher.normalize(value)

    private val editorialWords =
        setOf("official", "audio", "video", "lyrics", "lyric", "visualizer", "visualiser", "hd", "hq", "4k")

    private val editionWords = setOf("part", "pt", "chapter", "vol", "volume", "reprise", "edit", "mix", "version", "take", "mono", "stereo")
}
