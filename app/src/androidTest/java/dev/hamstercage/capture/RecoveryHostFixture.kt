package dev.hamstercage.capture

import androidx.test.platform.app.InstrumentationRegistry
import dev.hamstercage.data.HamsterDatabase
import dev.hamstercage.data.HamsterRepository
import dev.hamstercage.domain.Office
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in synthetic office only, for host reboot/update/permission/edit probes. */
class RecoveryHostFixture {
    @Test fun applyFixtureAction() = runBlocking {
        val action = InstrumentationRegistry.getArguments().getString("recoveryFixtureAction")
        assumeTrue(action in setOf("seed", "disable", "enable", "assert-active", "assert-no-offices", "assert-not-active"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (action == "assert-not-active") {
            assertTrue(CoverageStore.state(context).first().registration != RegistrationStatus.ACTIVE)
            return@runBlocking
        }
        if (action == "assert-active" || action == "assert-no-offices") {
            val expected = if (action == "assert-active") RegistrationStatus.ACTIVE else RegistrationStatus.NO_OFFICES
            assertEquals(expected, CoverageStore.state(context).first().registration)
            return@runBlocking
        }
        val db = HamsterDatabase.open(context)
        try {
            val dao = db.dao()
            assertTrue(dao.events().isEmpty() && dao.corrections().isEmpty() && dao.manualSessions().isEmpty())
            val offices = dao.offices()
            assertTrue(offices.isEmpty() || (offices.size == 1 && offices.single().id == "recovery-synthetic-office" &&
                offices.single().latitude == 0.0 && offices.single().longitude == 0.0))
        } finally { db.close() }
        val repository = HamsterRepository.get(context)
        val desired = Office("recovery-synthetic-office", "Synthetic recovery office", 0.0, 0.0,
            enabled = action != "disable")
        val version = repository.officeVersion(desired.id)
        if (action != "seed") assertTrue(version != null)
        repository.saveOffice(desired, version)
    }
}
