-- cancel(ticketId, playerId) -> 1 (cancelled) | 0 (too late / not theirs)
--
-- A player withdraws. Only works while the ticket is still QUEUED: once a match
-- has formed the ticket is committed, and pulling it out would leave the other
-- players in a match that can never fill.
--
-- KEYS[1] = mm:ticket:{ticketId}
-- KEYS[2] = mm:q:{mode}:rating
-- KEYS[3] = mm:q:{mode}:wait
-- KEYS[4] = mm:player:{playerId}

local ticketKey = KEYS[1]
local ratingZ   = KEYS[2]
local waitZ     = KEYS[3]
local guardKey  = KEYS[4]

local ticketId = ARGV[1]
local playerId = ARGV[2]

local state = redis.call('HGET', ticketKey, 'state')
if state ~= 'QUEUED' then
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

return 1
