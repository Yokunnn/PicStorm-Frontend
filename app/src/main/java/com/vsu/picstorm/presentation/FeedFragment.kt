package com.vsu.picstorm.presentation

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.auth0.android.jwt.JWT
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase
import com.skydoves.powerspinner.PowerSpinnerView
import com.vsu.picstorm.R
import com.vsu.picstorm.databinding.FragmentDialogAlertBinding
import com.vsu.picstorm.databinding.FragmentDialogConfirmBinding
import com.vsu.picstorm.databinding.FragmentDialogPhotoLoadBinding
import com.vsu.picstorm.databinding.FragmentFeedBinding
import com.vsu.picstorm.domain.TokenStorage
import com.vsu.picstorm.domain.model.enums.DateFilterType
import com.vsu.picstorm.domain.model.enums.HttpStatus
import com.vsu.picstorm.domain.model.enums.SortFilterType
import com.vsu.picstorm.domain.model.enums.UserFilterType
import com.vsu.picstorm.presentation.adapter.FeedAdapter
import com.vsu.picstorm.util.ApiStatus
import com.vsu.picstorm.util.DialogFactory
import com.vsu.picstorm.viewmodel.FeedViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FeedFragment : Fragment() {

    private val feedViewModel: FeedViewModel by viewModels()
    private lateinit var binding: FragmentFeedBinding
    private lateinit var alertBinding: FragmentDialogAlertBinding
    private lateinit var alertRecycleBinding: FragmentDialogAlertBinding
    private lateinit var confirmBanBinding: FragmentDialogConfirmBinding
    private lateinit var confirmDeleteBinding: FragmentDialogConfirmBinding
    private lateinit var photoAlertBinding: FragmentDialogPhotoLoadBinding
    private lateinit var dialog: Dialog
    private lateinit var loadDialog: Dialog
    private lateinit var tokenStorage: TokenStorage
    private lateinit var feedSpinner: PowerSpinnerView
    private lateinit var filterDateSpinner: PowerSpinnerView
    private lateinit var filterRatingSpinner: PowerSpinnerView

    private var accessToken: String? = null
    private lateinit var dateFilterType: DateFilterType
    private lateinit var sortFilterType: SortFilterType
    private lateinit var userFilterType: UserFilterType
    private val pageSize: Int = 5
    private var lastPage: Int = 0
    private lateinit var feedAdapter: FeedAdapter
    private var photoLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val intent: Intent? = result.data
                intent?.data?.let { uploadPhoto(it) }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        feedViewModel.init()
        binding = FragmentFeedBinding.inflate(inflater, container, false)
        tokenStorage = TokenStorage(this.requireContext())

        feedSpinner = binding.feedSpinner
        filterDateSpinner = binding.filterDateSpinner
        filterRatingSpinner = binding.filterRatingSpinner

        alertBinding = FragmentDialogAlertBinding.inflate(inflater, container, false)
        alertRecycleBinding = FragmentDialogAlertBinding.inflate(inflater, container, false)
        confirmBanBinding = FragmentDialogConfirmBinding.inflate(inflater, container, false)
        confirmDeleteBinding = FragmentDialogConfirmBinding.inflate(inflater, container, false)
        photoAlertBinding = FragmentDialogPhotoLoadBinding.inflate(inflater, container, false)
        dialog = DialogFactory.createAlertDialog(requireContext(), alertBinding)
        loadDialog = DialogFactory.createPhotoLoadDialog(requireContext(), photoAlertBinding)
        feedAdapter = FeedAdapter(
            feedViewModel, viewLifecycleOwner, tokenStorage, findNavController(),
            requireContext(), alertRecycleBinding, confirmBanBinding, confirmDeleteBinding
        )

        dateFilterType = DateFilterType.NONE
        sortFilterType = SortFilterType.NONE
        userFilterType = UserFilterType.ALL
        lastPage = 0

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initRecyclerView()

        initFilterDateSpinner()
        initFilterRatingSpinner()

        observeToken()
        observeLoadResult()
        observeFeed()
        setRefreshListener()
        observeUploadButtonState()
    }

    private fun setRefreshListener() {
        binding.refreshLayout.setOnRefreshListener {
            refreshFeed()
        }
    }

    private fun observeToken() {
        tokenStorage.token.observe(viewLifecycleOwner) { token ->
            if (token.accessToken != null) {
                val jwta = JWT(token.accessToken)
                userFilterType = UserFilterType.SUBSCRIPTIONS
                accessToken = token.accessToken
                val authorities = jwta.getClaim("authorities").asList(String::class.java)
                if (authorities.contains("UPLOAD_AUTHORITY")) {
                    initPhotoLoadBtnAuthorized()
                    initBottomNav(true)
                    initFeedSpinner()
                } else {
                    initPhotoLoadBtn()
                    initBottomNav(false)
                }
            } else {
                accessToken = null
                Firebase.analytics.logEvent(getString(R.string.unauthorized_enter_feed), null)
                feedViewModel.loadConfigValues()
                initPhotoLoadBtn()
                initBottomNav(false)
            }
            feedViewModel.getFeed(
                accessToken,
                dateFilterType,
                sortFilterType,
                userFilterType,
                null,
                0,
                pageSize
            )
        }
    }

    private fun initRecyclerView() {
        binding.feedRv.layoutManager =
            LinearLayoutManager(this.requireContext(), LinearLayoutManager.VERTICAL, false)
        binding.feedRv.adapter = feedAdapter

        val layoutManager = binding.feedRv.layoutManager as LinearLayoutManager
        binding.feedRv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                val itemsCount = layoutManager.itemCount
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                if (!feedAdapter.isLoading && itemsCount == lastVisibleItem + 1 && itemsCount / pageSize == lastPage + 1) {
                    feedViewModel.getFeed(
                        accessToken,
                        dateFilterType,
                        sortFilterType,
                        userFilterType,
                        null,
                        itemsCount / pageSize,
                        pageSize
                    )
                    lastPage++
                }
            }
        })
    }

    private fun initPhotoLoadBtn() {
        binding.photoLoadBtn.setOnClickListener {
            findNavController().navigate(R.id.action_feedFragment_to_loginFragment)
        }
    }

    private fun initPhotoLoadBtnAuthorized() {
        binding.photoLoadBtn.setOnClickListener {
            showFileChooser()
        }
    }

    private fun showFileChooser() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        photoLauncher.launch(intent)
    }

    private fun uploadPhoto(uri: Uri) {
        val bitmap: Bitmap = if (Build.VERSION.SDK_INT < 28) {
            MediaStore.Images.Media.getBitmap(
                activity?.contentResolver!!,
                uri
            )
        } else {
            val source = ImageDecoder.createSource(activity?.contentResolver!!, uri)
            ImageDecoder.decodeBitmap(source)
        }
        photoAlertBinding.imageView.setImageBitmap(bitmap)
        photoAlertBinding.buttonConfirm.setOnClickListener {
            feedViewModel.loadPhoto(accessToken, bitmap)
            loadDialog.dismiss()
        }
        loadDialog.show()
    }

    private fun observeLoadResult() {
        feedViewModel.loadResult.observe(viewLifecycleOwner) { result ->
            when (result.status) {
                ApiStatus.SUCCESS -> {
                    alertBinding.textView.text = resources.getString(R.string.photoWasLoaded)
                    dialog.show()
                }
                ApiStatus.LOADING -> {

                }
                ApiStatus.ERROR -> {
                    alertBinding.textView.text = result.message.toString()
                    dialog.show()
                }
            }
        }
    }

    private fun observeFeed() {
        feedViewModel.feedResult.observe(viewLifecycleOwner) { result ->
            when (result.status) {
                ApiStatus.SUCCESS -> {
                    val data = result.data!!
                    feedAdapter.update(data)
                    binding.refreshLayout.isRefreshing = false
                    feedAdapter.isLoading = false
                }
                ApiStatus.ERROR -> {
                    if (result.statusCode == HttpStatus.FORBIDDEN.code) {
                        lifecycleScope.launch {
                            tokenStorage.deleteToken()
                            findNavController().navigate(R.id.feedFragment)
                        }
                    }
                    feedAdapter.isLoading = false
                    alertBinding.textView.text = result.message.toString()
                    dialog.show()
                }
                ApiStatus.LOADING -> {
                    feedAdapter.isLoading = true
                }
            }
        }
    }

    private fun initBottomNav(isAuthorised: Boolean) {
        binding.bottomNav.binding.imageSearch.setOnClickListener {
            findNavController().navigate(R.id.action_feedFragment_to_searchFragment)
        }
        if (isAuthorised) {
            binding.bottomNav.binding.imageUser.setOnClickListener {
                findNavController().navigate(R.id.action_feedFragment_to_profileFragment)
            }
        } else {
            binding.bottomNav.binding.imageUser.setOnClickListener {
                findNavController().navigate(R.id.action_feedFragment_to_loginFragment)
            }
        }

    }

    private fun initFeedSpinner() {
        with(feedSpinner) {
            visibility = View.VISIBLE
            setItems(R.array.feedSpinnerGlobal)
            setHint(R.string.personal)
            setOnClickListener {
                setBackgroundResource(R.drawable.feed_spinner_openup_shape)
                showOrDismiss()
            }
            setOnSpinnerDismissListener {
                setBackgroundResource(R.drawable.feed_spinner_shape)
            }
            setOnSpinnerItemSelectedListener<String> { oldIndex, oldItem, newIndex, newItem ->
                if (newItem == resources.getString(R.string.global)) {
                    userFilterType = UserFilterType.ALL
                    setItems(R.array.feedSpinnerPersonal)
                }
                if (newItem == resources.getString(R.string.personal)) {
                    userFilterType = UserFilterType.SUBSCRIPTIONS
                    setItems(R.array.feedSpinnerGlobal)
                }
                refreshFeed()
                setBackgroundResource(R.drawable.feed_spinner_shape)
            }
        }
    }

    private fun initFilterDateSpinner() {
        with(filterDateSpinner) {
            setItems(R.array.filterDateSpinner)
            setHint(R.string.dateFilter)
            setOnClickListener {
                setBackgroundResource(R.drawable.filter_spinner_openup_shape)
                showOrDismiss()
            }
            setOnSpinnerDismissListener {
                setBackgroundResource(R.drawable.filter_spinner_shape)
            }
            setOnSpinnerItemSelectedListener<String> { oldIndex, oldItem, newIndex, newItem ->
                when (newItem) {
                    resources.getString(R.string.byDay) -> {
                        dateFilterType = DateFilterType.DAY
                    }
                    resources.getString(R.string.byWeek) -> {
                        dateFilterType = DateFilterType.WEEK
                    }
                    resources.getString(R.string.withoutFilter) -> {
                        dateFilterType = DateFilterType.NONE
                    }
                }
                refreshFeed()
                setBackgroundResource(R.drawable.filter_spinner_shape)
            }
        }
    }

    private fun initFilterRatingSpinner() {
        with(filterRatingSpinner) {
            setItems(R.array.filterRatingSpinner)
            setHint(R.string.ratingFilter)
            setOnClickListener {
                setBackgroundResource(R.drawable.filter_spinner_openup_shape)
                showOrDismiss()
            }
            setOnSpinnerDismissListener {
                setBackgroundResource(R.drawable.filter_spinner_shape)
            }
            setOnSpinnerItemSelectedListener<String> { oldIndex, oldItem, newIndex, newItem ->
                when (newItem) {
                    resources.getString(R.string.byLikes) -> {
                        sortFilterType = SortFilterType.LIKED_FIRST
                    }
                    resources.getString(R.string.byDislikes) -> {
                        sortFilterType = SortFilterType.DISLIKED_FIRST
                    }
                    resources.getString(R.string.withoutFilter) -> {
                        sortFilterType = SortFilterType.NONE
                    }
                }
                refreshFeed()
                setBackgroundResource(R.drawable.filter_spinner_shape)
            }
        }
    }

    private fun refreshFeed() {
        feedAdapter.clear()
        feedViewModel.getFeed(
            accessToken,
            dateFilterType,
            sortFilterType,
            userFilterType,
            null,
            0,
            pageSize
        )
        lastPage = 0
    }

    private fun observeUploadButtonState() {
        feedViewModel.hasUploadButton.observe(viewLifecycleOwner) { hasUploadButton ->
            if (!hasUploadButton) {
                binding.photoLoadBtn.visibility = View.GONE
            } else {
                binding.photoLoadBtn.visibility = View.VISIBLE
            }
        }
    }

    override fun onStop() {
        filterDateSpinner.dismiss()
        filterRatingSpinner.dismiss()
        feedSpinner.dismiss()
        super.onStop()
    }

    override fun onDestroy() {
        feedAdapter.recycleAll()
        super.onDestroy()
    }
}