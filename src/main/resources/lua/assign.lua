-- assign(gsName, address, port, matchId, requiredState) -> 1 (won) | 0 (lost)
--
-- Publishes the GameServer a match was allocated. Compare-and-set: only the
-- first assigner wins. Without this, two allocators recovering the same match
-- could each push a different server and the players would be split across
-- them — visibly broken.
--
-- The loser is expected to delete the GameServer it allocated (or leave it to
-- the reaper).
--
-- KEYS[1] = mm:match:{matchId}
-- KEYS[2] = mm:matches:live
-- KEYS[3] = mm:matches:preparing
-- ARGV[1] = gsName, ARGV[2] = address, ARGV[3] = port, ARGV[4] = matchId
-- ARGV[5] = the state the match must be in, '' for any but ASSIGNED/FAILED.
--           The ready call passes PREPARING, so a match the watchdog or a
--           leaver failed a moment earlier is not routed after all.
--
-- This is also where the player guards are released: the ticket is terminal,
-- so the player may queue again. Releasing them any earlier — at MATCHED, say
-- — would open a window where a player could hold two live tickets while their
-- first match was still being allocated.

local matchKey = KEYS[1]
local liveSet  = KEYS[2]

local gsName  = ARGV[1]
local address = ARGV[2]
local port    = ARGV[3]
local matchId = ARGV[4]
local requiredState = ARGV[5] or ''

local state = redis.call('HGET', matchKey, 'state')
if not state then
  return 0
end
if state == 'ASSIGNED' or state == 'FAILED' then
  return 0
end
if requiredState ~= '' and state ~= requiredState then
  return 0
end

redis.call('HSET', matchKey,
  'state',   'ASSIGNED',
  'gsName',  gsName,
  'address', address,
  'port',    port)

-- A server has it now, so the watchdog stops looking. An ASSIGNED match that is
-- old is just a long game, not a stalled one.
redis.call('SREM', liveSet, matchId)
redis.call('ZREM', KEYS[3], matchId)

local ticketsCsv = redis.call('HGET', matchKey, 'tickets')
if ticketsCsv then
  for id in string.gmatch(ticketsCsv, '([^,]+)') do
    local ticketKey = 'mm:ticket:' .. id
    -- Only tickets still bound to this match. A player who left while the
    -- round was being prepared is CANCELLED and stays that way: no route, no
    -- running match to be offered back.
    local tState = redis.call('HGET', ticketKey, 'state')
    if (tState == 'MATCHED' or tState == 'PREPARING')
        and redis.call('HGET', ticketKey, 'matchId') == matchId then
      redis.call('HSET', ticketKey,
        'state',   'ASSIGNED',
        'gsName',  gsName,
        'address', address,
        'port',    port)

      local playerId = redis.call('HGET', ticketKey, 'playerId')
      if playerId then
        -- Release the guard only if it still points at *this* ticket. The player
        -- may have cancelled and re-queued; clobbering that would let them hold
        -- two live tickets.
        local guardKey = 'mm:player:' .. playerId
        if redis.call('GET', guardKey) == id then
          redis.call('DEL', guardKey)
        end

        -- Where the player is playing, for a proxy that never saw this assignment:
        -- a player who drops and logs back in through another proxy is offered
        -- their match from this. It lives no longer than the match hash; a match
        -- hash without an expiry gets no entry rather than an immortal one.
        local matchTtl = redis.call('TTL', matchKey)
        if matchTtl > 0 then
          redis.call('SET', 'mm:active:' .. playerId, id, 'EX', matchTtl)
        end
      end
    end
  end
end

return 1
