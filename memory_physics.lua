-- memory_physics.lua
engine.name = 'MemoryPhysics'
local MAX_TIME = 30.0 

local state = {
  recording = false, start_time = 0, start_beat = 0, duration = 2.0,
  layers_active = 0, max_layers = 6, shift_held = false,
  surface_cycles = 0, current_amp = 0.0,
  -- Effects Engine States
  active_fx = 0, fx_p1 = 0.5, fx_p2 = 0.5, fx_p3 = 0.5,
  eq_low = 1.0, eq_mid = 1.0, eq_high = 1.0
}

function init()
  setup_params()
  -- (Keep your original OSC event handler here for looper functionality)
  -- Add these handlers inside your osc.event:
  -- if path == "/layer_phase" then ... (as original)
  -- if path == "/loop_reset" then ... (as original)
end

function setup_params()
  -- Original Config + FX parameters
  params:add_control("main_vol", "GLOBAL VOLUME", controlspec.new(0, 1, 'lin', 0.01, 0.8))
  params:set_action("main_vol", function(x) engine.main_vol(x) end)
  -- (Keep original auto-record params)
  params:bang()
end

-- Key/Enc functions updated to switch between Looper control and FX control
function enc(n, d)
  if state.shift_held then
    -- Universal EQ Control (Shift)
    if n==1 then state.eq_high = util.clamp(state.eq_high + d*0.05, 0, 2); engine.set_eq_high(state.eq_high) end
    -- ... etc for mid/low
  else
    if state.active_fx > 0 then
      if n==1 then state.fx_p1 = util.clamp(state.fx_p1 + d*0.02, 0, 1); engine.set_fx_p1(state.fx_p1) end
      -- ... etc for p2/p3
    else
      if n==1 then params:delta("main_vol", d) end
    end
  end
end
