-- memory_physics.lua
-- Last In First Out /^\/^\
--  /^\ Geological Strata /^\
-- /^\/^\Looper/^\/^\/^\/^\
-- ____________________________
-- Key 1 - Shift
-- Key 2 - Rec Start/Stop (Unshifted) / Cycle FX (Shift)
-- Key 3 - Toggle Sync Mode (Unshifted) / Excavate Surface (Shift)
-- Encoder 1 - Global Vol or FX P1 (Unshifted) / High EQ (Shift)
-- Encoder 2 - FX P2 (Unshifted) / Mid EQ (Shift)
-- Encoder 3 - FX P3 (Unshifted) / Low EQ (Shift)

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
      
      -- Auto-Erosion logic: If in auto mode and no activity for 16 beats
      if params:get("auto_record") == 2 and state.layers_active > 0 then
        local current_beat = math.floor(clock.get_beats())
        if (current_beat - state.last_activity_beat) >= 16 then
            engine.erode_layer()
            state.layers_active = math.max(0, state.layers_active - 1)
            state.last_activity_beat = current_beat
        end
      end
      
      if params:get("auto_record") == 2 then
        local amp = args[1]
        if not state.recording then
          if amp < (params:get("threshold") * 0.7) then
            state.auto_armed = true
          elseif amp >= params:get("threshold") and state.auto_armed then
            state.last_activity_beat = math.floor(clock.get_beats())
            toggle_formation()
          end
        elseif state.recording then
          if amp < (params:get("threshold") * 0.5) then
            state.silence_frames = state.silence_frames + 1
            if state.silence_frames > (params:get("release_time") * 15) then
              if (util.time() - state.start_time) > 1.0 then
                toggle_formation()
                state.silence_frames = 0
              end
            end
          else
            state.silence_frames = 0
          end
        end
      end
      
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
  params:set_action("main_vol", function(x) engine.set_volume(x) end)
  params:add_option("auto_record", "RECORD TRIGGER MODE", {"MANUAL [K2]", "AUTOMATIC [AMP]"}, 2)
  params:add_control("threshold", "AUTO THRESHOLD", controlspec.new(0.001, 1.0, 'exp', 0.001, 0.02))
  params:add_control("release_time", "AUTO TIMEOUT RELEASE (S)", controlspec.new(0.1, 5.0, 'lin', 0.1, 2.0))
  params:add_option("sync_mode", "SYNC MODE", {"FREE", "BEAT"}, 2)
  params:add_trigger("excavate", "EXCAVATE ENTIRE SITE")
  params:set_action("excavate", function()
    state.layers_active = 0
    state.surface_cycles = 0
    state.auto_armed = true
    engine.clear_layers()
  end)
  params:bang()
end

function calculate_quantized_duration(raw_dur)
  if params:get("sync_mode") == 1 then return math.max(0.1, math.min(raw_dur, MAX_TIME)) end
  local bpm = clock.get_tempo()
  local beat_sec = 60.0 / bpm
  local count = math.floor((raw_dur / beat_sec) + 0.5)
  count = util.clamp(count, 1, 16) 
  return math.min(count * beat_sec, MAX_TIME)
end

function calculate_smart_shift()
  if params:get("sync_mode") ~= 2 then return 0.0 end
  local bpm = clock.get_tempo()
  local beat_sec = 60.0 / bpm
  local nearest_grid = math.floor(state.start_beat + 0.5)
  return (nearest_grid - state.start_beat) * beat_sec
end

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

function cleanup()
  -- Intentionally left empty to prevent pthread_cancel errors
end

function redraw()
  screen.clear()
  screen.level(state.recording and 15 or 3)
  screen.move(0, 8)
  screen.text((state.recording and "REC" or "IDLE") .. " [" .. string.format("%.1f", state.duration) .. "s] C:" .. state.surface_cycles .. "/5")
  
  -- Render Geological Layers
  for i = 1, 6 do
    local y = 14 + (i * 7)
    if i <= state.layers_active then
      screen.level(math.floor(math.max(1, 11 - (i * 1.5))))
      if i == 1 then screen.move(0, y + 3); screen.line(96, y + 3); screen.stroke()
      else
        for x = 0, 96, 4 do
            local offset = (x % (3 * i)) == 0 and (math.floor(i * 0.5)) or 0
            screen.move(x, y + 3 + offset); screen.line_rel(3, 0); screen.stroke()
        end
      end
      local p = layer_phases[i] or 0.0
      screen.level(math.floor(math.max(4, 16 - (i * 2))))
      screen.rect(0 + (p * 94), y + 2, 2, 2); screen.fill()
    else
      screen.level(1); screen.move(0, y + 3); screen.line(96, y + 3); screen.stroke()
    end
  end
  
  -- Footer UI (Sync, FX, and EQ Overlays)
  if params:get("sync_mode") == 2 then
    local current_beat = math.floor(clock.get_beats()) % 16
    for i = 0, 15 do
      screen.level(i <= current_beat and 15 or 2)
      screen.rect((i * 5) + 2, 59, 3, 3)
      if i <= current_beat then screen.fill() else screen.stroke() end
    end
  end
  
  -- Tempo text
  screen.level(3)
  screen.move(128, 64)
  screen.text_right(params:get("sync_mode") == 1 and "FREE" or string.format("%.0f BPM", clock.get_tempo()))
  
  -- FX and EQ Display
  if state.shift_held then
    -- Show EQ values when shift is held
    screen.level(15)
    screen.move(0, 64)
    screen.text(string.format("EQ: H%.1f M%.1f L%.1f", state.eq_high, state.eq_mid, state.eq_low))
  elseif state.active_fx > 0 then
    -- Show active FX name when unshifted and an effect is on
    screen.level(15)
    screen.move(0, 64)
    screen.text("FX:" .. fx_names[state.active_fx+1])
  end
  
  screen.update()
end
