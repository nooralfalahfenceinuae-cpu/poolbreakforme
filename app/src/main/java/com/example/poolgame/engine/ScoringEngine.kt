package com.example.poolgame.engine

/**
 * Implements standard English Billiards scoring for a single shot:
 *
 *   Player strikes cue ball
 *     -> hits BOTH object balls in the same shot?              => +2 (Cannon)
 *     -> an object ball falls in a pocket?                      => +2 or +3 (Winning Hazard), object ball respots
 *     -> cue ball falls in a pocket after contacting a ball?     => +2 or +3 (Losing Hazard), cue ball respots in the "D"
 *
 * Multiple of these can happen on the same shot (e.g. a cannon that also
 * pots a ball), and their points add together, matching real billiards rules.
 */
object ScoringEngine {

    /** Point value of potting/contacting the red ball vs. the opponent's white ball. */
    const val RED_BALL_POINTS = 3
    const val WHITE_BALL_POINTS = 2
    const val CANNON_POINTS = 2

    data class ShotBreakdown(
        val cannon: Boolean,
        val winningHazardPoints: Int,   // 0 if no ball was potted
        val losingHazardPoints: Int,    // 0 if the cue ball wasn't potted (or was potted with no prior contact)
        val totalPoints: Int
    )

    /**
     * @param cueId id of the striker's cue ball
     * @param redId id of the red object ball
     * @param opponentWhiteId id of the opponent's white object ball
     * @param contactedIds every ball id the cue ball touched at any point during this shot
     * @param pottedIds every ball id that fell into a pocket during this shot
     */
    fun score(
        cueId: Int,
        redId: Int,
        opponentWhiteId: Int,
        contactedIds: Set<Int>,
        pottedIds: Set<Int>
    ): ShotBreakdown {
        val hitRed = contactedIds.contains(redId)
        val hitOpponentWhite = contactedIds.contains(opponentWhiteId)
        val cannon = hitRed && hitOpponentWhite

        // Winning hazard: an object ball (not the cue ball) went down.
        var winningPoints = 0
        if (pottedIds.contains(redId)) winningPoints += RED_BALL_POINTS
        if (pottedIds.contains(opponentWhiteId)) winningPoints += WHITE_BALL_POINTS

        // Losing hazard: the cue ball went down, but only scores if it made
        // contact with something first (a clean scratch with no contact is a foul,
        // not a scoring hazard — left as 0 here; wire in foul handling separately).
        var losingPoints = 0
        if (pottedIds.contains(cueId) && contactedIds.isNotEmpty()) {
            losingPoints = when {
                hitRed -> RED_BALL_POINTS
                hitOpponentWhite -> WHITE_BALL_POINTS
                else -> 0
            }
        }

        val cannonPoints = if (cannon) CANNON_POINTS else 0
        val total = cannonPoints + winningPoints + losingPoints

        return ShotBreakdown(
            cannon = cannon,
            winningHazardPoints = winningPoints,
            losingHazardPoints = losingPoints,
            totalPoints = total
        )
    }
}
