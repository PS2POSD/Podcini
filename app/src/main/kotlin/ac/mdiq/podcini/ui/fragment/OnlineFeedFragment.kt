package ac.mdiq.podcini.ui.fragment

import ac.mdiq.podcini.R
import ac.mdiq.podcini.databinding.EditTextDialogBinding
import ac.mdiq.podcini.databinding.OnlineFeedviewFragmentBinding
import ac.mdiq.podcini.net.download.DownloadError
import ac.mdiq.podcini.net.download.service.DownloadRequestCreator.create
import ac.mdiq.podcini.net.download.service.DownloadServiceInterface
import ac.mdiq.podcini.net.download.service.Downloader
import ac.mdiq.podcini.net.download.service.HttpDownloader
import ac.mdiq.podcini.net.feed.FeedUrlNotFoundException
import ac.mdiq.podcini.net.feed.discovery.CombinedSearcher
import ac.mdiq.podcini.net.feed.discovery.PodcastSearcherRegistry
import ac.mdiq.podcini.net.feed.parser.FeedHandler
import ac.mdiq.podcini.net.utils.HtmlToPlainText
import ac.mdiq.podcini.net.utils.UrlChecker.prepareUrl
import ac.mdiq.podcini.preferences.UserPreferences.isEnableAutodownload
import ac.mdiq.podcini.storage.database.Episodes.episodeFromStreamInfoItem
import ac.mdiq.podcini.storage.database.Feeds.getFeed
import ac.mdiq.podcini.storage.database.Feeds.getFeedList
import ac.mdiq.podcini.storage.database.Feeds.persistFeedPreferences
import ac.mdiq.podcini.storage.database.Feeds.updateFeed
import ac.mdiq.podcini.storage.model.*
import ac.mdiq.podcini.storage.utils.FilesUtils.feedfilePath
import ac.mdiq.podcini.storage.utils.FilesUtils.getFeedfileName
import ac.mdiq.podcini.ui.activity.MainActivity
import ac.mdiq.podcini.ui.dialog.AuthenticationDialog
import ac.mdiq.podcini.ui.utils.ThemeUtils.getColorFromAttr
import ac.mdiq.podcini.util.EventFlow
import ac.mdiq.podcini.util.FlowEvent
import ac.mdiq.podcini.util.Logd
import ac.mdiq.podcini.util.error.DownloadErrorLabel.from
import ac.mdiq.vista.extractor.InfoItem
import ac.mdiq.vista.extractor.Vista
import ac.mdiq.vista.extractor.channel.ChannelInfo
import ac.mdiq.vista.extractor.channel.tabs.ChannelTabInfo
import ac.mdiq.vista.extractor.exceptions.ExtractionException
import ac.mdiq.vista.extractor.playlist.PlaylistInfo
import ac.mdiq.vista.extractor.stream.StreamInfoItem
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.graphics.LightingColorFilter
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.annotation.UiThread
import androidx.collection.ArrayMap
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.realm.kotlin.ext.realmListOf
import io.realm.kotlin.types.RealmList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.Volatile

/**
 * Downloads a feed from a feed URL and parses it. Subclasses can display the
 * feed object that was parsed. This activity MUST be started with a given URL
 * or an Exception will be thrown.
 *
 * If the feed cannot be downloaded or parsed, an error dialog will be displayed
 * and the activity will finish as soon as the error dialog is closed.
 */
@OptIn(UnstableApi::class)
class OnlineFeedFragment : Fragment() {
    private var _binding: OnlineFeedviewFragmentBinding? = null
    private val binding get() = _binding!!

    private var displayUpArrow = false

    var feedSource: String = ""
    var feedUrl: String = ""

    private val feedId: Long
        get() {
            if (feeds == null) return 0
            for (f in feeds!!) {
                if (f.downloadUrl == selectedDownloadUrl) return f.id
            }
            return 0
        }

    @Volatile
    private var feeds: List<Feed>? = null
    private var selectedDownloadUrl: String? = null
    private var downloader: Downloader? = null
    private var username: String? = null
    private var password: String? = null

    private var isPaused = false
    private var didPressSubscribe = false
    private var isFeedFoundBySearch = false

    private var dialog: Dialog? = null

    @OptIn(UnstableApi::class) override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Logd(TAG, "fragment onCreateView")
        _binding = OnlineFeedviewFragmentBinding.inflate(layoutInflater)
        binding.closeButton.visibility = View.INVISIBLE
        binding.card.setOnClickListener(null)
        binding.card.setCardBackgroundColor(getColorFromAttr(requireContext(), com.google.android.material.R.attr.colorSurface))

        displayUpArrow = parentFragmentManager.backStackEntryCount != 0
        if (savedInstanceState != null) displayUpArrow = savedInstanceState.getBoolean(KEY_UP_ARROW)
        (activity as MainActivity).setupToolbarToggle(binding.toolbar, displayUpArrow)

        feedUrl = requireArguments().getString(ARG_FEEDURL) ?: ""
        Logd(TAG, "feedUrl: $feedUrl")
        if (feedUrl.isEmpty()) {
            Log.e(TAG, "feedUrl is null.")
            showNoPodcastFoundError()
        } else {
            Logd(TAG, "Activity was started with url $feedUrl")
            setLoadingLayout()
            // Remove subscribeonandroid.com from feed URL in order to subscribe to the actual feed URL
            if (feedUrl.contains("subscribeonandroid.com")) feedUrl = feedUrl.replaceFirst("((www.)?(subscribeonandroid.com/))".toRegex(), "")
            if (savedInstanceState != null) {
                username = savedInstanceState.getString("username")
                password = savedInstanceState.getString("password")
            }
            lookupUrlAndBuild(feedUrl)
        }
        return binding.root
    }

    /**
     * Displays a progress indicator.
     */
    private fun setLoadingLayout() {
        binding.progressBar.visibility = View.VISIBLE
        binding.feedDisplayContainer.visibility = View.GONE
    }

    override fun onStart() {
        super.onStart()
        isPaused = false
        procFlowEvents()
    }

    override fun onStop() {
        super.onStop()
        isPaused = true
        cancelFlowEvents()
        if (downloader != null && !downloader!!.isFinished) downloader!!.cancel()
        if (dialog != null && dialog!!.isShowing) dialog!!.dismiss()
    }

    override fun onDestroy() {
        _binding = null
        feeds = null
        super.onDestroy()
    }

    @OptIn(UnstableApi::class) override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_UP_ARROW, displayUpArrow)
        super.onSaveInstanceState(outState)
        outState.putString("username", username)
        outState.putString("password", password)
    }

    private fun lookupUrlAndBuild(url: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val urlString = PodcastSearcherRegistry.lookupUrl1(url)
            try { startFeedBuilding(urlString)
            } catch (e: FeedUrlNotFoundException) { tryToRetrieveFeedUrlBySearch(e)
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
                withContext(Dispatchers.Main) { showNoPodcastFoundError() }
            }
        }
    }

    private fun tryToRetrieveFeedUrlBySearch(error: FeedUrlNotFoundException) {
        Logd(TAG, "Unable to retrieve feed url, trying to retrieve feed url from search")
//        val url = searchFeedUrlByTrackName(error.trackName, error.artistName)
        lifecycleScope.launch(Dispatchers.IO) {
            var url: String? = null
            val searcher = CombinedSearcher()
            val query = "${error.trackName} ${error.artistName}"
            val results = searcher.search(query)
            if (results.isEmpty()) return@launch
            for (result in results) {
                if (result.feedUrl != null && result.author != null && result.author.equals(error.artistName, ignoreCase = true)
                        && result.title.equals(error.trackName, ignoreCase = true)) {
                    url = result.feedUrl
                    break
                }
            }
            if (url != null) {
                Logd(TAG, "Successfully retrieve feed url")
                isFeedFoundBySearch = true
                startFeedBuilding(url)
            } else {
                showNoPodcastFoundError()
                Logd(TAG, "Failed to retrieve feed url")
            }
        }
    }

//    private fun searchFeedUrlByTrackName(trackName: String, artistName: String): String? {
//        val searcher = CombinedSearcher()
//        val query = "$trackName $artistName"
//        val results = searcher.search(query).blockingGet()
//        if (results.isNullOrEmpty()) return null
//        for (result in results) {
//            if (result?.feedUrl != null && result.author != null && result.author.equals(artistName, ignoreCase = true)
//                    && result.title.equals(trackName, ignoreCase = true)) return result.feedUrl
//        }
//        return null
//    }

    private fun htmlOrXml(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        var type: String? = null
        try { type = connection.contentType } catch (e: IOException) {
            Log.e(TAG, "Error connecting to URL", e)
            showErrorDialog(e.message, "")
        } finally { connection.disconnect() }
        if (type == null) return null
        Logd(TAG, "connection type: $type")
        return when {
            type.contains("html", ignoreCase = true) -> "HTML"
            type.contains("xml", ignoreCase = true) -> "XML"
            else -> type
        }
    }

    private fun startFeedBuilding(url: String) {
        if (feedSource == "VistaGuide" || url.contains("youtube.com")) {
            feedSource = "VistaGuide"
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    feeds = getFeedList()
                    val service = try { Vista.getService("YouTube") } catch (e: ExtractionException) { throw ExtractionException("YouTube service not found") }
                    selectedDownloadUrl = prepareUrl(url)
                    val feed_ = Feed(selectedDownloadUrl, null)
                    feed_.id = Feed.newId()
                    feed_.type = Feed.FeedType.YOUTUBE.name
                    feed_.hasVideoMedia = true
                    feed_.fileUrl = File(feedfilePath, getFeedfileName(feed_)).toString()
                    val eList: RealmList<Episode> = realmListOf()

                    if (url.startsWith("https://youtube.com/playlist?") || url.startsWith("https://music.youtube.com/playlist?")) {
                        val playlistInfo = PlaylistInfo.getInfo(Vista.getService(0), url) ?: return@launch
                        feed_.title = playlistInfo.name
                        feed_.description = playlistInfo.description?.content ?: ""
                        feed_.author = playlistInfo.uploaderName
                        feed_.imageUrl = if (playlistInfo.thumbnails.isNotEmpty()) playlistInfo.thumbnails.first().url else null
                        var infoItems = playlistInfo.relatedItems
                        var nextPage = playlistInfo.nextPage
                        Logd(TAG, "infoItems: ${infoItems.size}")
                        while (infoItems.isNotEmpty()) {
                            for (r in infoItems) {
                                Logd(TAG, "startFeedBuilding relatedItem: $r")
                                if (r.infoType != InfoItem.InfoType.STREAM) continue
                                val e = episodeFromStreamInfoItem(r)
                                e.feed = feed_
                                e.feedId = feed_.id
                                eList.add(e)
                            }
                            if (nextPage == null || eList.size > 500) break
                            try {
                                val page = PlaylistInfo.getMoreItems(service, url, nextPage) ?: break
                                nextPage = page.nextPage
                                infoItems = page.items
                                Logd(TAG, "more infoItems: ${infoItems.size}")
                            } catch (e: Throwable) {
                                Logd(TAG, "PlaylistInfo.getMoreItems error: ${e.message}")
                                withContext(Dispatchers.Main) { showErrorDialog(e.message, "") }
                                break
                            }
                        }
                        feed_.episodes = eList
                        withContext(Dispatchers.Main) { showFeedInformation(feed_, mapOf()) }
                    } else {
                        val channelInfo = ChannelInfo.getInfo(service, url)
                        Logd(TAG, "startFeedBuilding result: $channelInfo ${channelInfo.tabs.size}")
                        if (channelInfo.tabs.isEmpty()) {
                            withContext(Dispatchers.Main) { showErrorDialog("Channel is empty", "") }
                            return@launch
                        }
                        try {
                            val channelTabInfo = ChannelTabInfo.getInfo(service, channelInfo.tabs.first())
                            Logd(TAG, "startFeedBuilding result1: $channelTabInfo ${channelTabInfo.relatedItems.size}")
                            feed_.title = channelInfo.name
                            feed_.description = channelInfo.description
                            feed_.author = channelInfo.parentChannelName
                            feed_.imageUrl = if (channelInfo.avatars.isNotEmpty()) channelInfo.avatars.first().url else null

                            var infoItems = channelTabInfo.relatedItems
                            var nextPage = channelTabInfo.nextPage
                            Logd(TAG, "infoItems: ${infoItems.size}")
                            while (infoItems.isNotEmpty()) {
                                for (r in infoItems) {
                                    Logd(TAG, "startFeedBuilding relatedItem: $r")
                                    if (r.infoType != InfoItem.InfoType.STREAM) continue
                                    val e = episodeFromStreamInfoItem(r as StreamInfoItem)
                                    e.feed = feed_
                                    e.feedId = feed_.id
                                    eList.add(e)
                                }
                                if (nextPage == null || eList.size > 200) break
                                try {
                                    val page = ChannelTabInfo.getMoreItems(service, channelInfo.tabs.first(), nextPage)
                                    nextPage = page.nextPage
                                    infoItems = page.items
                                    Logd(TAG, "more infoItems: ${infoItems.size}")
                                } catch (e: Throwable) {
                                    Logd(TAG, "ChannelTabInfo.getMoreItems error: ${e.message}")
                                    withContext(Dispatchers.Main) { showErrorDialog(e.message, "") }
                                    break
                                }
                            }
                            feed_.episodes = eList
                            withContext(Dispatchers.Main) { showFeedInformation(feed_, mapOf()) }
                        } catch (e: Throwable) {
                            Logd(TAG, "startFeedBuilding error1 ${e.message}")
                            withContext(Dispatchers.Main) { showErrorDialog(e.message, "") }
                        }
                    }
                } catch (e: Throwable) {
                    Logd(TAG, "startFeedBuilding error ${e.message}")
                    withContext(Dispatchers.Main) { showErrorDialog(e.message, "") }
                }
            }
            return
        }

//        handle normal podcast source
        when (val urlType = htmlOrXml(url)) {
            "HTML" -> {
                val doc = Jsoup.connect(url).get()
                val linkElements = doc.select("link[type=application/rss+xml]")
//                TODO: should show all as options
                for (element in linkElements) {
                    val rssUrl = element.attr("href")
                    Logd(TAG, "RSS URL: $rssUrl")
                    startFeedBuilding(rssUrl)
                    return
                }
            }
            "XML" -> {}
            else -> {
                Log.e(TAG, "unknown url type $urlType")
                showErrorDialog("unknown url type $urlType", "")
                return
            }
        }
        selectedDownloadUrl = prepareUrl(url)
        val request = create(Feed(selectedDownloadUrl, null))
            .withAuthentication(username, password)
            .withInitiatedByUser(true)
            .build()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                feeds = getFeedList()
                downloader = HttpDownloader(request)
                downloader?.call()
                val status = downloader?.result
                when {
                    request.destination == null || status == null -> return@launch
                    status.isSuccessful -> {
                        try {
                            val result = doParseFeed(request.destination)
                            if (result != null) withContext(Dispatchers.Main) { showFeedInformation(result.feed, result.alternateFeedUrls) }
                        } catch (e: Throwable) {
                            Logd(TAG, "Feed parser exception: " + Log.getStackTraceString(e))
                            withContext(Dispatchers.Main) { showErrorDialog(e.message, "") }
                        }
                    }
                    else -> withContext(Dispatchers.Main) {
                        when {
                            status.reason == DownloadError.ERROR_UNAUTHORIZED -> {
                                if (!isRemoving && !isPaused) {
                                    if (username != null && password != null)
                                        Toast.makeText(requireContext(), R.string.download_error_unauthorized, Toast.LENGTH_LONG).show()
                                    if (downloader?.downloadRequest?.source != null) {
                                        dialog = FeedViewAuthenticationDialog(requireContext(), R.string.authentication_notification_title, downloader!!.downloadRequest.source!!).create()
                                        dialog?.show()
                                    }
                                }
                            }
                            else -> showErrorDialog(getString(from(status.reason)), status.reasonDetailed)
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
                withContext(Dispatchers.Main) { showErrorDialog(e.message, "") }
            }
        }
    }

    private var eventSink: Job?     = null
    private var eventStickySink: Job? = null
    private fun cancelFlowEvents() {
        eventSink?.cancel()
        eventSink = null
        eventStickySink?.cancel()
        eventStickySink = null
    }
    @OptIn(UnstableApi::class) private fun procFlowEvents() {
        if (eventSink == null) eventSink = lifecycleScope.launch {
            EventFlow.events.collectLatest { event ->
                Logd(TAG, "Received event: ${event.TAG}")
                when (event) {
                    is FlowEvent.FeedListEvent -> onFeedListChanged(event)
                    else -> {}
                }
            }
        }
        if (eventStickySink == null) eventStickySink = lifecycleScope.launch {
            EventFlow.stickyEvents.collectLatest { event ->
                Logd(TAG, "Received sticky event: ${event.TAG}")
                when (event) {
                    is FlowEvent.EpisodeDownloadEvent -> handleUpdatedFeedStatus()
                    else -> {}
                }
            }
        }
    }

    private fun onFeedListChanged(event: FlowEvent.FeedListEvent) {
        lifecycleScope.launch {
            try {
                val feeds = withContext(Dispatchers.IO) { getFeedList() }
                withContext(Dispatchers.Main) {
                    this@OnlineFeedFragment.feeds = feeds
                    handleUpdatedFeedStatus()
                }
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
                withContext(Dispatchers.Main) { showErrorDialog(e.message, "") }
            }
        }
    }

    /**
     * Try to parse the feed.
     * @return  The FeedHandlerResult if successful.
     * Null if unsuccessful but we started another attempt.
     * @throws Exception If unsuccessful but we do not know a resolution.
     */
    @Throws(Exception::class)
    private fun doParseFeed(destination: String): FeedHandler.FeedHandlerResult? {
        val destinationFile = File(destination)
        return try {
            val feed = Feed(selectedDownloadUrl, null)
            feed.fileUrl = destination
            FeedHandler().parseFeed(feed)
        } catch (e: FeedHandler.UnsupportedFeedtypeException) {
            Logd(TAG, "Unsupported feed type detected")
            if ("html".equals(e.rootElement, ignoreCase = true)) {
                if (selectedDownloadUrl != null) {
//                    val doc = Jsoup.connect(selectedDownloadUrl).get()
//                    val linkElements = doc.select("link[type=application/rss+xml]")
//                    for (element in linkElements) {
//                        val rssUrl = element.attr("href")
//                        Log.d(TAG, "RSS URL: $rssUrl")
//                        val rc = destinationFile.delete()
//                        Log.d(TAG, "Deleted feed source file. Result: $rc")
//                        startFeedDownload(rssUrl)
//                        return null
//                    }
                    val dialogShown = showFeedDiscoveryDialog(destinationFile, selectedDownloadUrl!!)
                    if (dialogShown) null // Should not display an error message
                    else throw FeedHandler.UnsupportedFeedtypeException(getString(R.string.download_error_unsupported_type_html))
                } else null
            } else throw e
        } catch (e: Exception) {
            Log.e(TAG, Log.getStackTraceString(e))
            throw e
        } finally {
            val rc = destinationFile.delete()
            Logd(TAG, "Deleted feed source file. Result: $rc")
        }
    }

    /**
     * Called when feed parsed successfully.
     * This method is executed on the GUI thread.
     */
    @UnstableApi private fun showFeedInformation(feed: Feed, alternateFeedUrls: Map<String, String>) {
        binding.progressBar.visibility = View.GONE
        binding.feedDisplayContainer.visibility = View.VISIBLE
        if (isFeedFoundBySearch) {
            val resId = R.string.no_feed_url_podcast_found_by_search
            Snackbar.make(binding.root, resId, Snackbar.LENGTH_LONG).show()
        }
        binding.backgroundImage.colorFilter = LightingColorFilter(-0x7d7d7e, 0x000000)
        binding.episodeLabel.setOnClickListener { showEpisodes(feed.episodes)}
        if (!feed.imageUrl.isNullOrBlank()) {
            binding.coverImage.load(feed.imageUrl) {
                placeholder(R.color.light_gray)
                error(R.mipmap.ic_launcher)
            }
        }
        binding.titleLabel.text = feed.title
        binding.authorLabel.text = feed.author
        binding.txtvDescription.text = HtmlToPlainText.getPlainText(feed.description?:"")
        binding.txtvTechInfo.text = "${feed.episodes.size} episodes\n" +
                "${feed.mostRecentItem?.title ?: ""}\n\n" +
                "${feed.language} ${feed.type ?: ""} ${feed.lastUpdate ?: ""}\n" +
                "${feed.link}\n" +
                "${feed.downloadUrl}"
        binding.subscribeButton.setOnClickListener {
            if (feedInFeedlist()) openFeed()
            else {
                lifecycleScope.launch {
                    binding.progressBar.visibility = View.VISIBLE
                    withContext(Dispatchers.IO) {
                        feed.id = 0L
                        for (item in feed.episodes) {
                            item.id = 0L
                            item.media?.id = 0L
                            item.feedId = null
                            item.feed = feed
                            val media = item.media
                            media?.episode = item
                        }
                        val fo = updateFeed(requireContext(), feed, false)
                        Logd(TAG, "fo.id: ${fo?.id} feed.id: ${feed.id}")
                    }
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        didPressSubscribe = true
                        handleUpdatedFeedStatus()
                    }
                }
            }
        }
        if (feedSource != "VistaGuide" && isEnableAutodownload)
            binding.autoDownloadCheckBox.isChecked = prefs!!.getBoolean(PREF_LAST_AUTO_DOWNLOAD, true)

        if (alternateFeedUrls.isEmpty()) binding.alternateUrlsSpinner.visibility = View.GONE
        else {
            binding.alternateUrlsSpinner.visibility = View.VISIBLE
            val alternateUrlsList: MutableList<String> = ArrayList()
            val alternateUrlsTitleList: MutableList<String?> = ArrayList()
            if (feed.downloadUrl != null) alternateUrlsList.add(feed.downloadUrl!!)
            alternateUrlsTitleList.add(feed.title)
            alternateUrlsList.addAll(alternateFeedUrls.keys)
            for (url in alternateFeedUrls.keys) {
                alternateUrlsTitleList.add(alternateFeedUrls[url])
            }
            val adapter: ArrayAdapter<String> = object : ArrayAdapter<String>(requireContext(),
                R.layout.alternate_urls_item, alternateUrlsTitleList) {
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    // reusing the old view causes a visual bug on Android <= 10
                    return super.getDropDownView(position, null, parent)
                }
            }
            adapter.setDropDownViewResource(R.layout.alternate_urls_dropdown_item)
            binding.alternateUrlsSpinner.adapter = adapter
            binding.alternateUrlsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View, position: Int, id: Long) {
                    selectedDownloadUrl = alternateUrlsList[position]
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        handleUpdatedFeedStatus()
    }

    @UnstableApi private fun openFeed() {
        (activity as MainActivity).loadFeedFragmentById(feedId, null)
    }

    @UnstableApi private fun showEpisodes(episodes: MutableList<Episode>) {
        Logd(TAG, "showEpisodes ${episodes.size}")
        if (episodes.isEmpty()) return
        episodes.sortByDescending { it.pubDate }
        var id_ = Feed.newId()
        for (i in 0..<episodes.size) {
            episodes[i].id = id_++
            episodes[i].media?.id = episodes[i].id
        }
        val fragment: Fragment = RemoteEpisodesFragment.newInstance(episodes)
        (activity as MainActivity).loadChildFragment(fragment)
    }

    @UnstableApi private fun handleUpdatedFeedStatus() {
        val dli = DownloadServiceInterface.get()
        if (dli == null || selectedDownloadUrl == null) return

        when {
//            feedSource != "VistaGuide" -> {
//                binding.subscribeButton.isEnabled = false
//            }
            dli.isDownloadingEpisode(selectedDownloadUrl!!) -> {
                binding.subscribeButton.isEnabled = false
                binding.subscribeButton.setText(R.string.subscribing_label)
            }
            feedInFeedlist() -> {
                binding.subscribeButton.isEnabled = true
                binding.subscribeButton.setText(R.string.open)
                if (didPressSubscribe) {
                    didPressSubscribe = false
                    val feed1 = getFeed(feedId, true)?: return
                    if (feed1.preferences == null) feed1.preferences = FeedPreferences(feed1.id, false,
                        FeedPreferences.AutoDeleteAction.GLOBAL, VolumeAdaptionSetting.OFF, "", "")
                    if (feedSource == "VistaGuide") {
                        feed1.preferences!!.prefStreamOverDownload = true
                        feed1.preferences!!.autoDownload = false
                    } else if (isEnableAutodownload) {
                        val autoDownload = binding.autoDownloadCheckBox.isChecked
                        feed1.preferences!!.autoDownload = autoDownload
                        val editor = prefs!!.edit()
                        editor.putBoolean(PREF_LAST_AUTO_DOWNLOAD, autoDownload)
                        editor.apply()
                    }
                    if (username != null) {
                        feed1.preferences!!.username = username
                        feed1.preferences!!.password = password
                    }
                    persistFeedPreferences(feed1)
                    openFeed()
                }
            }
            else -> {
                binding.subscribeButton.isEnabled = true
                binding.subscribeButton.setText(R.string.subscribe_label)
                if (feedSource != "VistaGuide" && isEnableAutodownload) binding.autoDownloadCheckBox.visibility = View.VISIBLE
            }
        }
    }

    private fun feedInFeedlist(): Boolean {
        return feedId != 0L
    }

    @UiThread
    private fun showErrorDialog(errorMsg: String?, details: String) {
        if (!isRemoving && !isPaused) {
            val builder = MaterialAlertDialogBuilder(requireContext())
            builder.setTitle(R.string.error_label)
            if (errorMsg != null) {
                val total = """
                    $errorMsg
                    
                    $details
                    """.trimIndent()
                val errorMessage = SpannableString(total)
                errorMessage.setSpan(ForegroundColorSpan(-0x77777778), errorMsg.length, total.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setMessage(errorMessage)
            } else builder.setMessage(R.string.download_error_error_unknown)

            builder.setPositiveButton(android.R.string.ok) { dialog: DialogInterface, _: Int -> dialog.cancel() }
//            if (intent.getBooleanExtra(ARG_WAS_MANUAL_URL, false)) {
//                builder.setNeutralButton(R.string.edit_url_menu) { _: DialogInterface?, _: Int -> editUrl() }
//            }
            builder.setOnCancelListener {
//                setResult(RESULT_ERROR)
//                finish()
            }
            if (dialog != null && dialog!!.isShowing) dialog!!.dismiss()
            dialog = builder.show()
        }
    }

    private fun editUrl() {
        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(R.string.edit_url_menu)
        val dialogBinding = EditTextDialogBinding.inflate(layoutInflater)
        if (downloader != null) dialogBinding.editText.setText(downloader!!.downloadRequest.source)

        builder.setView(dialogBinding.root)
        builder.setPositiveButton(R.string.confirm_label) { _: DialogInterface?, _: Int ->
            setLoadingLayout()
            lookupUrlAndBuild(dialogBinding.editText.text.toString())
        }
        builder.setNegativeButton(R.string.cancel_label) { dialog1: DialogInterface, _: Int -> dialog1.cancel() }
        builder.setOnCancelListener {}
        builder.show()
    }

    /**
     *
     * @return true if a FeedDiscoveryDialog is shown, false otherwise (e.g., due to no feed found).
     */
    private fun showFeedDiscoveryDialog(feedFile: File, baseUrl: String): Boolean {
        val fd = FeedDiscoverer()
        val urlsMap: Map<String, String>
        try {
            urlsMap = fd.findLinks(feedFile, baseUrl)
            if (urlsMap.isEmpty()) return false
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        }

        if (isRemoving || isPaused) return false
        val titles: MutableList<String?> = ArrayList()
        val urls: List<String> = ArrayList(urlsMap.keys)
        for (url in urls) {
            titles.add(urlsMap[url])
        }
        if (urls.size == 1) {
            // Skip dialog and display the item directly
            startFeedBuilding(urls[0])
            return true
        }
        val adapter = ArrayAdapter(requireContext(), R.layout.ellipsize_start_listitem, R.id.txtvTitle, titles)
        val onClickListener = DialogInterface.OnClickListener { dialog: DialogInterface, which: Int ->
            val selectedUrl = urls[which]
            dialog.dismiss()
            startFeedBuilding(selectedUrl)
        }
        val ab = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.feeds_label)
            .setCancelable(true)
            .setOnCancelListener { _: DialogInterface? ->/*                finish() */ }
            .setAdapter(adapter, onClickListener)
        requireActivity().runOnUiThread {
            if (dialog != null && dialog!!.isShowing) dialog!!.dismiss()
            dialog = ab.show()
        }
        return true
    }

    private fun showNoPodcastFoundError() {
        requireActivity().runOnUiThread {
            MaterialAlertDialogBuilder(requireContext())
                .setNeutralButton(android.R.string.ok) { _: DialogInterface?, _: Int -> }
                .setTitle(R.string.error_label)
                .setMessage(R.string.null_value_podcast_error)
                .setOnDismissListener {}
                .show()
        }
    }

    private inner class FeedViewAuthenticationDialog(context: Context, titleRes: Int, private val feedUrl: String) :
        AuthenticationDialog(context, titleRes, true, username, password) {
        override fun onConfirmed(username: String, password: String) {
            this@OnlineFeedFragment.username = username
            this@OnlineFeedFragment.password = password
            startFeedBuilding(feedUrl)
        }
    }

    /**
     * Finds RSS/Atom URLs in a HTML document using the auto-discovery techniques described here:
     * http://www.rssboard.org/rss-autodiscovery
     * http://blog.whatwg.org/feed-autodiscovery
     */
    class FeedDiscoverer {
        /**
         * Discovers links to RSS and Atom feeds in the given File which must be a HTML document.
         * @return A map which contains the feed URLs as keys and titles as values (the feed URL is also used as a title if
         * a title cannot be found).
         */
        @Throws(IOException::class)
        fun findLinks(inVal: File, baseUrl: String): Map<String, String> {
            return findLinks(Jsoup.parse(inVal), baseUrl)
        }
        /**
         * Discovers links to RSS and Atom feeds in the given File which must be a HTML document.
         * @return A map which contains the feed URLs as keys and titles as values (the feed URL is also used as a title if
         * a title cannot be found).
         */
        fun findLinks(inVal: String, baseUrl: String): Map<String, String> {
            return findLinks(Jsoup.parse(inVal), baseUrl)
        }
        private fun findLinks(document: Document, baseUrl: String): Map<String, String> {
            val res: MutableMap<String, String> = ArrayMap()
            val links = document.head().getElementsByTag("link")
            for (link in links) {
                val rel = link.attr("rel")
                val href = link.attr("href")
                if (href.isNotEmpty() && (rel == "alternate" || rel == "feed")) {
                    val type = link.attr("type")
                    if (type == MIME_RSS || type == MIME_ATOM) {
                        val title = link.attr("title")
                        val processedUrl = processURL(baseUrl, href)
                        if (processedUrl != null) res[processedUrl] = title.ifEmpty { href }
                    }
                }
            }
            return res
        }
        private fun processURL(baseUrl: String, strUrl: String): String? {
            val uri = Uri.parse(strUrl)
            if (uri.isRelative) {
                val res = Uri.parse(baseUrl).buildUpon().path(strUrl).build()
                return res?.toString()
            } else return strUrl
        }
        companion object {
            private const val MIME_RSS = "application/rss+xml"
            private const val MIME_ATOM = "application/atom+xml"
        }
    }

    /**
     * Shows all episodes (possibly filtered by user).
     */
    @UnstableApi
    class RemoteEpisodesFragment : BaseEpisodesFragment() {
        private val episodeList: MutableList<Episode> = mutableListOf()

        @UnstableApi override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            val root = super.onCreateView(inflater, container, savedInstanceState)
            Logd(TAG, "fragment onCreateView")
            toolbar.inflateMenu(R.menu.episodes)
            toolbar.setTitle(R.string.episodes_label)
            updateToolbar()
//            adapter.setOnSelectModeListener(null)
            return root
        }
        override fun onStart() {
            super.onStart()
            procFlowEvents()
        }
        override fun onStop() {
            super.onStop()
            cancelFlowEvents()
        }
        override fun onDestroyView() {
            episodeList.clear()
            super.onDestroyView()
        }
        fun setEpisodes(episodeList_: MutableList<Episode>) {
            episodeList.clear()
            episodeList.addAll(episodeList_)
        }
        override fun loadData(): List<Episode> {
            if (episodeList.isEmpty()) return listOf()
            return episodeList
        }
        override fun loadTotalItemCount(): Int {
            return episodeList.size
        }
        override fun getPrefName(): String {
            return PREF_NAME
        }
        override fun updateToolbar() {
            binding.toolbar.menu.findItem(R.id.episodes_sort).setVisible(false)
//        binding.toolbar.menu.findItem(R.id.refresh_item).setVisible(false)
            binding.toolbar.menu.findItem(R.id.action_search).setVisible(false)
            binding.toolbar.menu.findItem(R.id.action_favorites).setVisible(false)
            binding.toolbar.menu.findItem(R.id.filter_items).setVisible(false)
            infoBarText.value = "${episodes.size} episodes"
        }
        @OptIn(UnstableApi::class) override fun onMenuItemClick(item: MenuItem): Boolean {
            if (super.onOptionsItemSelected(item)) return true
            when (item.itemId) {
                else -> return false
            }
        }
        private var eventSink: Job?     = null
        private fun cancelFlowEvents() {
            eventSink?.cancel()
            eventSink = null
        }
        private fun procFlowEvents() {
            if (eventSink != null) return
            eventSink = lifecycleScope.launch {
                EventFlow.events.collectLatest { event ->
                    Logd(TAG, "Received event: ${event.TAG}")
                    when (event) {
                        is FlowEvent.AllEpisodesFilterEvent -> page = 1
                        else -> {}
                    }
                }
            }
        }

        companion object {
            const val PREF_NAME: String = "EpisodesListFragment"

            fun newInstance(episodes: MutableList<Episode>): RemoteEpisodesFragment {
                val i = RemoteEpisodesFragment()
                i.setEpisodes(episodes)
                return i
            }
        }
    }

    companion object {
        const val ARG_FEEDURL: String = "arg.feedurl"
        const val ARG_WAS_MANUAL_URL: String = "manual_url"
        private const val RESULT_ERROR = 2
        private val TAG: String = OnlineFeedFragment::class.simpleName ?: "Anonymous"
        private const val PREFS = "OnlineFeedViewFragmentPreferences"
        private const val PREF_LAST_AUTO_DOWNLOAD = "lastAutoDownload"
        private const val KEY_UP_ARROW = "up_arrow"

        var prefs: SharedPreferences? = null
        fun getSharedPrefs(context: Context) {
            if (prefs == null) prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }

        fun newInstance(feedUrl: String): OnlineFeedFragment {
            val fragment = OnlineFeedFragment()
            val b = Bundle()
            b.putString(ARG_FEEDURL, feedUrl)
            fragment.arguments = b
            return fragment
        }
    }
}
