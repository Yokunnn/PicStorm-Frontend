package com.example.picstorm.domain

import com.example.picstorm.domain.model.Profile
import com.example.picstorm.domain.model.enums.UserRole
import com.example.picstorm.util.ApiResult
import kotlinx.coroutines.flow.Flow

interface ProfileRepository {
    suspend fun getProfile(token: String?, userId: Long): Flow<ApiResult<Profile>>
    suspend fun changeAdmin(token: String?, userId: Long): Flow<ApiResult<UserRole>>
    suspend fun banUser(token: String?, userId: Long): Flow<ApiResult<UserRole>>
    suspend fun uploadAvatar(token: String?, byteArray: ByteArray): Flow<ApiResult<Void>>
}