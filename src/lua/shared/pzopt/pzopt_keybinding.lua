-- pzopt: key binding for the performance overlay (pzopt.Overlay, drawn from GameWindow.renderInternal).
-- Appended to the stock keyBinding table right after "Display FPS" so it lists next to it in
-- Options > Key Bindings. Default F9 (unused by stock). Java polls GameKeyboard.isKeyPressed by
-- this name; when the binding is missing (loose Lua not installed) it falls back to
-- pzopt.Config overlayKey (default 67 = F9 too). On a controller L3 + R3 toggles it (pzopt.Overlay, not a binding).
-- Installed by scripts/pzopt.sh into <game dir>/media/lua/shared/pzopt/.
if keyBinding then
    local bind = {}
    bind.value = "Toggle performance overlay"
    bind.key = Keyboard.KEY_F9
    local at = #keyBinding + 1
    for i = 1, #keyBinding do
        if keyBinding[i].value == "Display FPS" then at = i + 1; break end
    end
    table.insert(keyBinding, at, bind)
    -- The rows read getText("UI_optionscreen_binding_" .. value); the game's UI.json has no entry for ours, so the
    -- raw key showed (Discord bug report, 2026-10-05). Added on every Lua load (each follows Translator.loadFiles).
    pcall(function()
        getPerformance():pzoptDefaultUiText("UI_optionscreen_binding_" .. bind.value, "Toggle Performance Overlay")
    end)
end
