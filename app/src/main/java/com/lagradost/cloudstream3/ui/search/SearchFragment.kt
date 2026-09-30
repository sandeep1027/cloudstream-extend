package com.lagradost.cloudstream3.ui.search

import android.app.Activity
import android.content.Intent
import android.content.DialogInterface
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKeys
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.FragmentSearchBinding
import com.lagradost.cloudstream3.databinding.SearchFilterSheetBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.BaseAdapter
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.home.HomeFragment
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.bindChips
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.currentSpan
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.loadHomepageList
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.updateChips
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.home.ParentItemAdapter
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiProviderLangSettings
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiSettings
import com.lagradost.cloudstream3.utils.AppContextUtils.ownHide
import com.lagradost.cloudstream3.utils.AppContextUtils.ownShow
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.getSpanCount
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import com.lagradost.cloudstream4.AppSettings
import java.util.Locale

class SearchFragment : BaseFragment<FragmentSearchBinding>(
    BaseFragment.BindingCreator.Bind(FragmentSearchBinding::bind)
) {
    companion object {
        fun List<SearchResponse>.filterSearchResponse(): List<SearchResponse> {
            return this.filter { response ->
                if (response is AnimeSearchResponse) {
                    val status = response.dubStatus
                    (status.isNullOrEmpty()) || (status.any {
                        APIRepository.dubStatusActive.contains(it)
                    })
                } else {
                    true
                }
            }
        }

        const val SEARCH_QUERY = "search_query"

        fun newInstance(query: String): Bundle {
            return Bundle().apply {
                if (query.isNotBlank()) putString(SEARCH_QUERY, query)
            }
        }
    }

    private val searchViewModel: SearchViewModel by activityViewModels()
    private var bottomSheetDialog: BottomSheetDialog? = null

    private val speechRecognizerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                val matches = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                if (!matches.isNullOrEmpty()) {
                    val recognizedText = matches[0]
                    binding?.mainSearch?.setQuery(recognizedText, true)
                }
            }
        }

    override fun pickLayout(): Int? =
        if (isLayout(TV or EMULATOR)) R.layout.fragment_search_tv else R.layout.fragment_search

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        activity?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        bottomSheetDialog?.ownShow()
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onDestroyView() {
        hideKeyboard()
        bottomSheetDialog?.ownHide()
        activity?.detachBackPressedCallback("SearchFragment")
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        searchViewModel.clearSuggestions()
        afterPluginsLoadedEvent += ::reloadRepos
        // Restore persisted filter state so results stay consistent across onResume.
        currentSearchFilter = loadPersistedFilter()
        refreshDisplayedResults()
    }

    override fun onStop() {
        super.onStop()
        afterPluginsLoadedEvent -= ::reloadRepos
    }

    var selectedSearchTypes = mutableListOf<TvType>()
    var selectedApis = mutableSetOf<String>()

    /** Currently-applied advanced search filters (quality exclusion, year range, sort). */
    var currentSearchFilter: SearchFilter = SearchFilter()
        private set

    /** Read the persisted filter state from [AppSettings]. */
    private fun loadPersistedFilter(): SearchFilter {
        val ctx = context ?: return SearchFilter()
        val prefs = AppSettings(ctx).ui
        val excluded = prefs.filterQuality.get()
        val yearMin = prefs.searchFilterYearMin.get()
        val yearMax = prefs.searchFilterYearMax.get()
        val sortMode = try {
            SearchSortMode.valueOf(prefs.searchFilterSortMode.get())
        } catch (_: IllegalArgumentException) {
            SearchSortMode.DEFAULT
        }
        return SearchFilter(
            excludedQualities = excluded,
            yearMin = yearMin.takeIf { it > 0 },
            yearMax = yearMax.takeIf { it > 0 },
            sortMode = sortMode,
        )
    }

    /**
     * Re-apply the current filter to whatever the ViewModel has already loaded.
     * Used when the filter sheet is dismissed without changing providers/types
     * (so we don't re-hit the network) and when the fragment is resumed.
     */
    private fun refreshDisplayedResults() {
        val b = binding ?: return
        val ctx = context ?: return

        // Advanced-search (per-provider) list
        val current = searchViewModel.currentSearch.value
        if (current != null) {
            renderAdvancedSearch(ctx, current)
        }

        // Flat (non-advanced) list
        val flat = searchViewModel.searchResponse.value
        if (flat is Resource.Success) {
            val filteredList = flat.value.list
                .filterSearchResponse()
                .applySearchFilter(currentSearchFilter)
            (b.searchAutofitResults.adapter as? SearchAdapter)?.submitList(filteredList)
        }
    }

    /**
     * Will filter all providers by preferred media and selectedSearchTypes.
     * If that results in no available providers then only filter
     * providers by preferred media
     **/
    fun search(query: String?) {
        if (query == null) return
        searchViewModel.clearSuggestions()
        // don't resume state from prev search
        (binding?.searchMasterRecycler?.adapter as? BaseAdapter<*, *>)?.clearState()
        context?.let { ctx ->
            val default = enumValues<TvType>().sorted().filter { it != TvType.NSFW }
                .map { it.ordinal.toString() }.toSet()
            val preferredTypes = (PreferenceManager.getDefaultSharedPreferences(ctx)
                .getStringSet(this.getString(R.string.prefer_media_type_key), default)
                ?.ifEmpty { default } ?: default)
                .mapNotNull { it.toIntOrNull() ?: return@mapNotNull null }

            val settings = ctx.getApiSettings()

            val notFilteredBySelectedTypes = selectedApis.filter { name ->
                settings.contains(name)
            }.map { name ->
                name to getApiFromNameNull(name)?.supportedTypes
            }.filter { (_, types) ->
                types?.any { preferredTypes.contains(it.ordinal) } == true
            }

            searchViewModel.searchAndCancel(
                query = query,
                providersActive = notFilteredBySelectedTypes.filter { (_, types) ->
                    types?.any { selectedSearchTypes.contains(it) } == true
                }.ifEmpty { notFilteredBySelectedTypes }.map { it.first }.toSet()
            )
        }
    }

    // Null if defined as a variable
    // This needs to be run after view created

    private fun reloadRepos(success: Boolean = false) = main {
        searchViewModel.reloadRepos()
        context?.filterProviderByPreferredMedia()?.let { validAPIs ->
            bindChips(
                binding?.tvtypesChipsScroll?.tvtypesChips,
                selectedSearchTypes,
                validAPIs.flatMap { api -> api.supportedTypes }.distinct()
            ) { list ->
                if (selectedSearchTypes.toSet() != list.toSet()) {
                    DataStoreHelper.searchPreferenceTags = list
                    selectedSearchTypes.clear()
                    selectedSearchTypes.addAll(list)
                    search(binding?.mainSearch?.query?.toString())
                }
            }
        }
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )

        // Fix grid
        currentSpan = view.context.getSpanCount()
        binding?.searchAutofitResults?.spanCount = currentSpan
        HomeFragment.configEvent.invoke()
    }

    override fun onBindingCreated(
        binding: FragmentSearchBinding,
        savedInstanceState: Bundle?
    ) {
        currentSearchFilter = loadPersistedFilter()

        reloadRepos()
        binding.apply {
            val adapter =
                SearchAdapter(
                    searchAutofitResults,
                ) { callback ->
                    SearchHelper.handleSearchClickCallback(callback)
                }

            searchRoot.findViewById<TextView>(androidx.appcompat.R.id.search_src_text)?.tag =
                "tv_no_focus_tag"
            searchAutofitResults.setRecycledViewPool(SearchAdapter.sharedPool)
            searchAutofitResults.adapter = adapter
            searchLoadingBar.alpha = 0f
        }

        binding.voiceSearch.setOnClickListener { searchView ->
            searchView?.context?.let { ctx ->
                try {
                    if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                        showToast(R.string.speech_recognition_unavailable)
                    } else {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                            )
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                            putExtra(
                                RecognizerIntent.EXTRA_PROMPT,
                                ctx.getString(R.string.begin_speaking)
                            )
                        }
                        speechRecognizerLauncher.launch(intent)
                    }
                } catch (_: Throwable) {
                    // launch may throw
                    showToast(R.string.speech_recognition_unavailable)
                }
            }
        }

        val searchExitIcon =
            binding.mainSearch.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)

        selectedApis = DataStoreHelper.searchPreferenceProviders.toMutableSet()

        binding.searchFilter.setOnClickListener { searchView ->
            searchView?.context?.let { ctx ->
                val validAPIs = ctx.filterProviderByPreferredMedia(hasHomePageIsRequired = false)
                var currentValidApis = listOf<MainAPI>()
                val currentSelectedApis = if (selectedApis.isEmpty()) validAPIs.map { it.name }
                    .toMutableSet() else selectedApis

                val builder = BottomSheetDialog(ctx)
                builder.behavior.state = BottomSheetBehavior.STATE_EXPANDED

                val sheetBinding: SearchFilterSheetBinding =
                    SearchFilterSheetBinding.inflate(builder.layoutInflater, null, false)
                builder.setContentView(sheetBinding.root)
                builder.show()

                // Prevent the parent SearchView from stealing focus from year fields.
                // We clear its focus and temporarily disable focusability while the sheet is open.
                binding.mainSearch.clearFocus()
                binding.mainSearch.isFocusable = false
                binding.mainSearch.isFocusableInTouchMode = false

                builder.let { dialog ->
                    val previousSelectedApis = selectedApis.toSet()
                    val previousSelectedSearchTypes = selectedSearchTypes.toSet()

                    // Snapshot the current filter so Cancel can roll back.
                    val appSettings = AppSettings(ctx)
                    var tempExcludedQualities =
                        appSettings.ui.filterQuality.get().toMutableSet()
                    var tempYearMin = appSettings.ui.searchFilterYearMin.get()
                    var tempYearMax = appSettings.ui.searchFilterYearMax.get()
                    var tempSortMode = try {
                        SearchSortMode.valueOf(appSettings.ui.searchFilterSortMode.get())
                    } catch (_: IllegalArgumentException) {
                        SearchSortMode.DEFAULT
                    }

                    val isMultiLang = ctx.getApiProviderLangSettings().let { set ->
                        set.size > 1 || set.contains(AllLanguagesName)
                    }

                    val cancelBtt = dialog.findViewById<MaterialButton>(R.id.cancel_btt)
                    val applyBtt = dialog.findViewById<MaterialButton>(R.id.apply_btt)
                    val resetBtt = dialog.findViewById<MaterialButton>(R.id.reset_btt)
                    val sortButton = dialog.findViewById<MaterialButton>(R.id.sort_button)
                    val yearMinEdit = sheetBinding.yearMin
                    val yearMaxEdit = sheetBinding.yearMax

                    val listView = dialog.findViewById<ListView>(R.id.listview1)
                    val arrayAdapter =
                        ArrayAdapter<String>(ctx, R.layout.sort_bottom_single_choice)
                    listView?.adapter = arrayAdapter
                    listView?.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE

                    listView?.setOnItemClickListener { _, _, i, _ ->
                        if (currentValidApis.isNotEmpty()) {
                            val api = currentValidApis[i].name
                            if (currentSelectedApis.contains(api)) {
                                listView.setItemChecked(i, false)
                                currentSelectedApis -= api
                            } else {
                                listView.setItemChecked(i, true)
                                currentSelectedApis += api
                            }
                        }
                    }

                    fun updateList(types: List<TvType>) {
                        DataStoreHelper.searchPreferenceTags = types

                        arrayAdapter.clear()
                        currentValidApis = validAPIs.filter { api ->
                            api.supportedTypes.any { types.contains(it) }
                        }.sortedBy { it.name.lowercase() }

                        val names = currentValidApis.map {
                            if (isMultiLang) "${
                                SubtitleHelper.getFlagFromIso(it.lang)?.plus(" ") ?: ""
                            }${it.name}" else it.name
                        }
                        for ((index, api) in currentValidApis.map { it.name }.withIndex()) {
                            listView?.setItemChecked(index, currentSelectedApis.contains(api))
                        }

                        arrayAdapter.addAll(names)
                        arrayAdapter.notifyDataSetChanged()
                    }

                    bindChips(
                        sheetBinding.tvtypesChipsScroll.tvtypesChips,
                        selectedSearchTypes,
                        validAPIs.flatMap { api -> api.supportedTypes }.distinct()
                    ) { list ->
                        updateList(list)

                        // refresh selected chips in main chips
                        if (selectedSearchTypes.toSet() != list.toSet()) {
                            selectedSearchTypes.clear()
                            selectedSearchTypes.addAll(list)
                            updateChips(
                                binding.tvtypesChipsScroll.tvtypesChips,
                                selectedSearchTypes
                            )
                        }
                    }

                    // ----- Quality chips -----
                    val qualityGroup = sheetBinding.qualityChips
                    qualityGroup.removeAllViews()
                    for (q in SearchQuality.entries) {
                        val chip = Chip(ctx).apply {
                            text = ctx.getString(q.toStringRes())
                            isCheckable = true
                            isChecked = tempExcludedQualities.contains(q)
                            setOnCheckedChangeListener { _, checked ->
                                if (checked) tempExcludedQualities.add(q)
                                else tempExcludedQualities.remove(q)
                            }
                        }
                        qualityGroup.addView(chip)
                    }

                    // ----- Year fields -----
                    if (tempYearMin > 0) yearMinEdit.setText(tempYearMin.toString())
                    if (tempYearMax > 0) yearMaxEdit.setText(tempYearMax.toString())

                    // ----- Sort button -----
                    fun sortModeLabel(mode: SearchSortMode): String = when (mode) {
                        SearchSortMode.DEFAULT -> ctx.getString(R.string.sort_default)
                        SearchSortMode.NAME_ASC -> ctx.getString(R.string.sort_alphabetical_a)
                        SearchSortMode.NAME_DESC -> ctx.getString(R.string.sort_alphabetical_z)
                        SearchSortMode.YEAR_DESC -> ctx.getString(R.string.sort_release_date_new)
                        SearchSortMode.YEAR_ASC -> ctx.getString(R.string.sort_release_date_old)
                        SearchSortMode.RATING_DESC -> ctx.getString(R.string.sort_rating_desc)
                        SearchSortMode.RATING_ASC -> ctx.getString(R.string.sort_rating_asc)
                    }

                    fun updateSortLabel() {
                        sortButton?.text = sortModeLabel(tempSortMode)
                    }
                    updateSortLabel()

                    sortButton?.setOnClickListener {
                        val modes = SearchSortMode.entries.toTypedArray()
                        val labels = modes.map { sortModeLabel(it) }.toTypedArray()
                        val checkedItem = modes.indexOf(tempSortMode)

                        AlertDialog.Builder(ctx)
                            .setTitle(R.string.search_filter_sort_label)
                            .setSingleChoiceItems(labels, checkedItem) { d, which ->
                                tempSortMode = modes[which]
                                updateSortLabel()
                                d.dismiss()
                            }
                            .show()
                    }

                    // ----- Reset button -----
                    resetBtt?.setOnClickListener {
                        tempExcludedQualities = mutableSetOf()
                        tempYearMin = -1
                        tempYearMax = -1
                        tempSortMode = SearchSortMode.DEFAULT

                        for (i in 0 until qualityGroup.childCount) {
                            (qualityGroup.getChildAt(i) as? Chip)?.isChecked = false
                        }
                        yearMinEdit.setText("")
                        yearMaxEdit.setText("")
                        updateSortLabel()

                        showToast(R.string.search_filter_reset_toast)
                    }

                    cancelBtt?.setOnClickListener {
                        dialog.dismissSafe()
                    }

                    applyBtt?.setOnClickListener {
                        val parsedYearMin = yearMinEdit.text.toString().toIntOrNull() ?: -1
                        val parsedYearMax = yearMaxEdit.text.toString().toIntOrNull() ?: -1

                        // Persist
                        val applySettings = AppSettings(ctx)
                        applySettings.ui.filterQuality.set(tempExcludedQualities)
                        applySettings.ui.searchFilterYearMin.set(parsedYearMin)
                        applySettings.ui.searchFilterYearMax.set(parsedYearMax)
                        applySettings.ui.searchFilterSortMode.set(tempSortMode.name)

                        // Apply in-memory
                        currentSearchFilter = SearchFilter(
                            excludedQualities = tempExcludedQualities,
                            yearMin = parsedYearMin.takeIf { it > 0 },
                            yearMax = parsedYearMax.takeIf { it > 0 },
                            sortMode = tempSortMode,
                        )

                        dialog.dismissSafe()
                    }

                    dialog.setOnDismissListener {
                        // Restore the SearchView's focusability now that the sheet is gone,
                        // then immediately clear focus so it doesn't auto-grab and pop the keyboard.
                        binding.mainSearch.isFocusable = true
                        binding.mainSearch.isFocusableInTouchMode = true
                        binding.mainSearch.clearFocus()
                        hideKeyboard()

                        DataStoreHelper.searchPreferenceProviders = currentSelectedApis.toList()
                        selectedApis = currentSelectedApis

                        // Re-run search if providers / types changed
                        if (previousSelectedApis != selectedApis.toSet() ||
                            previousSelectedSearchTypes != selectedSearchTypes.toSet()
                        ) {
                            search(binding.mainSearch.query.toString())
                        } else {
                            // Only filters changed — re-render current results.
                            refreshDisplayedResults()
                        }
                    }
                    updateList(selectedSearchTypes.toList())
                }
            }
        }

        val settingsManager = context?.let { PreferenceManager.getDefaultSharedPreferences(it) }
        val isAdvancedSearch = settingsManager?.getBoolean("advanced_search", true) ?: true
        val isSearchSuggestionsEnabled =
            settingsManager?.getBoolean("search_suggestions_enabled", true) ?: true

        selectedSearchTypes = DataStoreHelper.searchPreferenceTags.toMutableList()

        if (!isLayout(PHONE)) {
            binding.searchFilter.isFocusable = true
            binding.searchFilter.isFocusableInTouchMode = true
        }

        // Hide suggestions when search view loses focus (phone only)
        if (isLayout(PHONE)) {
            binding.mainSearch.setOnQueryTextFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    searchViewModel.clearSuggestions()
                }
            }
        }

        binding.mainSearch.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                search(query)
                searchViewModel.clearSuggestions()

                binding.mainSearch.let {
                    hideKeyboard(it)
                }

                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                val showHistory = newText.isBlank()
                if (showHistory) {
                    searchViewModel.clearSearch()
                    searchViewModel.updateHistory()
                    searchViewModel.clearSuggestions()
                } else {
                    if (isSearchSuggestionsEnabled) {
                        searchViewModel.fetchSuggestions(newText)
                    }
                }
                binding.apply {
                    searchHistoryRecycler.isVisible = showHistory
                    searchMasterRecycler.isVisible = !showHistory && isAdvancedSearch
                    searchAutofitResults.isVisible = !showHistory && !isAdvancedSearch
                    searchSuggestionsRecycler.isVisible = !showHistory && isSearchSuggestionsEnabled
                }

                return true
            }
        })

        observe(searchViewModel.searchResponse) {
            when (it) {
                is Resource.Success -> {
                    it.value.let { data ->
                        val list = data.list
                        if (list.isNotEmpty()) {
                            val filteredList = list
                                .filterSearchResponse()
                                .applySearchFilter(currentSearchFilter)
                            (binding.searchAutofitResults.adapter as? SearchAdapter)?.submitList(
                                filteredList
                            )
                        }
                    }
                    searchExitIcon?.alpha = 1f
                    binding.searchLoadingBar.alpha = 0f
                }

                is Resource.Failure -> {
                    searchExitIcon?.alpha = 1f
                    binding.searchLoadingBar.alpha = 0f
                }

                is Resource.Loading -> {
                    searchExitIcon?.alpha = 0f
                    binding.searchLoadingBar.alpha = 1f
                }
            }
        }

        observe(searchViewModel.currentSearch) { list ->
            try {
                renderAdvancedSearch(requireContext(), list)
            } catch (e: Exception) {
                logError(e)
            }
        }

        val masterAdapter =
            ParentItemAdapter(id = "masterAdapter".hashCode(), { callback ->
                SearchHelper.handleSearchClickCallback(callback)
            }, { item ->
                bottomSheetDialog = activity?.loadHomepageList(item, dismissCallback = {
                    bottomSheetDialog = null
                }, expandCallback = { name -> searchViewModel.expandAndReturn(name) })
            }, expandCallback = { name ->
                ioSafe {
                    searchViewModel.expandAndReturn(name)
                }
            })

        val historyAdapter = SearchHistoryAdaptor { click ->
            val searchItem = click.item
            when (click.clickAction) {
                SEARCH_HISTORY_OPEN -> {
                    if (searchItem == null) return@SearchHistoryAdaptor
                    searchViewModel.clearSearch()
                    if (searchItem.type.isNotEmpty())
                        updateChips(
                            binding.tvtypesChipsScroll.tvtypesChips,
                            searchItem.type.toMutableList()
                        )
                    binding.mainSearch.setQuery(searchItem.searchText, true)
                }

                SEARCH_HISTORY_REMOVE -> {
                    if (searchItem == null) return@SearchHistoryAdaptor
                    removeKey("$currentAccount/$SEARCH_HISTORY_KEY", searchItem.key)
                    searchViewModel.updateHistory()
                }

                SEARCH_HISTORY_CLEAR -> {
                    activity?.let { ctx ->
                        val builder: AlertDialog.Builder = AlertDialog.Builder(ctx)
                        val dialogClickListener =
                            DialogInterface.OnClickListener { _, which ->
                                when (which) {
                                    DialogInterface.BUTTON_POSITIVE -> {
                                        removeKeys("$currentAccount/$SEARCH_HISTORY_KEY")
                                        searchViewModel.updateHistory()
                                    }

                                    DialogInterface.BUTTON_NEGATIVE -> {
                                    }
                                }
                            }

                        try {
                            builder.setTitle(R.string.clear_history).setMessage(
                                ctx.getString(R.string.delete_message).format(
                                    ctx.getString(R.string.history)
                                )
                            )
                                .setPositiveButton(R.string.sort_clear, dialogClickListener)
                                .setNegativeButton(R.string.cancel, dialogClickListener)
                                .show().setDefaultFocus()
                        } catch (e: Exception) {
                            logError(e)
                        }
                    }
                }

                else -> {
                    // wth are you doing???
                }
            }
        }

        val suggestionAdapter = SearchSuggestionAdapter { callback ->
            when (callback.clickAction) {
                SEARCH_SUGGESTION_CLICK -> {
                    binding.mainSearch.setQuery(callback.suggestion, true)
                    searchViewModel.clearSuggestions()
                }
                SEARCH_SUGGESTION_FILL -> {
                    binding.mainSearch.setQuery(callback.suggestion, false)
                }
                SEARCH_SUGGESTION_CLEAR -> {
                    searchViewModel.clearSuggestions()
                }
            }
        }

        binding.apply {
            searchHistoryRecycler.adapter = historyAdapter
            searchHistoryRecycler.setLinearListLayout(isHorizontal = false, nextRight = FOCUS_SELF)

            searchSuggestionsRecycler.adapter = suggestionAdapter
            searchSuggestionsRecycler.layoutManager = LinearLayoutManager(context)

            searchMasterRecycler.setRecycledViewPool(ParentItemAdapter.sharedPool)
            searchMasterRecycler.adapter = masterAdapter

            searchMasterRecycler.layoutManager = GridLayoutManager(context, 1)

            var sq =
                arguments?.getString(SEARCH_QUERY) ?: savedInstanceState?.getString(SEARCH_QUERY)
            if (sq.isNullOrBlank()) {
                sq = MainActivity.nextSearchQuery
            }

            sq?.let { query ->
                if (query.isBlank()) return@let

                mainSearch.doOnLayout {
                    mainSearch.setQuery(query, true)
                }
                arguments?.remove(SEARCH_QUERY)
                savedInstanceState?.remove(SEARCH_QUERY)
                MainActivity.nextSearchQuery = null
            }
        }

        observe(searchViewModel.currentHistory) { list ->
            (binding.searchHistoryRecycler.adapter as? SearchHistoryAdaptor?)?.submitList(list)
            if (list.isNotEmpty()) {
                binding.searchHistoryRecycler.scrollToPosition(0)
            }
        }

        observe(searchViewModel.searchSuggestions) { suggestions ->
            val hasSuggestions = suggestions.isNotEmpty()
            binding.searchSuggestionsRecycler.isVisible = hasSuggestions
            (binding.searchSuggestionsRecycler.adapter as? SearchSuggestionAdapter?)?.submitList(
                suggestions
            )

            if (!isLayout(PHONE)) {
                if (hasSuggestions) {
                    binding.tvtypesChipsScroll.tvtypesChips.root.nextFocusDownId =
                        R.id.search_suggestions_recycler
                    activity?.attachBackPressedCallback("SearchFragment") {
                        searchViewModel.clearSuggestions()
                    }
                } else {
                    binding.tvtypesChipsScroll.tvtypesChips.root.nextFocusDownId =
                        R.id.search_history_recycler
                    activity?.detachBackPressedCallback("SearchFragment")
                }
            }
        }

        searchViewModel.updateHistory()
    }

    /**
     * Render the advanced-search (per-provider) list applying the current filter.
     * Pulled out of the observer so it can be reused when only the filter changes.
     */
    private fun renderAdvancedSearch(
        ctx: android.content.Context,
        list: Map<String, ExpandableSearchList>,
    ) {
        val b = binding ?: return
        val pinnedOrder = DataStoreHelper.pinnedProviders.reversedArray()

        val sortedList = list.toList().sortedWith(compareBy { (providerName, _) ->
            val index = pinnedOrder.indexOf(providerName)
            if (index == -1) Int.MAX_VALUE else index
        })

        (b.searchMasterRecycler.adapter as? ParentItemAdapter)?.apply {
            val newItems = sortedList.map { (providerName, providerData) ->
                val dataList = providerData.list
                    .filterSearchResponse()
                    .applySearchFilter(currentSearchFilter)

                val homePageList = HomePageList(providerName, dataList)

                HomeViewModel.ExpandableHomepageList(
                    homePageList,
                    providerData.currentPage,
                    providerData.hasNext
                )
            }

            submitList(newItems)
        }
        // suppress unused warning — ctx is kept for symmetry with the observer lambda
        @Suppress("UNUSED_EXPRESSION") ctx
    }
}

// Local alias so we don't have to import SettingsUIScreen in every call site.
private fun SearchQuality.toStringRes(): Int = when (this) {
    SearchQuality.BlueRay -> R.string.quality_blueray
    SearchQuality.Cam -> R.string.quality_cam
    SearchQuality.CamRip -> R.string.quality_cam_rip
    SearchQuality.DVD -> R.string.quality_dvd
    SearchQuality.HD -> R.string.quality_hd
    SearchQuality.HQ -> R.string.quality_hq
    SearchQuality.HdCam -> R.string.quality_cam_hd
    SearchQuality.Telecine -> R.string.quality_tc
    SearchQuality.Telesync -> R.string.quality_ts
    SearchQuality.WorkPrint -> R.string.quality_workprint
    SearchQuality.SD -> R.string.quality_sd
    SearchQuality.FourK -> R.string.quality_4k
    SearchQuality.UHD -> R.string.quality_uhd
    SearchQuality.SDR -> R.string.quality_sdr
    SearchQuality.HDR -> R.string.quality_hdr
    SearchQuality.WebRip -> R.string.quality_webrip
}
