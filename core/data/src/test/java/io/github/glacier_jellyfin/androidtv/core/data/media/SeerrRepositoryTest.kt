package io.github.glacier_jellyfin.androidtv.core.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class SeerrRepositoryTest {

    private val id = UUID.fromString("0123abcd-4567-89ef-0123-456789abcdef")

    @Test
    fun readsIdsWithoutDashes() = assertEquals(id, parseJellyfinId("0123abcd456789ef0123456789abcdef"))

    @Test
    fun readsIdsWithDashes() = assertEquals(id, parseJellyfinId(id.toString()))

    @Test
    fun rejectsOtherText() {
        assertNull(parseJellyfinId(""))
        assertNull(parseJellyfinId("not-an-id"))
        assertNull(parseJellyfinId("zz23abcd456789ef0123456789abcdef"))
    }

    @Test
    fun readsRefusals() {
        assertEquals(SeerrRequestResult.NotAllowed, requestFailure(403, """{"code":"no_request_permission","message":"You do not have permission"}"""))
        assertEquals(SeerrRequestResult.QuotaUsedUp, requestFailure(403, """{"message":"Movie Quota exceeded."}"""))
        assertEquals(SeerrRequestResult.AlreadyRequested, requestFailure(409, """{"message":"Request for this media already exists."}"""))
        assertEquals(SeerrRequestResult.Failed, requestFailure(500, ""))
    }
}
