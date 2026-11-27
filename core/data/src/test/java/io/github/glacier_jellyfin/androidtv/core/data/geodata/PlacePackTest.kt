package io.github.glacier_jellyfin.androidtv.core.data.geodata

import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlacePackTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val bavaria = TestAdmin("DE", "Bavaria", mapOf("de" to "Bayern"))
    private val berlinRegion = TestAdmin("DE", "Berlin")

    private val places = listOf(
        TestPlace("Munich", 48.13743, 11.57549, "DE", bavaria, mapOf("de" to "München")),
        TestPlace("Berlin", 52.52437, 13.41053, "DE", berlinRegion),
        TestPlace("Oslo", 59.91273, 10.74609, "NO"),
        TestPlace("Alert", 82.5018, -62.3481, null),
    )

    private fun pack(list: List<TestPlace> = places) = PlacePack.of(ByteBuffer.wrap(TestPack.build(list)))

    @Test
    fun `finds the nearest place and reads its parts`() {
        assertEquals(Place("Munich", "Bavaria", "DE"), pack().nearest(48.14, 11.58, "en"))
        assertEquals(Place("Berlin", "Berlin", "DE"), pack().nearest(52.5, 13.4, "en"))
    }

    @Test
    fun `uses localized names for place and region, else the GeoNames name`() {
        assertEquals(Place("München", "Bayern", "DE", "Munich", "Bavaria"), pack().nearest(48.14, 11.58, "de"))
        // No Spanish records, and a language the pack does not know: GeoNames names.
        assertEquals(Place("Munich", "Bavaria", "DE"), pack().nearest(48.14, 11.58, "es"))
        assertEquals(Place("Munich", "Bavaria", "DE"), pack().nearest(48.14, 11.58, "fr"))
        // Only the region is localized.
        val half = listOf(TestPlace("Town", 48.0, 11.0, "DE", bavaria))
        assertEquals(Place("Town", "Bayern", "DE", "Town", "Bavaria"), pack(half).nearest(48.0, 11.0, "de"))
    }

    @Test
    fun `finds a place across a cell border`() {
        val east = listOf(TestPlace("Neighbour", 50.5, 12.004, "DE"))
        assertEquals("Neighbour", pack(east).nearest(50.5, 11.9995, "en")?.name)
        val north = listOf(TestPlace("North", 51.004, 12.5, "DE"))
        assertEquals("North", pack(north).nearest(50.9995, 12.5, "en")?.name)
    }

    @Test
    fun `finds a place across the antimeridian in both directions`() {
        val east = listOf(TestPlace("East", -16.85, -179.99, "FJ"))
        assertEquals("East", pack(east).nearest(-16.85, 179.99, "en")?.name)
        val west = listOf(TestPlace("West", -16.85, 179.99, "FJ"))
        assertEquals("West", pack(west).nearest(-16.85, -179.99, "en")?.name)
        // Longitudes beyond 180 wrap.
        assertEquals("East", pack(east).nearest(-16.85, 180.01, "en")?.name)
    }

    @Test
    fun `cuts off at 50 km`() {
        val list = listOf(TestPlace("Far", 50.0, 10.0, "DE"))
        // 0.4 degrees of latitude are about 44.5 km, 0.46 degrees about 51 km.
        assertNotNull(pack(list).nearest(50.4, 10.0, "en"))
        assertNull(pack(list).nearest(50.46, 10.0, "en"))
        assertNull(pack(list).nearest(0.0, 0.0, "en"))
    }

    @Test
    fun `finds places near the poles`() {
        val north = listOf(TestPlace("Pole", 89.95, 170.0, null))
        assertEquals("Pole", pack(north).nearest(89.95, -170.0, "en")?.name)
        assertEquals("Pole", pack(north).nearest(90.0, 0.0, "en")?.name)
        val south = listOf(TestPlace("South", -89.9, 5.0, null))
        assertEquals("South", pack(south).nearest(-90.0, 100.0, "en")?.name)
    }

    @Test
    fun `the nearer place wins and a missing country is null`() {
        val list = listOf(TestPlace("A", 10.0, 10.0, null), TestPlace("B", 10.1, 10.0, "DE"))
        assertEquals(Place("A", null, null), pack(list).nearest(10.01, 10.0, "en"))
        assertEquals("B", pack(list).nearest(10.09, 10.0, "en")?.name)
    }

    @Test
    fun `rejects broken files`() {
        val good = TestPack.build(places)
        val broken = listOf(
            good.copyOf(40),
            good.copyOf(good.size - 100),
            good.copyOf().also { it[0] = 'X'.code.toByte() },
            good.copyOf().also { it[4] = 2 },
            good.copyOf().also { it[40] = 0x03 },
        )
        broken.forEach { bytes ->
            assertThrows(BrokenPackException::class.java) { PlacePack.of(ByteBuffer.wrap(bytes)) }
        }
    }

    @Test
    fun `opens a mapped file and checks files on disk`() {
        val file = folder.newFile("places.bin").also { it.writeBytes(TestPack.build(places, level = 5_000)) }
        PlacePack.check(file)
        assertEquals(5_000, PlacePack.open(file).minPopulation)
        val broken = File(folder.root, "broken.bin").also { it.writeBytes(ByteArray(10)) }
        assertThrows(BrokenPackException::class.java) { PlacePack.check(broken) }
        assertThrows(BrokenPackException::class.java) { PlacePack.open(broken) }
    }

    @Test
    fun `drops a region that only repeats the place`() {
        val de = Locale.GERMAN
        // Berlin: GeoNames admin1 "State of Berlin", German "Land Berlin".
        assertEquals(
            "Berlin, Deutschland",
            PlaceFormatter.format(Place("Berlin", "Land Berlin", "DE", "Berlin", "State of Berlin"), de),
        )
        assertEquals("Berlin, Germany", PlaceFormatter.format(Place("Berlin", "State of Berlin", "DE"), Locale.ENGLISH))
        assertEquals(
            "Hamburg, Deutschland",
            PlaceFormatter.format(Place("Hamburg", "Freie und Hansestadt Hamburg", "DE", "Hamburg", "Hamburg"), de),
        )
        assertEquals(
            "Madrid, España",
            PlaceFormatter.format(Place("Madrid", "Comunidad de Madrid", "ES"), Locale("es")),
        )
        assertEquals("Wien, Österreich", PlaceFormatter.format(Place("Wien", "Wien", "AT"), de))
    }

    @Test
    fun `keeps a region that is something else`() {
        val de = Locale.GERMAN
        assertEquals("München, Bayern, Deutschland", PlaceFormatter.format(Place("München", "Bayern", "DE"), de))
        assertEquals(
            "Baden, Baden-Württemberg, Deutschland",
            PlaceFormatter.format(Place("Baden", "Baden-Württemberg", "DE"), de),
        )
        assertEquals(
            "Friedrichswerder, State of Berlin, Deutschland",
            PlaceFormatter.format(Place("Friedrichswerder", "State of Berlin", "DE"), de),
        )
        assertEquals(
            "Brandenburg an der Havel, Brandenburg, Deutschland",
            PlaceFormatter.format(Place("Brandenburg an der Havel", "Brandenburg", "DE"), de),
        )
    }

    @Test
    fun `reads a localized region from the pack and drops it for Berlin`() {
        val land = TestAdmin("DE", "State of Berlin", mapOf("de" to "Land Berlin"))
        val berlin = pack(listOf(TestPlace("Berlin", 52.52, 13.41, "DE", land))).nearest(52.52, 13.41, "de")!!
        assertEquals("Land Berlin", berlin.admin1)
        assertEquals("Berlin, Deutschland", PlaceFormatter.format(berlin, Locale.GERMAN))
        assertEquals("Berlin, Germany", PlaceFormatter.format(berlin, Locale.ENGLISH))
    }

    @Test
    fun `formats place, region and country`() {
        val de = Locale.GERMAN
        assertEquals("München, Bayern, Deutschland", PlaceFormatter.format(Place("München", "Bayern", "DE"), de))
        assertEquals("Berlin, Deutschland", PlaceFormatter.format(Place("Berlin", "Berlin", "DE"), de))
        assertEquals("Oslo, Norwegen", PlaceFormatter.format(Place("Oslo", null, "NO"), de))
        assertEquals("Alert", PlaceFormatter.format(Place("Alert", null, null), de))
        assertEquals("Oslo, Norway", PlaceFormatter.format(Place("Oslo", null, "NO"), Locale.ENGLISH))
    }
}
