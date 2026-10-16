-- expire_mode(modeId, nowMs, action, leaseId) -> tickets cancelled | -1 (mode kept)
--
-- Removes a mode whose last lease is gone. One script, because the removal
-- spans the mode row, its metadata, its lease set, both queue indexes and every
-- ticket still waiting in it; done step by step, a registration renewing the
-- mode in between could be half-deleted, and a player enqueued in between
-- would be stranded in a queue nobody matches.
--
-- Any replica may run it, as often as it likes: once the mode is gone its row
-- is missing and this is a no-op.
--
-- action:
--   'expire'  drop expired leases; remove the mode if it is leased and none are left
--   'release' the same, after dropping ARGV[4]'s lease first (graceful shutdown)
--   'delete'  remove the mode whatever it is, permanent or leased (admin)
--
-- Waiting tickets are CANCELLED rather than left to their TTL: a ticket that
-- stays QUEUED keeps its player's guard, and the player could not queue for
-- anything else for hours. Tickets already in a match are left to it.
--
-- KEYS[1] = mm:modes
-- KEYS[2] = mm:mode:{id}:meta
-- KEYS[3] = mm:mode:{id}:leases
-- KEYS[4] = mm:q:{id}:rating
-- KEYS[5] = mm:q:{id}:wait

local modesKey  = KEYS[1]
local metaKey   = KEYS[2]
local leasesKey = KEYS[3]
local ratingZ   = KEYS[4]
local waitZ     = KEYS[5]

local modeId  = ARGV[1]
local nowMs   = ARGV[2]
local action  = ARGV[3]
local leaseId = ARGV[4]

local function isLeased(row)
  local i = 0
  for f in string.gmatch(row .. '|', '([^|]*)|') do
    if i == 12 then
      return f == '1'
    end
    i = i + 1
  end
  return false
end

if action == 'release' and leaseId and leaseId ~= '' then
  redis.call('ZREM', leasesKey, leaseId)
end
redis.call('ZREMRANGEBYSCORE', leasesKey, '-inf', nowMs)

local row = redis.call('HGET', modesKey, modeId)
if not row then
  return -1
end

if action ~= 'delete' then
  if not isLeased(row) then
    return -1
  end
  if redis.call('ZCARD', leasesKey) > 0 then
    return -1
  end
end

local cancelled = 0
local waiting = redis.call('ZRANGE', waitZ, 0, -1)
for i = 1, #waiting do
  local id = waiting[i]
  local ticketKey = 'mm:ticket:' .. id
  if redis.call('HGET', ticketKey, 'state') == 'QUEUED' then
    redis.call('HSET', ticketKey, 'state', 'CANCELLED')
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

redis.call('DEL', ratingZ, waitZ, metaKey, leasesKey)
redis.call('HDEL', modesKey, modeId)

return cancelled
