package moe.shizuku.manager.maxhz

internal enum class GameAutoEntryDecision {
    SCHEDULE,
    DEDUP_PENDING,
    DEDUP_COMMITTED,
    TRANSFER_COMMITTED_OWNER
}

internal data class GameAutoEntryState(
    val activePackage: String?,
    val activeTarget: String?,
    val pendingPackage: String?,
    val pendingTarget: String?,
    val pendingGeneration: Long?,
    val pendingJobActive: Boolean
)

internal fun decideGameAutoEntry(
    requestPackage: String,
    requestTarget: String,
    state: GameAutoEntryState
): GameAutoEntryDecision {
    if (
        state.activePackage != null &&
        state.activeTarget == requestTarget
    ) {
        return if (state.activePackage == requestPackage) {
            GameAutoEntryDecision.DEDUP_COMMITTED
        } else {
            GameAutoEntryDecision.TRANSFER_COMMITTED_OWNER
        }
    }
    if (
        state.pendingJobActive &&
        state.pendingGeneration != null &&
        state.pendingPackage == requestPackage &&
        state.pendingTarget == requestTarget
    ) {
        return GameAutoEntryDecision.DEDUP_PENDING
    }
    return GameAutoEntryDecision.SCHEDULE
}
