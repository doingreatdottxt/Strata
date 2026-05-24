local state = {
    -- ... [keep your existing state variables] ...
    
    -- New Effects Engine States
    active_fx = 0, -- 0: None, 1: Abyss, 2: Shatter, 3: Breeze, 4: Crackle
    eq_low = 1.0,  -- Maps to 5-band Engine Band 1
    eq_mid = 1.0,  -- Maps to 5-band Engine Bands 2, 3, 4
    eq_high = 1.0  -- Maps to 5-band Engine Band 5
}

function key(n, z)
    if n == 1 then
        state.shift_held = (z == 1)
    elseif n == 2 and z == 1 then
        if state.shift_held then
            -- Shift + Key 2: Cycle through effects
            state.active_fx = (state.active_fx + 1) % 5
            engine.select_fx(state.active_fx)
        else
            toggle_formation()
        end
    elseif n == 3 and z == 1 then
        if state.shift_held and state.layers_active > 0 then
            engine.erode_layer()
            state.layers_active = state.layers_active - 1
            state.surface_cycles = 0
        elseif not state.shift_held then
            params:set("sync_mode", params:get("sync_mode") == 1 and 2 or 1)
        end
    end
end

function enc(n, d)
    if state.shift_held then
        -- Shift + Encoders: Universal EQ Control
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
        -- Unshifted Encoders: FX Parameters or Master Volume
        if state.active_fx == 0 then
            if n == 1 then params:delta("main_vol", d) end
        else
            -- Map to dynamic engine parameters based on the active effect
            -- (e.g., 1: Abyss -> depth, shimmer, drift)
            if n == 1 then engine.set_fx_p1(d) end
            if n == 2 then engine.set_fx_p2(d) end
            if n == 3 then engine.set_fx_p3(d) end
        end
    end
end
