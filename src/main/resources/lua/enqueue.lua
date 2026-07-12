-- enqueue(ticketId, playerId, modeId, mu, sigma, nowMs, ttlSeconds) -> 1 | 0
--
-- Puts a player in a queue. Returns 0 if they already hold a live ticket.
--
-- KEYS[1] = mm:player:{playerId}      one-live-ticket guard
-- KEYS[2] = mm:ticket:{ticketId}      the ticket hash
-- KEYS[3] = mm:q:{mode}:rating        ZSET scored by mu     (band query)
-- KEYS[4] = mm:q:{mode}:wait          ZSET scored by enqueuedAt (anchor pick)
--
-- The guard is the whole point: one live ticket per player, full stop. Without
-- it a player could sit in two queues and be committed to two matches at once.
--
-- Self-heal: if the guard points at a ticket that is gone or already terminal,
-- it is stale (a crash between the guard write and the ticket write, or a TTL
-- expiry) and we overwrite it. Refusing here instead would lock the player out
-- until the guard's own TTL ran down.

local guardKey  = KEYS[1]
local ticketKey = KEYS[2]
local ratingZ   = KEYS[3]
local waitZ     = KEYS[4]

local ticketId  = ARGV[1]
local playerId  = ARGV[2]
local modeId    = ARGV[3]
local mu        = tonumber(ARGV[4])
local sigma     = tonumber(ARGV[5])
local nowMs     = tonumber(ARGV[6])
local ttl       = tonumber(ARGV[7])

local existing = redis.call('GET', guardKey)
if existing then
  local state = redis.call('HGET', 'mm:ticket:' .. existing, 'state')
  -- A live ticket is one that is still QUEUED or already committed to a match.
  -- Anything else (missing, CANCELLED, FAILED, ASSIGNED) is finished with.
  if state == 'QUEUED' or state == 'MATCHED' then
    return 0
  end
end

redis.call('SET', guardKey, ticketId)

redis.call('HSET', ticketKey,
  'ticketId',    ticketId,
  'playerId',    playerId,
  'modeId',      modeId,
  'mu',          tostring(mu),
  'sigma',       tostring(sigma),
  'enqueuedAt',  tostring(nowMs),
  'state',       'QUEUED')
redis.call('EXPIRE', ticketKey, ttl)

redis.call('ZADD', ratingZ, mu, ticketId)
redis.call('ZADD', waitZ, nowMs, ticketId)

return 1
