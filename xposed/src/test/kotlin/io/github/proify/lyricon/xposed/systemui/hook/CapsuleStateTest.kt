package io.github.proify.lyricon.xposed.systemui.hook

import org.junit.Assert.*
import org.junit.Test

class CapsuleStateTest {
    private fun capsule(state: String? = "NORMAL", visible: Boolean = true) =
        CapsuleObservation(state, visible, attached = true, shown = true, hasContent = true)

    @Test fun musicTimeoutAndSwipeUseTheCommittedStateEvenWithVisibleChildren() {
        val session = CapsuleStateSession()
        val events = mutableListOf<CapsuleDisplayState>()
        session.subscribe(events::add)
        session.update(1, listOf(capsule()))
        session.update(2, listOf(capsule("MIN")))
        session.update(3, listOf(capsule("NORMAL")))
        session.update(4, listOf(capsule("HIDE")))
        assertEquals(listOf(CapsuleDisplayState.ABSENT, CapsuleDisplayState.EXPANDED,
            CapsuleDisplayState.COLLAPSED, CapsuleDisplayState.EXPANDED,
            CapsuleDisplayState.ABSENT), events)
    }

    @Test fun oneCollapsedSourceDoesNotOverrideAnotherExpandedSource() {
        assertEquals(CapsuleDisplayState.EXPANDED,
            CapsuleStateSession.merge(listOf(capsule("MIN"), capsule())))
        assertEquals(CapsuleDisplayState.COLLAPSED,
            CapsuleStateSession.merge(listOf(capsule("MIN"), capsule("HIDE"))))
    }

    @Test fun fullStateDoesNotFlickerDuringChildSwitching() {
        assertEquals(CapsuleDisplayState.EXPANDED, capsule(visible = false).state)
        assertEquals(CapsuleDisplayState.ABSENT, capsule().copy(hasContent = false).state)
    }

    @Test fun legacyChildrenCannotBeCombinedAcrossHiddenOrDetachedContainers() {
        val observations = listOf(
            capsule(null, visible = false),
            capsule(null).copy(shown = false),
            capsule(null).copy(attached = false),
            capsule(null).copy(home = false)
        )
        assertEquals(CapsuleDisplayState.ABSENT, CapsuleStateSession.merge(observations))
        assertEquals(CapsuleDisplayState.EXPANDED, capsule(null).state)
    }

    @Test fun detachedAndKeyguardSemanticContainersAreExcluded() {
        assertEquals(CapsuleDisplayState.ABSENT, CapsuleStateSession.merge(listOf(
            capsule().copy(home = false), capsule().copy(attached = false),
            capsule().copy(shown = false))))
    }

    @Test fun bindingAndLateSubscribersReplayCurrentState() {
        val session = CapsuleStateSession(listOf(capsule()))
        val initial = mutableListOf<CapsuleDisplayState>()
        session.subscribe(initial::add)
        assertEquals(listOf(CapsuleDisplayState.EXPANDED), initial)
        session.update(1, listOf(capsule("MIN")))
        val late = mutableListOf<CapsuleDisplayState>()
        session.subscribe(late::add)
        assertEquals(listOf(CapsuleDisplayState.COLLAPSED), late)
    }

    @Test fun duplicateAndOutOfOrderEventsCannotRestoreOldWidth() {
        val session = CapsuleStateSession()
        val events = mutableListOf<CapsuleDisplayState>()
        session.subscribe(events::add)
        assertTrue(session.update(10, listOf(capsule("MIN"))))
        assertFalse(session.update(9, listOf(capsule())))
        assertFalse(session.update(10, listOf(capsule())))
        assertFalse(session.update(11, listOf(capsule("MIN"))))
        assertEquals(listOf(CapsuleDisplayState.ABSENT, CapsuleDisplayState.COLLAPSED), events)
    }

    @Test fun closingAnOwnerRejectsQueuedEventsAndDoesNotAffectReplacement() {
        val old = CapsuleStateSession(listOf(capsule()))
        val replacement = CapsuleStateSession(listOf(capsule("MIN")))
        val events = mutableListOf<CapsuleDisplayState>()
        old.subscribe(events::add)
        old.close()
        old.close()
        assertFalse(old.update(100, listOf(capsule())))
        assertEquals(CapsuleDisplayState.ABSENT, old.state)
        assertEquals(CapsuleDisplayState.COLLAPSED, replacement.state)
        assertEquals(listOf(CapsuleDisplayState.EXPANDED, CapsuleDisplayState.ABSENT), events)
    }
}
