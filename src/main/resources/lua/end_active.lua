-- end_active(matchId) -> number of entries removed
--
-- A match ended: its players are no longer playing it, so no proxy may offer
-- them a rejoin. Only entries that still point at a ticket of *this* match are
-- removed — a player who left early may already be playing their next match,
-- and that entry is theirs to keep.
--
-- KEYS    = mm:active:{playerId} for every player of the match
-- ARGV[1] = matchId

local matchId = ARGV[1]
local removed = 0

for _, activeKey in ipairs(KEYS) do
  local ticketId = redis.call('GET', activeKey)
  if ticketId and redis.call('HGET', 'mm:ticket:' .. ticketId, 'matchId') == matchId then
    redis.call('DEL', activeKey)
    removed = removed + 1
  end
end

return removed
