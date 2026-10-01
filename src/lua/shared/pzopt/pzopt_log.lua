-- pzopt: the routine [pzopt] lines of the Lua side (menu items added, options tab built) print only while the
-- consoleLog setting (Options > Profiler > Console log, pzopt.Config) is "all", like pzopt.Log's info lines;
-- failures keep their plain print. Without the PerformanceSettings override (loose Lua alone) it prints.
-- Shared, so it loads before the client files that call it.
function PzoptLogInfo(msg)
    local ok, level = pcall(function() return getPerformance():getPzoptOption("consoleLog") end)
    if ok and level ~= nil and level ~= "" and level ~= "all" then return end
    print(msg)
end
