package gg.grounds.events

/**
 * A match result on its way from the regional runtime to the core writer.
 *
 * Subject `match.result.submitted.<modeId>`, message id `match-result:<matchId>`. The regional
 * runtime has already checked it against the match record (the match exists, every placed player
 * was in it), so the writer only re-checks and applies. JSON rather than a proto: both ends are
 * this service, and the body is exactly the REST request plus the ids the URL carried.
 */
data class MatchResultSubmitted(
    val matchId: String,
    val modeId: String,
    val results: List<Placement>,
    val terminationReason: String,
    val submittedAt: String,
) {
    data class Placement(val playerId: String, val placement: Int)

    companion object {
        const val SUBJECT_PREFIX = "match.result.submitted."
        const val SUBJECT = "match.result.submitted.>"

        fun messageId(matchId: String) = "match-result:$matchId"

        /**
         * True when [token] can be the tail of a subject: no wildcards, no whitespace, no empty
         * token. Dots are fine (a mode id like `bedwars.solos` just spans two tokens).
         */
        fun isSubjectTail(token: String): Boolean =
            token.isNotEmpty() &&
                token.split('.').none { it.isEmpty() } &&
                token.none { it.isWhitespace() || it == '*' || it == '>' }
    }
}
