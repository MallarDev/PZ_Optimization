-- pzopt harness: Mods screen shots (2026-10-07, the install walkthrough animations, harness/install-anim.py).
--
-- mods_shot=1            stay on the main menu (pzopt_harness.lua does not press Continue), open MODS as the menu item does
--                        (without the first-time nag panel), type mods_search into the search box, select mods_id's row
--                        (its info panel shows), screenshot Screenshots/pzopt-mods-1.png, tick it, screenshot
--                        pzopt-mods-2.png, untick it again and quit without Accept. Logs `[pzopt-mods]` lines.
--   mods_id=<id>         the mod to select and tick (PZ_Optimization)
--   mods_search=<text>   typed into the search box first (default: none)
-- Each screenshot also writes Zomboid/Lua/pzopt-mods-<n>.txt (getFileWriter refuses other extensions such as .now: nil, nothing written) and holds 2.5 s: the game's own screenshot reads
-- Display.getDisplayMode()'s size, which under desktop scaling is the logical one (4096x1728 of a 5120x2160 framebuffer,
-- the top cut), so a wrapper takes a desktop capture (spectacle) when the marker appears.
-- The mod has to be in the list: a direct launch scans Zomboid/mods only, so copy the Workshop item's mod folder there
-- for the job (and remove it after). run.sh restores mods/default.txt anyway.

local FLAG_FILE = "pzopt-harness.txt"

local function readFlags()
    local reader = getFileReader(FLAG_FILE, false)
    if not reader then return nil end
    local flags = {}
    while true do
        local line = reader:readLine()
        if line == nil then break end
        local k, v = string.match(line, "^%s*([%w_]+)%s*=%s*(.-)%s*$")
        if k then flags[k] = v end
    end
    reader:close()
    return flags
end

local function log(s) print("[pzopt-mods] " .. s) end

local rig = nil

local function marker(n)
    local w = getFileWriter("pzopt-mods-" .. n .. ".txt", true, false)
    if w then w:write(tostring(getTimestampMs())); w:close() end
end

local function findRow(list, id)
    for i, v in ipairs(list.items) do
        local it = v.item
        if it and it.modInfo and it.modInfo:getId() == id then return i, it end
    end
    return nil
end

local function tick()
    local ms = MainScreen.instance
    if not ms or (ms.delay and ms.delay > 0) or not ms.modsOption or not ms.modSelect then return end
    local now = getTimestampMs()
    local sel = ms.modSelect
    if rig.step == 0 then
        sel.model.isNewGame = false
        sel:setVisible(true)
        sel.model:reloadMods()
        ModSelector.instance.returnToUI = ms
        log("mods screen opened")
        rig.step, rig.at = 1, now + 1500
    elseif now < rig.at then
        return
    elseif rig.step == 1 then
        local panel = sel.modListPanel
        if rig.search then
            panel.searchEntry:setText(rig.search)
            panel:updateView()
        end
        local i, it = findRow(panel.modList, rig.id)
        if not i then
            log("mod " .. rig.id .. " NOT in the list (" .. #panel.modList.items .. " rows)")
            rig = false
            getCore():quit()
            return
        end
        panel.modList.selected = i
        sel.modInfoPanel:setVisible(true)
        sel.modInfoPanel:updateView(it.modInfo)
        rig.item = it
        log("selected row " .. i .. " of " .. #panel.modList.items .. ": " .. tostring(it.modInfo:getName()) .. " active=" .. tostring(it.isActive))
        rig.step, rig.at = 2, now + 1000
    elseif rig.step == 2 then
        getCore():TakeFullScreenshot("pzopt-mods-1.png")
        marker(1)
        log("screenshot 1 (not enabled)")
        rig.step, rig.at = 3, now + 2500
    elseif rig.step == 3 then
        sel.model:forceActivateMods(rig.item.modInfo, true)
        sel.modListPanel:updateView()
        log("ticked: active=" .. tostring(sel.model:isModActive(rig.id)))
        rig.step, rig.at = 4, now + 1000
    elseif rig.step == 4 then
        getCore():TakeFullScreenshot("pzopt-mods-2.png")
        marker(2)
        log("screenshot 2 (enabled)")
        rig.step, rig.at = 5, now + 2500
    elseif rig.step == 5 then
        sel.model:forceActivateMods(rig.item.modInfo, false)
        log("unticked, quitting without Accept")
        rig = false
        getCore():quit()
    end
end

Events.OnFETick.Add(function()
    if rig == false then return end
    if rig == nil then
        local flags = readFlags()
        if not flags or not flags.mods_shot or flags.mods_shot == "" then rig = false; return end
        rig = { step = 0, at = 0, id = (flags.mods_id and flags.mods_id ~= "") and flags.mods_id or "PZ_Optimization",
                search = (flags.mods_search and flags.mods_search ~= "") and flags.mods_search or nil }
    end
    local ok, err = pcall(tick)
    if not ok then log("rig error " .. tostring(err)); rig = false; getCore():quit() end
end)
