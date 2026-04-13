package uz.yalla.sipphone.domain.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SemverComparatorTest {

    @Test
    fun `parse valid semver`() {
        val v = Semver.parse("1.2.3")
        assertEquals(1, v.major)
        assertEquals(2, v.minor)
        assertEquals(3, v.patch)
    }

    @Test
    fun `parse semver with leading v is rejected`() {
        assertFailsWith<IllegalArgumentException> { Semver.parse("v1.2.3") }
    }

    @Test
    fun `parse invalid semver throws`() {
        assertFailsWith<IllegalArgumentException> { Semver.parse("1.2") }
        assertFailsWith<IllegalArgumentException> { Semver.parse("1.2.3.4") }
        assertFailsWith<IllegalArgumentException> { Semver.parse("abc") }
        assertFailsWith<IllegalArgumentException> { Semver.parse("") }
    }

    @Test
    fun `1_2_0 greater than 1_1_9`() {
        assertTrue(Semver.parse("1.2.0") > Semver.parse("1.1.9"))
    }

    @Test
    fun `1_2_0 equal to 1_2_0`() {
        assertEquals(0, Semver.parse("1.2.0").compareTo(Semver.parse("1.2.0")))
    }

    @Test
    fun `0_9_99 less than 1_0_0`() {
        assertTrue(Semver.parse("0.9.99") < Semver.parse("1.0.0"))
    }

    @Test
    fun `major version dominates`() {
        assertTrue(Semver.parse("2.0.0") > Semver.parse("1.99.99"))
    }

    @Test
    fun `parseOrNull returns null for invalid`() {
        assertEquals(null, Semver.parseOrNull("not-a-version"))
    }
}
