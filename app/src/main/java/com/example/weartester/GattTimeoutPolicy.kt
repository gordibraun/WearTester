package com.example.weartester

internal object GattTimeoutPolicy {
    // Measured on the OnePlus Watch 3 (85 connects on 2026-10-03): a connect that will succeed
    // reports CONNECTED in 1.9 s typically, under 2.6 s in 95 % of cases, and every success ever
    // observed came within 5.3 s. One that has not by 5.5 s is a hung attempt - the stack never
    // calls back - and every second spent waiting on it is a second of the transmitter's ~15 s
    // advertising burst gone. The old 8 s left no time for a second try; this leaves about eight.
    const val ADVERTISED_CONNECT_TIMEOUT_MS = 5_500L
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
