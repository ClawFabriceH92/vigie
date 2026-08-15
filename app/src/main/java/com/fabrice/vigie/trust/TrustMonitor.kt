package com.fabrice.vigie.trust

/**
 * Logique d'armement/désarmement du mode confiance. PURE (testable).
 *
 * - Si au moins un périphérique de confiance est présent → DÉSARMÉ
 *   (l'utilisateur est là, pas de surveillance).
 * - Si aucun n'est présent depuis [disarmDelayMs] → ARMÉ.
 */
object TrustMonitor {

    enum class ArmState { ARMING, ARMED, DISARMED }

    data class Decision(
        val state: ArmState,
        val trustedPresent: List<String>, // MACs des périphériques de confiance vus au dernier scan
        val secondsSinceTrust: Long,
        val secondsUntilArmed: Long,
    )

    /**
     * @param trustedPresent MACs des périphériques de confiance présents au dernier scan
     * @param lastTrustSeenMs horodatage (epoch ms) du dernier scan où un périphérique de confiance était présent ; 0 si jamais vu
     * @param nowMs maintenant (epoch ms)
     * @param disarmDelayMs délai sans confiance avant armement
     */
    fun decide(
        trustedPresent: List<String>,
        lastTrustSeenMs: Long,
        nowMs: Long,
        disarmDelayMs: Long,
    ): Decision {
        if (trustedPresent.isNotEmpty()) {
            return Decision(ArmState.DISARMED, trustedPresent, 0, 0)
        }
        val sinceTrust = if (lastTrustSeenMs == 0L) nowMs else nowMs - lastTrustSeenMs
        if (sinceTrust >= disarmDelayMs) {
            return Decision(ArmState.ARMED, emptyList(), sinceTrust / 1000, 0)
        }
        return Decision(
            ArmState.ARMING,
            emptyList(),
            sinceTrust / 1000,
            (disarmDelayMs - sinceTrust) / 1000,
        )
    }
}
