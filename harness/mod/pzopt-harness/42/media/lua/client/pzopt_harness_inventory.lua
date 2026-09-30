-- pzopt harness: inventory / loot window rig (2026-09-30, "profile inventory and UI rendering").
--
-- Flags (harness/run.sh --flag, read from ~/Zomboid/Lua/pzopt-harness.txt):
--   inventory=open|player|loot   inventory_delay (2) s after the player exists: pin the player's inventory and / or the
--                                loot window open (stock collapses them to a title bar when the mouse leaves; pinned they
--                                stay drawn in full, as a player looting with both open sees them). Without the flag
--                                whether the player's inventory (the bench character's 30 rows) is drawn open or
--                                collapsed depends on where the desktop's mouse pointer happens to be: pass open or
--                                hidden for a controlled state
--   inventory=hidden             hide both windows (the UI without any inventory drawn or updated)
--   inventory_items=N            add N items of N distinct script types (Base module, plain / food / drainable / weapon /
--                                literature / clothing, not obsolete) to the player's main inventory: N rows in the pane
--   inventory_expand=true        expand every stacked group in both panes (more rows drawn)
--   inventory_count=true         also without inventory=: time and count the rebuilds (below) with the default UI
--   inventory_floor=true         point the loot window at the floor container (implied by inventory_transfer)
--   inventory_transfer=transfer_all  inventory_transfer_at (4) s after opening, press stock's Transfer all of the player's
--                                inventory (ISInventoryPane.transferAll: every item not equipped / favourite / on the hotbar
--                                queued as ISInventoryTransferAction into the selected loot container, here the floor);
--                                the 2 s line then also carries transfers=<items moved> inv=<items> floor=<items>
--                                queue=<timed actions queued> pending=<items merged into the running action>
-- Whenever the rig is on it wraps ISInventoryPage.refreshBackpacks and ISInventoryPane.refreshContainer (the rebuilds
-- stock runs when the player's square or facing changes, when a container changes, when a transfer ends) and every 2 s
-- prints "[pzopt-inv] t=<epoch ms> frames=<n> backpacks=<calls> <ms> containers=<calls> <ms> rows=<player>/<loot>
-- buttons=<player>/<loot>" (millisecond stamps: summed they average out). The per-frame methods are timed by the
-- main harness's lua_prof=ISInventoryPage,ISInventoryPane; pzopt-lua.out (luaProfile) samples all of it.

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

local inv = nil -- nil = not read yet, false = off
local stats = { bp = { 0, 0 }, rc = { 0, 0 }, tr = { 0, 0 }, frames = 0 }

local function wrapCounters()
    local function wrap(t, name, acc)
        local fn = t[name]
        if type(fn) ~= "function" then print("[pzopt-inv] no " .. name); return end
        t[name] = function(...)
            local t0 = getTimestampMs()
            local r1, r2, r3 = fn(...)
            acc[1] = acc[1] + 1
            acc[2] = acc[2] + (getTimestampMs() - t0)
            return r1, r2, r3
        end
    end
    wrap(ISInventoryPage, "refreshBackpacks", stats.bp)
    wrap(ISInventoryPane, "refreshContainer", stats.rc)
    wrap(ISInventoryTransferAction, "transferItem", stats.tr)
end

local ITEM_TYPES = { normal = true, food = true, drainable = true, weapon = true, literature = true, clothing = true }

local function fillInventory(player, n)
    local all = getScriptManager():getAllItems()
    local inventory = player:getInventory()
    local added = 0
    for i = 0, all:size() - 1 do
        if added >= n then break end
        local script = all:get(i)
        -- ItemType prints as its registry location ("base:Normal")
        local itemType = script:getItemType() and tostring(script:getItemType()):match("([^:]+)$"):lower()
        if script:getModuleName() == "Base" and not script:getObsolete() and ITEM_TYPES[itemType] then
            local ok, item = pcall(function() return inventory:AddItem(script:getFullName()) end)
            if ok and item then added = added + 1 end
        end
    end
    print("[pzopt-inv] added " .. added .. " items, weight " .. string.format("%.1f", inventory:getCapacityWeight()))
end

local function openPage(page, expand)
    if not page then return end
    page:setVisible(true)
    page:setPinned()
    page.isCollapsed = false
    page:clearMaxDrawHeight()
    page.collapseCounter = 0
    -- the pane's field expandAll is its header button, which shadows the method
    if expand then ISInventoryPane.expandAll(page.inventoryPane) end
end

local function rows(page)
    if not page or not page.inventoryPane or not page.inventoryPane.itemslist then return "-" end
    return tostring(#page.inventoryPane.itemslist)
end

local function buttons(page)
    if not page or not page.backpacks then return "-" end
    return tostring(#page.backpacks)
end

local function selectFloor(page)
    for _, button in ipairs(page.backpacks or {}) do
        if button.inventory and button.inventory:getType() == "floor" then
            page:selectContainer(button)
            return true
        end
    end
    return false
end

local function transferState(player, pdata)
    local queue = ISTimedActionQueue.getTimedActionQueue(player)
    local current = queue and queue.queue and queue.queue[1]
    local pending = 0
    if current and current.queueList then
        for _, v in ipairs(current.queueList) do pending = pending + #v.items end
    end
    local loot = pdata.lootInventory.inventory
    return string.format(" transfers=%d inv=%d floor=%d queue=%d pending=%d", stats.tr[1],
        player:getInventory():getItems():size(), loot and loot:getItems():size() or -1,
        queue and queue.queue and #queue.queue or 0, pending)
end

local function invTick()
    if inv == false then return end
    local player = getPlayer()
    if not player then return end
    local now = getTimestampMs()
    if inv == nil then
        local flags = readFlags()
        local mode = flags and flags.inventory or ""
        if mode == "" and not (flags and flags.inventory_count == "true") then inv = false; return end
        inv = { mode = mode, items = tonumber(flags.inventory_items) or 0, expand = flags.inventory_expand == "true",
            transfer = flags.inventory_transfer or "", transferAt = (tonumber(flags.inventory_transfer_at) or 4) * 1000,
            floor = flags.inventory_floor == "true" or (flags.inventory_transfer or "") ~= "",
            openMs = now + (tonumber(flags.inventory_delay) or 2) * 1000, next = now + 2000 }
        wrapCounters()
        print("[pzopt-inv] rig on: inventory=" .. mode .. " items=" .. inv.items .. " expand=" .. tostring(inv.expand))
    end
    local pdata = getPlayerData(0)
    if not inv.opened and now >= inv.openMs and pdata and pdata.playerInventory then
        inv.opened = true
        if inv.items > 0 then fillInventory(player, inv.items) end
        if inv.mode == "open" or inv.mode == "player" then openPage(pdata.playerInventory, inv.expand) end
        if inv.mode == "open" or inv.mode == "loot" then openPage(pdata.lootInventory, inv.expand) end
        if inv.mode == "hidden" then
            pdata.playerInventory:setVisible(false)
            pdata.lootInventory:setVisible(false)
        end
        if inv.items > 0 or inv.expand then pdata.playerInventory:refreshBackpacks() end
        if inv.floor then print("[pzopt-inv] loot window on the floor: " .. tostring(selectFloor(pdata.lootInventory))) end
        inv.transferMs = now + inv.transferAt
        print("[pzopt-inv] opened: player rows=" .. rows(pdata.playerInventory) .. " loot rows=" .. rows(pdata.lootInventory)
            .. " size=" .. pdata.playerInventory:getWidth() .. "x" .. pdata.playerInventory:getHeight()
            .. " / " .. pdata.lootInventory:getWidth() .. "x" .. pdata.lootInventory:getHeight())
    end
    if inv.opened and inv.mode ~= "" then
        -- keep them open: a stray collapse (aiming sets collapseCounter to 1000) is undone every tick
        for _, page in ipairs({ pdata.playerInventory, pdata.lootInventory }) do
            if page.pin and page.isCollapsed then page.isCollapsed = false; page:clearMaxDrawHeight() end
        end
    end
    if inv.opened and inv.transfer == "transfer_all" and not inv.transferred and now >= inv.transferMs then
        inv.transferred = true
        ISInventoryPane.transferAll(pdata.playerInventory.inventoryPane)
        print("[pzopt-inv] transfer_all pressed:" .. transferState(player, pdata))
    end
    stats.frames = stats.frames + 1
    if now >= inv.next then
        inv.next = now + 2000
        print(string.format("[pzopt-inv] t=%d frames=%d backpacks=%d %d containers=%d %d rows=%s/%s buttons=%s/%s", now,
            stats.frames, stats.bp[1], stats.bp[2], stats.rc[1], stats.rc[2],
            rows(pdata and pdata.playerInventory), rows(pdata and pdata.lootInventory),
            buttons(pdata and pdata.playerInventory), buttons(pdata and pdata.lootInventory))
            .. (inv.transfer ~= "" and pdata and transferState(player, pdata) or ""))
        stats.frames = 0
        stats.bp[1], stats.bp[2], stats.rc[1], stats.rc[2], stats.tr[1] = 0, 0, 0, 0, 0
    end
end

Events.OnTickEvenPaused.Add(function()
    local ok, err = pcall(invTick)
    if not ok then print("[pzopt-inv] rig error " .. tostring(err)); inv = false end
end)
