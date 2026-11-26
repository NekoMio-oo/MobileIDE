package dev.mobileide.data.editor

/** A file position the editor should reveal once the tab is loaded. */
data class EditorRevealTarget(
    val relativePath: String,
    val line: Int,
    val column: Int?
)

/**
 * One-shot hand-off between the build problem list and the editor. Both live in
 * the same process, so a small synchronized map avoids stuffing line numbers
 * into the navigation route.
 */
class EditorRevealRequests {

    private val targets = mutableMapOf<Long, EditorRevealTarget>()

    @Synchronized
    fun request(projectId: Long, target: EditorRevealTarget) {
        targets[projectId] = target
    }

    /** Removes and returns the pending target, if any. */
    @Synchronized
    fun consume(projectId: Long): EditorRevealTarget? = targets.remove(projectId)

    @Synchronized
    fun clear(projectId: Long) {
        targets.remove(projectId)
    }
}
