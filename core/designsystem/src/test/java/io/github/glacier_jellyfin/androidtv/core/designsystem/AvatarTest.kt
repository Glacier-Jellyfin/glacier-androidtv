package io.github.glacier_jellyfin.androidtv.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarTest {
    @Test
    fun fewPeopleAllShow() {
        assertEquals(AvatarSlots(0, 0), avatarSlots(0, 3))
        assertEquals(AvatarSlots(2, 0), avatarSlots(2, 3))
        assertEquals(AvatarSlots(3, 0), avatarSlots(3, 3))
    }

    @Test
    fun morePeopleShareTheLastCircle() {
        assertEquals(AvatarSlots(2, 2), avatarSlots(4, 3))
        assertEquals(AvatarSlots(2, 3), avatarSlots(5, 3))
    }

    @Test
    fun aMaximumOfOneShowsOnlyTheCount() {
        assertEquals(AvatarSlots(0, 4), avatarSlots(4, 1))
    }

    @Test
    fun theSameNameKeepsItsColour() {
        assertEquals(avatarColor("Anna"), avatarColor(" anna "))
    }
}
