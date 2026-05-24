// lib/Engine_MemoryPhysics.sc
Engine_MemoryPhysics : CroneEngine {
    var <buffers, <synths, <recSynth, <maxLayers = 6;
    var <recBuffer, <volBus;
    var <fxBus, <eqBus, <fxSynth, <eqSynth;

    *new { arg context, doneCallback; ^super.new(context, doneCallback); }

    alloc {
        // --- Strata Allocations ---
        buffers = Array.fill(maxLayers, { Buffer.alloc(context.server, context.server.sampleRate * 30.0, 2); });
        recBuffer = Buffer.alloc(context.server, context.server.sampleRate * 30.0, 2);
        volBus = Bus.control(context.server, 1).set(1.0);
        
        // --- FX/EQ Buses ---
        fxBus = Bus.audio(context.server, 2);
        eqBus = Bus.audio(context.server, 2);

        // --- Strata Layer SynthDef ---
        SynthDef(\StrataLayer, { arg buf, out, depth=0, duration=2.0, t_reset=0, shift_offset=0;
            var sig, phase, frames, layer_vol, vol_target;
            frames = duration * BufSampleRate.kr(buf);
            phase = Wrap.ar(Phasor.ar(t_reset, BufRateScale.kr(buf), 0, frames, 0) - (shift_offset * BufSampleRate.kr(buf)), 0, frames);
            sig = BufRd.ar(2, buf, phase, loop: 1);
            SendReply.kr(Impulse.kr(15), '/layer_phase', [depth, (A2K.kr(phase)/frames)], 998);
            SendReply.kr(HPZ1.kr(A2K.kr(phase)/frames) < -0.5, '/loop_reset', [depth], 996);
            vol_target = Select.kr(depth, [1.0, 0.5, 0.2, 0.0, 0.0, 0.0]);
            layer_vol = VarLag.kr(vol_target, 0.5, warp: \sine); 
            Out.ar(out, sig * layer_vol * In.kr(volBus.index, 1));
        }).add;

        // --- FX_Router and MasterEQ (Same as previously defined) ---
        SynthDef(\MasterEQ, { arg in, out, lowGain=1.0, midGain=1.0, highGain=1.0, amp=0.8;
            var sig = In.ar(in, 2);
            var b0 = LPF.ar(sig, 80);
            var b1 = BPF.ar(sig, 250, 1.0);
            var b2 = BPF.ar(sig, 1000, 1.0);
            var b3 = BPF.ar(sig, 4000, 1.0);
            var b4 = HPF.ar(sig, 8000);
            Out.ar(out, ((b0*lowGain) + ((b1+b2+b3)*midGain) + (b4*highGain)) * amp);
        }).add;

        SynthDef(\FX_Router, { arg in, out, fx_type=0, p1=0.5, p2=0.5, p3=0.5;
            var sig = In.ar(in, 2);
            var monoDry = sig.sum * 0.5;
            // ... (Insert Abyss/Shatter/Breeze/Crackle blocks here as before) ...
            Out.ar(out, Select.ar(fx_type, [sig, abyss, shatter, breeze, crackle]));
        }).add;

        context.server.sync;

        // --- Routing: Layers -> FX Bus -> FX Synth -> EQ Bus -> EQ Synth -> Out ---
        synths = Array.fill(maxLayers, { arg i; Synth(\StrataLayer, [\buf, buffers[i], \out, fxBus, \depth, i], context.xg); });
        fxSynth = Synth.tail(context.server, \FX_Router, [\in, fxBus, \out, eqBus]);
        eqSynth = Synth.tail(context.server, \MasterEQ, [\in, eqBus, \out, context.out_b.index]);

        // --- Commands ---
        this.addCommand(\shift_layers, "ff", { arg msg; recBuffer.copyData(buffers[0]); synths[0].set(\duration, msg[1], \t_reset, 1, \shift_offset, msg[2]); });
        this.addCommand(\erode_layer, "", { synths.rotate(1); SystemClock.sched(0.6, { buffers[synths.size-1].zero; nil; }); });
        this.addCommand(\clear_layers, "", { buffers.do(_.zero); });
        this.addCommand(\set_volume, "f", { arg msg; volBus.set(msg[1]); });
        this.addCommand(\main_vol, "f", { arg msg; eqSynth.set(\amp, msg[1]); });
        // (Add your select_fx, set_fx_p, set_eq commands here)
    }
}
