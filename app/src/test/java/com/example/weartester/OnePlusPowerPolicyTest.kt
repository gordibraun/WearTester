package com.example.weartester

import org.junit.Assert.*
import org.junit.Test

class OnePlusPowerPolicyTest {
    private class FakePort : OnePlusPowerPolicy.Port {
        var restriction: Boolean? = true
        var owner: Int? = null
        var writesWork = true
        var storeWorks = true
        var reads = 0
        val writes = mutableListOf<Boolean>()
        override fun readRestriction(): Boolean? { reads++; return restriction }
        override fun writeRestriction(enabled: Boolean) {
            writes.add(enabled)
            if (writesWork) restriction = enabled
        }
        override fun ownedBoot() = owner
        override fun saveOwnedBoot(boot: Int?): Boolean {
            if (storeWorks) owner = boot
            return storeWorks
        }
    }

    @Test fun disabledByDefaultDoesNotTouchSystem() {
        val port = FakePort()
        assertEquals(OnePlusPowerPolicy.Result.OFF, OnePlusPowerPolicy.reconcile(false, 8, port))
        assertEquals(0, port.reads)
        assertTrue(port.writes.isEmpty())
    }

    @Test fun enableVerifiesAndRestoresOnlyOwnChange() {
        val port = FakePort()
        assertEquals(OnePlusPowerPolicy.Result.ACTIVE, OnePlusPowerPolicy.reconcile(true, 8, port))
        assertEquals(8, port.owner)
        assertEquals(false, port.restriction)
        assertEquals(OnePlusPowerPolicy.Result.OFF, OnePlusPowerPolicy.reconcile(false, 8, port))
        assertEquals(listOf(false, true), port.writes)
        assertNull(port.owner)
    }

    @Test fun preexistingDisabledRestrictionIsNotOursToRestore() {
        val port = FakePort().apply { restriction = false }
        assertEquals(OnePlusPowerPolicy.Result.ACTIVE, OnePlusPowerPolicy.reconcile(true, 8, port))
        assertNull(port.owner)
        assertEquals(OnePlusPowerPolicy.Result.OFF, OnePlusPowerPolicy.reconcile(false, 8, port))
        assertTrue(port.writes.isEmpty())
        assertEquals(false, port.restriction)
    }

    @Test fun processRestartPreservesOwnership() {
        val port = FakePort().apply { owner = 8; restriction = false }
        assertEquals(OnePlusPowerPolicy.Result.ACTIVE, OnePlusPowerPolicy.reconcile(true, 8, port))
        assertEquals(OnePlusPowerPolicy.Result.OFF, OnePlusPowerPolicy.reconcile(false, 8, port))
        assertEquals(listOf(true), port.writes)
    }

    @Test fun rebootForgetsOldOwnershipAndReappliesOptIn() {
        val port = FakePort().apply { owner = 7 }
        assertEquals(OnePlusPowerPolicy.Result.ACTIVE, OnePlusPowerPolicy.reconcile(true, 8, port))
        assertEquals(8, port.owner)
        assertEquals(listOf(false), port.writes)
    }

    @Test fun rebootWhileDisabledDoesNotChangeSystem() {
        val port = FakePort().apply { owner = 7; restriction = false }
        assertEquals(OnePlusPowerPolicy.Result.OFF, OnePlusPowerPolicy.reconcile(false, 8, port))
        assertNull(port.owner)
        assertTrue(port.writes.isEmpty())
    }

    @Test fun failedReadNeverWrites() {
        val port = FakePort().apply { restriction = null }
        assertEquals(OnePlusPowerPolicy.Result.FAILED, OnePlusPowerPolicy.reconcile(true, 8, port))
        assertTrue(port.writes.isEmpty())
    }

    @Test fun storageFailurePreventsMutation() {
        val port = FakePort().apply { storeWorks = false }
        assertEquals(OnePlusPowerPolicy.Result.FAILED, OnePlusPowerPolicy.reconcile(true, 8, port))
        assertTrue(port.writes.isEmpty())
    }

    @Test fun failedEnableIsNotReportedAsActive() {
        val port = FakePort().apply { writesWork = false }
        assertEquals(OnePlusPowerPolicy.Result.FAILED, OnePlusPowerPolicy.reconcile(true, 8, port))
        assertEquals(8, port.owner)
    }

    @Test fun failedRestoreKeepsOwnershipForRetry() {
        val port = FakePort().apply { owner = 8; restriction = false; writesWork = false }
        assertEquals(OnePlusPowerPolicy.Result.RESTORE_PENDING, OnePlusPowerPolicy.reconcile(false, 8, port))
        assertEquals(8, port.owner)
        port.writesWork = true
        assertEquals(OnePlusPowerPolicy.Result.OFF, OnePlusPowerPolicy.reconcile(false, 8, port))
        assertNull(port.owner)
    }

    @Test fun repeatedChecksDoNotRepeatSuccessfulMutation() {
        val port = FakePort()
        repeat(5) { assertEquals(OnePlusPowerPolicy.Result.ACTIVE, OnePlusPowerPolicy.reconcile(true, 8, port)) }
        assertEquals(listOf(false), port.writes)
    }

    @Test fun onlyExactKnownSystemResponseIsAccepted() {
        assertEquals(true, OnePlusPowerPolicy.parseRestriction("bm power manager function is true\n"))
        assertEquals(false, OnePlusPowerPolicy.parseRestriction("bm power manager function is false\n"))
        assertNull(OnePlusPowerPolicy.parseRestriction("Permission Denial: false"))
        assertNull(OnePlusPowerPolicy.parseRestriction("Can't find service: app.manager"))
        assertNull(OnePlusPowerPolicy.parseRestriction(""))
    }
}
