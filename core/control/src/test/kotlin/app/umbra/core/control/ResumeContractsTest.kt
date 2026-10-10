package app.umbra.core.control

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class ResumeContractsTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun onlyAnUnexpiredContractThatAllowsItResumes() {
        var now = 1_000L
        val store = ControlStore(dir, clock = { now })
        val contracts = ResumeContracts(store)
        assertNotNull(contracts.eligibility("s1"), "no contract, no resume")
        contracts.set("s1", resume = true, expiresAtMs = 5_000, allowedWork = "builds only")
        assertNull(contracts.eligibility("s1"))
        now = 6_000
        assertEquals("the contract expired", contracts.eligibility("s1"))
        contracts.set("s2", resume = false, expiresAtMs = null, allowedWork = "")
        assertNotNull(contracts.eligibility("s2"))
        contracts.set("s3", resume = true, expiresAtMs = null, allowedWork = "")
        assertNull(ResumeContracts(ControlStore(dir, clock = { now })).eligibility("s3"), "contracts survive a restart")
        contracts.revoke("s3")
        assertNotNull(contracts.eligibility("s3"))
    }
}
