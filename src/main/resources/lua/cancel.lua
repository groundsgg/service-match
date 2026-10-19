-- cancel(ticketId, playerId, minPlayers) -> 0 (too late / not theirs) | 1 (cancelled) | 2 (cancelled, match failed)
--
-- A player withdraws. Works while the ticket is QUEUED, and while its match is
-- PREPARING — the server is loading the round and nobody has been sent yet.
-- Once a match has formed but has no server (MATCHED), the ticket is
-- committed: the allocation is already in flight with this player's slot.
--
-- Leaving a PREPARING match is decided here, in one step, because two players
-- leaving at once must not both conclude the match is still big enough:
--   * the leaver's ticket is CANCELLED and their guard released;
--   * if fewer than minPlayers of the match's tickets are still PREPARING, the
--     match fails and the rest go back on the queue with their original
--     enqueuedAt (fail_requeue's rules) — the answer is then 2, and the caller
--     tells the server to drop the round;
--   * otherwise the match carries on without the leaver, and the ready call
--     later routes only the tickets that are still PREPARING.
--
-- KEYS[1] = mm:ticket:{ticketId}
-- KEYS[2] = mm:q:{mode}:rating
-- KEYS[3] = mm:q:{mode}:wait
-- KEYS[4] = mm:player:{playerId}
-- KEYS[5] = mm:matches:live
-- KEYS[6] = mm:matches:preparing
-- ARGV[1] = ticketId, ARGV[2] = playerId
-- ARGV[3] = the fewest players the match may keep (the mode's minPlayers)

local ticketKey    = KEYS[1]
local ratingZ      = KEYS[2]
local waitZ        = KEYS[3]
local guardKey     = KEYS[4]
local liveSet      = KEYS[5]
local preparingSet = KEYS[6]

local ticketId   = ARGV[1]
local playerId   = ARGV[2]
local minPlayers = tonumber(ARGV[3]) or 1

local state = redis.call('HGET', ticketKey, 'state')
if state ~= 'QUEUED' and state ~= 'PREPARING' then
  return 0
end

-- A player can only cancel their own ticket.
if redis.call('HGET', ticketKey, 'playerId') ~= playerId then
  return 0
end

redis.call('ZREM', ratingZ, ticketId)
redis.call('ZREM', waitZ, ticketId)
redis.call('HSET', ticketKey, 'state', 'CANCELLED')

if redis.call('GET', guardKey) == ticketId then
  redis.call('DEL', guardKey)
end

if state == 'QUEUED' then
  return 1
end

local matchId = redis.call('HGET', ticketKey, 'matchId')
if not matchId then
  return 1
end
local matchKey = 'mm:match:' .. matchId
if redis.call('HGET', matchKey, 'state') ~= 'PREPARING' then
  return 1
end

local ticketsCsv = redis.call('HGET', matchKey, 'tickets') or ''
local remaining = {}
for id in string.gmatch(ticketsCsv, '([^,]+)') do
  local key = 'mm:ticket:' .. id
  if redis.call('HGET', key, 'state') == 'PREPARING'
      and redis.call('HGET', key, 'matchId') == matchId then
    remaining[#remaining + 1] = id
  end
end

if #remaining >= minPlayers then
  return 1
end

-- Too few left to play. Back on the queue with the wait they earned; their
-- guards stay, since they still hold exactly one live ticket.
for i = 1, #remaining do
  local id = remaining[i]
  local key = 'mm:ticket:' .. id
  local mu         = tonumber(redis.call('HGET', key, 'mu'))
  local enqueuedAt = tonumber(redis.call('HGET', key, 'enqueuedAt'))
  redis.call('HSET', key, 'state', 'QUEUED')
  redis.call('HDEL', key, 'matchId')
  redis.call('ZADD', ratingZ, mu, id)
  redis.call('ZADD', waitZ, enqueuedAt, id)
end

redis.call('HSET', matchKey, 'state', 'FAILED')
redis.call('SREM', liveSet, matchId)
redis.call('ZREM', preparingSet, matchId)

return 2
