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
       SynthDef(\FX_Router, { arg in, out, fx_type=0, p1=0.5, p2=0.5, p3=0.5;
            var sig = In.ar(in, 2);
            var monoDry = sig.sum * 0.5;
            var abyss, shatter, breeze, crackle; // <--- THESE MUST BE DECLARED HERE
            
            // Define each effect within the scope of the SynthDef
            abyss = {
                var shimmerLoop = LocalIn.ar(1) + monoDry;
                var wetAbyss;
                shimmerLoop = PitchShift.ar(shimmerLoop, 0.1, 2.0, 0.001, 0.001);
                shimmerLoop = LPF.ar(shimmerLoop, 8000);
                LocalOut.ar(shimmerLoop * (p2 * 0.85));
                wetAbyss = monoDry + (shimmerLoop * p2);
                8.do { wetAbyss = AllpassC.ar(wetAbyss, 0.1, LFNoise2.kr(0.1 + (p3 * 0.5)).range(0.01, 0.05 + (p3 * 0.04)), 1.0 + (p1 * 5.0)); };
                wetAbyss = LPF.ar(wetAbyss, 12000 - (p1 * 8000));
                XFade2.ar(sig, [wetAbyss, DelayC.ar(wetAbyss, 0.02, 0.015)], (p1 * 2) - 1);
            }.value;

            shatter = {
                var fb = LocalIn.ar(1) + monoDry;
                var clock = LFNoise0.kr(2 + (p1 * 18));
                var delayTime = SelectX.kr(p2, [0.4, clock.range(0.02, 0.4)]);
                var wetShatter = DelayC.ar(fb, 1.0, Lag.kr(delayTime, 0.05));
                wetShatter = (wetShatter * (1.0 + (p3 * 4.0))).tanh;
                wetShatter = LPF.ar(wetShatter, 10000 - (p3 * 8000));
                wetShatter = HPF.ar(wetShatter, 40 + (p3 * 400));
                LocalOut.ar(wetShatter * (0.6 + (p2 * 0.25)));
                XFade2.ar(sig, wetShatter ! 2, (p2 * 2) - 1); 
            }.value;

            breeze = {
                var chorused = DelayC.ar(monoDry, 0.2, SinOsc.kr(0.5 + (p1 * 2.0)).range(0.005, 0.01 + (p1 * 0.02)));
                var wetBreeze = FreeVerb.ar(HPF.ar(chorused, 800 + (p1 * 400)), 1.0, 0.7 + (p2 * 0.29), 0.1);
                wetBreeze = Pan2.ar(wetBreeze, SinOsc.kr(0.1 + (p1 * 0.2)));
                XFade2.ar(sig, wetBreeze, (p3 * 2) - 1);
            }.value;

            crackle = {
                var rhythm = Decay2.ar(Mix([Impulse.ar(8 + (p1 * 12)), Dust.ar(10 + (p1 * 20))]), 0.001, 0.03);
                var echo = CombC.ar(monoDry * rhythm, 0.2, 0.01 + ((1.0 - p2) * 0.05), 0.5 + (p2 * 1.5));
                var wetCrackle = HPF.ar(echo, 1500) ! 2;
                XFade2.ar(sig, wetCrackle, (p3 * 2) - 1);
            }.value;

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
