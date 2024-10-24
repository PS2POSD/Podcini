package ac.mdiq.podcini.storage.model

import ac.mdiq.podcini.R
import ac.mdiq.podcini.storage.database.Queues.inAnyQueue
import ac.mdiq.podcini.storage.model.MediaType.Companion.AUDIO_APPLICATION_MIME_STRINGS
import java.io.Serializable

class EpisodeFilter(vararg properties_: String) : Serializable {
    val properties: HashSet<String> = setOf(*properties_).filter { it.isNotEmpty() }.map {it.trim()}.toHashSet()

    val showPlayed: Boolean = properties.contains(States.played.name)
    val showUnplayed: Boolean = properties.contains(States.unplayed.name)
    val showNew: Boolean = properties.contains(States.new.name)
    val showQueued: Boolean = properties.contains(States.queued.name)
    val showNotQueued: Boolean = properties.contains(States.not_queued.name)
    val showDownloaded: Boolean = properties.contains(States.downloaded.name)
    val showNotDownloaded: Boolean = properties.contains(States.not_downloaded.name)
    val showIsFavorite: Boolean = properties.contains(States.is_favorite.name)
    val showNotFavorite: Boolean = properties.contains(States.not_favorite.name)

    constructor(properties: String) : this(*(properties.split(",").toTypedArray()))

//    fun matches(item: Episode): Boolean {
//        when {
//            showNew && !item.isNew -> return false
//            showPlayed && item.playState < PlayState.PLAYED.code -> return false
//            showUnplayed && item.playState >= PlayState.PLAYED.code -> return false
//            properties.contains(States.paused.name) && !item.isInProgress -> return false
//            properties.contains(States.not_paused.name) && item.isInProgress -> return false
//            showDownloaded && !item.isDownloaded -> return false
//            showNotDownloaded && item.isDownloaded -> return false
//            properties.contains(States.auto_downloadable.name) && !item.isAutoDownloadEnabled -> return false
//            properties.contains(States.not_auto_downloadable.name) && item.isAutoDownloadEnabled -> return false
//            properties.contains(States.has_media.name) && item.media == null -> return false
//            properties.contains(States.no_media.name) && item.media != null -> return false
//            properties.contains(States.has_comments.name) && item.comment.isEmpty() -> return false
//            properties.contains(States.no_comments.name) && item.comment.isNotEmpty() -> return false
//            showIsFavorite && !item.isFavorite -> return false
//            showNotFavorite && item.isFavorite -> return false
//            showQueued && !inAnyQueue(item) -> return false
//            showNotQueued && inAnyQueue(item) -> return false
//            else -> return true
//        }
//    }

//    filter on queues does not have a query string so it's not applied on query results, need to filter separately
    fun matchesForQueues(item: Episode): Boolean {
        return when {
            showQueued && !inAnyQueue(item) -> false
            showNotQueued && inAnyQueue(item) -> false
            else -> true
        }
    }

    fun queryString(): String {
        val statements: MutableList<String> = mutableListOf()
        when {
            showPlayed -> statements.add("playState >= ${PlayState.PLAYED.code}")
            showUnplayed -> statements.add(" playState < ${PlayState.PLAYED.code} ") // Match "New" items (read = -1) as well
            showNew -> statements.add("playState == -1 ")
        }

        val mediaTypeQuerys = mutableListOf<String>()
        if (properties.contains(States.unknown.name)) mediaTypeQuerys.add(" media == nil OR media.mimeType == nil OR media.mimeType == '' ")
        if (properties.contains(States.audio.name)) mediaTypeQuerys.add(" media.mimeType BEGINSWITH 'audio' ")
        if (properties.contains(States.video.name)) mediaTypeQuerys.add(" media.mimeType BEGINSWITH 'video' ")
        if (properties.contains(States.audio_app.name)) mediaTypeQuerys.add(" media.mimeType IN ${AUDIO_APPLICATION_MIME_STRINGS.toList()} ")
        if (mediaTypeQuerys.isNotEmpty()) {
            val query = StringBuilder(" (" + mediaTypeQuerys[0])
            if (mediaTypeQuerys.size > 1) for (r in statements.subList(1, mediaTypeQuerys.size)) {
                query.append(" OR ")
                query.append(r)
            }
            query.append(") ")
            statements.add(query.toString())
        }

        val ratingQuerys = mutableListOf<String>()
        if (properties.contains(States.unrated.name)) ratingQuerys.add(" rating == ${Rating.UNRATED.code} ")
        if (properties.contains(States.trash.name)) ratingQuerys.add(" rating == ${Rating.TRASH.code} ")
        if (properties.contains(States.bad.name)) ratingQuerys.add(" rating == ${Rating.BAD.code} ")
        if (properties.contains(States.neutral.name)) ratingQuerys.add(" rating == ${Rating.NEUTRAL.code} ")
        if (properties.contains(States.good.name)) ratingQuerys.add(" rating == ${Rating.GOOD.code} ")
        if (properties.contains(States.favorite.name)) ratingQuerys.add(" rating == ${Rating.FAVORITE.code} ")
        if (ratingQuerys.isNotEmpty()) {
            val query = StringBuilder(" (" + ratingQuerys[0])
            if (ratingQuerys.size > 1) for (r in statements.subList(1, ratingQuerys.size)) {
                query.append(" OR ")
                query.append(r)
            }
            query.append(") ")
            statements.add(query.toString())
        }

        val stateQuerys = mutableListOf<String>()
        if (properties.contains(States.unspecified.name)) stateQuerys.add(" playState == ${PlayState.UNSPECIFIED.code} ")
        if (properties.contains(States.building.name)) stateQuerys.add(" playState == ${PlayState.BUILDING.code} ")
        if (properties.contains(States.new.name)) stateQuerys.add(" playState == ${PlayState.NEW.code} ")
        if (properties.contains(States.unplayed.name)) stateQuerys.add(" playState == ${PlayState.UNPLAYED.code} ")
        if (properties.contains(States.later.name)) stateQuerys.add(" playState == ${PlayState.LATER.code} ")
        if (properties.contains(States.soon.name)) stateQuerys.add(" playState == ${PlayState.SOON.code} ")
        if (properties.contains(States.inQueue.name)) stateQuerys.add(" playState == ${PlayState.INQUEUE.code} ")
        if (properties.contains(States.inProgress.name)) stateQuerys.add(" playState == ${PlayState.INPROGRESS.code} ")
        if (properties.contains(States.skipped.name)) stateQuerys.add(" playState == ${PlayState.SKIPPED.code} ")
        if (properties.contains(States.played.name)) stateQuerys.add(" playState == ${PlayState.PLAYED.code} ")
        if (properties.contains(States.ignored.name)) stateQuerys.add(" playState == ${PlayState.IGNORED.code} ")
        if (stateQuerys.isNotEmpty()) {
            val query = StringBuilder(" (" + stateQuerys[0])
            if (stateQuerys.size > 1) for (r in statements.subList(1, stateQuerys.size)) {
                query.append(" OR ")
                query.append(r)
            }
            query.append(") ")
            statements.add(query.toString())
        }

        when {
            properties.contains(States.paused.name) -> statements.add(" media.position > 0 ")
            properties.contains(States.not_paused.name) -> statements.add(" media.position == 0 ")
        }
//        when {
//            showQueued -> statements.add("$keyItemId IN (SELECT $keyFeedItem FROM $tableQueue) ")
//            showNotQueued -> statements.add("$keyItemId NOT IN (SELECT $keyFeedItem FROM $tableQueue) ")
//        }
        when {
            showDownloaded -> statements.add("media.downloaded == true ")
            showNotDownloaded -> statements.add("media.downloaded == false ")
        }
        when {
            properties.contains(States.auto_downloadable.name) -> statements.add("isAutoDownloadEnabled == true ")
            properties.contains(States.not_auto_downloadable.name) -> statements.add("isAutoDownloadEnabled == false ")
        }
        when {
            properties.contains(States.has_media.name) -> statements.add("media != nil ")
            properties.contains(States.no_media.name) -> statements.add("media == nil ")
        }
        when {
            properties.contains(States.has_chapters.name) -> statements.add("chapters.@count > 0 ")
            properties.contains(States.no_chapters.name) -> statements.add("chapters.@count == 0 ")
        }
        when {
            properties.contains(States.has_comments.name) -> statements.add(" comment != '' ")
            properties.contains(States.no_comments.name) -> statements.add(" comment == '' ")
        }
        when {
            showIsFavorite -> statements.add("rating == ${Rating.FAVORITE.code} ")
            showNotFavorite -> statements.add("rating != ${Rating.FAVORITE.code} ")
        }

        if (statements.isEmpty()) return "id > 0"
        val query = StringBuilder(" (" + statements[0])
        if (statements.size > 1)  for (r in statements.subList(1, statements.size)) {
            query.append(" AND ")
            query.append(r)
        }
        query.append(") ")
        return query.toString()
    }

    @Suppress("EnumEntryName")
    enum class States {
        unspecified,
        building,
        new,
        unplayed,
        later,
        soon,
        inQueue,
        inProgress,
        skipped,
        played,
        ignored,
        has_chapters,
        no_chapters,
        audio,
        video,
        unknown,
        audio_app,
        paused,
        not_paused,
        is_favorite,
        not_favorite,
        has_media,
        no_media,
        has_comments,
        no_comments,
        queued,
        not_queued,
        downloaded,
        not_downloaded,
        auto_downloadable,
        not_auto_downloadable,
        unrated,
        trash,
        bad,
        neutral,
        good,
        favorite,
    }

    enum class EpisodesFilterGroup(val nameRes: Int, vararg values: ItemProperties) {
//        PLAYED(ItemProperties(R.string.hide_played_episodes_label, States.played.name), ItemProperties(R.string.not_played, States.unplayed.name)),
//        PAUSED(ItemProperties(R.string.hide_paused_episodes_label, States.paused.name), ItemProperties(R.string.not_paused, States.not_paused.name)),
//        FAVORITE(ItemProperties(R.string.hide_is_favorite_label, States.is_favorite.name), ItemProperties(R.string.not_favorite, States.not_favorite.name)),
        MEDIA(R.string.has_media, ItemProperties(R.string.yes, States.has_media.name), ItemProperties(R.string.no, States.no_media.name)),
        RATING(R.string.rating_label, ItemProperties(R.string.unrated, States.unrated.name),
            ItemProperties(R.string.trash, States.trash.name),
            ItemProperties(R.string.bad, States.bad.name),
            ItemProperties(R.string.neutral, States.neutral.name),
            ItemProperties(R.string.good, States.good.name),
            ItemProperties(R.string.favorite, States.favorite.name),
        ),
        PLAY_STATE(R.string.playstate, ItemProperties(R.string.unspecified, States.unspecified.name),
            ItemProperties(R.string.building, States.building.name),
            ItemProperties(R.string.new_label, States.new.name),
            ItemProperties(R.string.unplayed, States.unplayed.name),
            ItemProperties(R.string.later, States.later.name),
            ItemProperties(R.string.soon, States.soon.name),
            ItemProperties(R.string.in_queue, States.inQueue.name),
            ItemProperties(R.string.in_progress, States.inProgress.name),
            ItemProperties(R.string.skipped, States.skipped.name),
            ItemProperties(R.string.played, States.played.name),
            ItemProperties(R.string.ignored, States.ignored.name),
        ),
        OPINION(R.string.has_comments, ItemProperties(R.string.yes, States.has_comments.name), ItemProperties(R.string.no, States.no_comments.name)),
//        QUEUED(ItemProperties(R.string.queued_label, States.queued.name), ItemProperties(R.string.not_queued_label, States.not_queued.name)),
        DOWNLOADED(R.string.downloaded_label, ItemProperties(R.string.yes, States.downloaded.name), ItemProperties(R.string.no, States.not_downloaded.name)),
        CHAPTERS(R.string.has_chapters, ItemProperties(R.string.yes, States.has_chapters.name), ItemProperties(R.string.no, States.no_chapters.name)),
        MEDIA_TYPE(R.string.media_type, ItemProperties(R.string.unknown, States.unknown.name),
            ItemProperties(R.string.audio, States.audio.name),
            ItemProperties(R.string.video, States.video.name),
            ItemProperties(R.string.audio_app, States.audio_app.name)
        ),
        AUTO_DOWNLOADABLE(R.string.auto_downloadable_label, ItemProperties(R.string.yes, States.auto_downloadable.name), ItemProperties(R.string.no, States.not_auto_downloadable.name));

        @JvmField
        val values: Array<ItemProperties> = arrayOf(*values)

        class ItemProperties(@JvmField val displayName: Int, @JvmField val filterId: String)
    }

    companion object {
        @JvmStatic
        fun unfiltered(): EpisodeFilter {
            return EpisodeFilter("")
        }
    }
}
