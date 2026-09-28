package io.github.glacier_jellyfin.androidtv.core.jellyfin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticateWithQuickConnect
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.UserDto
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/** A signed-in user: the access token is the credential kept on the device. */
data class SignedInUser(
    val serverId: String,
    val userId: UUID,
    val name: String,
    val primaryImageTag: String?,
    val accessToken: String,
)

/** A user as listed on the server's login screen. */
data class PublicUser(
    val id: UUID,
    val name: String,
    val primaryImageTag: String?,
)

sealed interface SignInResult {
    data class Success(val user: SignedInUser) : SignInResult
    data object InvalidCredentials : SignInResult
    data class Failed(val cause: Throwable) : SignInResult
}

sealed interface QuickConnectState {
    /** Quick Connect is disabled on the server. */
    data object Unavailable : QuickConnectState

    /** Show [code]; the user approves it in another signed-in Jellyfin client. */
    data class WaitingForApproval(val code: String) : QuickConnectState

    data class Authorized(val user: SignedInUser) : QuickConnectState
    data class Failed(val cause: Throwable) : QuickConnectState
}

@Singleton
class Authenticator @Inject constructor(
    private val jellyfin: Jellyfin,
) {

    suspend fun publicUsers(address: String): List<PublicUser> = withContext(Dispatchers.IO) {
        jellyfin.createApi(baseUrl = address).userApi.getPublicUsers().content.map { it.toPublicUser() }
    }

    suspend fun signIn(server: ServerInfo, username: String, password: String): SignInResult = withContext(Dispatchers.IO) { signInBlocking(server, username, password) }

    private suspend fun signInBlocking(server: ServerInfo, username: String, password: String): SignInResult = try {
        val api = jellyfin.createApi(baseUrl = server.address)
        val result = api.authenticationApi.authenticateUserByName(username, password).content
        result.toSignedInUser(server)?.let(SignInResult::Success)
            ?: SignInResult.Failed(IllegalStateException("Server returned no access token"))
    } catch (e: InvalidStatusException) {
        if (e.status == HTTP_UNAUTHORIZED) SignInResult.InvalidCredentials else SignInResult.Failed(e)
    } catch (e: Exception) {
        SignInResult.Failed(e)
    }

    /**
     * Runs a Quick Connect session. A code expires on the server after a few
     * minutes; a fresh one is requested and emitted when that happens. The
     * flow completes after [QuickConnectState.Authorized], [QuickConnectState.Unavailable]
     * or [QuickConnectState.Failed].
     */
    /** Whether the server allows Quick Connect; false when it cannot be asked. */
    suspend fun isQuickConnectEnabled(server: ServerInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            jellyfin.createApi(baseUrl = server.address).authenticationApi.getQuickConnectEnabled().content
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    fun quickConnect(server: ServerInfo): Flow<QuickConnectState> = flow {
        val api = jellyfin.createApi(baseUrl = server.address).authenticationApi
        try {
            if (!api.getQuickConnectEnabled().content) {
                emit(QuickConnectState.Unavailable)
                return@flow
            }
            var session = api.initiateQuickConnect().content
            emit(QuickConnectState.WaitingForApproval(session.code))
            while (true) {
                delay(QUICK_CONNECT_POLL_INTERVAL)
                val state = try {
                    api.getQuickConnectState(session.secret).content
                } catch (e: InvalidStatusException) {
                    if (e.status != HTTP_NOT_FOUND) throw e
                    // Expired: start over with a new code.
                    session = api.initiateQuickConnect().content
                    emit(QuickConnectState.WaitingForApproval(session.code))
                    continue
                }
                if (state.authenticated) {
                    val result = api.authenticateWithQuickConnect(session.secret).content
                    val user = result.toSignedInUser(server)
                        ?: throw IllegalStateException("Server returned no access token")
                    emit(QuickConnectState.Authorized(user))
                    return@flow
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(QuickConnectState.Failed(e))
        }
    }.flowOn(Dispatchers.IO)

    /** Ends the session on the server, which invalidates the access token. */
    suspend fun signOut(address: String, accessToken: String) {
        withContext(Dispatchers.IO) {
            jellyfin.createApi(baseUrl = address, accessToken = accessToken).sessionApi.reportSessionEnded()
        }
    }

    /** Checks whether a stored token is still accepted. */
    suspend fun isTokenValid(address: String, accessToken: String): Boolean = withContext(Dispatchers.IO) {
        try {
            jellyfin.createApi(baseUrl = address, accessToken = accessToken).userApi.getCurrentUser()
            true
        } catch (e: InvalidStatusException) {
            if (e.status == HTTP_UNAUTHORIZED) false else throw e
        }
    }

    fun userImageUrl(address: String, userId: UUID, imageTag: String?): String? =
        imageTag?.let { jellyfin.createApi(baseUrl = address).imageApi.getUserImageUrl(userId = userId, tag = it) }

    private fun AuthenticationResult.toSignedInUser(server: ServerInfo): SignedInUser? {
        val token = accessToken ?: return null
        val dto = user ?: return null
        return SignedInUser(
            serverId = serverId ?: server.id,
            userId = dto.id,
            name = dto.name.orEmpty(),
            primaryImageTag = dto.primaryImageTag,
            accessToken = token,
        )
    }

    private fun UserDto.toPublicUser() = PublicUser(
        id = id,
        name = name.orEmpty(),
        primaryImageTag = primaryImageTag,
    )

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        val QUICK_CONNECT_POLL_INTERVAL = 5.seconds
    }
}
