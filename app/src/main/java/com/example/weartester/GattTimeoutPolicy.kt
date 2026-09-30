package com.example.weartester

internal object GattTimeoutPolicy {
    const val ADVERTISED_CONNECT_TIMEOUT_MS = 8_000L
    const val DISCOVERY_TIMEOUT_MS = 75_000L

    enum class Phase { CONNECTING, DISCOVERING }

    fun expiredPhase(
        attemptAge: Long,
        connectedAge: Long?,
        servicesDiscovered: Boolean,
        connectTimeout: Long,
    ): Phase? = when {
        servicesDiscovered -> null
        connectedAge != null -> Phase.DISCOVERING.takeIf { connectedAge >= DISCOVERY_TIMEOUT_MS }
        attemptAge >= connectTimeout -> Phase.CONNECTING
        else -> null
    }
}
