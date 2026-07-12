-- assign(matchId, gsName, address, port) -> 1 (won) | 0 (lost)
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
-- ARGV[1] = gsName, ARGV[2] = address, ARGV[3] = port
--
-- This is also where the player guards are released: the ticket is terminal,
-- so the player may queue again. Releasing them any earlier — at MATCHED, say
-- — would open a window where a player could hold two live tickets while their
-- first match was still being allocated.

local matchKey = KEYS[1]

local gsName  = ARGV[1]
local address = ARGV[2]
local port    = ARGV[3]

local state = redis.call('HGET', matchKey, 'state')
if not state then
  return 0
end
if state == 'ASSIGNED' or state == 'FAILED' then
  return 0
end

redis.call('HSET', matchKey,
  'state',   'ASSIGNED',
  'gsName',  gsName,
  'address', address,
  'port',    port)

local ticketsCsv = redis.call('HGET', matchKey, 'tickets')
if ticketsCsv then
  for id in string.gmatch(ticketsCsv, '([^,]+)') do
    local ticketKey = 'mm:ticket:' .. id
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
    end
  end
end

return 1
