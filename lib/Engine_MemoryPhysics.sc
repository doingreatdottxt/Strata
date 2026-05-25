// lib/Engine_MemoryPhysics.sc
Engine_MemoryPhysics : CroneEngine {
	var <buffers, <synths, <recSynth, <maxLayers = 6;
	var <recBuffer, <volBus, <tempoBus;
	var <fxBus, <eqBus, <fxSynth, <eqSynth;

	*new { arg context, doneCallback;
		^super.new(context, doneCallback);
	}

	alloc {
		// --- 1. Allocations ---
		buffers = Array.fill(maxLayers, {
			Buffer.alloc(context.server, context.server.sampleRate * 30.0, 2);
		});
		recBuffer = Buffer.alloc(context.server, context.server.sampleRate * 30.0, 2);
		volBus = Bus.control(context.server, 1).set(1.0);
		
		// NEW: Tempo Bus initialized to 120 BPM (2.0 Beats Per Second)
		tempoBus = Bus.control(context.server, 1).set(2.0); 
		
		fxBus = Bus.audio(context.server, 2);
		eqBus = Bus.audio(context.server, 2);

		// --- 2. SynthDefs ---
		SynthDef(\StrataLayer, { arg buf, out, depth=0, duration=2.0, t_reset=0, shift_offset=0;
			var sig, phase, frames, layer_vol, vol_target;
			frames = duration * BufSampleRate.kr(buf);
			phase = Wrap.ar(Phasor.ar(t_reset, BufRateScale.kr(buf), 0, frames, 0) - (shift_offset * BufSampleRate.kr(buf)), 0, frames);
			sig = BufRd.ar(2, buf, phase, loop: 1);
			
			SendReply.kr(Impulse.kr(15), '/layer_phase', [depth, (A2K.kr(phase)/frames)], 998);
			SendReply.kr(HPZ1.kr(A2K.kr(phase)/frames) < -0.5, '/loop_reset', [depth], 996);
			
			vol_target = Select.kr(depth, [1.0, 0.6, 0.3, 0.1, 0.0, 0.0]);
			layer_vol = VarLag.kr(vol_target, 0.5, warp: \sine);
			Out.ar(out, sig * layer_vol * In.kr(volBus.index, 1));
		}).add;

		SynthDef(\InputTracker, { arg in;
			var mono_sum = In.ar(in, 2).sum;
			SendReply.kr(Impulse.kr(15), '/in_amp', [Amplitude.kr(mono_sum, 0.005, 0.2)], 999);
		}).add;

		SynthDef(\SurfaceRecorder, { arg buf, in;
			RecordBuf.ar(In.ar(in, 2), buf, recLevel: 1.0, preLevel: 0.0, loop: 0, doneAction: 0);
		}).add;

		SynthDef(\MasterEQ, { arg in, out, lowGain=1.0, midGain=1.0, highGain=1.0, amp=0.8;
			var sig = In.ar(in, 2);
			
			// Convert Lua's linear gain (0.0 to 2.0) into Decibels.
			// max(0.01) creates a -40dB kill switch when an encoder is at 0.
			var lowDb = lowGain.max(0.01).ampdb;
			var midDb = midGain.max(0.01).ampdb;
			var highDb = highGain.max(0.01).ampdb;
			
			// True 3-Band Parametric EQ
			sig = BLowShelf.ar(sig, freq: 300, rs: 1.0, db: lowDb);
			sig = BPeakEQ.ar(sig, freq: 1200, rq: 1.2, db: midDb);
			sig = BHiShelf.ar(sig, freq: 3500, rs: 1.0, db: highDb);
			
			Out.ar(out, sig * amp);
		}).add;

		SynthDef(\FX_Bypass, { arg in, out;
			Out.ar(out, In.ar(in, 2));
		}).add;

		SynthDef(\FX_Abyss, { arg in, out, p1=0.5, p2=0.5, p3=0.5;
			var sig = In.ar(in, 2);
			var monoDry = (sig.sum * 0.5) + WhiteNoise.ar(1e-8); 
			var sp1_fast = Lag.kr(p1, 0.05); 
			var sp1_slow = Lag.kr(p1, 0.8);  
			var sp2 = Lag.kr(p2, 0.05);
			var sp3 = Lag.kr(p3, 0.05);
			var shimmerLoop = LocalIn.ar(1) + monoDry;
			var wetAbyss;

			shimmerLoop = PitchShift.ar(shimmerLoop, 0.1, 2.0, 0.001, 0.001);
			shimmerLoop = LPF.ar(shimmerLoop, 8000);
			
			shimmerLoop = LeakDC.ar(shimmerLoop);
			shimmerLoop = shimmerLoop.tanh;
			LocalOut.ar(shimmerLoop * (sp2 * 0.85));

			wetAbyss = (monoDry + (shimmerLoop * sp2)) * 0.4;

			8.do {
				var badFirewall;
				wetAbyss = AllpassC.ar(
					wetAbyss, 
					0.1, 
					LFNoise2.kr(0.1 + (sp3 * 0.5)).range(0.01, 0.05 + (sp3 * 0.04)), 
					1.0 + (sp1_slow * 5.0)
				);
				
				badFirewall = CheckBadValues.ar(wetAbyss, id: 0, post: 0);
				wetAbyss = Select.ar(badFirewall.min(1), [wetAbyss, DC.ar(0.0)]);
			};

			wetAbyss = LPF.ar(wetAbyss, 12000 - (sp1_fast * 8000));
			wetAbyss = wetAbyss.tanh; 
			wetAbyss = Limiter.ar(wetAbyss * 1.4, 0.95, 0.01);

			Out.ar(out, XFade2.ar(sig, [wetAbyss, DelayC.ar(wetAbyss, 0.02, 0.015)], (sp1_fast * 2) - 1));
		}).add;

		SynthDef(\FX_Shatter, { arg in, out, p1=0.5, p2=0.5, p3=0.5;
			var sig = In.ar(in, 2);
			var sp1 = Lag.kr(p1, 0.05);
			var sp2 = Lag.kr(p2, 0.05);
			var sp3 = Lag.kr(p3, 0.05);
			var monoDry = sig.sum * 0.5;
			var fb = LocalIn.ar(1) + monoDry;
			var clock = LFNoise0.kr(2 + (sp1 * 18));
			var delayTime = SelectX.kr(sp2, [0.4, clock.range(0.02, 0.4)]);
			var wetShatter = DelayC.ar(fb, 1.0, Lag.kr(delayTime, 0.05));
			
			wetShatter = LeakDC.ar(wetShatter);
			wetShatter = LPF.ar(wetShatter, 7000 - (sp3 * 5000));
			wetShatter = HPF.ar(wetShatter, 60 + (sp3 * 300));
			
			LocalOut.ar(wetShatter * (0.35 + (sp2 * 0.30)));

			wetShatter = (wetShatter * (1.0 + (sp3 * 2.5))).tanh; 

			Out.ar(out, XFade2.ar(sig, (wetShatter * 0.9) ! 2, (sp2 * 2) - 1));
		}).add;

		SynthDef(\FX_Breeze, { arg in, out, p1=0.5, p2=0.5, p3=0.5;
			var sig = In.ar(in, 2);
			var sp1 = Lag.kr(p1, 0.05);
			var sp2 = Lag.kr(p2, 0.05);
			var sp3 = Lag.kr(p3, 0.05);
			var monoDry = sig.sum * 0.5;
			var chorused = DelayC.ar(monoDry, 0.2, SinOsc.kr(0.5 + (sp1 * 2.0)).range(0.005, 0.01 + (sp1 * 0.02)));
			var wetBreeze = FreeVerb.ar(HPF.ar(chorused, 800 + (sp1 * 400)), 1.0, 0.7 + (sp2 * 0.29), 0.1);
			
			wetBreeze = Pan2.ar(wetBreeze, SinOsc.kr(0.1 + (sp1 * 0.2)));
			wetBreeze = Limiter.ar(wetBreeze * 5.0, 0.95, 0.01);

			Out.ar(out, XFade2.ar(sig, wetBreeze, (sp3 * 2) - 1));
		}).add;

		SynthDef(\FX_Crackle, { arg in, out, p1=0.5, p2=0.5, p3=0.5;
			var sig = In.ar(in, 2);
			var sp1 = Lag.kr(p1, 0.05);
			var sp2 = Lag.kr(p2, 0.05);
			var sp3 = Lag.kr(p3, 0.05);
			var monoDry = sig.sum * 0.5;
			
			var probability = (sp1 * 0.8) + 0.2; 
			var trigger = K2A.ar(Dust.kr(20) > (1.0 - probability));
			var rhythm = Decay2.ar(trigger, 0.001, 0.03);
			
			var echo = CombC.ar(monoDry * rhythm, 0.2, 0.01 + ((1.0 - sp2) * 0.05), 0.2 + (sp2 * 1.0));
			var wetCrackle; 
			
			echo = Select.ar(CheckBadValues.ar(echo, id: 0, post: 0).min(1), [echo, DC.ar(0.0)]);
			echo = LPF.ar(echo, 5000); 
			wetCrackle = HPF.ar(echo, 1000) ! 2;
			
			wetCrackle = (wetCrackle * 1.8).tanh;
			wetCrackle = Limiter.ar(wetCrackle * 5.0, 0.95, 0.01);

			Out.ar(out, XFade2.ar(sig, wetCrackle, (sp3 * 2) - 1));
		}).add;

		// NEW: FX_Pulse Ducking/Sidechain Effect
		SynthDef(\FX_Pulse, { arg in, out, p1=0.5, p2=0.5, p3=0.5, bpmBus;
			var sig = In.ar(in, 2);
			var sp1 = Lag.kr(p1, 0.05);
			var sp2 = Lag.kr(p2, 0.05);
			var sp3 = Lag.kr(p3, 0.05);

			// Read BPM from bus and safety clamp to avoid division by zero
			var bps = In.kr(bpmBus, 1).max(0.1); 
			
			// Map p1 strictly to subdivisions: Whole, Half, Quarter, Eighth, Sixteenth, 32nd
			var multIdx = (sp1 * 5).round;
			var mult = Select.kr(multIdx, [0.25, 0.5, 1.0, 2.0, 4.0, 8.0]);
			var freq = bps * mult;
			var cycle = 1.0 / freq;
			
			var trg = Impulse.ar(freq);

			// Shape 1: Smooth Tremolo swell
			var smoothEnv = EnvGen.ar(Env([1, 0, 1], [0.5, 0.5], [\sin, \sin]), trg, timeScale: cycle);
			
			// Shape 2: Hard Sidechain Pump (Drops instantly, recovers exponentially)
			var pumpEnv = EnvGen.ar(Env([1, 0, 1], [0.02, 0.98], [\lin, 4]), trg, timeScale: cycle);

			// Blend shapes based on p2
			var duckCurve = SelectX.ar(sp2, [smoothEnv, pumpEnv]);

			// Depth calculation: gets deeper as p2 increases
			var depth = 0.6 + (sp2 * 0.4); 
			var duckedSig = sig * (1.0 - (depth * (1.0 - duckCurve)));

			// Sweeping low-pass filter linked to the pump envelope at high p2
			var sweepFilter = LPF.ar(duckedSig, 800 + (duckCurve * 14000));
			var wetPulse = SelectX.ar(sp2, [duckedSig, sweepFilter]);

			// Gain stage to compensate for ducked energy
			wetPulse = Limiter.ar(wetPulse * 1.6, 0.95, 0.01);

			Out.ar(out, XFade2.ar(sig, wetPulse, (sp3 * 2) - 1));
		}).add;

		// --- 3. Sync and Instantiate ---
		context.server.sync;
		
		Synth(\InputTracker, [\in, context.in_b[0].index], context.xg);
		
		synths = Array.fill(maxLayers, { arg i;
			Synth(\StrataLayer, [\buf, buffers[i], \out, fxBus, \depth, i], context.xg);
		});
		
		fxSynth = Synth.new(\FX_Bypass, [\in, fxBus, \out, eqBus], context.xg, \addToTail);
		eqSynth = Synth.new(\MasterEQ, [\in, eqBus, \out, context.out_b.index], context.xg, \addToTail);

		// --- 4. Lua API Commands ---
		this.addCommand(\shift_layers, "ff", { arg msg;
			buffers = buffers.rotate(1);
			synths = synths.rotate(1);
			synths.do { arg syn, i; syn.set(\depth, i); };
			recBuffer.copyData(buffers[0]);
			synths[0].set(\duration, msg[1], \t_reset, 1, \shift_offset, msg[2]);
		});

		this.addCommand(\erode_layer, "", {
			buffers = buffers.rotate(-1);
			synths = synths.rotate(-1);
			synths.do { arg syn, i; syn.set(\depth, i); };
			SystemClock.sched(0.6, { buffers[buffers.size-1].zero; nil; });
		});

		this.addCommand(\clear_layers, "", { buffers.do(_.zero); });
		this.addCommand(\set_volume, "f", { arg msg; volBus.set(msg[1]); });
		
		// NEW: Tell SuperCollider what the norns tempo is (received in BPM)
		this.addCommand(\set_bpm, "f", { arg msg; tempoBus.set(msg[1] / 60.0); });

		this.addCommand(\record_start, "", {
			recBuffer.zero;
			recSynth = Synth(\SurfaceRecorder, [\buf, recBuffer, \in, context.in_b[0].index], context.xg);
		});

		this.addCommand(\record_stop, "", { recSynth.free; });
		this.addCommand(\main_vol, "f", { arg msg; eqSynth.set(\amp, msg[1]); });
		this.addCommand(\set_eq_low, "f", { arg msg; eqSynth.set(\lowGain, msg[1]); });
		this.addCommand(\set_eq_mid, "f", { arg msg; eqSynth.set(\midGain, msg[1]); });
		this.addCommand(\set_eq_high, "f", { arg msg; eqSynth.set(\highGain, msg[1]); });

		this.addCommand(\select_fx, "i", { arg msg;
			var fx_type = msg[1];
			// NEW: Appended \FX_Pulse to the end of the array (Index 5)
			var defs = [\FX_Bypass, \FX_Abyss, \FX_Shatter, \FX_Breeze, \FX_Crackle, \FX_Pulse];
			fxSynth.free;
			// NEW: We pass bpmBus to every FX. The ones that don't need it will safely ignore it.
			fxSynth = Synth.before(eqSynth, defs[fx_type], [\in, fxBus, \out, eqBus, \bpmBus, tempoBus.index]);
		});

		this.addCommand(\set_fx_p1, "f", { arg msg; fxSynth.set(\p1, msg[1]); });
		this.addCommand(\set_fx_p2, "f", { arg msg; fxSynth.set(\p2, msg[1]); });
		this.addCommand(\set_fx_p3, "f", { arg msg; fxSynth.set(\p3, msg[1]); });
	}

	free {
		fxBus.free;
		eqBus.free;
		tempoBus.free;
		fxSynth.free;
		eqSynth.free;
	}
}
