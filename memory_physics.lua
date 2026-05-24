-- memory_physics.lua
-- Strata Geological Looper + Dynamic FX

engine.name = 'MemoryPhysics'

-- ==========================================
-- STATE MANAGEMENT
-- ==========================================
local state = {
    -- Core Strata State
    shift_held = false,
    layers_active = 0,
    surface_cycles = 0,
    
    -- Multi-Effects Engine States
    active_fx = 0, 
    fx_p1 = 0.5,
    fx_p2 = 0.5,
    fx_p3 = 0.5,
    
    -- Universal EQ States
    eq_low = 1.0,  
    eq_mid = 1.0,  
    eq_high = 1.0  
}

local fx_names = {"BYPASS", "ABYSS", "SHATTER", "BREEZE", "CRACKLE"}

-- ==========================================
-- INITIALIZATION
-- ==========================================
function init()
    print("Strata initializing...")
    
    params:add_control("main_vol", "Master Volume", controlspec.new(0, 1, 'lin', 0.01, 0.8))
    params:set_action("main_vol", function(x) engine.main_vol(x) end)
    params:add_option("sync_mode", "Sync Mode", {"Free", "Beat"}, 1)
    
    -- Start 15fps screen refresh clock
    clock.run(
        function()
            while true do
                clock.sleep(1/15)
                redraw()
            end
        end
    )
end

function toggle_formation()
    -- Your geological loop toggle logic goes here
    state.layers_active = state.layers_active + 1
end

-- ==========================================
-- HARDWARE CONTROLS
-- ==========================================
function key(n, z)
    if n == 1 then
        state.shift_held = (z == 1)
        
    elseif n == 2 and z == 1 then
        if state.shift_held then
            -- Cycle FX (0 to 4)
            state.active_fx = (state.active_fx + 1) % 5
            engine.select_fx(state.active_fx)
            
            -- Reset local params for the new effect
            state.fx_p1, state.fx_p2, state.fx_p3 = 0.5, 0.5, 0.5
            engine.set_fx_p1(0.5)
            engine.set_fx_p2(0.5)
            engine.set_fx_p3(0.5)
        else
            toggle_formation()
        end
        
    elseif n == 3 and z == 1 then
        if state.shift_held and state.layers_active > 0 then
            -- Erode layer
            -- engine.erode_layer() 
            state.layers_active = state.layers_active - 1
            state.surface_cycles = 0
        elseif not state.shift_held then
            params:set("sync_mode", params:get("sync_mode") == 1 and 2 or 1)
        end
    end
end

function enc(n, d)
    if state.shift_held then
        -- Universal EQ Control (Enc 1: High, Enc 2: Mid, Enc 3: Low)
        if n == 1 then
            state.eq_high = util.clamp(state.eq_high + (d * 0.05), 0, 2.0)
            engine.set_eq_high(state.eq_high)
        elseif n == 2 then
            state.eq_mid = util.clamp(state.eq_mid + (d * 0.05), 0, 2.0)
            engine.set_eq_mid(state.eq_mid)
        elseif n == 3 then
            state.eq_low = util.clamp(state.eq_low + (d * 0.05), 0, 2.0)
            engine.set_eq_low(state.eq_low)
        end
    else
        -- Contextual Controls
        if state.active_fx == 0 then
            if n == 1 then params:delta("main_vol", d) end
        else
            if n == 1 then 
                state.fx_p1 = util.clamp(state.fx_p1 + (d * 0.02), 0, 1.0)
                engine.set_fx_p1(state.fx_p1) 
            end
            if n == 2 then 
                state.fx_p2 = util.clamp(state.fx_p2 + (d * 0.02), 0, 1.0)
                engine.set_fx_p2(state.fx_p2) 
            end
            if n == 3 then 
                state.fx_p3 = util.clamp(state.fx_p3 + (d * 0.02), 0, 1.0)
                engine.set_fx_p3(state.fx_p3) 
            end
        end
    end
end

-- ==========================================
-- SCREEN RENDERING
-- ==========================================
function redraw()
    screen.clear()
    screen.aa(1)
    
    -- 1. Main Strata UI
    screen.level(15)
    screen.move(0, 10)
    screen.text("STRATA")
    
    screen.move(0, 25)
    screen.text("Layers: " .. state.layers_active)
    screen.move(64, 25)
    screen.text("Sync: " .. (params:get("sync_mode") == 1 and "Free" or "Beat"))
    
    -- 2. Effects & EQ Overlay
    screen.level(4)
    screen.move(0, 50)
    screen.text("FX: " .. fx_names[state.active_fx + 1])

    if state.shift_held then
        screen.move(128, 50)
        -- Ordered H -> M -> L to map visually to Enc 1 -> 2 -> 3
        screen.text_right(string.format("H:%.1f M:%.1f L:%.1f", state.eq_high, state.eq_mid, state.eq_low))
    else
        if state.active_fx == 0 then
            screen.move(128, 50)
            screen.text_right(string.format("Vol: %.2f", params:get("main_vol")))
        else
            screen.move(128, 50)
            -- Show the 0.0-1.0 parameters of the active effect
            screen.text_right(string.format("%.2f  %.2f  %.2f", state.fx_p1, state.fx_p2, state.fx_p3))
        end
    end
    
    screen.update()
end
