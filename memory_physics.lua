-- Memory Physics
--
-- K2: Hold to Record, Release to Loop
-- K3: Erode oldest layer
-- K1 + K3: Clear all layers
--
-- E1: Select FX Mode
-- E2: FX Parameter 1
-- E3: FX Parameter 2
-- K1 + E2: FX Mix (Wet/Dry)

engine.name = "MemoryPhysics"

local fx_names = {"Bypass", "Abyss", "Shatter", "Breeze", "Crackle", "Pulse"}
local active_fx = 1

-- State tracking
local is_recording = false
local shift_pressed = false
local rec_start_time = 0

-- Visualizer variables updated via OSC
local in_level = 0
local layer_phases = {0, 0, 0, 0, 0, 0}

function init()
  -- --- 1. Parameters ---
  params:add_separator("Memory Physics")
  
  params:add_control("main_vol", "Main Vol", controlspec.new(0, 2, 'lin', 0.01, 1.0, ""))
  params:set_action("main_vol", function(v) engine.main_vol(v) end)

  params:add_option("fx_type", "FX Type", fx_names, 1)
  params:set_action("fx_type", function(v) 
    active_fx = v
    engine.select_fx(v - 1) -- SC arrays are 0-indexed
  end)

  params:add_control("fx_p1", "FX P1 (Rate/Time)", controlspec.new(0, 1, 'lin', 0.01, 0.5, ""))
  params:set_action("fx_p1", function(v) engine.set_fx_p1(v) end)

  params:add_control("fx_p2", "FX P2 (Shape/FB)", controlspec.new(0, 1, 'lin', 0.01, 0.5, ""))
  params:set_action("fx_p2", function(v) engine.set_fx_p2(v) end)

  params:add_control("fx_p3", "FX P3 (Mix)", controlspec.new(0, 1, 'lin', 0.01, 0.5, ""))
  params:set_action("fx_p3", function(v) engine.set_fx_p3(v) end)
  
  -- Master EQ
  params:add_group("Master EQ", 3)
  params:add_control("eq_low", "Low", controlspec.new(0, 2, 'lin', 0.01, 1.0, ""))
  params:set_action("eq_low", function(v) engine.set_eq_low(v) end)
  params:add_control("eq_mid", "Mid", controlspec.new(0, 2, 'lin', 0.01, 1.0, ""))
  params:set_action("eq_mid", function(v) engine.set_eq_mid(v) end)
  params:add_control("eq_high", "High", controlspec.new(0, 2, 'lin', 0.01, 1.0, ""))
  params:set_action("eq_high", function(v) engine.set_eq_high(v) end)

  -- --- 2. Tempo Sync ---
  -- Send initial norns tempo to SC
  engine.set_bpm(clock.get_tempo())
  
  -- Update SC whenever the norns global clock changes
  clock.tempo_change_handler = function(bpm)
    engine.set_bpm(bpm)
  end

  -- --- 3. OSC Listeners ---
  osc.event = function(path, args, from)
    if path == '/in_amp' then
      in_level = args[1]
    elseif path == '/layer_phase' then
      local layer_idx = args[1] + 1 -- Convert SC 0-index to Lua 1-index
      layer_phases[layer_idx] = args[2]
    end
  end

  -- --- 4. Screen Refresh Metro ---
  local screen_metro = metro.init()
  screen_metro.time = 1/15 -- 15 fps
  screen_metro.event = function() redraw() end
  screen_metro:start()
end

-- --- Hardware Interactions ---

function key(n, z)
  -- K1: Shift key modifier
  if n == 1 then
    shift_pressed = (z == 1)
  
  -- K2: Record/Loop
  elseif n == 2 then
    if z == 1 then
      is_recording = true
      rec_start_time = util.time()
      engine.record_start()
    else
      is_recording = false
      engine.record_stop()
      -- Calculate exactly how long K2 was held and pass it as the loop duration
      local duration = util.time() - rec_start_time
      engine.shift_layers(duration, 0.0) 
    end
  
  -- K3: Erode / Clear
  elseif n == 3 then
    if z == 1 then
      if shift_pressed then
        engine.clear_layers()
        -- Reset UI playheads instantly
        layer_phases = {0, 0, 0, 0, 0, 0}
      else
        engine.erode_layer()
      end
    end
  end
end

function enc(n, d)
  -- E1: Select FX
  if n == 1 then
    params:delta("fx_type", d)
  
  -- E2: P1 or Mix (if Shift is held)
  elseif n == 2 then
    if shift_pressed then
      params:delta("fx_p3", d) 
    else
      params:delta("fx_p1", d)
    end
  
  -- E3: P2
  elseif n == 3 then
    params:delta("fx_p2", d)
  end
end

-- --- Screen Drawing ---

function redraw()
  screen.clear()
  
  -- 1. Input Level Indicator (Left edge)
  screen.level(is_recording and 15 or 2)
  local bar_height = math.min(math.floor(in_level * 128), 64)
  screen.rect(0, 64 - bar_height, 3, bar_height)
  screen.fill()

  if is_recording then
    screen.level(15)
    screen.move(6, 10)
    screen.text("REC...")
  end

  -- 2. Layer Playheads
  for i=1, 6 do
    local y_pos = i * 8
    -- Older layers get darker to represent volume decay
    screen.level(math.floor(15 - (i * 2))) 
    
    screen.move(12, y_pos)
    screen.line(12 + (layer_phases[i] * 110), y_pos)
    screen.stroke()
  end

  -- 3. FX HUD (Bottom)
  screen.level(4)
  screen.move(12, 60)
  screen.line(128, 60)
  screen.stroke()

  screen.level(15)
  screen.move(12, 56)
  screen.text(fx_names[active_fx])
  
  -- Show params
  screen.level(shift_pressed and 4 or 15)
  screen.move(56, 56)
  screen.text("P1:" .. string.format("%.2f", params:get("fx_p1")))
  
  screen.level(15)
  screen.move(96, 56)
  screen.text("P2:" .. string.format("%.2f", params:get("fx_p2")))

  -- Show mix if shift is held
  if shift_pressed then
    screen.level(15)
    screen.move(56, 64)
    screen.text("MIX:" .. string.format("%.2f", params:get("fx_p3")))
  end

  screen.update()
end
