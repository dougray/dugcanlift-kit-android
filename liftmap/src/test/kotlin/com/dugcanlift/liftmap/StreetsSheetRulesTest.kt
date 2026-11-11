package com.dugcanlift.liftmap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class StreetsSheetRulesTest {
    private val id = UUID.randomUUID()

    @Test fun aCancelDeletesTheAreaThisSheetBegan() {
        assertTrue(StreetsSheetRules.cancelMayDelete(StreetsSheetMode.New, id, finished = false))
        assertTrue(StreetsSheetRules.cancelMayDelete(StreetsSheetMode.Update, id, finished = false))
    }

    @Test fun aCancelAfterTheDownloadFinishedDeletesNothing() {
        // onDone(area) already went to the host; in Update mode the old area is already gone.
        assertFalse(StreetsSheetRules.cancelMayDelete(StreetsSheetMode.New, id, finished = true))
        assertFalse(StreetsSheetRules.cancelMayDelete(StreetsSheetMode.Update, id, finished = true))
    }

    @Test fun aRetryKeepsTheUnfinishedAreaItCameFrom() {
        assertFalse(StreetsSheetRules.cancelMayDelete(StreetsSheetMode.Resume(id), id, finished = false))
    }

    @Test fun nothingBegunIsNothingToDelete() {
        assertFalse(StreetsSheetRules.cancelMayDelete(StreetsSheetMode.New, null, finished = false))
    }

    @Test fun aFinishedSheetIsNotCancelledAgain() {
        assertFalse(StreetsSheetRules.cancelReports(finished = true))
        assertTrue(StreetsSheetRules.cancelReports(finished = false))
    }
}
