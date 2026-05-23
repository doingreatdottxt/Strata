// lib/Engine_MemoryPhysics.sc
Engine_MemoryPhysics : CroneEngine {
    var <buffers, <synths, <recSynth, <maxLayers = 6;
    var <recBuffer;
    var <volBus;
    var <durations, <synthDepths;
    var <monitorSynth;
    var <ampForwarder, <phaseForwarder, <resetForwarder, <luaAddr;

    *new { arg context, doneCallback;
        ^super.new(context, doneCallback);
    }

    alloc {
        luaAddr = NetAddr("127.0.0.1", 10111);
        
        buffers = Array.fill(maxLayers, { Buffer.alloc(context.server, context.server.sampleRate * 30.0, 2); });
        recBuffer = Buffer.alloc(context.server, context.server.sampleRate * 30.0, 2);
        
        volBus = Bus.control(context.server, 1).set(1.0);
        
        durations = Array.fill(maxLayers, { 2.0 });
        synthDepths = Array.fill(maxLayers, { arg i; i });
        
        ampForwarder = OSCFunc({ arg msg; luaAddr.sendMsg('/in_amp', msg[3]); }, '/in_amp', context.server.addr).fix;
        phaseForwarder = OSCFunc({ arg msg; luaAddr.sendMsg('/layer_phase', msg[3], msg[4]); }, '/layer_phase', context.server.addr).fix;
        resetForwarder = OSCFunc({ arg msg; luaAddr.sendMsg('/loop_reset', msg[3]); }, '/loop_reset', context.server.addr).fix;

        // 1. LIVE INPUT MONITOR
        SynthDef(\InputMonitor, { arg in, out;
            var sig = In.ar(in, 2);
            Out.ar(out, sig * In.kr(volBus.index, 1));
        }).add;

        // 2. CLEAN LAYER PLAYBACK ENGINE
        SynthDef(\StrataLayer, { arg buf, out, depth=0, duration=2.0, t_reset=0, shift_offset=0, fade_time=4.0;
            var sig, raw_phase, phase, frames, offset_frames, layer_vol;
            var norm_phase, wrapped_trig;
            
            frames = duration * BufSampleRate.kr(buf);
            offset_frames = shift_offset * BufSampleRate.kr(buf);
            
            raw_phase = Phasor.ar(t_reset, BufRateScale.kr(buf), 0, frames, 0);
            phase = Wrap.ar(raw_phase - offset_frames, 0, frames);
            sig = BufRd.ar(2, buf, phase, loop: 1);
            
            norm_phase = A2K.kr(phase) / frames;
            SendReply.kr(Impulse.kr(15), '/layer_phase', [depth, norm_phase], 998);
            
            wrapped_trig = HPZ1.kr(norm_phase) < -0.5;
            SendReply.kr(wrapped_trig, '/loop_reset', [depth], 996);

            layer_vol = Select.kr(depth, [1.0, 0.5, 0.2, 0.0, 0.0, 0.0]).lag(fade_time);
            Out.ar(out, sig * layer_vol * In.kr(volBus.index, 1));
        }).add;

        // 3. TRACKER ENGINE
        SynthDef(\InputTracker, { arg in;
            var input_signal = In.ar(in, 2);
            // Sum inputs without halving so mono Left inputs register cleanly
            var mono_sum = input_signal[0] + input_signal[1];
            // Faster 0.005 attack catches hard initial transients
            var tracked_amp = Amplitude.kr(mono_sum, 0.005, 0.2);
            
            SendReply.kr(Impulse.kr(15), '/in_amp', [tracked_amp], 999);
        }).add;

        // 4. SURFACE RECORDING ENGINE
        SynthDef(\SurfaceRecorder, { arg buf, in;
            RecordBuf.ar(In.ar(in, 2), buf, recLevel: 1.0, preLevel: 0.0, loop: 0, doneAction: 0);
        }).add;

        context.server.sync;

        Synth(\InputTracker, [\in, context.in_b[0].index], context.xg);
        
        synths = Array.fill(maxLayers, { arg i;
            Synth(\StrataLayer, [\buf, buffers[i], \out, context.out_b.index, \depth, i, \duration, 2.0], context.xg);
        });

        monitorSynth = Synth(\InputMonitor, [\in, context.in_b[0].index, \out, context.out_b.index], context.xg);

        this.addCommand(\shift_layers, "ff", { arg msg;
            var new_dur = msg[1];
            var shift_val = msg[2];
            var newLayer0SynthIndex;

            synthDepths = synthDepths.collect({ arg d; (d + 1) % maxLayers });
            newLayer0SynthIndex = synthDepths.indexOf(0);
            
            recBuffer.copyData(buffers[newLayer0SynthIndex]);
            durations[newLayer0SynthIndex] = new_dur;
            
            synths.do({ arg syn, i;
                syn.set(\depth, synthDepths[i]);
            });

            synths[newLayer0SynthIndex].set(\duration, new_dur, \t_reset, 1, \shift_offset, shift_val);
        });

        this.addCommand(\erode_layer, "", {
            var newLayer5SynthIndex;
            synthDepths = synthDepths.collect({ arg d; (d - 1).wrap(0, maxLayers - 1) });
            newLayer5SynthIndex = synthDepths.indexOf(maxLayers - 1);
            
            synths[newLayer5SynthIndex].set(\fade_time, 0.5);
            synths.do({ arg syn, i; syn.set(\depth, synthDepths[i]); });

            SystemClock.sched(0.5, {
                buffers[newLayer5SynthIndex].zero;
                durations[newLayer5SynthIndex] = 2.0;
                synths[newLayer5SynthIndex].set(\duration, 2.0, \fade_time, 4.0, \t_reset, 1);
                nil;
            });
        });

        this.addCommand(\clear_layers, "", {
            buffers.do({ arg b; b.zero; });
            recBuffer.zero;
            synthDepths = Array.fill(maxLayers, {arg i; i});
            synths.do({ arg syn, i;
                syn.set(\depth, synthDepths[i], \duration, 2.0, \t_reset, 1);
            });
        });

        this.addCommand(\set_duration, "if", { arg msg;
            durations[msg[1]] = msg[2];
            synths[msg[1]].set(\duration, msg[2]);
        });

        this.addCommand(\set_volume, "f", { arg msg; volBus.set(msg[1]); });

        this.addCommand(\record_start, "", {
            recBuffer.zero;
            recSynth = Synth(\SurfaceRecorder, [\buf, recBuffer, \in, context.in_b[0].index], context.xg);
        });

        this.addCommand(\record_stop, "", {
            recSynth.free;
        });
    }

    free {
        ampForwarder.free;
        phaseForwarder.free;
        resetForwarder.free;
        buffers.do({ arg b; b.free; });
        recBuffer.free;
        volBus.free;
    }
}
