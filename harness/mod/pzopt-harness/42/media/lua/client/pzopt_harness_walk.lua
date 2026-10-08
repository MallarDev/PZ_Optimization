-- pzopt harness walking (2026-10-08, pzopt.Nav): the player walks to a point with the game's own "walk to" timed action
-- (ISWalkToTimedActionF: the pathfinder's route around the furniture footprints, doors opened on the way, stairs taken),
-- never with movement keys and never teleported. Java calls PzoptWalkTo / PzoptWalkCancel / PzoptWalkStatus.

PzoptWalk = PzoptWalk or { seq = 0, state = "idle", since = 0 }

local function point(x, y, z)
    -- ISWalkToTimedActionF:start reads location:x() / y() / z() (a JOML Vector3f in the game's own callers)
    return { x = function() return x end, y = function() return y end, z = function() return z end }
end

function PzoptWalkTo(x, y, z)
    local player = getPlayer()
    if not player then
        PzoptWalk.state = "failed"
        return PzoptWalk.seq
    end
    ISTimedActionQueue.clear(player)
    PzoptWalk.seq = PzoptWalk.seq + 1
    local seq = PzoptWalk.seq
    PzoptWalk.state = "walking"
    PzoptWalk.since = getTimestampMs()
    local action = ISWalkToTimedActionF:new(player, point(x, y, z))
    local perform, stop = action.perform, action.stop
    action.perform = function(self)
        if PzoptWalk.seq == seq then PzoptWalk.state = "arrived" end
        perform(self)
    end
    action.stop = function(self)
        if PzoptWalk.seq == seq and PzoptWalk.state == "walking" then
            PzoptWalk.state = self.result == BehaviorResult.Failed and "failed" or "stopped"
        end
        stop(self)
    end
    ISTimedActionQueue.add(action)
    return seq
end

function PzoptWalkCancel()
    local player = getPlayer()
    PzoptWalk.seq = PzoptWalk.seq + 1
    PzoptWalk.state = "idle"
    if player then
        ISTimedActionQueue.clear(player)
        player:getPathFindBehavior2():cancel()
        player:setPath2(nil)
    end
end

-- "<seq> <state>"; a walk whose action left the queue without perform / stop (isValid false) reads "lost"
function PzoptWalkStatus()
    local state = PzoptWalk.state
    if state == "walking" then
        local player = getPlayer()
        local q = player and ISTimedActionQueue.getTimedActionQueue(player)
        if (not q or not q.queue or #q.queue == 0) and getTimestampMs() - PzoptWalk.since > 500 then
            state = "lost"
            PzoptWalk.state = state
        end
    end
    return tostring(PzoptWalk.seq) .. " " .. state
end
