-- prepare(gsName, address, port, matchId, nowMs) -> 1 (preparing) | 0 (not FORMED any more)
--
-- A server accepted the match and said it will report when the round is ready
-- (StartMatchReply.signals_ready). The players are not routed yet: a round
-- that is still loading its map would greet them with a void. So the match
-- moves to PREPARING and its tickets with it, and assign only runs once the
-- server reports ready (POST /v1/match/matches/{id}/ready).
--
-- The server is recorded on the MATCH hash only. A ticket carries a server
-- exactly when it is ASSIGNED — that is what every proxy routes on — so
-- writing it onto a PREPARING ticket would send its player early.
--
-- The match stays on mm:matches:live, and joins mm:matches:preparing scored by
-- preparingAt: the watchdog's prepare-timeout worklist, and the PREPARING
-- gauge. A server that never reports ready is failed from there, and its
-- players go back on the queue with the wait they had earned.
--
-- KEYS[1] = mm:match:{matchId}
-- KEYS[2] = mm:matches:preparing
-- ARGV[1] = gsName, ARGV[2] = address, ARGV[3] = port, ARGV[4] = matchId, ARGV[5] = nowMs

local matchKey     = KEYS[1]
local preparingSet = KEYS[2]

local gsName  = ARGV[1]
local address = ARGV[2]
local port    = ARGV[3]
local matchId = ARGV[4]
local nowMs   = ARGV[5]

-- Only a FORMED match can start preparing. Anything else was decided while the
-- push was in flight: the watchdog failed it, or another worker got further.
if redis.call('HGET', matchKey, 'state') ~= 'FORMED' then
  return 0
end

redis.call('HSET', matchKey,
  'state',       'PREPARING',
  'gsName',      gsName,
  'address',     address,
  'port',        port,
  'preparingAt', nowMs)
redis.call('ZADD', preparingSet, nowMs, matchId)

local ticketsCsv = redis.call('HGET', matchKey, 'tickets')
if ticketsCsv then
  for id in string.gmatch(ticketsCsv, '([^,]+)') do
    local ticketKey = 'mm:ticket:' .. id
    -- Only tickets still bound to this match; the guards stay where they are,
    -- because a PREPARING ticket is still the player's one live ticket.
    if redis.call('HGET', ticketKey, 'state') == 'MATCHED'
        and redis.call('HGET', ticketKey, 'matchId') == matchId then
      redis.call('HSET', ticketKey, 'state', 'PREPARING')
    end
  end
end

return 1
