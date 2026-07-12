-- claim(matchId, modeId, nowMs, ttl, teamSize, ticketId...) -> 1 | 0
--
-- Commits a proposed match. This is the ONLY place a ticket becomes MATCHED,
-- and it is the reason the matchmaker needs no leader election: Valkey runs
-- this script atomically, so two matchers racing on the same tickets cannot
-- both win. The loser sees a ticket that is no longer QUEUED and returns 0.
--
-- Verify-all-then-mutate-all. A partial claim would strand tickets in a match
-- that never forms.
--
-- KEYS[1] = mm:q:{mode}:rating
-- KEYS[2] = mm:q:{mode}:wait
-- KEYS[3] = mm:match:{matchId}
-- KEYS[4] = mm:alloc                  the allocation stream
--
-- ARGV[1] = matchId
-- ARGV[2] = modeId
-- ARGV[3] = nowMs
-- ARGV[4] = ttlSeconds (match hash)
-- ARGV[5] = teamSize   (teams are the ticket list chunked by this)
-- ARGV[6..] = ticketIds, in team order

local ratingZ  = KEYS[1]
local waitZ    = KEYS[2]
local matchKey = KEYS[3]
local allocS   = KEYS[4]

local matchId  = ARGV[1]
local modeId   = ARGV[2]
local nowMs    = ARGV[3]
local ttl      = tonumber(ARGV[4])
local teamSize = tonumber(ARGV[5])

local ticketIds = {}
for i = 6, #ARGV do
  ticketIds[#ticketIds + 1] = ARGV[i]
end

if #ticketIds == 0 then
  return 0
end

-- Verify every ticket is still claimable. The proposal was computed from a
-- snapshot that may already be stale: a player may have cancelled, or another
-- matcher may have taken them.
for i = 1, #ticketIds do
  local id = ticketIds[i]
  local state = redis.call('HGET', 'mm:ticket:' .. id, 'state')
  if state ~= 'QUEUED' then
    return 0
  end
  -- Also require it to still be in the queue's index. A ticket can be QUEUED
  -- in its hash but already pulled from the ZSETs by a concurrent claim that
  -- has not finished; treating it as claimable would double-book it.
  if not redis.call('ZSCORE', ratingZ, id) then
    return 0
  end
end

-- Everything checks out. Commit.
for i = 1, #ticketIds do
  local id = ticketIds[i]
  redis.call('ZREM', ratingZ, id)
  redis.call('ZREM', waitZ, id)
  redis.call('HSET', 'mm:ticket:' .. id, 'state', 'MATCHED', 'matchId', matchId)
end

local teams = {}
for i = 1, #ticketIds do
  local team = math.floor((i - 1) / teamSize)
  teams[team] = (teams[team] and (teams[team] .. ',') or '') .. ticketIds[i]
end
local teamsEncoded = ''
local t = 0
while teams[t] do
  teamsEncoded = teamsEncoded .. (t > 0 and ';' or '') .. teams[t]
  t = t + 1
end

redis.call('HSET', matchKey,
  'matchId',  matchId,
  'modeId',   modeId,
  'tickets',  table.concat(ticketIds, ','),
  'teams',    teamsEncoded,
  'state',    'FORMED',
  'attempts', '0',
  'createdAt', nowMs)
redis.call('EXPIRE', matchKey, ttl)

-- The durable pending index exists from the instant the match does. This XADD
-- is inside the same script as the commit on purpose: if it were a separate
-- call, a crash in between would leave a match that nothing ever allocates.
-- Recovery is then not a special case — it is the consumer group replaying
-- what was never acknowledged.
redis.call('XADD', allocS, '*', 'matchId', matchId, 'modeId', modeId)

return 1
