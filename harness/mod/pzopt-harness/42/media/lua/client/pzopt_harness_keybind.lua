-- pzopt harness: key rebinding rig (2026-10-06, Workshop reports "can't bind the open map key to anything other than M").
--
-- keybind_check=S        S seconds after the player exists: open the pause menu and Options > Key Bindings the way the
--                        menu items do, press the binding's button, "press" keybind_key in the set-key dialog (its
--                        onKeyRelease, the stock path), Accept, close the menus, then raise the new key and the old one as
--                        the game does (OnKeyStartPressed + OnKeyPressed) and log whether the map opened. Every step logs
--                        `[pzopt-keybind]` lines; screenshots Screenshots/pzopt-keybind-<step>.png.
--   keybind_name=<name>  binding to change (Map)
--   keybind_key=<code>   new LWJGL key code (49 = N)
--   keybind_quit=true    quit the game when done
--   keybind_skip=true    no Options steps (the key file already holds the binding): only press the keys
--   keybind_old=<code>   the key the binding had before (default: the current one)
--   keybind_menu=true    first rebind on the MAIN MENU's options screen (the lazily built one), in OnMainMenuEnter
--                        before the harness presses Continue (open as the OPTIONS item does, Key Bindings tab, the
--                        binding's button, the dialog's onKeyRelease, Accept); the world part then only presses keys
--   keybind_dup=keep|clear|cancel  the new key is bound elsewhere: press that button of the "already used" dialog
--                        (a real click with keybind_real, else its onclick)
--   keybind_click=true   open the set-key dialog with a real click on the binding's button (needs keybind_real)
--   keybind_real=true    real key presses (xdotool, sent by the job wrapper /tmp/pzopt-keybind-check.sh) instead of
--                        calling the dialog's onKeyRelease and raising the Lua events; keybind_keysym (F8) and
--                        keybind_old_keysym (m) are the xdotool names of keybind_key and keybind_old
-- Every run with keybind_check also logs the binding at world entry (`boot`), so a second run shows whether the
-- choice survived a restart. Accept writes Zomboid/Lua/keysB42.ini: back it up before the run and restore it after.

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

local function log(s) print("[pzopt-keybind] " .. s) end

local function fileLine(name)
    local r = getFileReader("keysB42.ini", false)
    if not r then return "(no keysB42.ini)" end
    local found = "(absent)"
    while true do
        local line = r:readLine()
        if line == nil then break end
        if luautils.stringStarts(line, name .. "=") then found = line end
    end
    r:close()
    return found
end

local function state(name, key, old)
    local core = getCore()
    return string.format("core %s=%d alt=%d isKey(new %d)=%s isKey(old %d)=%s file '%s'", name, core:getKey(name),
        core:getAltKey(name), key, tostring(core:isKey(name, key)), old, tostring(core:isKey(name, old)), fileLine(name))
end

local function entryOf(name)
    for _, v in ipairs(MainOptions.keyText or {}) do
        if not v.value and v.txt and v.txt:getName() == name then return v end
    end
end

local function mapOpen() return ISWorldMap_instance ~= nil and ISWorldMap_instance:isVisible() end

local function escapeKey()
    local k = getCore():getKey("Main Menu")
    return k ~= 0 and k or Keyboard.KEY_ESCAPE
end

local function raise(key)
    triggerEvent("OnKeyStartPressed", key)
    triggerEvent("OnKeyPressed", key)
end

local rig = nil
local lastDup = nil -- the newest ISDuplicateKeybindDialog (the "key already used" dialog)
local function hookDup()
    if not ISDuplicateKeybindDialog or ISDuplicateKeybindDialog.pzoptRigHooked then return end
    ISDuplicateKeybindDialog.pzoptRigHooked = true
    local orig = ISDuplicateKeybindDialog.new
    ISDuplicateKeybindDialog.new = function(self, ...)
        local o = orig(self, ...)
        lastDup = o
        log("duplicate dialog: key " .. tostring(o.key) .. " " .. tostring(o.keybindName) .. " vs " .. tostring(o.keybind2Name))
        return o
    end
end

local function request(line)
    local w = getFileWriter("pzopt-keybind-press.txt", true, false)
    w:write(line .. "\n")
    w:close()
    log("requested " .. line)
end
-- keybind_real=true: the key presses are real ones. The rig writes the xdotool key name into Zomboid/Lua/
-- pzopt-keybind-press.txt; the job's wrapper focuses the game window, sends it and deletes the file.
local function press(code, sym)
    if rig.real then
        request("key " .. sym .. " " .. code)
    else
        raise(code)
    end
end

local function tick()
    if rig == false then return end
    if not getPlayer() then return end
    if rig == nil then
        local flags = readFlags()
        if not flags or not flags.keybind_check or flags.keybind_check == "" then rig = false; return end
        rig = { at = getTimestampMs() + (tonumber(flags.keybind_check) or 8) * 1000, step = 0,
            name = flags.keybind_name or "Map", key = tonumber(flags.keybind_key) or 49, quit = flags.keybind_quit == "true",
            real = flags.keybind_real == "true", sym = flags.keybind_keysym or "F8", oldSym = flags.keybind_old_keysym or "m" }
        if flags.keybind_skip == "true" then rig.step = 4 end -- binding already in the key file: only press the keys
        rig.old = tonumber(flags.keybind_old) or getCore():getKey(rig.name)
        rig.click = flags.keybind_click == "true"
        rig.dup = (flags.keybind_dup and flags.keybind_dup ~= "") and flags.keybind_dup or nil
        hookDup()
        local mo = MainScreen.instance and MainScreen.instance.mainOptions
        log("boot " .. state(rig.name, rig.key, rig.old) .. " options pending=" .. tostring(mo and mo.pzoptCreatePending)
            .. " keyText=" .. #(MainOptions.keyText or {}) .. " real=" .. tostring(rig.real))
    end
    local now = getTimestampMs()
    if now < rig.at then return end
    local s = rig.step
    rig.step = s + 1
    rig.at = now + 2000
    local mo = MainScreen.instance and MainScreen.instance.mainOptions
    if s == 0 then
        ToggleEscapeMenu(escapeKey())
        mo:toUI()
        mo:setVisible(true)
        mo.tabs:activateView(getText("UI_optionscreen_keybinding"))
        local e = entryOf(rig.name)
        log("options open: visible=" .. tostring(mo:isVisible()) .. " keyText=" .. #MainOptions.keyText .. " entry="
            .. tostring(e ~= nil) .. (e and (" keyCode=" .. tostring(e.keyCode) .. " title='" .. tostring(e.btn.title) .. "'") or ""))
    elseif s == 1 and rig.click and not rig.clicked then
        -- keybind_click=true: a real mouse click on the binding's button (the panel scrolled so it is in view)
        local e = entryOf(rig.name)
        if not e then log("no entry for " .. rig.name); rig = false; return end
        local b = e.btn
        local p = b.parent -- the Key Bindings page (mo.mainPanel is the last page built)
        rig.page = p
        rig.clicked = true
        rig.step = 1
        rig.at = now + 3000
        local sy = b:getAbsoluteY() + b:getHeight() / 2
        log(string.format("button '%s' abs y=%d, page abs y=%d h=%d scroll=%d scrollHeight=%s", tostring(b.title), math.floor(sy),
            math.floor(p:getAbsoluteY()), math.floor(p:getHeight()), math.floor(p:getYScroll()), tostring(p.getScrollHeight and p:getScrollHeight())))
        local bottom = p:getAbsoluteY() + p:getHeight()
        if sy > bottom - 40 then
            -- below the page's visible part: scroll it the way a player does, with the mouse wheel over the page
            local notches = math.ceil((sy - (p:getAbsoluteY() + p:getHeight() / 2)) / 40) + 2
            request(string.format("wheel %d %d %d", math.floor(p:getAbsoluteX() + p:getWidth() / 2), math.floor(p:getAbsoluteY() + p:getHeight() / 2), notches))
        end
    elseif s == 1 and rig.click and rig.clicked and not rig.clickSent then
        local e = entryOf(rig.name)
        local b = e.btn
        local x, y = math.floor(b:getAbsoluteX() + b:getWidth() / 2), math.floor(b:getAbsoluteY() + b:getHeight() / 2)
        local p = rig.page
        log(string.format("after wheel: page scroll=%d, button abs y=%d (page %d..%d)", math.floor(p:getYScroll()), y,
            math.floor(p:getAbsoluteY()), math.floor(p:getAbsoluteY() + p:getHeight())))
        if y > p:getAbsoluteY() + p:getHeight() or y < p:getAbsoluteY() then
            getCore():TakeFullScreenshot("pzopt-keybind-noscroll.png")
            log("RESULT the wheel did not bring the button into view")
        end
        log(string.format("button '%s' at %d,%d size %dx%d visible=%s reallyVisible=%s screen %dx%d", tostring(b.title), x, y,
            b:getWidth(), b:getHeight(), tostring(b:isVisible()), tostring(b:isReallyVisible()), getCore():getScreenWidth(), getCore():getScreenHeight()))
        getCore():TakeFullScreenshot("pzopt-keybind-before.png")
        rig.clickSent = true
        rig.step = 1
        rig.at = now + 2500
        request(string.format("click %d %d", x, y))
    elseif s == 1 and rig.click then
        local dlg = MainOptions.setKeybindDialog
        log("after click: set-key dialog=" .. tostring(dlg ~= nil) .. " name=" .. tostring(dlg and dlg.keybindName))
        if not dlg then
            getCore():TakeFullScreenshot("pzopt-keybind-noclick.png")
            log("RESULT click did not open the set-key dialog")
            rig.step = 8
            return
        end
        press(rig.key, rig.sym)
    elseif s == 1 then
        getCore():TakeFullScreenshot("pzopt-keybind-before.png")
        local e = entryOf(rig.name)
        if not e then log("no entry for " .. rig.name); rig = false; return end
        MainOptions.onKeyBindingBtnPress(mo, e.btn)
        local dlg = MainOptions.setKeybindDialog
        log("dialog=" .. tostring(dlg ~= nil) .. " name=" .. tostring(dlg and dlg.keybindName))
        if dlg then
            if rig.real then press(rig.key, rig.sym) else dlg:onKeyRelease(rig.key) end
        end
    elseif s == 2 and rig.dup and lastDup and lastDup:isVisible() and not rig.dupClicked then
        -- the pressed key is bound elsewhere: click the dialog's button for real (keybind_dup=keep|clear|cancel)
        local b = lastDup[rig.dup]
        getCore():TakeFullScreenshot("pzopt-keybind-dup.png")
        rig.dupClicked = true
        rig.step = 2
        if rig.real then
            request(string.format("click %d %d", math.floor(b:getAbsoluteX() + b:getWidth() / 2), math.floor(b:getAbsoluteY() + b:getHeight() / 2)))
        else
            b.onclick(b.target, b)
        end
    elseif s == 2 then
        if rig.dup then
            log("duplicate dialog: seen=" .. tostring(lastDup ~= nil) .. " still visible=" .. tostring(lastDup ~= nil and lastDup:isVisible())
                .. " clicked=" .. tostring(rig.dupClicked))
        end
        local e = entryOf(rig.name)
        log("after key: dialog=" .. tostring(MainOptions.setKeybindDialog ~= nil) .. " keyCode=" .. tostring(e.keyCode)
            .. " title='" .. tostring(e.btn.title) .. "' changed=" .. tostring(mo.gameOptions.changed) .. " " .. state(rig.name, rig.key, rig.old))
        getCore():TakeFullScreenshot("pzopt-keybind-set.png")
        if MainOptions.setKeybindDialog then MainOptions.setKeybindDialog:destroy() end
    elseif s == 3 then
        mo:apply(true)
        log("after accept: options visible=" .. tostring(mo:isVisible()) .. " " .. state(rig.name, rig.key, rig.old))
    elseif s == 4 then
        if MainScreen.instance:isVisible() then ToggleEscapeMenu(escapeKey()) end
        log("menu closed: " .. tostring(not MainScreen.instance:isVisible()) .. " checkKey(new)=" .. tostring(ISWorldMap.checkKey(rig.key))
            .. " checkKey(old)=" .. tostring(ISWorldMap.checkKey(rig.old)))
    elseif s == 5 then
        press(rig.key, rig.sym)
    elseif s == 6 then
        rig.newOpened = mapOpen()
        log("new key " .. rig.key .. ": map open=" .. tostring(rig.newOpened))
        getCore():TakeFullScreenshot("pzopt-keybind-newkey.png")
        if mapOpen() then ISWorldMap_instance:close() end
    elseif s == 7 then
        press(rig.old, rig.oldSym)
    elseif s == 8 then
        log("old key " .. rig.old .. ": map open=" .. tostring(mapOpen()))
        if mapOpen() then ISWorldMap_instance:close() end
        log("done " .. state(rig.name, rig.key, rig.old) .. " result new_opens=" .. tostring(rig.newOpened))
        if not rig.quit then rig = false end
    else
        rig = false
        getCore():quit()
    end
end

local function menuRebind()
    local flags = readFlags()
    if not flags or flags.keybind_menu ~= "true" or flags.consumed then return end
    local name, key = flags.keybind_name or "Map", tonumber(flags.keybind_key) or 49
    local mo = MainScreen.instance and MainScreen.instance.mainOptions
    if not mo then log("menu: no MainOptions"); return end
    local old = getCore():getKey(name)
    log("menu: before " .. state(name, key, old) .. " pending=" .. tostring(mo.pzoptCreatePending) .. " created=" .. tostring(mo.pzoptCreated)
        .. " keyText=" .. #(MainOptions.keyText or {}))
    mo:toUI()
    mo:setVisible(true)
    mo.tabs:activateView(getText("UI_optionscreen_keybinding"))
    local e = entryOf(name)
    log("menu: options open pending=" .. tostring(mo.pzoptCreatePending) .. " keyText=" .. #MainOptions.keyText .. " entry=" .. tostring(e ~= nil)
        .. (e and (" keyCode=" .. tostring(e.keyCode)) or ""))
    if not e then return end
    MainOptions.onKeyBindingBtnPress(mo, e.btn)
    local dlg = MainOptions.setKeybindDialog
    if dlg then dlg:onKeyRelease(key) end
    e = entryOf(name)
    log("menu: after key keyCode=" .. tostring(e.keyCode) .. " title='" .. tostring(e.btn.title) .. "' changed=" .. tostring(mo.gameOptions.changed))
    mo:apply(true)
    log("menu: after accept visible=" .. tostring(mo:isVisible()) .. " " .. state(name, key, old))
end

Events.OnMainMenuEnter.Add(function()
    local ok, err = pcall(menuRebind)
    if not ok then print("[pzopt-keybind] menu rig error " .. tostring(err)) end
end)

Events.OnTickEvenPaused.Add(function()
    local ok, err = pcall(tick)
    if not ok then print("[pzopt-keybind] rig error " .. tostring(err)); rig = false end
end)
