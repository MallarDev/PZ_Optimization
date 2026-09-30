-- pzopt: uiLuaFast (2026-09-30, the UI snappiness pass). Vanilla-identical fast paths for the inventory windows'
-- per-tick and per-render Lua. Each one is installed at game start only while the game's function is still the vanilla
-- one this file saw when it loaded (a mod that replaced or wrapped it keeps its version and gets no fast path), and only
-- when the key uiLuaFast is on (Options > Optimizations, default on).
--
-- 1. ISInventoryPage:update re-arranges the container controls row (ISInventoryWindowContainerControls /
--    ISLootWindowContainerControls:arrange removes and re-adds every control and asks every handler) and resizes the
--    pane every 100 ms, also while the window is hidden: ~70-100 us of its ~110-150 us per call. Here a hidden window
--    skips that block; it runs when the window is shown (setVisible) and on the first update after it is visible again,
--    so a shown window has the controls stock would have given it.
-- 2. ISInventoryPane:renderdetails(true) (the dragged-items pass of render) walks every item again although nothing is
--    dragged; with no drag in progress it only counts the rows (the scroll height), ages collapsed food stacks and
--    clears a stale item highlight. Here that pass does exactly those things without the per-row work.

local VANILLA_PAGE_UPDATE = ISInventoryPage and ISInventoryPage.update
local VANILLA_PANE_RENDERDETAILS = ISInventoryPane and ISInventoryPane.renderdetails
local VANILLA_PAGE_SETVISIBLE = ISInventoryPage and ISInventoryPage.setVisible

local function enabled()
    local ok, v = pcall(function() return getPerformance():getPzoptOption("uiLuaFast") end)
    return ok and v == "true"
end

-- The controls block of ISInventoryPage:update (42.21), shared by the fast update and the setVisible hook.
local function arrangeControls(self)
    if self.controlsUI then
        self.controlsUI:arrange()
        self.inventoryPane:setHeight(self.height - self.inventoryPane.y - self.resizeWidget.height - self.controlsUI.height)
        self.inventoryPane:setY(self:titleBarHeight())
    end
end

-- ISInventoryPage:update of 42.21 with the controls block skipped while the window is hidden.
local function fastPageUpdate(self)
    local playerObj = getSpecificPlayer(self.player)
    if self.inventory:getEffectiveCapacity(playerObj) ~= self.capacity then
        self.capacity = self.inventory:getEffectiveCapacity(playerObj)
    end

    self:updateContainerHighlight()

    if (ISMouseDrag.dragging ~= nil and #ISMouseDrag.dragging > 0) or self.pin then
        self.collapseCounter = 0;
        if isClient() and self.isCollapsed then
            self.inventoryPane.inventory:requestSync();
        end
        self.isCollapsed = false;
        self:clearMaxDrawHeight();
        self.collapseCounter = 0;
    end

    if not self.onCharacter then
        if self.lastDir ~= playerObj:getDir() then
            self.lastDir = playerObj:getDir()
            self:refreshBackpacks()
        elseif self.lastSquare ~= playerObj:getCurrentSquare() then
            self.lastSquare = playerObj:getCurrentSquare()
            self:refreshBackpacks()
        end

        -- If the currently-selected container is locked to the player, select another container.
        local object = self.inventory and self.inventory:getParent() or nil
        if #self.backpacks > 1 and instanceof(object, "IsoThumpable") and object:isLockedToCharacter(playerObj) then
            local currentIndex = self:getCurrentBackpackIndex()
            local unlockedIndex = self:prevUnlockedContainer(currentIndex, false)
            if unlockedIndex == -1 then
                unlockedIndex = self:nextUnlockedContainer(currentIndex, false)
            end
            if unlockedIndex ~= -1 then
                if playerObj:getJoypadBind() ~= -1 then
                    self.backpackChoice = unlockedIndex
                end
                self:selectContainer(self.backpacks[unlockedIndex])
            end
        end
    end

    if self:getIsVisible() then
        arrangeControls(self)
        self.pzoptControlsStale = nil
    else
        self.pzoptControlsStale = true
    end
    self.containerButtonPanel:setHeight(self.inventoryPane.height)
    self.containerButtonPanel:setY(self.inventoryPane.y)
    self.containerButtonPanel:setScrollHeight(self.backpacks[#self.backpacks]:getBottom())

	self:updateContainerOpenCloseSounds()
end

local function fastPageSetVisible(self, bVisible)
    VANILLA_PAGE_SETVISIBLE(self, bVisible)
    if bVisible and self.pzoptControlsStale and self.controlsUI and self.inventoryPane then
        arrangeControls(self)
        self.containerButtonPanel:setHeight(self.inventoryPane.height)
        self.containerButtonPanel:setY(self.inventoryPane.y)
        self.pzoptControlsStale = nil
    end
end

-- ISInventoryPane:renderdetails(true) of 42.21 when no drag is in progress: everything it does then, nothing else.
local function draggedPassNoDrag(self)
    self:updateScrollbars();

    local player = getSpecificPlayer(self.player)
    if self.inventory:isLockedToCharacter(player) then
        DebugType.General:error("Container that is locked to the player is being rendered " .. tostring(self.inventory));
        return
    end

    if (self.itemsToHighlight ~= nil) and ((self.itemsToHighlightOwner == nil) or (not self.itemsToHighlightOwner:isReallyVisible())) then
        self.itemsToHighlightOwner = nil
        self.itemsToHighlight = nil
    end

    local y = 0;
    if self.itemslist == nil then
        self:refreshContainer();
    end
    local maxInStack = ISInventoryPane.MAX_ITEMS_IN_STACK_TO_RENDER + 1
    for k, v in ipairs(self.itemslist) do
        local count = 1;
        for k2, v2 in ipairs(v.items) do
            y = y + 1;
            if count == 1 and self.collapsed ~= nil and v.name ~= nil and self.collapsed[v.name] then
                if instanceof(v2, "Food") then
                    for k3,v3 in ipairs(v.items) do
                        v3:updateAge()
                    end
                end
                break
            end
            if count == maxInStack then
                break
            end
            count = count + 1;
        end
    end

    self:setScrollHeight(y * self.itemHgt);
    self:setScrollWidth(0);

    if self.draggingMarquis then
        local w = self:getMouseX() - self.draggingMarquisX;
        local h = self:getMouseY() - self.draggingMarquisY;
        self:drawRectBorder(self.draggingMarquisX, self.draggingMarquisY, w, h, 0.4, 0.9, 0.9, 1);
    end
end

local function fastRenderdetails(self, doDragged)
    if doDragged and (self.dragging == nil or not self.dragStarted) then
        return draggedPassNoDrag(self)
    end
    return VANILLA_PANE_RENDERDETAILS(self, doDragged)
end

local function install()
    if not enabled() then
        print("[pzopt] uiLuaFast: off")
        return
    end
    local done = {}
    if VANILLA_PAGE_UPDATE and ISInventoryPage.update == VANILLA_PAGE_UPDATE and ISInventoryPage.setVisible == VANILLA_PAGE_SETVISIBLE then
        ISInventoryPage.update = fastPageUpdate
        ISInventoryPage.setVisible = fastPageSetVisible
        table.insert(done, "ISInventoryPage.update")
    end
    if VANILLA_PANE_RENDERDETAILS and ISInventoryPane.renderdetails == VANILLA_PANE_RENDERDETAILS then
        ISInventoryPane.renderdetails = fastRenderdetails
        table.insert(done, "ISInventoryPane.renderdetails")
    end
    print("[pzopt] uiLuaFast: " .. (#done > 0 and table.concat(done, ", ") or "nothing (replaced by a mod)"))
end

Events.OnGameStart.Add(install)
