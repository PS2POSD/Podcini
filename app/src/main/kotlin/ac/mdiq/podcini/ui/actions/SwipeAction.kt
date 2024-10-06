package ac.mdiq.podcini.ui.actions

import android.content.Context
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.fragment.app.Fragment
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.EpisodeFilter


interface SwipeAction {
    fun getId(): String?
    fun getTitle(context: Context): String

    @DrawableRes
    fun getActionIcon(): Int

    @AttrRes
    @DrawableRes
    fun getActionColor(): Int

    fun performAction(item: Episode, fragment: Fragment, filter: EpisodeFilter)

    fun willRemove(filter: EpisodeFilter, item: Episode): Boolean

    companion object {
        const val NO_ACTION: String = "NO_ACTION"

        const val ADD_TO_QUEUE: String = "ADD_TO_QUEUE"
        const val START_DOWNLOAD: String = "START_DOWNLOAD"
        const val MARK_FAV: String = "MARK_FAV"
        const val TOGGLE_PLAYED: String = "MARK_PLAYED"
        const val REMOVE_FROM_QUEUE: String = "REMOVE_FROM_QUEUE"
        const val DELETE: String = "DELETE"
        const val REMOVE_FROM_HISTORY: String = "REMOVE_FROM_HISTORY"
    }
}
