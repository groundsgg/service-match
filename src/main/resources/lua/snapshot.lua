-- snapshot(limit) -> [ [field, value, ...], ... ]  one entry per live ticket
--
-- The matcher's read of the queue, in ONE round trip.
--
-- It used to be a ZRANGE followed by an HGETALL per id, which is fine when
-- Valkey is a Service in the same namespace and indefensible once it is not:
-- the queue spans regions now, so the matcher in the region that does not hold
-- the queue would pay a round trip per waiting ticket, every tick. At 200
-- tickets and a 20ms hop that is four seconds of round trips for a loop that
-- runs every two.
--
-- Reads only. It takes no lock and makes no promise that the result is still
-- true when it returns — the claim script re-verifies every ticket, which is
-- where the actual guarantee lives.
--
-- KEYS[1] = mm:q:{mode}:wait     the by-wait index, oldest first
--
-- ARGV[1] = limit                how many of the longest-waiting to return

local waitZ = KEYS[1]
local limit = tonumber(ARGV[1])

local ids = redis.call('ZRANGE', waitZ, 0, limit - 1)

local out = {}
for i = 1, #ids do
  local fields = redis.call('HGETALL', 'mm:ticket:' .. ids[i])
  -- A ticket can expire between the index read and this call. Skipping it is
  -- correct: the index is allowed to lag, and a ticket that is gone is not a
  -- candidate. The caller sees a shorter list, never a hole.
  if #fields > 0 then
    out[#out + 1] = fields
  end
end

return out
