package gg.grounds.api

import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

/**
 * The ways a matchmaker call legitimately fails, rendered as problem details.
 *
 * They are separate status codes rather than one generic 400 because callers act on them
 * differently: a proxy tells the player they are already queued, a gamemode whose mode is unknown
 * has a deployment problem rather than a request problem, and a result for a match we never formed
 * is a fabrication that should be surfaced, not retried.
 *
 * The codes here are the same distinctions the gRPC surface draws with `ALREADY_EXISTS`,
 * `NOT_FOUND` and `PERMISSION_DENIED`, so a caller's existing branching ports across unchanged.
 */
@Provider
class AlreadyQueuedMapper : ExceptionMapper<AlreadyQueuedException> {
    override fun toResponse(exception: AlreadyQueuedException): Response =
        // 409, not 400: nothing about the request was wrong. One live ticket per player,
        // worldwide, is the invariant that stops a player being committed to two matches.
        problem(409, "Already queued", exception.message, "ticket_exists")
}

@Provider
class UnknownModeMapper : ExceptionMapper<UnknownModeException> {
    override fun toResponse(exception: UnknownModeException): Response =
        problem(404, "Unknown mode", exception.message, "unknown_mode")
}

@Provider
class UnknownMatchMapper : ExceptionMapper<UnknownMatchException> {
    override fun toResponse(exception: UnknownMatchException): Response =
        // Not a race: the match record is written at claim time, so an unknown id means this
        // match was never ours.
        problem(404, "Unknown match", exception.message, "unknown_match")
}

@Provider
class NotInMatchMapper : ExceptionMapper<NotInMatchException> {
    override fun toResponse(exception: NotInMatchException): Response =
        // A gamemode naming players who were not in the match could otherwise move any player's
        // rating at will.
        problem(403, "Player was not in this match", exception.message, "not_in_match")
}

@Provider
class UnknownTicketMapper : ExceptionMapper<UnknownTicketException> {
    override fun toResponse(exception: UnknownTicketException): Response =
        problem(404, "Unknown ticket", exception.message, "unknown_ticket")
}

/**
 * Argument validation, as thrown by the resources before anything is queued or rated. Kept out of
 * the domain so it never has to know about HTTP.
 */
class InvalidRequestException(message: String) : RuntimeException(message)

@Provider
class InvalidRequestMapper : ExceptionMapper<InvalidRequestException> {
    override fun toResponse(exception: InvalidRequestException): Response =
        problem(400, "Invalid request", exception.message, "invalid_request")
}

private fun problem(status: Int, title: String, detail: String?, code: String): Response =
    Response.status(status)
        .type(PROBLEM_JSON)
        .entity(ProblemDetails(title = title, status = status, detail = detail, code = code))
        .build()
