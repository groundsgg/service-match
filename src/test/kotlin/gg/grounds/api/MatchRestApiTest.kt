package gg.grounds.api

import gg.grounds.domain.ModeConfig
import gg.grounds.domain.Rating
import gg.grounds.domain.RatingRepository
import gg.grounds.domain.ResultOutcome
import gg.grounds.domain.ServerAssignment
import gg.grounds.domain.StoredRating
import gg.grounds.domain.Ticket
import gg.grounds.domain.TicketState
import gg.grounds.persistence.ValkeyQueueIT
import io.quarkus.test.InjectMock
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The REST surface, end to end through the HTTP stack.
 *
 * The domain is mocked on purpose: what is under test here is the translation — which status code
 * each domain refusal becomes, and that a caller can tell them apart from the body. The maths and
 * the storage semantics have their own tests, and asserting them again through HTTP would only make
 * this file fail for reasons that have nothing to do with the API.
 *
 * The container resources are still needed because `@QuarkusTest` boots the whole application, and
 * Flyway migrates at start.
 */
@QuarkusTest
@QuarkusTestResource(ValkeyQueueIT.PostgresResource::class)
@QuarkusTestResource(ValkeyQueueIT.ValkeyResource::class)
class MatchRestApiTest {

    @InjectMock lateinit var queue: QueueService

    @InjectMock lateinit var results: ResultService

    @InjectMock lateinit var ratings: RatingRepository

    // -----------------------------------------------------------
    // POST /v1/match/tickets
    // -----------------------------------------------------------

    @Test
    fun `enqueue answers 201 with the ticket`() {
        whenever(queue.enqueue(PLAYER, MODE)).thenReturn(ticket())
        whenever(queue.queueDepth(MODE)).thenReturn(3)

        given()
            .contentType(ContentType.JSON)
            .body("""{"playerId":"$PLAYER","modeId":"$MODE"}""")
            .post("/v1/match/tickets")
            .then()
            .statusCode(201)
            .body("ticketId", equalTo(TICKET))
            .body("mu", equalTo(25.0f))
            .body("queuePosition", equalTo(3))
    }

    @Test
    fun `a player who already holds a ticket is 409, not 400`() {
        // Nothing about the request was wrong — the answer has to be
        // distinguishable from a malformed one, or a proxy cannot tell the
        // player why they were refused.
        whenever(queue.enqueue(PLAYER, MODE)).thenThrow(AlreadyQueuedException())

        given()
            .contentType(ContentType.JSON)
            .body("""{"playerId":"$PLAYER","modeId":"$MODE"}""")
            .post("/v1/match/tickets")
            .then()
            .statusCode(409)
            .contentType("application/problem+json")
            .body("code", equalTo("ticket_exists"))
    }

    @Test
    fun `a mode forge never pushed is 404`() {
        whenever(queue.enqueue(PLAYER, MODE)).thenThrow(UnknownModeException(MODE))

        given()
            .contentType(ContentType.JSON)
            .body("""{"playerId":"$PLAYER","modeId":"$MODE"}""")
            .post("/v1/match/tickets")
            .then()
            .statusCode(404)
            .body("code", equalTo("unknown_mode"))
    }

    @Test
    fun `a player id that is not a UUID never reaches the queue`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"playerId":"not-a-uuid","modeId":"$MODE"}""")
            .post("/v1/match/tickets")
            .then()
            .statusCode(400)
            .body("code", equalTo("invalid_request"))
    }

    @Test
    fun `a missing mode id is rejected before the queue is touched`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"playerId":"$PLAYER","modeId":""}""")
            .post("/v1/match/tickets")
            .then()
            .statusCode(400)
            .body("code", equalTo("invalid_request"))
    }

    // -----------------------------------------------------------
    // DELETE /v1/match/tickets/{id}
    // -----------------------------------------------------------

    @Test
    fun `a cancel that came too late is 200 with cancelled false`() {
        // The match had already formed. That is an answer, not an error: the
        // caller logs it and moves on, exactly as it does on the gRPC path.
        whenever(queue.cancel(TICKET, PLAYER)).thenReturn(false)

        given()
            .delete("/v1/match/tickets/$TICKET?playerId=$PLAYER")
            .then()
            .statusCode(200)
            .body("cancelled", equalTo(false))
    }

    @Test
    fun `cancelling a live ticket answers cancelled true`() {
        whenever(queue.cancel(TICKET, PLAYER)).thenReturn(true)

        given()
            .delete("/v1/match/tickets/$TICKET?playerId=$PLAYER")
            .then()
            .statusCode(200)
            .body("cancelled", equalTo(true))
    }

    @Test
    fun `cancelling without a player id is rejected`() {
        given().delete("/v1/match/tickets/$TICKET").then().statusCode(400)
    }

    // -----------------------------------------------------------
    // GET /v1/match/tickets/{id}
    // -----------------------------------------------------------

    @Test
    fun `a queued ticket carries no assignment`() {
        whenever(queue.findTicket(TICKET)).thenReturn(ticket())
        whenever(queue.queueDepth(MODE)).thenReturn(7)

        given()
            .get("/v1/match/tickets/$TICKET")
            .then()
            .statusCode(200)
            .body("state", equalTo("QUEUED"))
            .body("queueDepth", equalTo(7))
            .body("assignment", nullValue())
    }

    @Test
    fun `an assigned ticket carries the server to route to`() {
        val matchId = UUID.randomUUID().toString()
        whenever(queue.findTicket(TICKET))
            .thenReturn(
                ticket()
                    .copy(
                        state = TicketState.ASSIGNED,
                        matchId = matchId,
                        assignment = ServerAssignment("bedwars-abc12", "10.42.0.7", 25565),
                    )
            )
        whenever(queue.queueDepth(MODE)).thenReturn(0)

        given()
            .get("/v1/match/tickets/$TICKET")
            .then()
            .statusCode(200)
            // The state name is the proto enum name, unchanged. plugin-match
            // compares these strings, so a rename here is a routing outage.
            .body("state", equalTo("ASSIGNED"))
            .body("assignment.matchId", equalTo(matchId))
            .body("assignment.gameServerName", equalTo("bedwars-abc12"))
            .body("assignment.address", equalTo("10.42.0.7"))
            .body("assignment.port", equalTo(25565))
    }

    @Test
    fun `an unknown ticket is 404 rather than an empty ticket`() {
        whenever(queue.findTicket(TICKET)).thenReturn(null)

        given()
            .get("/v1/match/tickets/$TICKET")
            .then()
            .statusCode(404)
            .body("code", equalTo("unknown_ticket"))
    }

    // -----------------------------------------------------------
    // GET /v1/match/players/{id}/ratings/{mode}
    // -----------------------------------------------------------

    @Test
    fun `an unrated player gets the seeded defaults, not an error`() {
        whenever(ratings.find(PLAYER, MODE)).thenReturn(null)

        given()
            .get("/v1/match/players/$PLAYER/ratings/$MODE")
            .then()
            .statusCode(200)
            .body("mu", equalTo(25.0f))
            .body("gamesPlayed", equalTo(0))
    }

    @Test
    fun `a rated player gets their stored rating`() {
        whenever(ratings.find(PLAYER, MODE)).thenReturn(StoredRating(Rating(30.0, 4.0), 12))

        given()
            .get("/v1/match/players/$PLAYER/ratings/$MODE")
            .then()
            .statusCode(200)
            .body("mu", equalTo(30.0f))
            .body("sigma", equalTo(4.0f))
            .body("display", equalTo(18.0f))
            .body("gamesPlayed", equalTo(12))
    }

    // -----------------------------------------------------------
    // GET /v1/match/modes/{mode}/queue-stats
    // -----------------------------------------------------------

    @Test
    fun `an unconfigured mode reports unavailable with a zero depth`() {
        // Not "an empty queue": nobody can be waiting for a mode that has no
        // config, and reporting a depth would read as one that is simply idle.
        whenever(queue.isKnownMode(MODE)).thenReturn(false)

        given()
            .get("/v1/match/modes/$MODE/queue-stats")
            .then()
            .statusCode(200)
            .body("available", equalTo(false))
            .body("ticketsQueued", equalTo(0))
    }

    @Test
    fun `a configured mode reports its depth`() {
        whenever(queue.isKnownMode(MODE)).thenReturn(true)
        whenever(queue.queueDepth(MODE)).thenReturn(4)

        given()
            .get("/v1/match/modes/$MODE/queue-stats")
            .then()
            .statusCode(200)
            .body("available", equalTo(true))
            .body("ticketsQueued", equalTo(4))
    }

    // -----------------------------------------------------------
    // PUT /v1/match/modes/{mode}/queue
    // -----------------------------------------------------------

    @Test
    fun `an upsert without a band uses the defaults`() {
        whenever(queue.upsertMode(any())).thenReturn(true)

        given()
            .contentType(ContentType.JSON)
            .body("""{"teamSize":4,"teamCount":2,"ranked":true}""")
            .put("/v1/match/modes/$MODE/queue")
            .then()
            .statusCode(200)
            .body("created", equalTo(true))

        val captor = argumentCaptor<ModeConfig>()
        verify(queue).upsertMode(captor.capture())
        val config = captor.firstValue
        assert(config.modeId == MODE)
        assert(config.teamSize == 4 && config.teamCount == 2)
        assert(config.ranked)
        assert(config.band == gg.grounds.domain.BandConfig())
    }

    @Test
    fun `a zero in the band means unset, exactly as it does on the wire`() {
        // A caller porting from gRPC sends the zeros proto3 gave it for free. If
        // those were taken literally the band would be nothing and no two
        // players would ever match.
        whenever(queue.upsertMode(any())).thenReturn(false)

        given()
            .contentType(ContentType.JSON)
            .body(
                """{"teamSize":1,"teamCount":2,"band":{"b0":0,"k":0,"w":0,"sSeconds":0,""" +
                    """"mercySeconds":0,"mutualSeconds":0}}"""
            )
            .put("/v1/match/modes/$MODE/queue")
            .then()
            .statusCode(200)
            .body("created", equalTo(false))

        val captor = argumentCaptor<ModeConfig>()
        verify(queue).upsertMode(captor.capture())
        assert(captor.firstValue.band == gg.grounds.domain.BandConfig())
    }

    @Test
    fun `a band that is set is carried through`() {
        whenever(queue.upsertMode(any())).thenReturn(true)

        given()
            .contentType(ContentType.JSON)
            .body("""{"teamSize":1,"teamCount":2,"band":{"b0":5.5,"mercySeconds":30}}""")
            .put("/v1/match/modes/$MODE/queue")
            .then()
            .statusCode(200)

        val captor = argumentCaptor<ModeConfig>()
        verify(queue).upsertMode(captor.capture())
        val band = captor.firstValue.band
        assert(band.b0 == 5.5)
        assert(band.mercySeconds == 30)
        // Untouched fields keep their defaults rather than collapsing to zero.
        assert(band.k == gg.grounds.domain.BandConfig().k)
    }

    @Test
    fun `a mode with one team is refused`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"teamSize":4,"teamCount":1}""")
            .put("/v1/match/modes/$MODE/queue")
            .then()
            .statusCode(400)
            .body("code", equalTo("invalid_request"))
    }

    // -----------------------------------------------------------
    // POST /v1/match/matches/{id}/result
    // -----------------------------------------------------------

    @Test
    fun `a rated result answers applied and rated`() {
        val matchId = UUID.randomUUID()
        whenever(results.report(eq(matchId), any(), eq("normal_finish")))
            .thenReturn(ResultOutcome(applied = true, rated = true))

        given()
            .contentType(ContentType.JSON)
            .body("""{"results":[{"playerId":"$PLAYER","placement":1}]}""")
            .post("/v1/match/matches/$matchId/result")
            .then()
            .statusCode(200)
            .body("applied", equalTo(true))
            .body("rated", equalTo(true))
    }

    @Test
    fun `a blank termination reason falls back to normal_finish`() {
        val matchId = UUID.randomUUID()
        whenever(results.report(eq(matchId), any(), eq("normal_finish")))
            .thenReturn(ResultOutcome(applied = true, rated = false))

        given()
            .contentType(ContentType.JSON)
            .body("""{"results":[{"playerId":"$PLAYER","placement":1}],"terminationReason":"  "}""")
            .post("/v1/match/matches/$matchId/result")
            .then()
            .statusCode(200)
    }

    @Test
    fun `a result for a match we never formed is 404`() {
        val matchId = UUID.randomUUID()
        whenever(results.report(eq(matchId), any(), any()))
            .thenThrow(UnknownMatchException(matchId.toString()))

        given()
            .contentType(ContentType.JSON)
            .body("""{"results":[{"playerId":"$PLAYER","placement":1}]}""")
            .post("/v1/match/matches/$matchId/result")
            .then()
            .statusCode(404)
            .body("code", equalTo("unknown_match"))
    }

    @Test
    fun `naming a player who was not in the match is 403`() {
        // Anything softer would let a gamemode move any player's rating at will.
        val matchId = UUID.randomUUID()
        whenever(results.report(eq(matchId), any(), any()))
            .thenThrow(NotInMatchException(PLAYER, matchId))

        given()
            .contentType(ContentType.JSON)
            .body("""{"results":[{"playerId":"$PLAYER","placement":1}]}""")
            .post("/v1/match/matches/$matchId/result")
            .then()
            .statusCode(403)
            .body("code", equalTo("not_in_match"))
    }

    @Test
    fun `a result with no players is refused`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"results":[]}""")
            .post("/v1/match/matches/${UUID.randomUUID()}/result")
            .then()
            .statusCode(400)
            .body("code", equalTo("invalid_request"))
    }

    @Test
    fun `a placement below one is refused`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"results":[{"playerId":"$PLAYER","placement":0}]}""")
            .post("/v1/match/matches/${UUID.randomUUID()}/result")
            .then()
            .statusCode(400)
            .body("code", equalTo("invalid_request"))
    }

    @Test
    fun `a match id that is not a UUID is refused`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"results":[{"playerId":"$PLAYER","placement":1}]}""")
            .post("/v1/match/matches/not-a-uuid/result")
            .then()
            .statusCode(400)
            .body("code", equalTo("invalid_request"))
    }

    // -----------------------------------------------------------

    private fun ticket() =
        Ticket(
            id = TICKET,
            playerId = PLAYER.toString(),
            modeId = MODE,
            mu = 25.0,
            sigma = 8.333333333333334,
            enqueuedAt = Instant.now(),
        )

    private companion object {
        private val PLAYER: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")
        private const val MODE = "bedwars-squads"
        private const val TICKET = "ticket-1"
    }
}
