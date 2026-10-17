-- The first 8 hex characters of match_id are the match's short id: what the
-- game shows on its scoreboard and what prism and the map page look a match up
-- by. 32 random bits collide eventually, so the matcher re-rolls an id whose
-- short id is taken before claiming it. This index is what it asks.
CREATE INDEX match_record_short_id_idx ON match_record (left(match_id::text, 8));

-- The backstop if two replicas ever roll the same short id at once. Only for
-- matches formed after this migration: earlier ones were never checked and
-- already share short ids (stage had one pair), and their ids are referenced
-- from analytics, so they cannot be re-rolled. An index predicate cannot call
-- now(), so the cutoff is written in as a literal.
DO $$
BEGIN
  EXECUTE format(
    'CREATE UNIQUE INDEX match_record_short_id_unique_idx '
    'ON match_record (left(match_id::text, 8)) WHERE created_at >= %L::timestamptz',
    now());
END
$$;
