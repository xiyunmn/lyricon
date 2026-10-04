/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.hook

enum class CapsuleDisplayState { ABSENT, COLLAPSED, EXPANDED }

/** One container and its own children, captured from a single status bar tree. */
internal data class CapsuleObservation(
    val contentState: String?,
    val hasVisibleCapsule: Boolean,
    val attached: Boolean,
    val shown: Boolean,
    val home: Boolean = true,
    val hasContent: Boolean = hasVisibleCapsule,
    val identity: Int = 0
) {
    val state: CapsuleDisplayState
        get() = when {
            !attached || !shown || !home -> CapsuleDisplayState.ABSENT
            contentState == "MIN" -> CapsuleDisplayState.COLLAPSED
            contentState == "HIDE" -> CapsuleDisplayState.ABSENT
            contentState == "NORMAL" -> if (hasContent) CapsuleDisplayState.EXPANDED
                else CapsuleDisplayState.ABSENT
            hasVisibleCapsule -> CapsuleDisplayState.EXPANDED
            else -> CapsuleDisplayState.ABSENT
        }
}

/** Main-thread session. Closing a detached owner prevents late events resurrecting it. */
internal class CapsuleStateSession(initial: List<CapsuleObservation> = emptyList()) {
    var state: CapsuleDisplayState = merge(initial)
        private set
    private var revision = Long.MIN_VALUE
    private var closed = false
    private val listeners = mutableListOf<(CapsuleDisplayState) -> Unit>()

    fun subscribe(listener: (CapsuleDisplayState) -> Unit) {
        if (closed) return
        listeners.add(listener)
        listener(state)
    }

    fun update(revision: Long, observations: List<CapsuleObservation>): Boolean {
        if (closed || revision <= this.revision) return false
        this.revision = revision
        val next = merge(observations)
        if (next == state) return false
        state = next
        listeners.toList().forEach { it(next) }
        return true
    }

    fun close() {
        if (closed) return
        closed = true
        state = CapsuleDisplayState.ABSENT
        listeners.toList().forEach { it(state) }
        listeners.clear()
    }

    companion object {
        fun merge(observations: List<CapsuleObservation>): CapsuleDisplayState =
            observations.maxOfOrNull { it.state } ?: CapsuleDisplayState.ABSENT
    }
}
