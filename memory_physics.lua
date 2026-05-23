-- memory_physics.lua
-- Last In First Out /^\/^\
--  /^\ Geological Strata /^\
-- /^\/^\Looper/^\/^\/^\/^\
-- ____________________________
-- Key 1 - Shift
-- Key 2 - Rec Start/Stop
-- Key 3 - Quantization
-- Shift + key 3 - Excavate Surface
-- Encoder 1 - Global Volume
-- Encoder 2 - [Unassigned]
-- Encoder 3 - [Unassigned]

engine.name = 'MemoryPhysics'
local MAX_TIME = 10.0

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
local redraw_metro

function init()
  setup_params()
  
  osc.event = function(path, args, from)
    if path == "/in_amp" then
      if params:get("auto_record") == 2 then
        local amp = args[1]
        
        if not state.recording then
          if amp < (params:get("threshold") * 0.5) then
            state.auto_armed = true
          elseif amp > params:get("threshold") and state.auto_armed then
            toggle_formation()
          end
        elseif state.recording then
          if amp < (params:get("threshold") * 0.5) then
            state.silence_frames = state.silence_frames + 1
            if state.silence_frames > (params:get("release_time") * 15) then
              toggle_formation()
              state.silence_frames = 0
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
  params:add_group("MEMORY PHYSICS QUANTIZATION", 6)
  params:add_control("main_vol", "GLOBAL VOLUME", controlspec.new(0, 2, 'lin', 0.01, 1.0))
  params:set_action("main_vol", function(x) engine.set_volume(x) end)
  
  params:add_option("auto_record", "RECORD TRIGGER MODE", {"MANUAL [K2]", "AUTOMATIC [AMP]"}, 2)
  params:add_control("threshold", "AUTO THRESHOLD", controlspec.new(0.001, 1.0, 'exp', 0.001, 0.05))
  params:add_control("release_time", "AUTO TIMEOUT RELEASE (S)", controlspec.new(0.1, 5.0, 'lin', 0.1, 2.0))
  
  params:add_option("quant_mode", "QUANTIZATION STYLE", {"FREE", "BPM SYNC"}, 2)
  params:add_option("sync_div", "SYNC RESOLUTION", {"1 BEAT", "1 BAR (4/4)"}, 2)
  
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
  local mode = params:get("quant_mode")
  
  if mode == 1 then
    return math.max(0.1, math.min(raw_dur, MAX_TIME))
  end
  
  local bpm = clock.get_tempo()
  local beat_sec = 60.0 / bpm
  local div = params:get("sync_div") == 1 and 1 or 4
  local grid_sec = beat_sec * div
  
  local count = math.floor((raw_dur / grid_sec) + 0.5)
  count = math.max(1, count)
  
  local final_dur = count * grid_sec
  return math.min(final_dur, MAX_TIME)
end

function calculate_smart_shift()
  if params:get("quant_mode") ~= 2 then return 0.0 end
  
  local bpm = clock.get_tempo()
  local beat_sec = 60.0 / bpm
  local div = params:get("sync_div") == 1 and 1 or 4
  
  local nearest_grid = math.floor((state.start_beat / div) + 0.5) * div
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
      params:set("auto_record", params:get("auto_record") == 1 and 2 or 1)
    end
  end
end

function enc(n, d)
  if n == 1 then
    params:delta("main_vol", d)
  elseif n == 2 then
    -- Ready for new effects mapping
  elseif n == 3 then
    -- Ready for new effects mapping
  end
end

function cleanup()
  if redraw_metro then redraw_metro:stop() end
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
  
  -- Straight ON/OFF Flash Clock Pulse Indicator
  local current_beats = clock.get_beats()
  local beat_fraction = current_beats % 1.0
  local is_downbeat = (math.floor(current_beats) % 4 == 0)
  local clock_gate = beat_fraction < 0.5
  
  if params:get("quant_mode") == 2 and clock_gate then
    if params:get("sync_div") == 2 and not is_downbeat then
      screen.level(5)
    else
      screen.level(15)
    end
    
    screen.move(122, 53)
    screen.line(125, 50)
    screen.line(128, 53)
    screen.line(125, 56)
    screen.line(122, 53)
    screen.fill()
  end
  
  -- Quantization Style Context Info Footer
  screen.level(3)
  screen.move(128, 64)
  if params:get("quant_mode") == 1 then
    screen.text_right("FREE")
  else
    local bpm_string = string.format("%.0f BPM", clock.get_tempo())
    screen.text_right(bpm_string)
  end
  
  screen.update()
end
