-- fail_requeue(matchId, requiredState) -> number of tickets put back | -1 (not in requiredState)
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
-- KEYS[4] = mm:matches:live
-- KEYS[5] = mm:matches:preparing
--
-- ARGV[1] = matchId
-- ARGV[2] = the state the match must be in, '' for any but ASSIGNED. The
--           prepare timeout, a failed ready and a leaver pass PREPARING: of two
--           replicas failing the same match, only the first gets a count back,
--           so the server is told to cancel and the outcome is counted once.

local matchKey = KEYS[1]
local ratingZ  = KEYS[2]
local waitZ    = KEYS[3]
local liveSet  = KEYS[4]
local requiredState = ARGV[2] or ''

local state = redis.call('HGET', matchKey, 'state')
if requiredState ~= '' and state ~= requiredState then
  return -1
end
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
  -- Only tickets still bound to this match, whether the server was still being
  -- found (MATCHED) or was preparing the round (PREPARING). A player who
  -- cancelled in the meantime stays cancelled.
  if (tState == 'MATCHED' or tState == 'PREPARING') and redis.call('HGET', ticketKey, 'matchId') == ARGV[1] then
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
redis.call('SREM', liveSet, ARGV[1])
redis.call('ZREM', KEYS[5], ARGV[1])

return requeued
