package com.deniscerri.ytdl.ui.downloadcard

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import android.widget.ImageView
import androidx.core.content.ContextCompat
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.DownloadItem
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.repository.DownloadRepository
import com.deniscerri.ytdl.database.viewmodel.CommandTemplateViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadCardViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.database.viewmodel.HistoryViewModel
import com.deniscerri.ytdl.database.viewmodel.ResultViewModel
import com.deniscerri.ytdl.receiver.ShareActivity
import com.deniscerri.ytdl.ui.BaseActivity
import com.deniscerri.ytdl.ui.more.cookies.WebViewActivity
import com.deniscerri.ytdl.util.Extensions.getIDFromYoutubeURL
import com.deniscerri.ytdl.util.UiUtil
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL


class DownloadBottomSheetDialog : BottomSheetDialogFragment() {
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager2: ViewPager2
    private lateinit var fragmentAdapter : DownloadFragmentAdapter
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var historyViewModel: HistoryViewModel
    private lateinit var resultViewModel: ResultViewModel
    private lateinit var downloadCardViewModel: DownloadCardViewModel
    private lateinit var behavior: BottomSheetBehavior<View>
    private lateinit var commandTemplateViewModel : CommandTemplateViewModel
    private lateinit var sharedPreferences : SharedPreferences
    private lateinit var updateItem : Button
    private lateinit var view: View
    private lateinit var shimmerLoading :ShimmerFrameLayout
    private lateinit var title : View
    private lateinit var shimmerLoadingSubtitle : ShimmerFrameLayout
    private lateinit var subtitle : View
    private lateinit var parentActivity: BaseActivity


    private lateinit var result: ResultItem
    private lateinit var type: DownloadType
    private var ignoreDuplicates: Boolean = false
    private var disableUpdateData : Boolean = false
    private var currentDownloadItem: DownloadItem? = null
    private var incognito: Boolean = false
    private var quickMode: Boolean = false
    private var quickCuts: List<String> = emptyList()
    private var quickSubsEnabled: Boolean = false
    private var quickSubsLanguages: String = "ar.*,en.*,.*-orig"
    private var quickSelectedAudioFormat: String? = null
    private var quickSelectedAudioLanguage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        historyViewModel = ViewModelProvider(requireActivity())[HistoryViewModel::class.java]
        resultViewModel = ViewModelProvider(requireActivity())[ResultViewModel::class.java]
        commandTemplateViewModel = ViewModelProvider(requireActivity())[CommandTemplateViewModel::class.java]
        downloadCardViewModel = ViewModelProvider(requireActivity())[DownloadCardViewModel::class.java]
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())

        val res = downloadCardViewModel.resultItem
        val dwl = downloadCardViewModel.downloadItem

        type = arguments?.getSerializable("type") as DownloadType
        disableUpdateData = arguments?.getBoolean("disableUpdateData") == true
        ignoreDuplicates = arguments?.getBoolean("ignore_duplicates") == true
        quickMode = arguments?.getBoolean("quickMode", false) == true

        if (res == null){
            dismiss()
            return
        }
        result = res
        currentDownloadItem = dwl
        incognito = currentDownloadItem?.incognito ?: sharedPreferences.getBoolean("incognito", false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val downloadItem = getDownloadItem()
        downloadCardViewModel.setResultItem(result)
        downloadCardViewModel.setDownloadItem(downloadItem)
        arguments?.putSerializable("type", downloadItem.type)
    }

    @SuppressLint("RestrictedApi", "InflateParams")
    override fun setupDialog(dialog: Dialog, style: Int) {
        super.setupDialog(dialog, style)
        view = if (quickMode) {
            LayoutInflater.from(context).inflate(R.layout.download_bottom_sheet_quick, null)
        } else {
            LayoutInflater.from(context).inflate(R.layout.download_bottom_sheet, null)
        }
        dialog.setContentView(view)
        dialog.window?.navigationBarColor = SurfaceColors.SURFACE_1.getColor(requireActivity())
        parentActivity = activity as BaseActivity

        dialog.setOnShowListener {
            behavior = BottomSheetBehavior.from(view.parent as View)
            val displayMetrics = DisplayMetrics()
            requireActivity().windowManager.defaultDisplay.getMetrics(displayMetrics)
            if(resources.getBoolean(R.bool.isTablet) || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE){
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.peekHeight = displayMetrics.heightPixels
            }
        }

        if (quickMode) {
            initQuickMode()
        } else {
            initFullMode()
        }
    }

    private fun initFullMode() {
        tabLayout = view.findViewById(R.id.download_tablayout)
        viewPager2 = view.findViewById(R.id.download_viewpager)
        updateItem = view.findViewById(R.id.update_item)
        viewPager2.isUserInputEnabled = sharedPreferences.getBoolean("swipe_gestures_download_card", true)


        //loading shimmers
        shimmerLoading = view.findViewById(R.id.shimmer_loading_title)
        title = view.findViewById(R.id.bottom_sheet_title)
        shimmerLoadingSubtitle = view.findViewById(R.id.shimmer_loading_subtitle)
        subtitle = view.findViewById(R.id.bottom_sheet_subtitle)

        shimmerLoading.setOnClickListener {
            lifecycleScope.launch {
                resultViewModel.cancelUpdateItemData()
                (updateItem.parent as LinearLayout).visibility = View.VISIBLE
            }
        }


        (viewPager2.getChildAt(0) as? RecyclerView)?.apply {
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        var commandTemplateNr = 0
        lifecycleScope.launch{
            withContext(Dispatchers.IO){
                commandTemplateNr = commandTemplateViewModel.getTotalNumber()
                if (!Patterns.WEB_URL.matcher(result.url).matches()) commandTemplateNr++
                if(commandTemplateNr <= 0){
                    (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(2)?.isClickable = true
                    (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(2)?.alpha = 0.3f
                }
            }
        }

        //check if the item has formats and its audio-only
        val formats = result.formats
        var isAudioOnly = formats.isNotEmpty() && formats.none { !it.format_note.contains("audio") }
        if (isAudioOnly){
            (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(1)?.isClickable = true
            (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(1)?.alpha = 0.3f
        }

        //remove outdated player url of 1hr so it can refetch it in the cut player
        if (result.creationTime > System.currentTimeMillis() - 3600000) result.urls = ""
        val fragmentManager = parentFragmentManager
        fragmentAdapter = DownloadFragmentAdapter(
            fragmentManager,
            lifecycle,
            result,
            currentDownloadItem,
            nonSpecific = result.url.endsWith(".txt"),
            isIncognito = incognito
        )

        viewPager2.adapter = fragmentAdapter
        viewPager2.isSaveFromParentEnabled = false

        view.post {
            when(type) {
                DownloadType.audio -> {
                    tabLayout.getTabAt(0)!!.select()
                    viewPager2.setCurrentItem(0, false)
                }
                DownloadType.video -> {
                    if (isAudioOnly){
                        tabLayout.getTabAt(0)!!.select()
                        viewPager2.setCurrentItem(0, false)
                        Toast.makeText(context, getString(R.string.audio_only_item), Toast.LENGTH_SHORT).show()
                    }else{
                        tabLayout.getTabAt(1)!!.select()
                        viewPager2.setCurrentItem(1, false)
                    }
                }
                else -> {
                    tabLayout.getTabAt(2)!!.select()
                    viewPager2.postDelayed( {
                        viewPager2.setCurrentItem(2, false)
                    }, 200)
                }
            }

            //check if the item is coming from a text file
            val isCommandOnly = (type == DownloadType.command && !Patterns.WEB_URL.matcher(result.url).matches())
            if (isCommandOnly){
                (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.isClickable = false
                (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.alpha = 0.3f

                (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(1)?.isClickable = false
                (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(1)?.alpha = 0.3f

                (updateItem.parent as LinearLayout).visibility = View.GONE
            }
        }

        sharedPreferences.edit(commit = true) {
            putString("last_used_download_type",
                type.toString())
        }


        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (tab!!.position == 2 && commandTemplateNr == 0){
                    tabLayout.selectTab(tabLayout.getTabAt(1))
                    val s = Snackbar.make(view, getString(R.string.add_template_first), Snackbar.LENGTH_LONG)
                    val snackbarView: View = s.view
                    val snackTextView = snackbarView.findViewById<View>(com.google.android.material.R.id.snackbar_text) as TextView
                    snackTextView.maxLines = 9999999
                    s.setAction(R.string.new_template){
                        UiUtil.showCommandTemplateCreationOrUpdatingSheet(
                            item = null, context = requireActivity(), lifeCycle = this@DownloadBottomSheetDialog, commandTemplateViewModel = commandTemplateViewModel,
                            newTemplate = {
                                commandTemplateNr = 1
                                (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(2)?.isClickable = true
                                (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(2)?.alpha = 1f
                                tabLayout.selectTab(tabLayout.getTabAt(2))
                            },
                            dismissed = {

                            }
                        )
                    }
                    s.show()
                }else if (tab.position == 1 && isAudioOnly){
                    tabLayout.selectTab(tabLayout.getTabAt(0))
                    Toast.makeText(context, getString(R.string.audio_only_item), Toast.LENGTH_SHORT).show()
                }
                else{
                    viewPager2.setCurrentItem(tab.position, false)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {
            }

            override fun onTabReselected(tab: TabLayout.Tab?) {
            }
        })

        viewPager2.registerOnPageChangeCallback(object: ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                tabLayout.selectTab(tabLayout.getTabAt(position))
                runCatching {
                    sharedPreferences.edit(commit = true) {
                        putString("last_used_download_type",
                            listOf(DownloadType.audio, DownloadType.video, DownloadType.command)[position].toString())
                    }
                    fragmentAdapter.updateWhenSwitching(viewPager2.currentItem)
                }
            }
        })

        viewPager2.setPageTransformer(BackgroundToForegroundPageTransformer())

        val shownFields = sharedPreferences.getStringSet("modify_download_card", requireContext().resources.getStringArray(R.array.modify_download_card_values).toSet())!!.toList()

        val scheduleBtn = view.findViewById<MaterialButton>(R.id.bottomsheet_schedule_button)
        scheduleBtn.visibility = if(shownFields.contains("schedule")){
            View.VISIBLE
        }else{
            View.GONE
        }
        val download = view.findViewById<Button>(R.id.bottomsheet_download_button)


        scheduleBtn.setOnClickListener{
            UiUtil.showDatePicker(fragmentManager, sharedPreferences) {
                lifecycleScope.launch {
                    resultViewModel.cancelUpdateItemData()
                    resultViewModel.cancelUpdateFormatsItemData()
                }

                scheduleBtn.isEnabled = false
                download.isEnabled = false
                val item: DownloadItem = getDownloadItem()
                item.status = DownloadRepository.Status.Scheduled.toString()
                item.downloadStartTime = it.timeInMillis
                if (item.videoPreferences.alsoDownloadAsAudio){
                    val itemsToQueue = mutableListOf<DownloadItem>()
                    itemsToQueue.add(item)

                    getAlsoAudioDownloadItem(finished = { audioDownloadItem ->
                        audioDownloadItem.downloadStartTime = it.timeInMillis
                        audioDownloadItem.status = DownloadRepository.Status.Scheduled.toString()
                        itemsToQueue.add(audioDownloadItem)

                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO){
                                downloadViewModel.queueDownloads(itemsToQueue, ignoreDuplicates)
                            }

                            if (result.message.isNotBlank()){
                                Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                            }

                            withContext(Dispatchers.Main){
                                handleDuplicatesAndDismiss(result.duplicateDownloadIDs)
                            }
                        }
                    })
                }else{
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO){
                            downloadViewModel.queueDownloads(listOf(item), ignoreDuplicates)
                        }

                        if (result.message.isNotBlank()){
                            Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                        }

                        withContext(Dispatchers.Main){
                            handleDuplicatesAndDismiss(result.duplicateDownloadIDs)
                        }
                    }
                }

            }
        }
        download!!.setOnClickListener {
            lifecycleScope.launch {
                resultViewModel.cancelUpdateItemData()
                resultViewModel.cancelUpdateFormatsItemData()
                scheduleBtn.isEnabled = false
                download.isEnabled = false
                val item: DownloadItem = getDownloadItem()
                if (item.videoPreferences.alsoDownloadAsAudio){
                    val itemsToQueue = mutableListOf<DownloadItem>()
                    itemsToQueue.add(item)

                    getAlsoAudioDownloadItem(finished = {
                        itemsToQueue.add(it)

                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                downloadViewModel.queueDownloads(itemsToQueue, ignoreDuplicates)
                            }
                            withContext(Dispatchers.Main){
                                handleDuplicatesAndDismiss(result.duplicateDownloadIDs)
                            }
                        }
                    })
                }else{
                    val result = withContext(Dispatchers.IO) {
                        downloadViewModel.queueDownloads(listOf(item), ignoreDuplicates)
                    }
                    handleDuplicatesAndDismiss(result.duplicateDownloadIDs)
                }
            }
        }

        download.setOnLongClickListener {
            val dd = MaterialAlertDialogBuilder(requireContext())
            dd.setTitle(getString(R.string.save_for_later))
            dd.setNegativeButton(getString(R.string.cancel)) { dialogInterface: DialogInterface, _: Int -> dialogInterface.cancel() }
            dd.setPositiveButton(getString(R.string.ok)) { _: DialogInterface?, _: Int ->
                lifecycleScope.launch(Dispatchers.IO){
                    downloadViewModel.putToSaved(getDownloadItem())
                    dismiss()
                }
            }
            dd.show()
            true
        }

        val link = view.findViewById<Button>(R.id.bottom_sheet_link)
        link.visibility = if(shownFields.contains("url")){
            View.VISIBLE
        }else{
            View.GONE
        }

        if (Patterns.WEB_URL.matcher(result.url).matches()){
            link.text = result.url
            link.setOnClickListener{
                UiUtil.openLinkIntent(requireContext(), result.url)
            }
            link.setOnLongClickListener{
                UiUtil.copyLinkToClipBoard(requireContext(), result.url)
                true
            }

            //if auto-update after the card is open is off
            if (result.title.isEmpty() && currentDownloadItem == null && sharedPreferences.getBoolean("quick_download", false)) {
                (updateItem.parent as LinearLayout).visibility = View.VISIBLE
                updateItem.setOnClickListener {
                    (updateItem.parent as LinearLayout).visibility = View.GONE
                    initUpdateData()
                }
            }else{
                (updateItem.parent as LinearLayout).visibility = View.GONE
            }

        }else{
            link.visibility = View.GONE
            (updateItem.parent as LinearLayout).visibility = View.GONE
        }

        val incognitoBtn = view.findViewById<Button>(R.id.bottomsheet_incognito)
        incognitoBtn.alpha = if (incognito) 1f else 0.3f
        incognitoBtn.setOnClickListener {
            if (incognito) {
                it.alpha = 0.3f
            }else{
                it.alpha = 1f
            }

            incognito = !incognito
            fragmentAdapter.isIncognito = incognito
            val onOff = if (incognito) getString(R.string.ok) else getString(R.string.disabled)
            Snackbar.make(incognitoBtn, "${getString(R.string.incognito)}: $onOff", Snackbar.LENGTH_SHORT).show()
        }


        //update in the background if there is no data
        if (!disableUpdateData) {
            if(result.title.isEmpty() && currentDownloadItem == null && !sharedPreferences.getBoolean("quick_download", false) && type != DownloadType.command){
                initUpdateData()
            }else {
                val usingGenericFormatsOrEmpty = result.formats.isEmpty() || result.formats.any { it.format_note.contains("mwydgeneric") }
                if (usingGenericFormatsOrEmpty && sharedPreferences.getBoolean("update_formats", false) && !sharedPreferences.getBoolean("quick_download", false)){
                    initUpdateFormats(result)
                }
            }
        }

        lifecycleScope.launch {
            resultViewModel.uiState.collectLatest { res ->
                if (res.errorMessage != null){
                    kotlin.runCatching {
                        UiUtil.handleNoResults(requireActivity(), res.errorMessage!!,
                            url = result.url,
                            continueAnyway =  true,
                            continued = {},
                            cookieFetch = {
                                val myIntent = Intent(requireContext(), WebViewActivity::class.java)
                                myIntent.putExtra("url", "https://${URL(result.url).host}")
                                cookiesFetchedResultLauncher.launch(myIntent)
                            },
                            closed = {
                                dismiss()
                            }
                        )
                    }

                    resultViewModel.uiState.update {it.copy(errorMessage  = null) }
                }
            }
        }

        lifecycleScope.launch {
            resultViewModel.updatingData.collectLatest {
                kotlin.runCatching {
                    if (it){
                        title.visibility = View.GONE
                        subtitle.visibility = View.GONE
                        shimmerLoading.visibility = View.VISIBLE
                        shimmerLoadingSubtitle.visibility = View.VISIBLE
                        shimmerLoading.startShimmer()
                        shimmerLoadingSubtitle.startShimmer()
                        (updateItem.parent as LinearLayout).visibility = View.GONE
                    }else{
                        title.visibility = View.VISIBLE
                        subtitle.visibility = View.VISIBLE
                        shimmerLoading.visibility = View.GONE
                        shimmerLoadingSubtitle.visibility = View.GONE
                        shimmerLoading.stopShimmer()
                        shimmerLoadingSubtitle.stopShimmer()
                    }
                }
            }
        }

        lifecycleScope.launch {
            resultViewModel.updatingFormats.collectLatest {
                kotlin.runCatching {
                    if (it){
                        delay(500)
                        runCatching {
                            (fragmentAdapter.fragments[0] as DownloadAudioFragment).apply {
                                view?.findViewById<LinearProgressIndicator>(R.id.format_loading_progress)?.apply {
                                    isVisible = true
                                    isClickable = true
                                    setOnClickListener {
                                        lifecycleScope.launch {
                                            resultViewModel.cancelUpdateFormatsItemData()
                                        }
                                    }
                                }
                            }
                        }
                        runCatching {
                            (fragmentAdapter.fragments[1] as DownloadVideoFragment).apply {
                                view?.findViewById<LinearProgressIndicator>(R.id.format_loading_progress)?.apply {
                                    isVisible = true
                                    isClickable = true
                                    setOnClickListener {
                                        lifecycleScope.launch {
                                            resultViewModel.cancelUpdateFormatsItemData()
                                        }
                                    }
                                }
                            }
                        }
                    }else{
                        runCatching {
                            (fragmentAdapter.fragments[0] as DownloadAudioFragment).apply {
                                view?.findViewById<LinearProgressIndicator>(R.id.format_loading_progress)?.apply {
                                    isVisible = false
                                    isClickable = false
                                }
                            }
                        }
                        runCatching {
                            (fragmentAdapter.fragments[1] as DownloadVideoFragment).apply {
                                view?.findViewById<LinearProgressIndicator>(R.id.format_loading_progress)?.apply {
                                    isVisible = false
                                    isClickable = false
                                }
                            }
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            resultViewModel.updateResultData.collectLatest { result ->
                if (result == null) return@collectLatest
                kotlin.runCatching {
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (result.size == 1 && result[0] != null) {
                            val res = result[0]!!
                            fragmentAdapter.setResultItem(res)

                            title.visibility = View.VISIBLE
                            subtitle.visibility = View.VISIBLE
                            shimmerLoading.visibility = View.GONE
                            shimmerLoadingSubtitle.visibility = View.GONE
                            shimmerLoading.stopShimmer()
                            shimmerLoadingSubtitle.stopShimmer()

                            val usingGenericFormatsOrEmpty = res.formats.isEmpty() || res.formats.any { it.format_note.contains("mwydgeneric") }
                            downloadCardViewModel.setResultItem(res)
                            if (usingGenericFormatsOrEmpty && sharedPreferences.getBoolean("update_formats", false)){
                                initUpdateFormats(res)
                            }

                        }else if (result.size > 1) {
                            //open multi download card instead
                            if (activity is ShareActivity){
                                findNavController().navigate(R.id.action_downloadBottomSheetDialog_to_selectPlaylistItemsDialog, bundleOf(
                                    Pair("resultIDs", result.map { it!!.id }.toLongArray()),
                                ))
                            }else{
                                dismiss()
                            }
                        }

                        resultViewModel.updateResultData.emit(null)
                    }

                }
            }
        }

        lifecycleScope.launch {
            launch{
                downloadViewModel.alreadyExistsUiState.collectLatest { res ->
                    if (res.isNotEmpty() && activity is ShareActivity){
                        withContext(Dispatchers.Main){
                            val bundle = bundleOf(
                                Pair("duplicates", ArrayList(res))
                            )
                            delay(500)
                            findNavController().navigate(R.id.action_downloadBottomSheetDialog_to_downloadsAlreadyExistDialog2, bundle)
                        }
                        downloadViewModel.alreadyExistsUiState.value = mutableListOf()
                    }
                }
            }
        }

        lifecycleScope.launch {
            resultViewModel.updateFormatsResultData.collectLatest { formats ->
                if (formats == null) return@collectLatest
                kotlin.runCatching {
                    isAudioOnly = formats.isNotEmpty() && formats.none { !it.format_note.contains("audio") }
                    if (isAudioOnly){
                        (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(1)?.isClickable = true
                        (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(1)?.alpha = 0.3f
                        Toast.makeText(context, getString(R.string.audio_only_item), Toast.LENGTH_SHORT).show()
                        tabLayout.getTabAt(0)!!.select()
                        viewPager2.setCurrentItem(0, false)
                    }

                    lifecycleScope.launch {
                        withContext(Dispatchers.Main){
                            runCatching {
                                val f1 = fragmentAdapter.fragments[0] as DownloadAudioFragment
                                val resultItem = downloadViewModel.createResultItemFromDownload(f1.downloadItem)
                                resultItem.formats = formats
                                fragmentAdapter.setResultItem(resultItem)
                                f1.view?.findViewById<LinearProgressIndicator>(R.id.format_loading_progress)?.visibility = View.GONE
                            }
                            runCatching {
                                val f1 = fragmentAdapter.fragments[1] as DownloadVideoFragment
                                val resultItem = downloadViewModel.createResultItemFromDownload(f1.downloadItem)
                                resultItem.formats = formats
                                fragmentAdapter.setResultItem(resultItem)
                                f1.view?.findViewById<LinearProgressIndicator>(R.id.format_loading_progress)?.visibility = View.GONE
                            }
                        }

                        if (formats.isNotEmpty()){
                            result.formats = formats
                        }
                        resultViewModel.updateFormatsResultData.emit(null)
                    }
                }
            }
        }
    }

    private var cookiesFetchedResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            sharedPreferences.edit().putBoolean("use_cookies", true).apply()
            updateItem.isVisible = true
            initUpdateData()
        }
    }

    private fun getDownloadItem(selectedTabPosition: Int = tabLayout.selectedTabPosition) : DownloadItem {
        return fragmentAdapter.getDownloadItem(selectedTabPosition)
    }

    private fun getAlsoAudioDownloadItem(finished: (it: DownloadItem) -> Unit) {
        try {
            val ff = fragmentAdapter.fragments[0] as DownloadAudioFragment
            getDownloadItem(1).videoPreferences.audioFormatIDs.apply {
                if (this.isNotEmpty()) {
                    ff.updateSelectedAudioFormat(this.first())
                }
            }
            finished(ff.downloadItem)
        }catch (e: Exception){
            val fragmentLifecycleCallback = object:
                FragmentManager.FragmentLifecycleCallbacks() {

                override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                    fragmentManager?.unregisterFragmentLifecycleCallbacks(this)
                    val ff = (f as DownloadAudioFragment)
                    ff.requireView().post {
                        ff.updateSelectedAudioFormat(getDownloadItem(1).videoPreferences.audioFormatIDs.first())
                        finished(ff.downloadItem)
                    }
                    super.onFragmentStarted(fm, f)
                }


            }

            fragmentManager?.registerFragmentLifecycleCallbacks(fragmentLifecycleCallback, true)
            viewPager2.setCurrentItem(0, true)
        }
    }

    private fun initUpdateData() {
        kotlin.runCatching {
            if (result.url.isBlank()) {
                dismiss()
                return
            }
            if (resultViewModel.updatingData.value) return

            lifecycleScope.launch(Dispatchers.IO) {
                resultViewModel.updateItemData(result)
            }
        }
    }

    private fun initUpdateFormats(res: ResultItem){
        kotlin.runCatching {
            CoroutineScope(SupervisorJob()).launch(Dispatchers.IO) {
                try {
                    resultViewModel.cancelUpdateFormatsItemData()
                } catch (_: Exception) {}
                resultViewModel.updateFormatItemData(res)
            }
        }
    }

    private data class QuickQualityOption(
        val label: String,
        val maxHeight: Int?,
        val estimatedBytes: Long,
        val bitrate: Int? = null,
        val formatId: String? = null
    ) {
        val displayLabel: String
            get() {
                val sizeStr = QuickQualityOption.formatBytes(estimatedBytes)
                return if (sizeStr.isNotEmpty()) "$label  •  $sizeStr" else label
            }

        companion object {
            fun formatBytes(bytes: Long): String {
                if (bytes <= 0) return "غير معروف"
                val kb = bytes / 1024.0
                val mb = kb / 1024.0
                val gb = mb / 1024.0
                return when {
                    gb >= 1 -> String.format("%.1f GB", gb)
                    mb >= 1 -> String.format("%.0f MB", mb)
                    kb >= 1 -> String.format("%.0f KB", kb)
                    else -> "$bytes B"
                }
            }
        }
    }

    private fun parseHeightLabel(label: String): Int? {
        val digits = label.filter { it.isDigit() }
        return digits.toIntOrNull()
    }

    private fun parseDurationSeconds(duration: String): Long {
        if (duration.isBlank()) return 0L
        val parts = duration.split(":").mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            1 -> parts[0]
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0L
        }
    }

    private fun getEstimatedFormatSize(f: com.deniscerri.ytdl.database.models.Format, bestAudioSize: Long = 0L): Long {
        var size = f.filesize
        if (size <= 0L) {
            val tbrNum = f.tbr?.replace("k", "")?.toDoubleOrNull() ?: 0.0
            val durationSec = parseDurationSeconds(result.duration)
            if (tbrNum > 0.0 && durationSec > 0) {
                size = ((tbrNum * 1000.0 / 8.0) * durationSec).toLong()
            }
        }
        val isVideoOnly = (f.vcodec.isNotEmpty() && f.vcodec != "none") && (f.acodec.isEmpty() || f.acodec == "none")
        if (isVideoOnly && size > 0L && bestAudioSize > 0L) {
            size += bestAudioSize
        }
        return size
    }

    private var showAllQuickFormats: Boolean = false

    private fun buildVideoQualityOptions(showAll: Boolean = false): List<QuickQualityOption> {
        val videoCandidates = result.formats.filter { f ->
            val hasVideo = (f.height ?: 0) > 0 || (f.vcodec.isNotEmpty() && f.vcodec != "none")
            hasVideo && !f.format_note.contains("storyboard", ignoreCase = true)
        }

        if (videoCandidates.isEmpty()) {
            val durationSec = parseDurationSeconds(result.duration)
            fun estVid(h: Int): Long {
                if (durationSec <= 0L) return 0L
                val kbps = when(h) {
                    2160 -> 18000L
                    1440 -> 9000L
                    1080 -> 4000L
                    720 -> 2200L
                    480 -> 1000L
                    360 -> 600L
                    240 -> 350L
                    144 -> 200L
                    else -> (h * 3.5).toLong()
                }
                return ((kbps + 128L) * 1000L / 8L) * durationSec
            }

            val list = mutableListOf(
                QuickQualityOption(
                    label = "1080p (Full HD)",
                    maxHeight = 1080,
                    estimatedBytes = estVid(1080),
                    formatId = "bestvideo[height<=1080]+bestaudio/best"
                ),
                QuickQualityOption(
                    label = "720p (HD)",
                    maxHeight = 720,
                    estimatedBytes = estVid(720),
                    formatId = "bestvideo[height<=720]+bestaudio/best"
                ),
                QuickQualityOption(
                    label = "480p (SD)",
                    maxHeight = 480,
                    estimatedBytes = estVid(480),
                    formatId = "bestvideo[height<=480]+bestaudio/best"
                ),
                QuickQualityOption(
                    label = "360p (SD)",
                    maxHeight = 360,
                    estimatedBytes = estVid(360),
                    formatId = "bestvideo[height<=360]+bestaudio/best"
                )
            )
            if (showAll) {
                list.add(0,
                    QuickQualityOption(
                        label = "2160p (4K Ultra HD)",
                        maxHeight = 2160,
                        estimatedBytes = estVid(2160),
                        formatId = "bestvideo[height<=2160]+bestaudio/best"
                    )
                )
                list.add(1,
                    QuickQualityOption(
                        label = "1440p (2K Quad HD)",
                        maxHeight = 1440,
                        estimatedBytes = estVid(1440),
                        formatId = "bestvideo[height<=1440]+bestaudio/best"
                    )
                )
                list.add(
                    QuickQualityOption(
                        label = "240p",
                        maxHeight = 240,
                        estimatedBytes = estVid(240),
                        formatId = "bestvideo[height<=240]+bestaudio/best"
                    )
                )
                list.add(
                    QuickQualityOption(
                        label = "144p",
                        maxHeight = 144,
                        estimatedBytes = estVid(144),
                        formatId = "bestvideo[height<=144]+bestaudio/best"
                    )
                )
            }
            return list
        }

        val audioCandidates = result.formats.filter { f ->
            val isAudioOnly = (f.vcodec.isNullOrEmpty() || f.vcodec == "none") &&
                    (f.acodec.isNotEmpty() && f.acodec != "none" || f.format_note.contains("audio", ignoreCase = true))
            isAudioOnly && !f.format_note.contains("storyboard", ignoreCase = true)
        }
        val bestAudioFormat = audioCandidates.maxByOrNull { parseAudioBitrate(it) }
        var bestAudioSize = bestAudioFormat?.filesize ?: 0L
        if (bestAudioSize <= 0L && bestAudioFormat != null) {
            val abr = parseAudioBitrate(bestAudioFormat)
            val durationSec = parseDurationSeconds(result.duration)
            if (abr > 0 && durationSec > 0) {
                bestAudioSize = ((abr * 1000.0 / 8.0) * durationSec).toLong()
            }
        }

        val sorted = videoCandidates.sortedWith(
            compareByDescending<com.deniscerri.ytdl.database.models.Format> { it.height ?: parseHeightLabel(it.format_note) ?: 0 }
                .thenByDescending { it.fps?.filter { c -> c.isDigit() }?.toIntOrNull() ?: 0 }
                .thenByDescending { getEstimatedFormatSize(it, bestAudioSize) }
        )

        val seenHeights = mutableSetOf<Int>()
        val options = mutableListOf<QuickQualityOption>()

        for (f in sorted) {
            val height = f.height ?: parseHeightLabel(f.format_note) ?: 0
            if (height > 0) {
                if (seenHeights.add(height)) {
                    val fps = f.fps?.filter { c -> c.isDigit() }?.toIntOrNull() ?: 0
                    val fpsLabel = if (fps >= 50) " (${fps}fps)" else ""
                    val label = when (height) {
                        2160 -> "2160p (4K$fpsLabel)"
                        1440 -> "1440p (2K$fpsLabel)"
                        1080 -> "1080p (Full HD$fpsLabel)"
                        720 -> "720p (HD$fpsLabel)"
                        480 -> "480p$fpsLabel"
                        360 -> "360p$fpsLabel"
                        240 -> "240p$fpsLabel"
                        144 -> "144p$fpsLabel"
                        else -> "${height}p$fpsLabel"
                    }
                    val estimatedSize = getEstimatedFormatSize(f, bestAudioSize)
                    options.add(
                        QuickQualityOption(
                            label = label,
                            maxHeight = height,
                            estimatedBytes = estimatedSize,
                            formatId = f.format_id
                        )
                    )
                }
            } else if (f.format_note.isNotBlank() && seenHeights.add(f.format_note.hashCode())) {
                val label = f.format_note
                val estimatedSize = getEstimatedFormatSize(f, bestAudioSize)
                options.add(
                    QuickQualityOption(
                        label = label,
                        maxHeight = null,
                        estimatedBytes = estimatedSize,
                        formatId = f.format_id
                    )
                )
            }
        }

        if (!showAll && options.size > 4) {
            return options.take(4)
        }

        return if (options.isNotEmpty()) options else listOf(
            QuickQualityOption(
                label = getString(R.string.quick_mode_quality_best),
                maxHeight = null,
                estimatedBytes = 0L,
                formatId = "bestvideo+bestaudio/best"
            )
        )
    }

    private fun buildAudioQualityOptions(showAll: Boolean = false): List<QuickQualityOption> {
        val audioCandidates = result.formats.filter { f ->
            val isAudioOnly = (f.vcodec.isNullOrEmpty() || f.vcodec == "none") &&
                    (f.acodec.isNotEmpty() && f.acodec != "none" || f.format_note.contains("audio", ignoreCase = true))
            isAudioOnly && !f.format_note.contains("storyboard", ignoreCase = true)
        }

        if (audioCandidates.isEmpty()) {
            val durationSec = parseDurationSeconds(result.duration)
            fun estAud(kbps: Long): Long {
                if (durationSec <= 0L) return 0L
                return (kbps * 1000L / 8L) * durationSec
            }

            val list = mutableListOf(
                QuickQualityOption(
                    label = "320 kbps (HQ Audio)",
                    maxHeight = null,
                    estimatedBytes = estAud(320L),
                    bitrate = 320,
                    formatId = "bestaudio/best"
                ),
                QuickQualityOption(
                    label = "128 kbps (Standard)",
                    maxHeight = null,
                    estimatedBytes = estAud(128L),
                    bitrate = 128,
                    formatId = "bestaudio[abr<=128]/best"
                )
            )
            if (showAll) {
                list.add(
                    QuickQualityOption(
                        label = "70 kbps (Lite)",
                        maxHeight = null,
                        estimatedBytes = estAud(70L),
                        bitrate = 70,
                        formatId = "bestaudio[abr<=70]/best"
                    )
                )
            }
            return list
        }

        val byBitrateDescending = audioCandidates.sortedByDescending { parseAudioBitrate(it) }
        val seenBitrates = mutableSetOf<Int>()
        val options = mutableListOf<QuickQualityOption>()

        for (f in byBitrateDescending) {
            val abr = parseAudioBitrate(f)
            val bucket = if (abr > 0) ((abr + 8) / 16) * 16 else 0
            if (bucket > 0 && seenBitrates.add(bucket)) {
                val containerStr = f.container.ifBlank { "M4A" }.uppercase()
                val label = "$abr kbps ($containerStr)"

                var size = f.filesize
                if (size <= 0L) {
                    val durationSec = parseDurationSeconds(result.duration)
                    if (abr > 0 && durationSec > 0) {
                        size = ((abr * 1000.0 / 8.0) * durationSec).toLong()
                    }
                }

                options.add(
                    QuickQualityOption(
                        label = label,
                        maxHeight = null,
                        estimatedBytes = size,
                        bitrate = abr,
                        formatId = f.format_id
                    )
                )
            } else if (bucket == 0 && options.isEmpty()) {
                val label = f.format_note.ifBlank { "Audio" }
                options.add(
                    QuickQualityOption(
                        label = label,
                        maxHeight = null,
                        estimatedBytes = f.filesize,
                        bitrate = null,
                        formatId = f.format_id
                    )
                )
            }
        }

        if (!showAll && options.size > 2) {
            return options.take(2)
        }

        return if (options.isNotEmpty()) options else listOf(
            QuickQualityOption(
                label = getString(R.string.quick_mode_quality_best),
                maxHeight = null,
                estimatedBytes = 0L,
                bitrate = 0,
                formatId = "bestaudio/best"
            )
        )
    }

    private fun parseAudioBitrate(f: com.deniscerri.ytdl.database.models.Format): Int {
        val tbrNum = f.tbr?.replace("k", "")?.toDoubleOrNull()?.toInt() ?: 0
        if (tbrNum > 0) return tbrNum
        val noteMatch = "(\\d+)k".toRegex(RegexOption.IGNORE_CASE).find(f.format_note)
        if (noteMatch != null) {
            return noteMatch.groupValues[1].toIntOrNull() ?: 0
        }
        return 0
    }

    private var selectedQuickOption: QuickQualityOption? = null
    private var selectedQuickIsAudio: Boolean = false

    private fun refreshQuickRadioGroups(
        audioContainer: LinearLayout,
        videoContainer: LinearLayout,
        loadingBar: ProgressBar,
        downloadBtn: com.google.android.material.button.MaterialButton? = null
    ) {
        audioContainer.removeAllViews()
        videoContainer.removeAllViews()

        val audioOptions = buildAudioQualityOptions(showAllQuickFormats)
        val videoOptions = buildVideoQualityOptions(showAllQuickFormats)

        if (result.formats.isNotEmpty()) {
            loadingBar.visibility = View.GONE
        }

        // Set default selection if none selected
        if (selectedQuickOption == null) {
            if (type == DownloadType.audio) {
                selectedQuickOption = audioOptions.firstOrNull()
                selectedQuickIsAudio = true
            } else {
                selectedQuickOption = videoOptions.find { it.maxHeight == 1080 }
                    ?: videoOptions.find { it.maxHeight == 720 }
                    ?: videoOptions.firstOrNull()
                selectedQuickIsAudio = false
            }
        }

        fun updateDownloadButtonText() {
            downloadBtn?.let { btn ->
                val opt = selectedQuickOption
                if (opt != null) {
                    val sizeStr = if (opt.estimatedBytes > 0) " • ${formatBytes(opt.estimatedBytes)}" else ""
                    btn.text = "تنزيل (${opt.label}$sizeStr)"
                } else {
                    btn.text = "بدء التنزيل"
                }
            }
        }

        updateDownloadButtonText()

        val allRowBinders = mutableListOf<() -> Unit>()

        // Render Audio Rows
        for (opt in audioOptions) {
            val rowView = layoutInflater.inflate(R.layout.item_quick_quality_row, audioContainer, false)
            val card = rowView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.quality_row_card)
            val radio = rowView.findViewById<RadioButton>(R.id.quality_radio)
            val sizeText = rowView.findViewById<TextView>(R.id.quality_size)
            val labelText = rowView.findViewById<TextView>(R.id.quality_label)
            val iconView = rowView.findViewById<ImageView>(R.id.quality_icon)

            iconView.setImageResource(R.drawable.ic_music)
            labelText.text = opt.label
            sizeText.text = if (opt.estimatedBytes > 0) formatBytes(opt.estimatedBytes) else "--"

            val isCurrentSelected = selectedQuickIsAudio && selectedQuickOption?.formatId == opt.formatId
            radio.isChecked = isCurrentSelected
            updateCardHighlight(card, isCurrentSelected)

            val binder = {
                val isSelected = selectedQuickIsAudio && selectedQuickOption?.formatId == opt.formatId
                radio.isChecked = isSelected
                updateCardHighlight(card, isSelected)
            }
            allRowBinders.add(binder)

            val clickListener = View.OnClickListener {
                selectedQuickOption = opt
                selectedQuickIsAudio = true
                for (b in allRowBinders) b()
                updateDownloadButtonText()
            }
            card.setOnClickListener(clickListener)
            radio.setOnClickListener(clickListener)

            audioContainer.addView(rowView)
        }

        // Render Video Rows
        for (opt in videoOptions) {
            val rowView = layoutInflater.inflate(R.layout.item_quick_quality_row, videoContainer, false)
            val card = rowView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.quality_row_card)
            val radio = rowView.findViewById<RadioButton>(R.id.quality_radio)
            val sizeText = rowView.findViewById<TextView>(R.id.quality_size)
            val labelText = rowView.findViewById<TextView>(R.id.quality_label)
            val iconView = rowView.findViewById<ImageView>(R.id.quality_icon)

            iconView.setImageResource(R.drawable.ic_video)
            labelText.text = opt.label
            sizeText.text = if (opt.estimatedBytes > 0) formatBytes(opt.estimatedBytes) else "--"

            val isCurrentSelected = !selectedQuickIsAudio && (
                selectedQuickOption?.formatId == opt.formatId ||
                (selectedQuickOption?.maxHeight != null && selectedQuickOption?.maxHeight == opt.maxHeight)
            )
            radio.isChecked = isCurrentSelected
            updateCardHighlight(card, isCurrentSelected)

            val binder = {
                val isSelected = !selectedQuickIsAudio && (
                    selectedQuickOption?.formatId == opt.formatId ||
                    (selectedQuickOption?.maxHeight != null && selectedQuickOption?.maxHeight == opt.maxHeight)
                )
                radio.isChecked = isSelected
                updateCardHighlight(card, isSelected)
            }
            allRowBinders.add(binder)

            val clickListener = View.OnClickListener {
                selectedQuickOption = opt
                selectedQuickIsAudio = false
                for (b in allRowBinders) b()
                updateDownloadButtonText()
            }
            card.setOnClickListener(clickListener)
            radio.setOnClickListener(clickListener)

            videoContainer.addView(rowView)
        }
    }

    private fun updateCardHighlight(card: com.google.android.material.card.MaterialCardView, isSelected: Boolean) {
        if (isSelected) {
            card.strokeColor = ContextCompat.getColor(requireContext(), R.color.mwyd_theme_dark_primary)
            card.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.mwyd_theme_dark_surfaceVariant))
        } else {
            card.strokeColor = android.graphics.Color.TRANSPARENT
            card.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }

    private fun formatBytes(bytes: Long): String {
        return QuickQualityOption.formatBytes(bytes)
    }

    private fun initQuickMode() {
        val titleView = view.findViewById<TextView>(R.id.quick_title)
        val subtitleView = view.findViewById<TextView>(R.id.quick_subtitle)
        val audioContainer = view.findViewById<LinearLayout>(R.id.quick_audio_options_container)
        val videoContainer = view.findViewById<LinearLayout>(R.id.quick_video_options_container)
        val loadingBar = view.findViewById<ProgressBar>(R.id.quick_loading)
        val downloadBtn = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.quick_download_btn)
        val moreOptionsBtn = view.findViewById<View>(R.id.quick_more_options)
        val moreText = view.findViewById<TextView>(R.id.quick_more_text)
        val moreArrow = view.findViewById<ImageView>(R.id.quick_more_arrow)

        titleView.text = result.title.ifBlank { result.url }
        val parts = mutableListOf<String>()
        if (result.author.isNotBlank()) parts.add(result.author)
        if (result.duration.isNotBlank()) parts.add(result.duration)
        subtitleView.text = parts.joinToString(" • ")

        refreshQuickRadioGroups(audioContainer, videoContainer, loadingBar, downloadBtn)

        if (result.formats.isNotEmpty()) {
            loadingBar.visibility = View.GONE
        } else {
            loadingBar.visibility = View.VISIBLE
        }

        if (result.title.isBlank() || result.formats.isEmpty()) {
            initUpdateData()
        }

        lifecycleScope.launch {
            resultViewModel.updateResultData.collectLatest { list ->
                val currentYtId = result.url.getIDFromYoutubeURL()
                val fresh = list?.filterNotNull()?.firstOrNull { item ->
                    item.url == result.url || (currentYtId != null && item.url.getIDFromYoutubeURL() == currentYtId)
                } ?: list?.filterNotNull()?.firstOrNull()

                if (fresh != null) {
                    result = fresh
                    titleView.text = fresh.title.ifBlank { fresh.url }
                    val p = mutableListOf<String>()
                    if (fresh.author.isNotBlank()) p.add(fresh.author)
                    if (fresh.duration.isNotBlank()) p.add(fresh.duration)
                    subtitleView.text = p.joinToString(" • ")
                    if (fresh.formats.isNotEmpty()) {
                        refreshQuickRadioGroups(audioContainer, videoContainer, loadingBar, downloadBtn)
                        loadingBar.visibility = View.GONE
                    }
                }
            }
        }

        lifecycleScope.launch {
            resultViewModel.updateFormatsResultData.collectLatest { formats ->
                if (formats != null && formats.isNotEmpty()) {
                    result.formats = formats.toMutableList()
                    refreshQuickRadioGroups(audioContainer, videoContainer, loadingBar, downloadBtn)
                    loadingBar.visibility = View.GONE
                }
            }
        }

        lifecycleScope.launch {
            resultViewModel.updatingFormats.collectLatest { isUpdating ->
                if (!isUpdating) {
                    loadingBar.visibility = View.GONE
                    if (result.formats.isEmpty() && result.url.isNotBlank()) {
                        val currentYtId = result.url.getIDFromYoutubeURL()
                        val fresh = withContext(Dispatchers.IO) {
                            val items = resultViewModel.getAllByURL(result.url)
                            if (items.isNotEmpty()) items.first() else {
                                if (currentYtId != null) {
                                    val all = resultViewModel.getAllIds()
                                    all.mapNotNull { resultViewModel.getByID(it) }.firstOrNull { it.url.getIDFromYoutubeURL() == currentYtId }
                                } else null
                            }
                        }
                        if (fresh != null && fresh.formats.isNotEmpty()) {
                            result = fresh
                            titleView.text = fresh.title.ifBlank { fresh.url }
                            val p = mutableListOf<String>()
                            if (fresh.author.isNotBlank()) p.add(fresh.author)
                            if (fresh.duration.isNotBlank()) p.add(fresh.duration)
                            subtitleView.text = p.joinToString(" • ")
                            refreshQuickRadioGroups(audioContainer, videoContainer, loadingBar, downloadBtn)
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            resultViewModel.uiState.collectLatest { state ->
                if (state.errorMessage != null) {
                    loadingBar.visibility = View.GONE
                }
            }
        }

        // Quick Custom Options: Cut, Subtitles, Dubbing / Audio Language
        val cutBtn = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.quick_btn_cut)
        val cutText = view.findViewById<TextView>(R.id.quick_cut_text)
        val subsBtn = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.quick_btn_subs)
        val subsText = view.findViewById<TextView>(R.id.quick_subs_text)
        val dubbingBtn = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.quick_btn_dubbing)
        val dubbingText = view.findViewById<TextView>(R.id.quick_dubbing_text)

        cutBtn?.setOnClickListener {
            val dummyItem = downloadViewModel.createDownloadItemFromResult(
                result = result,
                givenType = if (selectedQuickIsAudio) DownloadType.audio else DownloadType.video
            )
            val cutSheet = CutVideoBottomSheetDialog(
                dummyItem,
                result.urls.ifBlank { result.url },
                result.chapters,
                object : VideoCutListener {
                    override fun onChangeCut(list: List<String>) {
                        quickCuts = list
                        if (quickCuts.isNotEmpty()) {
                            cutText?.text = "محدد (${quickCuts.size})"
                            cutBtn.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.mwyd_theme_dark_primaryContainer))
                        } else {
                            cutText?.text = "القص"
                            cutBtn.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.mwyd_theme_dark_surfaceVariant))
                        }
                    }
                }
            )
            cutSheet.show(parentFragmentManager, "cutVideoSheet")
        }

        subsBtn?.setOnClickListener {
            val availableSubs = result.availableSubtitles.ifEmpty { listOf("ar", "en") }
            UiUtil.showSubtitleLanguagesDialog(requireActivity(), availableSubs, quickSubsLanguages) { chosenLang ->
                quickSubsLanguages = chosenLang
                quickSubsEnabled = true
                subsText?.text = "الترجمة ($chosenLang)"
                subsBtn.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.mwyd_theme_dark_primaryContainer))
            }
        }

        dubbingBtn?.setOnClickListener {
            val audioFormats = result.formats.filter { f ->
                (f.vcodec.isNullOrEmpty() || f.vcodec == "none") && (f.acodec.isNotEmpty() && f.acodec != "none")
            }
            val audioLanguages = audioFormats.mapNotNull { it.lang?.ifBlank { null } }.distinct()
            if (audioLanguages.size > 1) {
                val items = audioLanguages.toTypedArray()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("اختر لغة الصوت / الدبلجة")
                    .setItems(items) { _, which ->
                        val selectedLang = items[which]
                        val matched = audioFormats.find { it.lang == selectedLang }
                        quickSelectedAudioFormat = matched?.format_id
                        quickSelectedAudioLanguage = selectedLang
                        dubbingText?.text = "صوت: $selectedLang"
                        dubbingBtn.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.mwyd_theme_dark_primaryContainer))
                    }
                    .show()
            } else {
                Toast.makeText(requireContext(), "هذا الفيديو يحتوي على مسار صوتي رئيسي فقط", Toast.LENGTH_SHORT).show()
            }
        }

        downloadBtn.setOnClickListener {
            downloadBtn.isEnabled = false
            val chosenType = if (selectedQuickIsAudio) DownloadType.audio else DownloadType.video
            val chosenOption = selectedQuickOption

            lifecycleScope.launch(Dispatchers.IO) {
                val baseItem = downloadViewModel.createDownloadItemFromResult(
                    result = result,
                    givenType = chosenType
                )
                val finalItem = applyQuickOverrides(
                    baseItem,
                    chosenType,
                    chosenOption
                )
                val res = downloadViewModel.queueDownloads(listOf(finalItem), ignoreDuplicates = ignoreDuplicates)
                withContext(Dispatchers.Main) {
                    if (res.duplicateDownloadIDs.isNotEmpty() && !ignoreDuplicates) {
                        handleDuplicatesAndDismiss(res.duplicateDownloadIDs)
                    } else {
                        dismiss()
                    }
                }
            }
        }

        moreOptionsBtn.setOnClickListener {
            showAllQuickFormats = !showAllQuickFormats
            if (showAllQuickFormats) {
                moreText?.text = "عرض خيارات أقل"
                moreArrow?.rotation = 90f
            } else {
                moreText?.text = "المزيد من التنسيقات"
                moreArrow?.rotation = 0f
            }
            refreshQuickRadioGroups(audioContainer, videoContainer, loadingBar, downloadBtn)
        }
    }

    private fun applyQuickOverrides(
        item: DownloadItem,
        selectedType: DownloadType,
        chosenOption: QuickQualityOption?
    ): DownloadItem {
        val container = if (selectedType == DownloadType.audio) {
            sharedPreferences.getString("audio_format", "m4a") ?: "m4a"
        } else {
            sharedPreferences.getString("video_format", "mp4") ?: "mp4"
        }

        var updated = item.copy(type = selectedType, container = container)

        // Always automatically embed thumbnail
        updated.videoPreferences.embedThumbnail = true
        updated.audioPreferences.embedThumb = true

        // Apply cut sections if specified
        if (quickCuts.isNotEmpty()) {
            updated.downloadSections = quickCuts.joinToString(";")
        }

        // Apply subtitles if enabled
        if (quickSubsEnabled) {
            updated.videoPreferences.embedSubs = true
            updated.videoPreferences.writeSubs = true
            updated.videoPreferences.writeAutoSubs = true
            updated.videoPreferences.subsLanguages = quickSubsLanguages.ifEmpty { "all" }
        }

        if (quickSelectedAudioLanguage != null) {
            updated.videoPreferences.audioLanguage = quickSelectedAudioLanguage!!
            updated.audioPreferences.audioLanguage = quickSelectedAudioLanguage!!
        }

        if (selectedType == DownloadType.video) {
            val targetHeight = chosenOption?.maxHeight
            if (targetHeight != null && targetHeight > 0) {
                val videoCandidates = updated.allFormats.filter { f ->
                    val hasVideo = f.vcodec.isNotEmpty() && f.vcodec != "none"
                    hasVideo && !f.format_note.contains("storyboard", ignoreCase = true)
                }
                val matching = videoCandidates
                    .filter { (it.height ?: Int.MAX_VALUE) == targetHeight }
                    .maxByOrNull { it.fps?.filter(Char::isDigit)?.toIntOrNull() ?: 0 }
                    ?: videoCandidates
                        .filter { (it.height ?: Int.MAX_VALUE) <= targetHeight }
                        .maxByOrNull { it.height ?: 0 }

                updated = updated.copy(
                    format = (matching ?: updated.format).copy(
                        format_id = "${targetHeight}p_mwydgeneric",
                        container = container,
                        _height = targetHeight.toString()
                    )
                )
            } else if (chosenOption?.formatId != null) {
                val filterOrId = chosenOption.formatId
                val matched = updated.allFormats.find { it.format_id == filterOrId }
                if (matched != null) {
                    updated = updated.copy(format = matched.copy(container = container))
                } else {
                    updated = updated.copy(
                        format = updated.format.copy(
                            format_id = filterOrId,
                            container = container
                        )
                    )
                }
            }

            // Audio track / Dubbing override or default best audio
            if (quickSelectedAudioFormat != null) {
                updated.videoPreferences.audioFormatIDs.clear()
                updated.videoPreferences.audioFormatIDs.add(quickSelectedAudioFormat!!)
            } else {
                val audioCandidates = updated.allFormats.filter { f ->
                    val isAudioOnly = (f.vcodec.isNullOrEmpty() || f.vcodec == "none") &&
                            (f.acodec.isNotEmpty() && f.acodec != "none" || f.format_note.contains("audio", ignoreCase = true))
                    isAudioOnly && !f.format_note.contains("storyboard", ignoreCase = true)
                }
                val bestAudio = audioCandidates.maxByOrNull { parseAudioBitrate(it) }
                if (bestAudio != null) {
                    updated.videoPreferences.audioFormatIDs.clear()
                    updated.videoPreferences.audioFormatIDs.add(bestAudio.format_id)
                }
            }
        } else {
            // Audio mode
            if (quickSelectedAudioFormat != null) {
                val matched = updated.allFormats.find { it.format_id == quickSelectedAudioFormat }
                if (matched != null) {
                    updated = updated.copy(format = matched.copy(container = container))
                }
            } else if (chosenOption?.formatId != null) {
                val filterOrId = chosenOption.formatId
                val matched = updated.allFormats.find { it.format_id == filterOrId }
                if (matched != null) {
                    updated = updated.copy(format = matched.copy(container = container))
                } else {
                    val abr = chosenOption.bitrate ?: 128
                    updated = updated.copy(
                        format = updated.format.copy(
                            format_id = if (abr > 0) "${abr}kbps_mwydgeneric" else "ba",
                            container = container
                        )
                    )
                }
            }
        }

        return updated
    }

    override fun onDismiss(dialog: DialogInterface) {
        lifecycleScope.launch {
            resultViewModel.cancelUpdateItemData()
            resultViewModel.cancelUpdateFormatsItemData()
            super.onDismiss(dialog)
        }
    }

    private fun handleDuplicatesAndDismiss(res: List<DownloadViewModel.AlreadyExistsIDs>) {
        if (activity is ShareActivity && res.isNotEmpty()) {
            //let the lifecycle listener handle it
        }else{
            dismiss()
        }
    }
}

