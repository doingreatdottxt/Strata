-- memory_physics.lua
-- Last In First Out /^\/^\
-- Geological Strata Looper

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
  last_activity_beat = 0 
}

local layer_phases = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0}
local redraw_metro = nil

function init()
  setup_params()
  
  osc.event = function(path, args, from)
    if path == "/in_amp" then
      state.current_amp = args[1]
      
      -- Auto-Erosion: Erode if no activity for 16 beats
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
          if amp < (params:get("threshold") * 0.7) then state.auto_armed = true
          elseif amp >= params:get("threshold") and state.auto_armed then
            state.last_activity_beat = math.floor(clock.get_beats())
            toggle_formation()
          end
        elseif state.recording then
          if amp < (params:get("threshold") * 0.5) then
            state.silence_frames = state.silence_frames + 1
            if state.silence_frames > (params:get("release_time") * 15) and (util.time() - state.start_time) > 1.0 then
              toggle_formation()
              state.silence_frames = 0
            end
          else state.silence_frames = 0 end
        end
      end
      
    elseif path == "/layer_phase" then
      local layer_idx = math.floor(args[1] + 1)
      local phase_val = args[2]
      if layer_idx >= 1 and layer_idx <= state.max_layers then layer_phases[layer_idx] = phase_val end
      
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
    local bpm = clock.get_tempo()
    local beat_sec = 60.0 / bpm
    local dur = (params:get("sync_mode") == 1) and measured_dur or (math.max(1, math.floor((measured_dur / beat_sec) + 0.5)) * beat_sec)
    state.duration = math.min(dur, MAX_TIME)
    engine.record_stop()
    engine.shift_layers(state.duration, (params:get("sync_mode") == 1) and 0.0 or ((math.floor(state.start_beat + 0.5) - state.start_beat) * beat_sec))
    state.layers_active = math.min(state.max_layers, state.layers_active + 1)
  end
end

function key(n, z)
  if n == 1 then state.shift_held = (z == 1)
  elseif n == 2 and z == 1 and not state.shift_held then toggle_formation()
  elseif n == 3 and z == 1 then
    if state.shift_held and state.layers_active > 0 then
      engine.erode_layer()
      state.layers_active = state.layers_active - 1
      state.surface_cycles = 0
    elseif not state.shift_held then params:set("sync_mode", params:get("sync_mode") == 1 and 2 or 1) end
  end
end

function enc(n, d) if n == 1 then params:delta("main_vol", d) end end

function cleanup() end

function redraw()
  screen.clear()
  screen.level(state.recording and 15 or 3)
  screen.move(0, 8); screen.text((state.recording and "REC" or "IDLE") .. " [" .. string.format("%.1f", state.duration) .. "s]")
  -- Input Meter
  if params:get("auto_record") == 2 then
    local w = 30; local x = 96
    screen.level(2); screen.rect(x, 2, w, 6); screen.stroke()
    screen.level(10); screen.rect(x, 2, util.clamp((state.current_amp / 0.2) * w, 0, w), 6); screen.fill()
  end
  for i = 1, 6 do
    local y = 14 + (i * 7)
    if i <= state.layers_active then
      screen.level(math.max(1, 11 - i)); screen.move(0, y + 3); screen.line(96, y + 3); screen.stroke()
      screen.level(math.max(4, 16 - (i * 2))); screen.rect(0 + ((layer_phases[i] or 0) * 94), y + 2, 2, 2); screen.fill()
    else screen.level(1); screen.move(0, y + 3); screen.line(96, y + 3); screen.stroke() end
  end
  if params:get("sync_mode") == 2 then
    local b = math.floor(clock.get_beats()) % 16
    for i = 0, 15 do screen.level(i <= b and 15 or 2); screen.rect((i * 5) + 2, 59, 3, 3); if i <= b then screen.fill() else screen.stroke() end end
  end
  screen.level(3); screen.move(128, 64); screen.text_right(params:get("sync_mode") == 1 and "FREE" or string.format("%.0f BPM", clock.get_tempo()))
  screen.update()
end
