# service-match

The central matchmaker. It forms MMR-based matches per
(project, mode, region), allocates an Agones GameServer in the target
cluster, and routes the matched players there. Match results feed a
durable rating store.

**How players actually reach the queue is not decided yet.** There is a
`/queue <mode>` command on the Velocity proxy, but treat it as a test
vehicle for driving the service end-to-end — not as the product surface.
The service only ever sees an `Enqueue` call; whatever triggers it (a
command, a lobby NPC, a UI, an automatic hook on join) is a decision for
later and costs nothing to change, because it lives entirely above this
API.

## The split that shapes everything

**Ephemeral vs. central-durable.** Gamemode servers, vClusters and their
analytics die with the workspace — but MMR only means something if
ratings outlive the session that produced them. So:

- **Tickets, queues, in-flight matches → Valkey.** Losable. If they go,
  players simply re-queue.
- **Ratings → the central services Postgres**, next to the durable player
  identity (Keycloak UUID).

A match is *played* in an ephemeral vCluster, but *formed* and *rated*
centrally.

## Correctness

Stated deliberately weaker than it is tempting to state it:

> At-most-once assignment per player and at-most-one match per ticket are
> **absolute**, under arbitrary concurrency. Agones allocation is
> **at-least-once with adopt-dedup**: duplicates are rare and bounded,
> not impossible — a reaper cleans them up.

Everything that must be correct happens inside Valkey Lua scripts, which
Valkey serialises. The JVM process holds no correctness-bearing state,
which is why scaling from one replica to many is configuration rather
than a rewrite. `GameServerAllocation` is a non-idempotent create with no
idempotency key, so allocation is fenced by a lease plus an
adopt-by-label pre-GET — that narrows the duplicate window, it does not
close it, and the design says so out loud.

## Ratings: Weng-Lin, not TrueSkill

TrueSkill is the right family (N teams, free-for-all, mu/sigma) but is
patented until 2029-04-09 (WO2007094909A1) — not a risk worth taking for
a commercial platform. Weng-Lin (OpenSkill) is the same class of model,
licence-free and closed-form.

We use `io.github.toveri:openskill` (MIT) with the **Plackett-Luce**
model, because it handles multi-team and free-for-all placements natively
— which is the shape Grounds' minigames actually have.

`RatingGoldenVectorTest` pins the library to openskill.py 6.2.0 to ten
decimal places, across 1v1, 4-way FFA, a 2v2 with mixed certainties, and
a tie. If that test ever goes red after a dependency bump, the library
has drifted from the reference: pin the old version, do not re-baseline
the numbers — that would silently move every player's rating.

## Build status

Phase 1 of the design. Live today:

- the `match` gRPC contract and the Quarkus scaffold
- the Flyway schema (`player_rating`, `match_record`, `rating_update`)
- `GetRating`, which answers with seeded defaults for an unrated player
- the rating math, golden-vector-pinned

The queue RPCs (`Enqueue`, `CancelTicket`, `GetTicket`, `GetQueueStats`,
`UpsertQueue`) answer `UNIMPLEMENTED` until the Valkey spine lands in
phase 2 — deliberately, rather than returning a plausible-looking empty
queue a caller would read as "nobody is waiting".

## Local dev

```bash
./gradlew quarkusDev            # needs github.user/github.token gradle properties
./gradlew test                  # golden vectors, no containers needed
```

Auth is on by default and validates the caller's projected
ServiceAccount token against the k8s JWKS. Locally there is no such
token — set `GROUNDS_AUTH_ENABLED=false`.
