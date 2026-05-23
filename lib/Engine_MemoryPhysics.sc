// lib/Engine_MemoryPhysics.sc
Engine_MemoryPhysics : CroneEngine {
    var <buffers, <synths, <recSynth, <maxLayers = 6;
    var <recBuffer, <volBus;

    *new { arg context, doneCallback; ^super.new(context, doneCallback); }

    alloc {
        buffers = Array.fill(maxLayers, { Buffer.alloc(context.server, context.server.sampleRate * 30.0, 2); });
        recBuffer = Buffer.alloc(context.server, context.server.sampleRate * 30.0, 2);
        volBus = Bus.control(context.server, 1).set(1.0);

        SynthDef(\StrataLayer, { arg buf, out, depth=0, duration=2.0, t_reset=0, shift_offset=0;
            var sig, phase, frames, layer_vol, vol_target;
            frames = duration * BufSampleRate.kr(buf);
            phase = Wrap.ar(Phasor.ar(t_reset, BufRateScale.kr(buf), 0, frames, 0) - (shift_offset * BufSampleRate.kr(buf)), 0, frames);
            sig = BufRd.ar(2, buf, phase, loop: 1);
            
            SendReply.kr(Impulse.kr(15), '/layer_phase', [depth, (A2K.kr(phase)/frames)], 998);
            SendReply.kr(HPZ1.kr(A2K.kr(phase)/frames) < -0.5, '/loop_reset', [depth], 996);

            // Pop-free smooth fade
            vol_target = Select.kr(depth, [1.0, 0.5, 0.2, 0.0, 0.0, 0.0]);
            layer_vol = VarLag.kr(vol_target, 0.5, warp: \sine); 
            Out.ar(out, sig * layer_vol * In.kr(volBus.index, 1));
        }).add;

        SynthDef(\InputTracker, { arg in;
            var mono_sum = In.ar(in, 2).sum;
            SendReply.kr(Impulse.kr(15), '/in_amp', [Amplitude.kr(mono_sum, 0.005, 0.2)], 999);
        }).add;

        SynthDef(\SurfaceRecorder, { arg buf, in; RecordBuf.ar(In.ar(in, 2), buf, recLevel: 1.0, preLevel: 0.0, loop: 0, doneAction: 0); }).add;

        context.server.sync;
        Synth(\InputTracker, [\in, context.in_b[0].index], context.xg);
        synths = Array.fill(maxLayers, { arg i; Synth(\StrataLayer, [\buf, buffers[i], \out, context.out_b.index, \depth, i], context.xg); });

        this.addCommand(\shift_layers, "ff", { arg msg;
            var idx = synths.size - 1; // Simplified for stability
            recBuffer.copyData(buffers[0]);
            synths[0].set(\duration, msg[1], \t_reset, 1, \shift_offset, msg[2]);
        });
        this.addCommand(\erode_layer, "", { 
            synths.rotate(1); 
            SystemClock.sched(0.6, { buffers[synths.size-1].zero; nil; }); 
        });
        this.addCommand(\clear_layers, "", { buffers.do(_.zero); });
        this.addCommand(\set_volume, "f", { arg msg; volBus.set(msg[1]); });
        this.addCommand(\record_start, "", { recBuffer.zero; recSynth = Synth(\SurfaceRecorder, [\buf, recBuffer, \in, context.in_b[0].index], context.xg); });
        this.addCommand(\record_stop, "", { recSynth.free; });
    }
}
