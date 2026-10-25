package app.apexview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun versionsCompareByNumberNotText() {
        assertTrue(Updater.isNewer("0.36", "0.35"))
        assertTrue(Updater.isNewer("0.100", "0.99"))
        assertTrue(Updater.isNewer("1.0", "0.99"))
        assertTrue(Updater.isNewer("v0.35.1".removePrefix("v"), "0.35"))
        assertFalse(Updater.isNewer("0.35", "0.35"))
        assertFalse(Updater.isNewer("0.34", "0.35"))
    }
}
