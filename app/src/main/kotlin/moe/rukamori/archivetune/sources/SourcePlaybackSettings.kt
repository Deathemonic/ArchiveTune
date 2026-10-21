package moe.rukamori.archivetune.sources

data class SourceAccountState(
    val connected: Boolean,
    val quality: String,
    val matchRevision: Long = 0L,
    val accountRevision: Long = 0L,
    val initialized: Boolean = true,
)

data class SourcePlaybackSettings(
    val accounts: Map<SourceProvider, SourceAccountState>,
    val configuration: SourceConfiguration,
) {
    val connectedProviders: Set<SourceProvider>
        get() = accounts.filterValues { it.connected }.keys

    fun requiresPlaybackRefresh(previous: SourcePlaybackSettings?): Boolean = previous != null && previous.accounts.values.all { it.initialized } &&
        accounts.values.all { it.initialized } && this != previous

    val matchRevision: Long
        get() = accounts.values.sumOf { it.matchRevision }
}
