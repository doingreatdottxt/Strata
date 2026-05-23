-- memory_physics.lua
-- Last In First Out /^\/^\
--  /^\ Geological Strata /^\
-- /^\/^\Looper/^\/^\/^\/^\
-- ____________________________
-- Key 1 - Shift
-- Key 2 - Rec Start/Stop
-- Key 3 - Toggle Sync Mode (Free / Beat)
-- Shift + key 3 - Excavate Surface
-- Encoder 1 - Global Volume
-- Encoder 2 - [Unassigned]
-- Encoder 3 - [Unassigned]

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
  auto_armed = true 
}

local layer_phases = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0}
local redraw_metro = nil

function init()
  setup_params()
  
  osc.event = function(path, args, from)
    if path == "/in_amp" then
      if params:get("auto_record") == 2 then
        local amp = args[1]
        
        if not state.recording then
          -- Arming gate: sound must fall well below threshold to re-arm
          if amp < (params:get("threshold") * 0.5) then
            state.auto_armed = true
          elseif amp > params:get("threshold") and state.auto_armed then
            toggle_formation()
          end
        elseif state.recording then
          -- Drop-off gate: lowered to 0.25 to capture long instrument decay tails
          if amp < (params:get("threshold") * 0.25) then
            state.silence_frames = state.silence_frames + 1
            
            if state.silence_frames > (params:get("release_time") * 15) then
              -- Minimum loop safeguard: Force at least 1.0 second of audio
              if (util.time() - state.start_time) > 1.0 then
                toggle_formation()
                state.silence_frames = 0
                state.auto_armed = false 
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
  
  redraw_metro = metro.init(function() redraw() end, 1/15)
  redraw_metro:start()
end

function setup_params()
  params:add_group("MEMORY PHYSICS CONFIG", 5)
  params:add_control("main_vol", "GLOBAL VOLUME", controlspec.new(0, 2, 'lin', 0.01, 1.0))
  params:set_action("main_vol", function(x) engine.set_volume(x) end)
  
  params:add_option("auto_record", "RECORD TRIGGER MODE", {"MANUAL [K2]", "AUTOMATIC [AMP]"}, 2)
  params:add_control("threshold", "AUTO THRESHOLD", controlspec.new(0.001, 1.0, 'exp', 0.001, 0.05))
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
  if params:get("sync_mode") == 1 then
    return math.max(0.1, math.min(raw_dur, MAX_TIME))
  end
  
  local bpm = clock.get_tempo()
  local beat_sec = 60.0 / bpm
  
  local count = math.floor((raw_dur / beat_sec) + 0.5)
  count = util.clamp(count, 1, 16) 
  
  local final_dur = count * beat_sec
  return math.min(final_dur, MAX_TIME)
end

function calculate_smart_shift()
  if params:get("sync_mode") ~= 2 then return 0.0 end
  
  local bpm = clock.get_tempo()
  local beat_sec = 60.0 / bpm
  
  local nearest_grid = math.floor(state.start_beat + 0.5)
  local beat_diff = nearest_grid - state.start_beat 
  
  return beat_diff * beat_sec
end

function toggle_formation()
  if not state.recording then
    state.surface_cycles = 0
    state.start_time = util.time()
    state.start_beat = clock.get_beats() 
    engine.record_start()
    state.recording = true
  else
    state.recording = false
    local measured_dur = util.time() - state.start_time
    state.duration = calculate_quantized_duration(measured_dur)
    local smart_shift_offset = calculate_smart_shift()
    
    engine.record_stop()
    engine.shift_layers(state.duration, smart_shift_offset)
    state.layers_active = math.min(state.max_layers, state.layers_active + 1)
  end
end

function key(n, z)
  if n == 1 then
    state.shift_held = (z == 1)
  elseif n == 2 and z == 1 then
    if not state.shift_held then
      toggle_formation()
      if not state.recording and params:get("auto_record") == 2 then
        state.auto_armed = false
      end
    end
  elseif n == 3 and z == 1 then
    if state.shift_held then
      if state.layers_active > 0 then
        engine.erode_layer()
        state.layers_active = state.layers_active - 1
        state.surface_cycles = 0
      end
    else
      params:set("sync_mode", params:get("sync_mode") == 1 and 2 or 1)
    end
  end
end

function enc(n, d)
  if n == 1 then
    params:delta("main_vol", d)
  end
end

function cleanup()
  if redraw_metro ~= nil then 
    redraw_metro:stop()
    redraw_metro = nil
  end
end

function redraw()
  screen.clear()
  screen.level(state.recording and 15 or 3)
  screen.move(0, 8)
  local msg = state.recording and "FORMING STRATA" or "STABLE"
  screen.text(msg .. " [" .. string.format("%.2f", state.duration) .. "s] C:" .. state.surface_cycles .. "/5")
  
  -- Render Geological Layers
  for i = 1, 6 do
    local y = 14 + (i * 7)
    if i <= state.layers_active then
      screen.level(math.floor(math.max(1, 11 - (i * 1.5))))
      if i == 1 then
        screen.move(0, y + 3)
        screen.line(96, y + 3)
        screen.stroke()
      else
        for x = 0, 96, 4 do
          local offset = (x % (3 * i)) == 0 and (math.floor(i * 0.5)) or 0
          screen.move(x, y + 3 + offset)
          screen.line_rel(3, 0)
          screen.stroke()
        end
      end
      local p = layer_phases[i] or 0.0
      screen.level(math.floor(math.max(4, 16 - (i * 2))))
      screen.rect(0 + (p * 94), y + 2, 2, 2)
      screen.fill()
    else
      screen.level(1)
      screen.move(0, y + 3)
      screen.line(96, y + 3)
      screen.stroke()
    end
  end
  
  -- Footer UI Clean Context Segmentation
  if params:get("sync_mode") == 1 then
    screen.level(3)
    screen.move(128, 64)
    screen.text_right("FREE")
  else
    local current_beat = math.floor(clock.get_beats()) % 16
    
    for i = 0, 15 do
      local x = (i * 5) + 2 
      local y = 59
      if i <= current_beat then
        screen.level(15)
        screen.rect(x, y, 3, 3)
        screen.fill()
      else
        screen.level(2)
        screen.rect(x, y, 3, 3)
        screen.stroke()
      end
    end
    
    screen.level(3)
    screen.move(128, 64)
    local bpm_string = string.format("%.0f BPM", clock.get_tempo())
    screen.text_right(bpm_string)
  end
  
  screen.update()
end
