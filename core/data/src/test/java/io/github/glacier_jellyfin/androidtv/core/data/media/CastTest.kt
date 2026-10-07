package io.github.glacier_jellyfin.androidtv.core.data.media

import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.PersonKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class CastTest {

    private fun person(n: Int, type: PersonKind = PersonKind.ACTOR, role: String? = null) =
        BaseItemPerson(id = UUID(0, n.toLong()), name = "person$n", type = type, role = role)

    @Test
    fun `a person with several roles is listed once, roles joined`() {
        val cast = actingPeople(
            listOf(
                person(1, role = "Hero"),
                person(2, role = "Villain"),
                person(1, type = PersonKind.GUEST_STAR, role = "Twin"),
            ),
        )
        assertEquals(listOf("person1" to "Hero / Twin", "person2" to "Villain"), cast.map { it.name to it.role })
    }

    @Test
    fun `crew is left out and blank roles are dropped`() {
        val cast = actingPeople(
            listOf(person(1, role = ""), person(1, type = PersonKind.DIRECTOR, role = "Director"), person(3, role = " ")),
        )
        assertEquals(listOf("person1" to null, "person3" to null), cast.map { it.name to it.role })
    }
}
