package ac.mdiq.podcini.ui.statistics.downloads


import ac.mdiq.podcini.R
import ac.mdiq.podcini.databinding.StatisticsFragmentBinding
import ac.mdiq.podcini.storage.DBReader
import ac.mdiq.podcini.storage.DBReader.StatisticsResult
import ac.mdiq.podcini.storage.StatisticsItem
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Displays the 'download statistics' screen
 */
class DownloadStatisticsFragment : Fragment() {

    private var _binding: StatisticsFragmentBinding? = null
    private val binding get() = _binding!!

//    private var disposable: Disposable? = null

    private lateinit var downloadStatisticsList: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var listAdapter: DownloadStatisticsListAdapter


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = StatisticsFragmentBinding.inflate(inflater)
        downloadStatisticsList = binding.statisticsList
        progressBar = binding.progressBar
        listAdapter = DownloadStatisticsListAdapter(requireContext(), this)
        downloadStatisticsList.layoutManager = LinearLayoutManager(context)
        downloadStatisticsList.adapter = listAdapter
        refreshDownloadStatistics()

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        menu.findItem(R.id.statistics_reset).setVisible(false)
        menu.findItem(R.id.statistics_filter).setVisible(false)
    }

    private fun refreshDownloadStatistics() {
        progressBar.visibility = View.VISIBLE
        downloadStatisticsList.visibility = View.GONE
        loadStatistics()
    }

    private fun loadStatistics() {
        lifecycleScope.launch {
            try {
                val statisticsData = withContext(Dispatchers.IO) {
                    val data = DBReader.getStatistics(false, 0, Long.MAX_VALUE)
                    data.feedTime.sortWith { item1: StatisticsItem, item2: StatisticsItem ->
                        item2.totalDownloadSize.compareTo(item1.totalDownloadSize)
                    }
                    data
                }
                listAdapter.update(statisticsData.feedTime)
                progressBar.visibility = View.GONE
                downloadStatisticsList.visibility = View.VISIBLE
            } catch (error: Throwable) {
                Log.e(TAG, Log.getStackTraceString(error))
            }
        }

    }

    companion object {
        private val TAG: String = DownloadStatisticsFragment::class.java.simpleName
    }
}
