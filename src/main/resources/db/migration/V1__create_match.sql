-- Durable half of the matchmaker.
--
-- The split that drives this schema: tickets, queues and in-flight
-- matches live in Valkey and are losable (a player simply re-queues).
-- Ratings are not — MMR only means anything if it survives the session
-- that produced it. So ratings live here, in the central services
-- Postgres, next to the durable player identity (Keycloak UUID).

-- A player's Weng-Lin (OpenSkill) rating for one mode.
-- Global and player-scoped: there is no per-region ladder, because
-- region is a queue/allocation dimension, not a skill dimension.
CREATE TABLE player_rating (
  player_id  uuid        NOT NULL,
  mode_id    text        NOT NULL,
  mu         float8      NOT NULL DEFAULT 25.0,
  sigma      float8      NOT NULL DEFAULT 8.3333333333333339,
  games_played int       NOT NULL DEFAULT 0,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (player_id, mode_id)
);

-- One row per match the matchmaker formed. Written at claim time —
-- i.e. the record exists before the GameServer is even allocated, which
-- is what lets the result consumer reject results for matches we never
-- made.
CREATE TABLE match_record (
  match_id    uuid        PRIMARY KEY,
  project_ref text        NOT NULL,
  mode_id     text        NOT NULL,
  -- The region the match was played in. Stored so a later regional
  -- rollout needs no backfill.
  region      text        NOT NULL,
  -- Whether this match moves ratings. Decided from the mode config at
  -- match-formation time, never from the result event: forge only sets
  -- ranked=true for release-target deployments, so ephemeral test
  -- workspaces cannot pollute the ladder.
  ranked      bool        NOT NULL,
  created_at  timestamptz NOT NULL,
  ended_at    timestamptz,
  termination_reason text
);

-- The rating delta a match applied to a player, with before/after.
--
-- The primary key is the idempotency latch: results arrive over a
-- JetStream durable, which is at-least-once, so a redelivered MatchEnded
-- must be a no-op. INSERT ... ON CONFLICT DO NOTHING on this PK is what
-- makes replay safe — see the write path in the design doc §4.
CREATE TABLE rating_update (
  match_id    uuid   NOT NULL REFERENCES match_record(match_id),
  player_id   uuid   NOT NULL,
  -- 1 = won. Weng-Lin needs a ranking, not a win/loss bit: without a
  -- placement the update degrades to winner-vs-rest.
  placement   int,
  mu_before   float8,
  sigma_before float8,
  mu_after    float8,
  sigma_after float8,
  PRIMARY KEY (match_id, player_id)
);

-- Ladder reads ("top N by conservative rating in this mode") and the
-- portal's per-mode listing.
CREATE INDEX player_rating_mode_mu_idx ON player_rating (mode_id, mu DESC);
