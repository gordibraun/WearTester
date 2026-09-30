package com.example.weartester

internal object OnePlusPowerPolicy {
    enum class Result { OFF, ACTIVE, FAILED, RESTORE_PENDING }

    interface Port {
        fun readRestriction(): Boolean?
        fun writeRestriction(enabled: Boolean)
        fun ownedBoot(): Int?
        fun saveOwnedBoot(boot: Int?): Boolean
    }

    fun parseRestriction(output: String): Boolean? = when (output.trim()) {
        "bm power manager function is true" -> true
        "bm power manager function is false" -> false
        else -> null
    }

    fun reconcile(wanted: Boolean, boot: Int, port: Port): Result {
        if (boot < 0) return Result.FAILED
        // The vendor flag is in memory only. Never restore ownership from a past boot.
        if (port.ownedBoot()?.let { it != boot } == true && !port.saveOwnedBoot(null)) {
            return Result.FAILED
        }
        if (!wanted && port.ownedBoot() == null) return Result.OFF
        val restricted = port.readRestriction() ?: return Result.FAILED
        if (wanted) {
            if (!restricted) return Result.ACTIVE
            // Persist restoration intent before a command with potentially uncertain completion.
            if (!port.saveOwnedBoot(boot)) return Result.FAILED
            port.writeRestriction(false)
            return if (port.readRestriction() == false) Result.ACTIVE else Result.FAILED
        }
        if (!restricted) {
            port.writeRestriction(true)
            if (port.readRestriction() != true) return Result.RESTORE_PENDING
        }
        return if (port.saveOwnedBoot(null)) Result.OFF else Result.RESTORE_PENDING
    }
}
