package com.example.picstorm.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.picstorm.data.repository.FeedRepositoryImpl
import com.example.picstorm.util.ApiResult
import com.example.picstorm.util.ImageCompressor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject


@HiltViewModel
class FeedViewModel @Inject constructor(
    private val feedRepository: FeedRepositoryImpl
) : ViewModel() {

    val loadResult = MutableLiveData<ApiResult<Void>>()

    fun loadPhoto(token: String?, image: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            val compressed = ImageCompressor.compress(image, 1024)
            feedRepository.loadPhoto(token, compressed).collect{result ->
                loadResult.postValue(result)
            }
        }
    }
}