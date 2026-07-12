-- fail_requeue(matchId) -> number of tickets put back
--
-- Allocation failed for a transient reason. Put the tickets back so the players
-- get another shot instead of being told to re-queue by hand.
--
-- The subtlety, and the reason this is a script rather than a loop in Kotlin:
-- the tickets are re-added with their ORIGINAL enqueuedAt. Waiting time earns a
-- wider matching band, and a failed allocation is not the player's fault — if
-- we reset the clock, the unluckiest players would be the hardest to match.
--
-- Guards are deliberately NOT released: these players are going straight back
-- into the queue, and they still hold exactly one live ticket.
--
-- KEYS[1] = mm:match:{matchId}
-- KEYS[2] = mm:q:{mode}:rating
-- KEYS[3] = mm:q:{mode}:wait

local matchKey = KEYS[1]
local ratingZ  = KEYS[2]
local waitZ    = KEYS[3]

local state = redis.call('HGET', matchKey, 'state')
-- An assigned match must never be unwound: its players may already be on the
-- server.
if not state or state == 'ASSIGNED' then
  return 0
end

local ticketsCsv = redis.call('HGET', matchKey, 'tickets')
if not ticketsCsv then
  return 0
end

local requeued = 0
for id in string.gmatch(ticketsCsv, '([^,]+)') do
  local ticketKey = 'mm:ticket:' .. id
  local tState = redis.call('HGET', ticketKey, 'state')
  -- Only tickets still bound to this match. A player who cancelled in the
  -- meantime stays cancelled.
  if tState == 'MATCHED' and redis.call('HGET', ticketKey, 'matchId') == ARGV[1] then
    local mu         = tonumber(redis.call('HGET', ticketKey, 'mu'))
    local enqueuedAt = tonumber(redis.call('HGET', ticketKey, 'enqueuedAt'))
    redis.call('HSET', ticketKey, 'state', 'QUEUED')
    redis.call('HDEL', ticketKey, 'matchId')
    redis.call('ZADD', ratingZ, mu, id)
    redis.call('ZADD', waitZ, enqueuedAt, id)   -- original score, not now()
    requeued = requeued + 1
  end
end

redis.call('HSET', matchKey, 'state', 'FAILED')

return requeued
