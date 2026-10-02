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
    fun `signing in again keeps parental control`() {
        val state = AccountState()
            .withSignIn(signIn(anna, "Anna"), now = 1)
            .updateUser("s1", anna.toString()) { it.copy(protection = Protection(maxAge = AgeLimit.A12, pinForLocked = true)) }
            .withSignIn(signIn(anna, "Anna", token = "new"), now = 2)
        assertEquals(Protection(maxAge = AgeLimit.A12, pinForLocked = true), state.users.single().protection)
    }

    @Test
    fun `a profile asks for its pin only when one is set and the switch is on`() {
        val pin = Pins.hash("1234", salt = ByteArray(16), iterations = 1)
        fun locked(change: (StoredUser) -> StoredUser) = AccountState()
            .withSignIn(signIn(anna, "Anna"), now = 1)
            .updateUser("s1", anna.toString(), change)
            .profilesFor("s1", publicUsers = null)
            .single().pinLocked
        assertFalse(locked { it.copy(pin = pin) })
        assertFalse(locked { it.copy(protection = Protection(pinOnProfileSwitch = true)) })
        assertTrue(locked { it.copy(pin = pin, protection = Protection(pinOnProfileSwitch = true)) })
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

    private fun twoProfiles(mode: StartMode) = AccountState()
        .withServer(server, now = 0)
        .withSignIn(signIn(anna, "Anna"), now = 1)
        .withSignIn(signIn(jonas, "Jonas"), now = 2)
        .copy(startProfile = StartProfile(mode, "s1", anna.toString()))

    private fun AccountState.choice() = startChoice("s1", profilesFor("s1", publicUsers = null))

    @Test
    fun `picker mode always shows the profiles`() {
        assertEquals(StartChoice.Pick, twoProfiles(StartMode.Picker).choice())
    }

    @Test
    fun `single mode opens only a lone profile`() {
        assertEquals(StartChoice.Pick, twoProfiles(StartMode.Single).choice())
        val alone = AccountState().withSignIn(signIn(anna, "Anna"), now = 1)
        assertEquals(StartChoice.Open(anna.toString()), alone.choice())
    }

    @Test
    fun `single mode counts profiles the server lists but nobody signed in to`() {
        val state = AccountState().withSignIn(signIn(anna, "Anna"), now = 1)
        val public = listOf(PublicUser(anna, "Anna", primaryImageTag = null), PublicUser(jonas, "Jonas", primaryImageTag = null))
        assertEquals(StartChoice.Pick, state.startChoice("s1", state.profilesFor("s1", public)))
    }

    @Test
    fun `last mode opens the profile used last`() {
        assertEquals(StartChoice.Open(jonas.toString()), twoProfiles(StartMode.Last).choice())
        val signedOut = twoProfiles(StartMode.Last).updateUser("s1", jonas.toString()) { it.copy(accessToken = null) }
        assertEquals(StartChoice.Open(anna.toString()), signedOut.choice())
    }

    @Test
    fun `fixed mode opens the chosen profile while it is signed in`() {
        assertEquals(StartChoice.Open(anna.toString()), twoProfiles(StartMode.Fixed).choice())
        val signedOut = twoProfiles(StartMode.Fixed).updateUser("s1", anna.toString()) { it.copy(accessToken = null) }
        assertEquals(StartChoice.Pick, signedOut.choice())
    }

    @Test
    fun `a profile with a pin on switching is focused instead of opened`() {
        val pin = Pins.hash("1234", salt = ByteArray(16), iterations = 1)
        val state = twoProfiles(StartMode.Fixed)
            .updateUser("s1", anna.toString()) { it.copy(pin = pin, protection = Protection(pinOnProfileSwitch = true)) }
        assertEquals(StartChoice.Focus(anna.toString()), state.choice())
    }

    @Test
    fun `a home screen title opens its own profile whatever the start mode, its pin first`() {
        val state = twoProfiles(StartMode.Picker)
        val profiles = state.profilesFor("s1", publicUsers = null)
        assertEquals(StartChoice.Open(anna.toString()), profileChoice(anna.toString(), profiles))
        val pin = Pins.hash("1234", salt = ByteArray(16), iterations = 1)
        val locked = state.updateUser("s1", anna.toString()) { it.copy(pin = pin, protection = Protection(pinOnProfileSwitch = true)) }
        assertEquals(StartChoice.Focus(anna.toString()), profileChoice(anna.toString(), locked.profilesFor("s1", publicUsers = null)))
        val signedOut = state.updateUser("s1", anna.toString()) { it.copy(accessToken = null) }
        assertEquals(StartChoice.Pick, profileChoice(anna.toString(), signedOut.profilesFor("s1", publicUsers = null)))
    }

    @Test
    fun `the app starts on the fixed profile's server`() {
        val other = ServerInfo(id = "s2", name = "Other", address = "http://other:8096", version = "12.1.0")
        val state = twoProfiles(StartMode.Fixed).withServer(other, now = 3)
        assertEquals("s1", state.startServerId())
        assertEquals("s2", state.copy(startProfile = StartProfile(StartMode.Last)).startServerId())
        assertEquals("s2", state.withoutServer("s1").startServerId())
        assertEquals(StartProfile(), state.withoutServer("s1").startProfile)
    }
}
