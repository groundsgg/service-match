-- enqueue(ticketId, playerId, modeId, mu, sigma, nowMs, ttlSeconds, provisional, location) -> 1 | 0
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
-- '1' when the rating store could not be read and mu/sigma are the defaults
-- rather than this player's. Carried on the ticket so the match it forms can
-- be recorded unranked — see Matcher.recordDurably.
local provisional = ARGV[8]
-- Where this player is connected. A QoS hint, never a queue dimension: the
-- queue spans every region on the continent so that two players in different
-- ones can meet at all. The claim reads these to decide where the match is
-- hosted.
local location = ARGV[9]

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
  'provisional', provisional,
  'location',    location,
  'state',       'QUEUED')
redis.call('EXPIRE', ticketKey, ttl)

redis.call('ZADD', ratingZ, mu, ticketId)
redis.call('ZADD', waitZ, nowMs, ticketId)

return 1
