-- upsert_mode(modeId, configFields, metaJson, leaseId, leaseExpiryMs) -> 1 (created) | 0 (replaced)
--
-- Registers a mode, its lobby metadata and, when the caller holds one, its
-- lease — in one step. Split across separate writes, an expiry sweep on another
-- replica could run between them and remove a mode that was being renewed at
-- that very moment: a leased row with no lease yet reads exactly like a dead one.
--
-- The `leased` flag (field 12 of the encoded row) is decided here, not by the
-- caller, because it depends on what is already stored: a registration with a
-- lease makes the mode leased, and one without leaves the flag as it was. A
-- mode that has ever been leased stays leased until it is removed, so forge's
-- deploy-time push cannot pin a mode whose servers have all gone.
--
-- KEYS[1] = mm:modes
-- KEYS[2] = mm:mode:{id}:meta
-- KEYS[3] = mm:mode:{id}:leases

local modesKey  = KEYS[1]
local metaKey   = KEYS[2]
local leasesKey = KEYS[3]

local modeId   = ARGV[1]
local fields   = ARGV[2]   -- encoded fields 0..11, without the leased flag
local metaJson = ARGV[3]
local leaseId  = ARGV[4]   -- '' when the registration holds no lease
local expiryMs = ARGV[5]

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

local existing = redis.call('HGET', modesKey, modeId)

local leased = '0'
if leaseId ~= '' then
  leased = '1'
  redis.call('ZADD', leasesKey, expiryMs, leaseId)
elseif existing and isLeased(existing) then
  leased = '1'
end

redis.call('HSET', modesKey, modeId, fields .. '|' .. leased)
redis.call('SET', metaKey, metaJson)

if existing then
  return 0
end
return 1
