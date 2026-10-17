-- The first 8 hex characters of match_id are the match's short id: what the
-- game shows on its scoreboard and what prism and the map page look a match up
-- by. 32 random bits collide eventually, so the matcher re-rolls an id whose
-- short id is taken before claiming it. This index is what it asks, and the
-- backstop if two replicas ever roll the same short id at once.
CREATE UNIQUE INDEX match_record_short_id_idx ON match_record (left(match_id::text, 8));
