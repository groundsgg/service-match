-- fail_cancel(matchId) -> number of tickets cancelled
--
-- A match whose mode no longer exists. fail_requeue would put the players back
-- into a queue nobody matches any more, still holding their guards, so they
-- could not queue for anything else until the ticket TTL ran out. Cancel
-- instead: the player's client sees CANCELLED and asks them to queue again.
--
-- KEYS[1] = mm:match:{matchId}
-- KEYS[2] = mm:matches:live
-- KEYS[3] = mm:matches:preparing

local matchKey = KEYS[1]
local liveSet  = KEYS[2]

local state = redis.call('HGET', matchKey, 'state')
-- An assigned match must never be unwound: its players may already be on the
-- server.
if not state or state == 'ASSIGNED' then
  return 0
end

local cancelled = 0
local ticketsCsv = redis.call('HGET', matchKey, 'tickets')
if ticketsCsv then
  for id in string.gmatch(ticketsCsv, '([^,]+)') do
    local ticketKey = 'mm:ticket:' .. id
    local tState = redis.call('HGET', ticketKey, 'state')
    if (tState == 'MATCHED' or tState == 'PREPARING')
        and redis.call('HGET', ticketKey, 'matchId') == ARGV[1] then
      redis.call('HSET', ticketKey, 'state', 'CANCELLED')
      redis.call('HDEL', ticketKey, 'matchId')
      local playerId = redis.call('HGET', ticketKey, 'playerId')
      if playerId then
        -- Only if the guard still points at this ticket — see cancel.lua.
        local guardKey = 'mm:player:' .. playerId
        if redis.call('GET', guardKey) == id then
          redis.call('DEL', guardKey)
        end
      end
      cancelled = cancelled + 1
    end
  end
end

redis.call('HSET', matchKey, 'state', 'FAILED')
redis.call('SREM', liveSet, ARGV[1])
redis.call('ZREM', KEYS[3], ARGV[1])

return cancelled
