-- memory_physics.lua
-- ... [Keep existing initialization and state vars] ...

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
  last_activity_beat = 0 -- New tracking variable
}

-- ... [Keep init and setup_params] ...

function osc.event(path, args, from)
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
            state.last_activity_beat = math.floor(clock.get_beats()) -- Reset erosion timer
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
    -- ... [Keep loop_reset and layer_phase logic] ...
end

-- ... [Keep helper functions] ...

function key(n, z)
  if n == 1 then
    state.shift_held = (z == 1)
  elseif n == 2 and z == 1 then
    if not state.shift_held then
      state.last_activity_beat = math.floor(clock.get_beats()) -- Reset timer on manual action
      toggle_formation()
    end
  elseif n == 3 and z == 1 then
    if state.shift_held then
      -- Manual excavation as requested
      if state.layers_active > 0 then
        engine.erode_layer()
        state.layers_active = state.layers_active - 1
      end
    else
      params:set("sync_mode", params:get("sync_mode") == 1 and 2 or 1)
    end
  end
end
