package de.test.podcini.util.syndication.feedgenerator

import ac.mdiq.podcini.net.feed.parser.FeedHandler
import android.util.Xml
import ac.mdiq.podcini.util.MiscFormatter.formatRfc822Date
import ac.mdiq.podcini.storage.model.Feed
import de.test.podcini.util.syndication.feedgenerator.GeneratorUtil.addPaymentLink
import java.io.IOException
import java.io.OutputStream

/**
 * Creates RSS 2.0 feeds. See FeedGenerator for more information.
 */
class Rss2Generator : FeedGenerator {
    @Throws(IOException::class)
    override fun writeFeed(feed: Feed?, outputStream: OutputStream?, encoding: String?, flags: Long) {
        requireNotNull(feed) { "feed = null" }
        requireNotNull(outputStream) { "outputStream = null" }

        val xml = Xml.newSerializer()
        xml.setOutput(outputStream, encoding)
        xml.startDocument(encoding, null)

        xml.setPrefix("atom", "http://www.w3.org/2005/Atom")
        xml.startTag(null, "rss")
        xml.attribute(null, "version", "2.0")
        xml.startTag(null, "channel")

        // Write Feed data
        if (feed.title != null) {
            xml.startTag(null, "title")
            xml.text(feed.title)
            xml.endTag(null, "title")
        }
        if (feed.description != null) {
            xml.startTag(null, "description")
            xml.text(feed.description)
            xml.endTag(null, "description")
        }
        if (feed.link != null) {
            xml.startTag(null, "link")
            xml.text(feed.link)
            xml.endTag(null, "link")
        }
        if (feed.language != null) {
            xml.startTag(null, "language")
            xml.text(feed.language)
            xml.endTag(null, "language")
        }
        if (feed.imageUrl != null) {
            xml.startTag(null, "image")
            xml.startTag(null, "url")
            xml.text(feed.imageUrl)
            xml.endTag(null, "url")
            xml.endTag(null, "image")
        }

        val fundingList = feed.paymentLinks
        if (fundingList.isNotEmpty()) {
            for (funding in fundingList) {
                addPaymentLink(xml, funding.url, true)
            }
        }

        // Write FeedItem data
        if (feed.episodes.isNotEmpty()) {
            for (item in feed.episodes) {
                xml.startTag(null, "item")

                if (item.title != null) {
                    xml.startTag(null, "title")
                    xml.text(item.title)
                    xml.endTag(null, "title")
                }
                if (item.description != null) {
                    xml.startTag(null, "description")
                    xml.text(item.description)
                    xml.endTag(null, "description")
                }
                if (item.link != null) {
                    xml.startTag(null, "link")
                    xml.text(item.link)
                    xml.endTag(null, "link")
                }
                if (item.getPubDate() != null) {
                    xml.startTag(null, "pubDate")
                    xml.text(formatRfc822Date(item.getPubDate()))
                    xml.endTag(null, "pubDate")
                }
                if ((flags and FEATURE_WRITE_GUID) != 0L) {
                    xml.startTag(null, "guid")
                    xml.text(item.identifier)
                    xml.endTag(null, "guid")
                }
                if (item.media != null) {
                    xml.startTag(null, "enclosure")
                    xml.attribute(null, "url", item.media!!.downloadUrl)
                    xml.attribute(null, "length", item.media!!.size.toString())
                    xml.attribute(null, "type", item.media!!.mimeType)
                    xml.endTag(null, "enclosure")
                }
                if (fundingList.isNotEmpty()) {
                    for (funding in fundingList) {
                        xml.startTag(FeedHandler.PodcastIndex.NSTAG, "funding")
                        xml.attribute(FeedHandler.PodcastIndex.NSTAG, "url", funding.url)
                        xml.text(funding.content)
                        addPaymentLink(xml, funding.url, true)
                        xml.endTag(FeedHandler.PodcastIndex.NSTAG, "funding")
                    }
                }

                xml.endTag(null, "item")
            }
        }

        xml.endTag(null, "channel")
        xml.endTag(null, "rss")

        xml.endDocument()
    }

    companion object {
        const val FEATURE_WRITE_GUID: Long = 1
    }
}
