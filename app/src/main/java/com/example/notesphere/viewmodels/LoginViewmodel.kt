package com.example.notesphere.viewmodels

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.notesphere.data.LoginRequest
import com.example.notesphere.network.ApiService
import com.example.notesphere.utils.AuthManager
import com.example.notesphere.utils.extractInfoFromIdCard
import com.example.notesphere.utils.saveBitmapToFile
import com.example.notesphere.utils.uriToMultipart
import kotlinx.coroutines.launch

data class UiState(
    val email: String = "",
    val password: String = "",
    val profileImageUri: String? = null,
    val username: String = "",
    val role: String = "",
    val isEmailValid: Boolean = true,
    val errorMessage: String = "",
    val alertMessage: String = "",
    val showAlert: Boolean = false,
    val showBottomSheet: Boolean = false,
    val isLoading: Boolean = false
)

class LoginViewModel(
    private val apiService: ApiService,
    private val authManager: AuthManager
) : ViewModel() {
    private val _uiState = mutableStateOf(UiState())
    val uiState: State<UiState> = _uiState

    var takePictureLauncher: ManagedActivityResultLauncher<Uri, Boolean>? = null
    var pickImageLauncher: ManagedActivityResultLauncher<PickVisualMediaRequest, Uri?>? = null

    init {
        Log.d("LoginViewModel", "Initialized with apiService=$apiService, authManager=$authManager")
    }

    fun updateEmail(email: String) {
        _uiState.value = _uiState.value.copy(
            email = email,
            isEmailValid = isValidEmail(email)
        )
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password)
    }

    fun updateProfileImageUri(uri: Uri?, context: Context) {
        if (uri == null || uri.toString().isEmpty()) {
            _uiState.value = _uiState.value.copy(
                profileImageUri = null,
                username = "",
                role = ""
            )
            showAlert("Invalid image URI")
            return
        }

        viewModelScope.launch {
            try {
                val (role, name, faceBitmap) = extractInfoFromIdCard(uri, context)
                val faceUri = saveBitmapToFile(faceBitmap, context)
                _uiState.value = _uiState.value.copy(
                    profileImageUri = faceUri.toString(),
                    username = name,
                    role = role
                )
                showAlert("ID card processed successfully")
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    profileImageUri = null,
                    username = "",
                    role = ""
                )
                showAlert("Failed to process ID card: ${e.message}")
                Log.e("LoginViewModel", "ID card processing failed", e)
            }
        }
    }

    fun updateShowBottomSheet(show: Boolean) {
        _uiState.value = _uiState.value.copy(showBottomSheet = show)
    }

    fun onGallerySelected() {
        pickImageLauncher?.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun showAlert(message: String) {
        _uiState.value = _uiState.value.copy(
            showAlert = true,
            alertMessage = message
        )
    }

    fun dismissAlert() {
        _uiState.value = _uiState.value.copy(
            showAlert = false,
            alertMessage = ""
        )
    }

    fun login(context: Context, onSuccess: () -> Unit) {
        viewModelScope.launch {
            if (!validateLogin()) return@launch
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                Log.d("LoginViewModel", "Attempting login with email=${_uiState.value.email}")
                val response = apiService.login(
                    LoginRequest(
                        email = _uiState.value.email,
                        password = _uiState.value.password,
                        //username = _uiState.value.username,
                        //role = _uiState.value.role
                    )
                )
                Log.d("LoginViewModel", "Login response: code=${response.code()}, body=${response.body()}")
                if (response.isSuccessful && response.body()?.success == true) {
                    val token = response.body()?.token
                    val user = response.body()?.user
                    if (token != null && user != null) {
                        authManager.saveAuthState(token, user)
                        Log.d("LoginViewModel", "Token saved: $token")
                        _uiState.value.profileImageUri?.let { uriString ->
                            uploadProfilePhoto(context, Uri.parse(uriString))
                        }
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            showAlert = true,
                            alertMessage = "Login successful!"
                        )
                        onSuccess()
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            errorMessage = "Login failed: No token or user data received"
                        )
                        Log.e("LoginViewModel", "No token or user in response")
                    }
                } else {
                    val errorMsg = response.body()?.message ?: "Login failed: ${response.code()}"
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = errorMsg
                    )
                    Log.e("LoginViewModel", "Login failed: $errorMsg")
                }
            } catch (e: Exception) {
                val errorMsg = "Network error: ${e.message}"
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = errorMsg
                )
                Log.e("LoginViewModel", errorMsg, e)
            }
        }
    }

    fun uploadProfilePhoto(context: Context, uri: Uri) {
        viewModelScope.launch {
            val token = authManager.getToken() ?: return@launch showAlert("No token available").also {
                Log.e("LoginViewModel", "No token available for photo upload")
            }
            try {
                Log.d("LoginViewModel", "Uploading profile photo: uri=$uri")
                val multipart = uriToMultipart(context, uri)
                val response = apiService.uploadProfilePhoto(multipart, "Bearer $token")
                Log.d("LoginViewModel", "Photo upload response: code=${response.code()}, body=${response.body()}")
                if (response.isSuccessful && response.body()?.success == true) {
                    response.body()?.profilePhotoPath?.let {
                        authManager.updateProfileInfo(profilePhotoPath = it)
                    }
                    showAlert("Profile photo uploaded successfully")
                } else {
                    val errorMsg = response.body()?.message ?: "Failed to upload profile photo"
                    showAlert(errorMsg)
                    Log.e("LoginViewModel", "Photo upload failed: $errorMsg")
                }
            } catch (e: Exception) {
                val errorMsg = "Upload error: ${e.message}"
                showAlert(errorMsg)
                Log.e("LoginViewModel", errorMsg, e)
            }
        }
    }

    private fun validateLogin(): Boolean {
        return when {
            _uiState.value.email.isEmpty() -> {
                _uiState.value = _uiState.value.copy(errorMessage = "Email is required")
                Log.w("LoginViewModel", "Validation failed: Email is required")
                false
            }
            !_uiState.value.isEmailValid -> {
                _uiState.value = _uiState.value.copy(errorMessage = "Invalid email format")
                Log.w("LoginViewModel", "Validation failed: Invalid email format")
                false
            }
            _uiState.value.password.isEmpty() -> {
                _uiState.value = _uiState.value.copy(errorMessage = "Password is required")
                Log.w("LoginViewModel", "Validation failed: Password is required")
                false
            }
            _uiState.value.password.length < 6 -> {
                _uiState.value = _uiState.value.copy(errorMessage = "Password must be at least 6 characters")
                Log.w("LoginViewModel", "Validation failed: Password too short")
                false
            }
            _uiState.value.username.isEmpty() && _uiState.value.profileImageUri != null -> {
                _uiState.value = _uiState.value.copy(errorMessage = "Username required from ID card")
                Log.w("LoginViewModel", "Validation failed: Username required")
                false
            }
            _uiState.value.role.isEmpty() && _uiState.value.profileImageUri != null -> {
                _uiState.value = _uiState.value.copy(errorMessage = "Role required from ID card")
                Log.w("LoginViewModel", "Validation failed: Role required")
                false
            }
            else -> {
                _uiState.value = _uiState.value.copy(errorMessage = "")
                true
            }
        }
    }

    private fun isValidEmail(email: String): Boolean {
        return email.matches(Regex("[a-zA-Z0-9._-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"))
    }
}