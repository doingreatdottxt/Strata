-- memory_physics.lua
-- Last In First Out /^\/^\
-- /^\ Geological Strata /^\
-- /^\/^\Looper/^\/^\/^\/^\
-- ____________________________
-- Key 1 - Shift
-- Key 2 - Rec Start/Stop (Unshifted) / Cycle FX (Shift)
-- Key 3 - Toggle Sync Mode (Unshifted) / Excavate Surface (Shift)
-- Encoder 1 - Global Volume (Unshifted) / High EQ (Shift)
-- Encoder 2 - Mid EQ (Shift)
-- Encoder 3 - Low EQ (Shift)

engine.name = 'MemoryPhysics'
local MAX_TIME = 30.0 

local state = {
  recording = false,
  start_time = 0,
  start_beat = 0, 
  duration = 2.0,
  layers_active = 0,
  max_layers = 6,
  shift_held = false,
  silence_frames = 0,
  surface_cycles = 0,
  auto_armed = true,
  current_amp = 0.0,
  last_activity_beat = 0,
  
  -- Effects Engine States
  active_fx = 0, -- 0: Bypass, 1: Abyss, 2: Shatter, 3: Breeze, 4: Crackle
  fx_p1 = 0.5,
  fx_p2 = 0.5,
  fx_p3 = 0.5,
  eq_low = 1.0,
  eq_mid = 1.0,
  eq_high = 1.0 
}

local fx_names = {"BYPASS", "ABYSS", "SHATTER", "BREEZE", "CRACKLE"}
local layer_phases = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0}
local redraw_metro = nil

function init()
  setup_params()
  
  osc.event = function(path, args, from)
    if path == "/in_amp" then
      state.current_amp = args[1]
      -- [Keep your existing Auto-Record/Erosion logic here]
      
    elseif path == "/layer_phase" then
      local layer_idx = math.floor(args[1] + 1)
      local phase_val = args[2]
      if layer_idx >= 1 and layer_idx <= state.max_layers then
        layer_phases[layer_idx] = phase_val
      end
      
    elseif path == "/loop_reset" then
      local layer_idx = math.floor(args[1] + 1)
      if layer_idx == 1 and state.layers_active > 0 and not state.recording then
        state.surface_cycles = state.surface_cycles + 1
        if state.surface_cycles >= 5 then
          engine.erode_layer()
          state.layers_active = math.max(0, state.layers_active - 1)
          state.surface_cycles = 0
        end
      end
    end
  end
  
  redraw_metro = metro.init()
  redraw_metro.time = 1/15
  redraw_metro.event = function() redraw() end
  redraw_metro:start()
end

function setup_params()
  params:add_group("MEMORY PHYSICS CONFIG", 5)
  params:add_control("main_vol", "GLOBAL VOLUME", controlspec.new(0, 2, 'lin', 0.01, 1.0))
  params:set_action("main_vol", function(x) engine.main_vol(x) end)
  
  -- [Keep other original parameters]
  
  params:bang()
end

-- [Keep calculate_quantized_duration and calculate_smart_shift]

function toggle_formation()
  if not state.recording then
    state.surface_cycles = 0
    state.start_time = util.time()
    state.start_beat = clock.get_beats() 
    engine.record_start()
    state.recording = true
    state.auto_armed = false
  else
    state.recording = false
    local measured_dur = util.time() - state.start_time
    state.duration = calculate_quantized_duration(measured_dur)
    engine.record_stop()
    engine.shift_layers(state.duration, calculate_smart_shift())
    state.layers_active = math.min(state.max_layers, state.layers_active + 1)
  end
end

function key(n, z)
  if n == 1 then state.shift_held = (z == 1)
  elseif n == 2 and z == 1 then
    if state.shift_held then
        state.active_fx = (state.active_fx + 1) % 5
        engine.select_fx(state.active_fx)
    else
        toggle_formation()
    end
  elseif n == 3 and z == 1 then
    if state.shift_held and state.layers_active > 0 then
      engine.erode_layer()
      state.layers_active = math.max(0, state.layers_active - 1)
      state.surface_cycles = 0
    elseif not state.shift_held then
      params:set("sync_mode", params:get("sync_mode") == 1 and 2 or 1)
    end
  end
end

function enc(n, d)
  if state.shift_held then
    -- Universal EQ Control (Shift + Encoders)
    if n == 1 then state.eq_high = util.clamp(state.eq_high + d*0.05, 0, 2); engine.set_eq_high(state.eq_high)
    elseif n == 2 then state.eq_mid = util.clamp(state.eq_mid + d*0.05, 0, 2); engine.set_eq_mid(state.eq_mid)
    elseif n == 3 then state.eq_low = util.clamp(state.eq_low + d*0.05, 0, 2); engine.set_eq_low(state.eq_low) end
  else
    -- Contextual FX Parameters or Master Volume
    if state.active_fx > 0 then
      if n == 1 then state.fx_p1 = util.clamp(state.fx_p1 + d*0.02, 0, 1); engine.set_fx_p1(state.fx_p1)
      elseif n == 2 then state.fx_p2 = util.clamp(state.fx_p2 + d*0.02, 0, 1); engine.set_fx_p2(state.fx_p2)
      elseif n == 3 then state.fx_p3 = util.clamp(state.fx_p3 + d*0.02, 0, 1); engine.set_fx_p3(state.fx_p3) end
    else
      if n == 1 then params:delta("main_vol", d) end
    end
  end
end

function redraw()
  screen.clear()
  -- [Keep your original Layer Rendering logic]
  
  -- Add FX/EQ Footer Overlay
  screen.level(4)
  screen.move(0, 64)
  screen.text("FX:" .. fx_names[state.active_fx+1])
  
  if state.shift_held then
    screen.move(128, 64)
    screen.text_right(string.format("H%.1f M%.1f L%.1f", state.eq_high, state.eq_mid, state.eq_low))
  end
  screen.update()
end
