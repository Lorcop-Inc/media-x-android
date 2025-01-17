package dev.jdtech.jellyfin.viewmodels

import android.content.SharedPreferences
import android.content.res.Resources
import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.BaseApplication
import dev.jdtech.jellyfin.R
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.Server
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import javax.inject.Inject
import kotlin.Exception

@HiltViewModel
class LoginViewModel
@Inject
constructor(
    application: BaseApplication,
    private val sharedPreferences: SharedPreferences,
    private val jellyfinApi: JellyfinApi,
    private val database: ServerDatabaseDao
) : ViewModel() {
    private val resources: Resources = application.resources

    private val _uiState = MutableStateFlow<UiState>(UiState.Normal)
    val uiState = _uiState.asStateFlow()
    private val _usersState = MutableStateFlow<UsersState>(UsersState.Loading)
    val usersState = _usersState.asStateFlow()
    private val _navigateToMain = MutableSharedFlow<Boolean>()
    val navigateToMain = _navigateToMain.asSharedFlow()

    sealed class UiState {
        object Normal : UiState()
        object Loading : UiState()
        data class Error(val message: String) : UiState()
    }

    sealed class UsersState {
        object Loading : UsersState()
        data class Users(val users: List<User>) : UsersState()
    }

    init {
        loadPublicUsers()
    }

    private fun loadPublicUsers() {
        viewModelScope.launch {
            _usersState.emit(UsersState.Loading)
            try {
                val publicUsers by jellyfinApi.userApi.getPublicUsers()
                val users = publicUsers.map { User(it.id, it.name.orEmpty()) }
                _usersState.emit(UsersState.Users(users))
            } catch (e: Exception) {
                _usersState.emit(UsersState.Users(emptyList()))
            }
        }
    }

    /**
     * Send a authentication request to the Jellyfin server
     *
     * @param username Username
     * @param password Password
     */
    fun login(username: String, password: String) {
        viewModelScope.launch {
            _uiState.emit(UiState.Loading)

            try {
                val authenticationResult by jellyfinApi.userApi.authenticateUserByName(
                    data = AuthenticateUserByName(
                        username = username,
                        pw = password
                    )
                )

                val serverInfo by jellyfinApi.systemApi.getPublicSystemInfo()

                val server = Server(
                    serverInfo.id!!,
                    serverInfo.serverName!!,
                    jellyfinApi.api.baseUrl!!,
                    authenticationResult.user?.id.toString(),
                    authenticationResult.user?.name!!,
                    authenticationResult.accessToken!!
                )

                insert(server)

                val spEdit = sharedPreferences.edit()
                spEdit.putString("selectedServer", server.id)
                spEdit.apply()

                jellyfinApi.apply {
                    api.accessToken = authenticationResult.accessToken
                    userId = authenticationResult.user?.id
                }

                _uiState.emit(UiState.Normal)
                _navigateToMain.emit(true)
            } catch (e: Exception) {
                val message =
                    if (e.message?.contains("401") == true) resources.getString(R.string.login_error_wrong_username_password) else resources.getString(
                        R.string.unknown_error
                    )
                _uiState.emit(UiState.Error(message))
            }
        }
    }

    /**
     * Add server to the database
     *
     * @param server The server
     */
    private suspend fun insert(server: Server) {
        withContext(Dispatchers.IO) {
            database.insert(server)
        }
    }
}