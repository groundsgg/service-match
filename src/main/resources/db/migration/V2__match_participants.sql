-- Record who is in a match, at the moment the match is formed.
--
-- Without this, the only source of "who played" would be the result the
-- gamemode reports — and then a gamemode could name any player it liked and
-- move their rating. Writing the roster at claim time means the result path can
-- only ever *place* players who were actually in the match; anyone else is
-- rejected.
ALTER TABLE match_record ADD COLUMN player_ids uuid[] NOT NULL DEFAULT '{}';

-- project_ref and region were a central-matchmaker concern: one service formed
-- matches for many projects across many regions, so a record had to say which.
-- service-match now runs inside the project's own vCluster, so both are implied
-- by *where the row is* — the project has exactly one matchmaker and one
-- database. Keeping them would mean inventing a value to satisfy NOT NULL.
ALTER TABLE match_record ALTER COLUMN project_ref DROP NOT NULL;
ALTER TABLE match_record ALTER COLUMN region DROP NOT NULL;

-- The result path asks "has this match already ended?" on every report, because
-- a retry must be a no-op.
CREATE INDEX match_record_ended_idx ON match_record (ended_at) WHERE ended_at IS NULL;
