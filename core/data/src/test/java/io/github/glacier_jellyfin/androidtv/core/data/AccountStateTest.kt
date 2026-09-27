package io.github.glacier_jellyfin.androidtv.core.data

import io.github.glacier_jellyfin.androidtv.core.jellyfin.PublicUser
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerInfo
import io.github.glacier_jellyfin.androidtv.core.jellyfin.SignedInUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AccountStateTest {

    private val server = ServerInfo(id = "s1", name = "Home", address = "http://home:8096", version = "12.1.0")
    private val anna = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val jonas = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val hidden = UUID.fromString("00000000-0000-0000-0000-00000000000c")

    private fun signIn(id: UUID, name: String, token: String = "t-$name") =
        SignedInUser(serverId = "s1", userId = id, name = name, primaryImageTag = null, accessToken = token)

    @Test
    fun `remembering a server replaces the previous entry and selects it`() {
        val state = AccountState().withServer(server, now = 1).withServer(server.copy(name = "Renamed"), now = 2)
        assertEquals(listOf("Renamed"), state.servers.map { it.name })
        assertEquals("s1", state.lastServerId)
    }

    @Test
    fun `signing in again keeps the local pin`() {
        val pin = Pins.hash("1234", salt = ByteArray(16), iterations = 1)
        val state = AccountState()
            .withSignIn(signIn(anna, "Anna"), now = 1)
            .updateUser("s1", anna.toString()) { it.copy(pin = pin) }
            .withSignIn(signIn(anna, "Anna", token = "new"), now = 2)
        val user = state.users.single()
        assertEquals("new", user.accessToken)
        assertEquals(pin, user.pin)
    }

    @Test
    fun `profiles list public users first, then hidden users known on this device`() {
        val state = AccountState()
            .withSignIn(signIn(hidden, "Hidden"), now = 1)
            .withSignIn(signIn(jonas, "Jonas"), now = 2)
            .updateUser("s1", jonas.toString()) { it.copy(accessToken = null) }
        val public = listOf(
            PublicUser(anna, "Anna", primaryImageTag = null),
            PublicUser(jonas, "Jonas", primaryImageTag = null),
        )
        val profiles = state.profilesFor("s1", public)
        assertEquals(listOf("Anna", "Jonas", "Hidden"), profiles.map { it.name })
        assertFalse(profiles[0].isSignedIn)
        assertFalse(profiles[1].isSignedIn)
        assertTrue(profiles[2].isSignedIn)
    }

    @Test
    fun `profiles fall back to local users when the server is unreachable`() {
        val state = AccountState().withSignIn(signIn(anna, "Anna"), now = 1)
        assertEquals(listOf("Anna"), state.profilesFor("s1", publicUsers = null).map { it.name })
    }

    @Test
    fun `removing a server drops its users`() {
        val state = AccountState().withServer(server, now = 1).withSignIn(signIn(anna, "Anna"), now = 2).withoutServer("s1")
        assertTrue(state.servers.isEmpty())
        assertTrue(state.users.isEmpty())
        assertNull(state.lastServerId)
    }
}
