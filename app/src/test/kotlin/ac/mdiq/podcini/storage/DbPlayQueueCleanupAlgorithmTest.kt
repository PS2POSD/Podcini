package ac.mdiq.podcini.storage

import ac.mdiq.podcini.storage.model.Feed
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.preferences.UserPreferences
import ac.mdiq.podcini.storage.algorithms.AutoCleanups.performAutoCleanup
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/**
 * Tests that the APQueueCleanupAlgorithm is working correctly.
 */
@RunWith(RobolectricTestRunner::class)
class DbPlayQueueCleanupAlgorithmTest : DbCleanupTests() {
    init {
        setCleanupAlgorithm(UserPreferences.EPISODE_CLEANUP_QUEUE)
    }

    /**
     * For APQueueCleanupAlgorithm we expect even unplayed episodes to be deleted if needed
     * if they aren't in the queue.
     */
    @Test
    @Throws(IOException::class)
    override fun testPerformAutoCleanupHandleUnplayed() {
        val numItems = EPISODE_CACHE_SIZE * 2

        val feed = Feed("url", null, "title")
        val items: MutableList<Episode> = ArrayList()
        feed.episodes.addAll(items)
        val files: MutableList<File> = ArrayList()
        populateItems(numItems, feed, items, files, PlayState.UNPLAYED.code, false, false)

        performAutoCleanup(context)
        for (i in files.indices) {
            if (i < EPISODE_CACHE_SIZE) {
                Assert.assertTrue(files[i].exists())
            } else {
                Assert.assertFalse(files[i].exists())
            }
        }
    }
}
