package com.example.chessmentor.domain.training

import org.junit.Assert.*
import org.junit.Test

class InterleavedPatternQueueTest {
    @Test fun blankMistakesAreIgnoredAndEquivalentNamesShareSlots() {
        val queue = InterleavedPatternQueue()
        queue.scheduleAfterMistake(" \t")
        assertEquals(0, queue.pendingCount)
        queue.scheduleAfterMistake(" PIN ")
        repeat(5) { queue.scheduleAfterMistake("PIN") }
        assertEquals(2, queue.pendingCount)
    }

    @Test fun pollingAloneNeverAdvancesTheCountdown() {
        val queue = InterleavedPatternQueue(firstGap = 1)
        queue.scheduleAfterMistake("PIN")
        repeat(5) { assertNull(queue.pollDuePattern()) }
        queue.onExerciseServed("FORK")
        assertEquals("PIN", queue.pollDuePattern())
        assertEquals(1, queue.pendingCount)
    }

    @Test fun servingTheScheduledPatternDoesNotCountAsAnInterveningExercise() {
        val queue = InterleavedPatternQueue(firstGap = 2)
        queue.scheduleAfterMistake("PIN")
        repeat(3) { queue.onExerciseServed(" PIN ") }
        queue.onExerciseServed("FORK")
        assertNull(queue.pollDuePattern())
        queue.onExerciseServed("SKEWER")
        assertEquals("PIN", queue.pollDuePattern())
    }

    @Test fun dueMotifBlockedByLastServedDoesNotBlockOtherMotifs() {
        val queue = InterleavedPatternQueue(firstGap = 0, secondGap = 3)
        queue.scheduleAfterMistake("PIN")
        queue.scheduleAfterMistake("FORK")
        queue.onExerciseServed("PIN")
        assertEquals("FORK", queue.pollDuePattern())
        queue.onExerciseServed("FORK")
        assertEquals("PIN", queue.pollDuePattern())
    }

    @Test fun fullQueueKeepsExistingWorkEvenAtOddCapacity() {
        val queue = InterleavedPatternQueue(firstGap = 0, secondGap = 0, maxScheduled = 3)
        listOf("PIN", "FORK", "SKEWER").forEach(queue::scheduleAfterMistake)
        assertEquals(3, queue.pendingCount)
        assertEquals(listOf("PIN", "PIN", "FORK"), List(3) { queue.pollDuePattern() })
        assertNull(queue.pollDuePattern())
    }

    @Test fun clearStartsANewSessionWithoutOldMotifsOrSuppression() {
        val queue = InterleavedPatternQueue(firstGap = 0)
        queue.onExerciseServed("PIN")
        queue.scheduleAfterMistake("FORK")
        queue.clear()
        assertNull(queue.lastServedPattern)
        assertNull(queue.pollDuePattern())
        queue.scheduleAfterMistake("PIN")
        assertEquals("PIN", queue.pollDuePattern())
    }
}
