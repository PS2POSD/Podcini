package ac.mdiq.podcini.storage.model

import ac.mdiq.podcini.net.download.DownloadStatus
import ac.mdiq.podcini.playback.base.InTheatre.curQueue
import ac.mdiq.podcini.playback.base.InTheatre.isCurrentlyPlaying
import ac.mdiq.podcini.storage.database.Feeds.getFeed
import ac.mdiq.vista.extractor.Vista
import ac.mdiq.vista.extractor.stream.StreamInfo
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import io.realm.kotlin.ext.realmListOf
import io.realm.kotlin.ext.realmSetOf
import io.realm.kotlin.types.RealmList
import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.RealmSet
import io.realm.kotlin.types.annotations.FullText
import io.realm.kotlin.types.annotations.Ignore
import io.realm.kotlin.types.annotations.Index
import io.realm.kotlin.types.annotations.PrimaryKey
import org.apache.commons.lang3.builder.ToStringBuilder
import org.apache.commons.lang3.builder.ToStringStyle
import java.util.*

/**
 * Episode within a feed.
 */
class Episode : RealmObject {
    @PrimaryKey
    var id: Long = 0L   // increments from Date().time * 100 at time of creation

    /**
     * The id/guid that can be found in the rss/atom feed. Might not be set.
     */
    @Index
    var identifier: String? = null

    @FullText
    var title: String? = null

    @FullText
    var description: String? = null

    @FullText
    var transcript: String? = null

    var link: String? = null

    @get:JvmName("getPubDateProperty")
    @set:JvmName("setPubDateProperty")
    var pubDate: Long = 0

    @set:JvmName("setMediaProperty")
    var media: EpisodeMedia? = null

//    val feedlink: RealmResults<Feed> by backlinks(Feed::episodes)

    @Ignore
    var feed: Feed? = null
        get() {
            if (field == null && feedId != null) field = getFeed(feedId!!)
            return field
        }

    var feedId: Long? = null

    var podcastIndexChapterUrl: String? = null

    var playState: Int

    var paymentLink: String? = null

    /**
     * Returns the image of this item, as specified in the feed.
     * To load the image that can be displayed to the user, use [.getImageLocation],
     * which also considers embedded pictures or the feed picture if no other picture is present.
     */
    var imageUrl: String? = null

    var isAutoDownloadEnabled: Boolean = true
        private set

    var tags: RealmSet<String> = realmSetOf()

    /**
     * The list of chapters of this item. This might be null even if there are chapters of this item
     * in the database. The 'hasChapters' attribute should be used to check if this item has any chapters.
     */
    var chapters: RealmList<Chapter> = realmListOf()

    var isFavorite: Boolean = false

    @Ignore
    val isNew: Boolean
        get() = playState == PlayState.NEW.code

    @Ignore
    val isInProgress: Boolean
        get() = (media != null && media!!.isInProgress)

    @Ignore
    val isDownloaded: Boolean
        get() = media != null && media!!.downloaded

    /**
     * Returns the value that uniquely identifies this FeedItem. If the
     * itemIdentifier attribute is not null, it will be returned. Else it will
     * try to return the title. If the title is not given, it will use the link
     * of the entry.
     */
    @Ignore
    val identifyingValue: String?
        get() = when {
            !identifier.isNullOrEmpty() -> identifier
            !title.isNullOrEmpty() -> title
            media?.downloadUrl != null -> media!!.downloadUrl
            else -> link
        }

    @Ignore
    val imageLocation: String?
        get() = when {
            imageUrl != null -> imageUrl
            media != null && media?.hasEmbeddedPicture() == true -> EpisodeMedia.FILENAME_PREFIX_EMBEDDED_COVER + media!!.getLocalMediaUrl()
            feed != null -> {
                feed!!.imageUrl
            }
            else -> null
        }

    @Ignore
    var streamInfo: StreamInfo? = null
        get() {
            if (field == null) {
                if (media?.downloadUrl == null) return null
                field = StreamInfo.getInfo(Vista.getService(0), media!!.downloadUrl!!)
            }
            return field
        }

    @Ignore
    val inQueueState = mutableStateOf(curQueue.contains(this))

    @Ignore
    val isPlayingState = mutableStateOf(isCurrentlyPlaying(media))

    @Ignore
    val downloadState = mutableIntStateOf(if (media?.downloaded == true) DownloadStatus.State.COMPLETED.ordinal else DownloadStatus.State.UNKNOWN.ordinal)

    @Ignore
    val stopMonitoring = mutableStateOf(false)

    constructor() {
        this.playState = PlayState.UNPLAYED.code
    }

    /**
     * This constructor should be used for creating test objects.
     */
    constructor(id: Long, title: String?, itemIdentifier: String?, link: String?, pubDate: Date?, state: Int, feed: Feed?) {
        this.id = id
        this.title = title
        this.identifier = itemIdentifier
        this.link = link
        this.pubDate = pubDate?.time ?: 0
        this.playState = state
        if (feed != null) this.feedId = feed.id
        this.feed = feed
    }

    fun copyStates(other: Episode) {
        inQueueState.value = other.inQueueState.value
        isPlayingState.value = other.isPlayingState.value
        downloadState.value = other.downloadState.value
        stopMonitoring.value = other.stopMonitoring.value
    }

    fun updateFromOther(other: Episode) {
        if (other.imageUrl != null) this.imageUrl = other.imageUrl
        if (other.title != null) title = other.title
        if (other.description != null) description = other.description
        if (other.link != null) link = other.link
        if (other.pubDate != 0L && other.pubDate != pubDate) pubDate = other.pubDate

        if (other.media != null) {
            when {
                media == null -> {
                    setMedia(other.media)
                    // reset to new if feed item did link to a file before
                    setNew()
                }
                media!!.compareWithOther(other.media!!) -> media!!.updateFromOther(other.media!!)
            }
        }
        if (other.paymentLink != null) paymentLink = other.paymentLink
        if (other.chapters.isNotEmpty()) {
            chapters.clear()
            chapters.addAll(other.chapters)
        }
        if (other.podcastIndexChapterUrl != null) podcastIndexChapterUrl = other.podcastIndexChapterUrl
    }

    @JvmName("getPubDateFunction")
    fun getPubDate(): Date? {
        return if (pubDate > 0) Date(pubDate) else null
    }

    @JvmName("setPubDateFunction")
    fun setPubDate(pubDate: Date?) {
        if (pubDate != null) this.pubDate = pubDate.time
        else this.pubDate = 0
    }

    /**
     * Sets the media object of this FeedItem. If the given
     * EpisodeMedia object is not null, it's 'item'-attribute value
     * will also be set to this item.
     */
    @JvmName("setMediaFunction")
    fun setMedia(media: EpisodeMedia?) {
        this.media = media
    }

    fun setNew() {
        playState = PlayState.NEW.code
    }

    fun isPlayed(): Boolean {
        return playState == PlayState.PLAYED.code
    }

    fun setPlayed(played: Boolean) {
        playState = if (played) PlayState.PLAYED.code else PlayState.UNPLAYED.code
    }

    fun setBuilding() {
        playState = PlayState.BUILDING.code
    }

    /**
     * Updates this item's description property if the given argument is longer than the already stored description
     * @param newDescription The new item description, content:encoded, itunes:description, etc.
     */
    fun setDescriptionIfLonger(newDescription: String?) {
        if (newDescription.isNullOrEmpty()) return
        when {
            this.description == null -> this.description = newDescription
            description!!.length < newDescription.length -> this.description = newDescription
        }
    }

    fun setTranscriptIfLonger(newTranscript: String?) {
        if (newTranscript.isNullOrEmpty()) return
        when {
            this.transcript == null -> this.transcript = newTranscript
            transcript!!.length < newTranscript.length -> this.transcript = newTranscript
        }
    }

    /**
     * Get the link for the feed item for the purpose of Share. It fallbacks to
     * use the feed's link if the named feed item has no link.
     */
    fun getLinkWithFallback(): String? {
        return when {
            link.isNullOrBlank() -> link
            !feed?.link.isNullOrEmpty() -> feed!!.link
            else -> null
        }
    }

    fun disableAutoDownload() {
        this.isAutoDownloadEnabled = false
    }

    override fun toString(): String {
        return ToStringBuilder.reflectionToString(this, ToStringStyle.SHORT_PREFIX_STYLE)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Episode) return false
        return id == other.id
    }

    override fun hashCode(): Int {
        val result = (id xor (id ushr 32)).toInt()
        return result
    }

    enum class PlayState(val code: Int) {
        UNSPECIFIED(-2),
        NEW(-1),
        UNPLAYED(0),
        PLAYED(1),
        BUILDING(2),
        ABANDONED(3)
    }
    companion object {
        val TAG: String = Episode::class.simpleName ?: "Anonymous"
    }
}
