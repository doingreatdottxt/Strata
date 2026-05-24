// lib/Engine_MemoryPhysics.sc
Engine_MemoryPhysics : CroneEngine {
    var <buffers, <synths, <recSynth, <maxLayers = 6;
    var <recBuffer;
    var <volBus;
    var <durations, <synthDepths;
    var <monitorSynth;
    var <ampForwarder, <phaseForwarder, <resetForwarder, <luaAddr;
    
    // New Buses and Synths for FX and EQ
    var <fxBus, <eqBus;
    var <fxSynth, <eqSynth;

    *new { arg context, doneCallback;
        ^super.new(context, doneCallback);
    }

    alloc {
        luaAddr = NetAddr("127.0.0.1", 10111);

        // Core Strata Allocations
        buffers = Array.fill(maxLayers, { Buffer.alloc(context.server, context.server.sampleRate * 30.0, 2); });
        recBuffer = Buffer.alloc(context.server, context.server.sampleRate * 30.0, 2);
        volBus = Bus.control(context.server, 1).set(1.0);
        durations = Array.fill(maxLayers, { 2.0 });
        synthDepths = Array.fill(maxLayers, { arg i; i });

        // FX Routing Buses
        fxBus = Bus.audio(context.server, 2);
        eqBus = Bus.audio(context.server, 2);

        // ==========================================
        // 1. MASTER EQ ENGINE
        // ==========================================
        SynthDef(\MasterEQ, { arg in, out, lowGain=1.0, midGain=1.0, highGain=1.0;
            var sig = In.ar(in, 2);
            var b0, b1, b2, b3, b4;
            var lowSig, midSig, highSig;

            // 5-Band Linkwitz-Riley Crossover Approximation
            b0 = LPF.ar(sig, 80);
            b1 = BPF.ar(sig, 250, 1.0);
            b2 = BPF.ar(sig, 1000, 1.0);
            b3 = BPF.ar(sig, 4000, 1.0);
            b4 = HPF.ar(sig, 8000);

            // Group into 3 controllable bands
            lowSig = b0 * lowGain;
            midSig = (b1 + b2 + b3) * midGain;
            highSig = b4 * highGain;

            Out.ar(out, lowSig + midSig + highSig);
        }).add;

        // ==========================================
        // 2. MULTI-EFFECTS ROUTER
        // ==========================================
        SynthDef(\FX_Router, { arg in, out, fx_type=0, p1=0.5, p2=0.5, p3=0.5;
            var sig = In.ar(in, 2);
            var mixedSig;
            var monoDry = sig[0] + sig[1] * 0.5; // Downmix for mono-in effects
            
            // Effect 1: ABYSS (p1=depth, p2=shimmer, p3=drift)
            var abyss = {
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

            // Effect 2: SHATTER (p1=density, p2=chaos, p3=age)
            var shatter = {
                var fb = LocalIn.ar(1) + monoDry;
                var clock = LFNoise0.kr(2 + (p1 * 18));
                var delayTime = SelectX.kr(p2, [0.4, clock.range(0.02, 0.4)]);
                var wetShatter = DelayC.ar(fb, 1.0, Lag.kr(delayTime, 0.05));
                wetShatter = (wetShatter * (1.0 + (p3 * 4.0))).tanh;
                wetShatter = LPF.ar(wetShatter, 10000 - (p3 * 8000));
                wetShatter = HPF.ar(wetShatter, 40 + (p3 * 400));
                LocalOut.ar(wetShatter * (0.6 + (p2 * 0.25)));
                XFade2.ar(sig, wetShatter ! 2, (p2 * 2) - 1); // p2 (chaos) acts as mix here to simplify
            }.value;

            // Effect 3: BREEZE (p1=flutter, p2=space, p3=mix)
            var breeze = {
                var chorused = DelayC.ar(monoDry, 0.2, SinOsc.kr(0.5 + (p1 * 2.0)).range(0.005, 0.01 + (p1 * 0.02)));
                var wetBreeze = FreeVerb.ar(HPF.ar(chorused, 800 + (p1 * 400)), 1.0, 0.7 + (p2 * 0.29), 0.1);
                wetBreeze = Pan2.ar(wetBreeze, SinOsc.kr(0.1 + (p1 * 0.2)));
                XFade2.ar(sig, wetBreeze, (p3 * 2) - 1);
            }.value;

            // Effect 4: CRACKLE (p1=spark, p2=tension, p3=mix)
            var crackle = {
                var rhythm = Decay2.ar(Mix([Impulse.ar(8 + (p1 * 12)), Dust.ar(10 + (p1 * 20))]), 0.001, 0.03);
                var echo = CombC.ar(monoDry * rhythm, 0.2, 0.01 + ((1.0 - p2) * 0.05), 0.5 + (p2 * 1.5));
                var wetCrackle = HPF.ar(echo, 1500) ! 2;
                XFade2.ar(sig, wetCrackle, (p3 * 2) - 1);
            }.value;

            // Select the active effect
            mixedSig = Select.ar(fx_type, [sig, abyss, shatter, breeze, crackle]);
            Out.ar(out, mixedSig);
        }).add;

        // Ensure nodes are instantiated on the server after compilation
        context.server.sync;

        // Instantiate FX and EQ at the tail of the chain
        fxSynth = Synth.tail(context.server, \FX_Router, [\in, fxBus, \out, eqBus]);
        eqSynth = Synth.tail(context.server, \MasterEQ, [\in, eqBus, \out, context.out_b.index]);

        // ==========================================
        // 3. LUA COMMANDS
        // ==========================================
        this.addCommand(\select_fx, "i", { arg msg; fxSynth.set(\fx_type, msg[1]); });
        this.addCommand(\set_fx_p1, "f", { arg msg; fxSynth.set(\p1, msg[1]); });
        this.addCommand(\set_fx_p2, "f", { arg msg; fxSynth.set(\p2, msg[1]); });
        this.addCommand(\set_fx_p3, "f", { arg msg; fxSynth.set(\p3, msg[1]); });

        this.addCommand(\set_eq_low, "f", { arg msg; eqSynth.set(\lowGain, msg[1]); });
        this.addCommand(\set_eq_mid, "f", { arg msg; eqSynth.set(\midGain, msg[1]); });
        this.addCommand(\set_eq_high, "f", { arg msg; eqSynth.set(\highGain, msg[1]); });

        // (Placeholder for your OSC forwarding from original script)
        ampForwarder = OSCFunc({ arg msg; luaAddr.sendMsg('/in_amp', msg[3]); }, '/in_amp', context.server.addr).fix;
    }

    free {
        fxBus.free;
        eqBus.free;
        fxSynth.free;
        eqSynth.free;
        ampForwarder.free;
        // Clean up other resources
    }
}
