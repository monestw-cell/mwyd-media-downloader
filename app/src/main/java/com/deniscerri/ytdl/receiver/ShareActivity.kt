package com.deniscerri.ytdl.receiver

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.MainActivity
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.viewmodel.CookieViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadCardViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.database.viewmodel.HistoryViewModel
import com.deniscerri.ytdl.database.viewmodel.ResultViewModel
import com.deniscerri.ytdl.ui.BaseActivity
import com.deniscerri.ytdl.util.Extensions.extractURL
import com.deniscerri.ytdl.util.ThemeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.properties.Delegates


class ShareActivity : BaseActivity() {

    lateinit var context: Context
    private lateinit var resultViewModel: ResultViewModel
    private lateinit var historyViewModel: HistoryViewModel
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var cookieViewModel: CookieViewModel
    private lateinit var downloadCardViewModel: DownloadCardViewModel
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var navController: NavController
    private var quickDownload by Delegates.notNull<Boolean>()

    private lateinit var wm: WindowManager
    private lateinit var myView: View


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeUtil.updateTheme(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setContentView(R.layout.activity_share)

        context = baseContext
        resultViewModel = ViewModelProvider(this)[ResultViewModel::class.java]
        historyViewModel = ViewModelProvider(this)[HistoryViewModel::class.java]
        downloadViewModel = ViewModelProvider(this)[DownloadViewModel::class.java]
        cookieViewModel = ViewModelProvider(this)[CookieViewModel::class.java]
        downloadCardViewModel = ViewModelProvider(this)[DownloadCardViewModel::class.java]
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        cookieViewModel.updateCookiesFile()
        val intent = intent
        handleIntents(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntents(intent)
    }

    private fun handleIntents(intent: Intent) {
        askPermissions()

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.frame_layout) as NavHostFragment
        navController = navHostFragment.findNavController()
        navController.addOnDestinationChangedListener(object: NavController.OnDestinationChangedListener{
            @SuppressLint("RestrictedApi")
            override fun onDestinationChanged(
                controller: NavController,
                destination: NavDestination,
                arguments: Bundle?
            ) {
                navController.removeOnDestinationChangedListener(this)
                CoroutineScope(SupervisorJob()).launch {
                    navController.currentBackStack.collectLatest {
                        if (it.isEmpty()){
                            this@ShareActivity.finish()
                        }
                    }
                }
            }
        })

        val action = intent.action
        Log.e("ShareActivity", "Action: $action, Intent: $intent")
        if (Intent.ACTION_SEND == action || Intent.ACTION_VIEW == action) {
            if (intent.getStringExtra(Intent.EXTRA_TEXT) == null && Intent.ACTION_SEND == action){
                intent.setClass(this, MainActivity::class.java)
                startActivity(intent)
                finishAffinity()
                return
            }

            runCatching { supportFragmentManager.popBackStack() }

            val data = when(action){
                Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)!!
                else -> intent.dataString!!
            }

            val inputQuery = data.extractURL()
            val ai = packageManager.getActivityInfo(componentName, PackageManager.GET_META_DATA)

            val type = intent.getStringExtra("TYPE")
            val background = intent.getBooleanExtra("BACKGROUND", ai.metaData?.getBoolean("quick_run_background", false) == true)

            val initialResult = downloadViewModel.createEmptyResultItem(inputQuery)
            val downloadType = DownloadType.valueOf(type ?: downloadViewModel.getDownloadType(url = initialResult.url).toString())

            val isPlaylistUrl = inputQuery.contains("playlist?list=", ignoreCase = true) ||
                    inputQuery.contains("&list=", ignoreCase = true) ||
                    inputQuery.contains("/sets/", ignoreCase = true)
            val useQuickMode = sharedPreferences.getBoolean("quick_share_mode", true) && !isPlaylistUrl

            if (sharedPreferences.getBoolean("download_card", true) && !background){
                downloadCardViewModel.setResultItem(initialResult)
                downloadCardViewModel.setDownloadItem(null)
                val bundle = Bundle()
                bundle.putSerializable("type", downloadType)
                bundle.putBoolean("quickMode", useQuickMode)
                navController.setGraph(R.navigation.share_nav_graph, bundle)
            } else {
                Toast.makeText(this@ShareActivity, "${getString(R.string.downloading)} $inputQuery", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch(Dispatchers.IO){
                    val downloadItem = downloadViewModel.createDownloadItemFromResult(
                        result = initialResult,
                        givenType = downloadType)
                    downloadViewModel.queueDownloads(listOf(downloadItem))
                }
                this@ShareActivity.finish()
            }

            // In background, fetch rich formats and update ResultViewModel
            lifecycleScope.launch(Dispatchers.IO) {
                val existingResults = resultViewModel.getAllByURL(inputQuery)
                if (existingResults.isNotEmpty() && existingResults.first().formats.isNotEmpty()) {
                    val cached = existingResults.first()
                    withContext(Dispatchers.Main) {
                        downloadCardViewModel.setResultItem(cached)
                    }
                } else {
                    resultViewModel.updateItemData(initialResult)
                }
            }
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        startActivity(Intent(this, MainActivity::class.java))
        super.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        )
    }
}
