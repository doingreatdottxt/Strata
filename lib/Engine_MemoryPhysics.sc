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
		
		// Tempo Bus initialized to 120 BPM (2.0 Beats Per Second)
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

		SynthDef(\FX_Abyss, { arg in, out, p1=0.5, p2=0.5, p3=0.5, bpmBus;
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

		// ADVANCED SHATTER: Dynamic Coarse Loop-Fracturing Sampler
		SynthDef(\FX_Shatter, { arg in, out, p1=0.5, p2=0.5, p3=0.5, bpmBus;
			var sig = In.ar(in, 2);
			var bps = In.kr(bpmBus, 1).max(0.1); // Convert bus tracking to Beats Per Second
			var beatSec = 1.0 / bps;
			
			// 8-second secondary loop buffer
			var maxFrames = SampleRate.ir * 8.0; 
			var buf = LocalBuf(maxFrames, 2).clear;
			var writePhase = Phasor.ar(0, 1, 0, maxFrames);
			
			// Sync clock explicitly to structural step intervals
			var trig = TDuty.ar(Drand([0.25, 0.5, 1.0, 2.0, 3.0], inf) * beatSec);
			
			// p1: Chaos Engine Evaluation Probability
			var jumpProb = TRand.ar(0.0, 1.0, trig) < p1;
			
			// Determine buffer leap distances in beats
			var offsetBeats = Demand.ar(trig, 0, Drand([0.5, 1.0, 1.5, 2.0, 3.0], inf));
			
			// p2: Maximum fracture distance ceiling
			var maxJump = p2.linlin(0, 1, 0.5, 4.0);
			var frameOffset, actualOffset, readPhase, wet, duck;
			
			offsetBeats = offsetBeats.min(maxJump);
			frameOffset = offsetBeats * beatSec * SampleRate.ir;
			
			// DC.ar wrapper guarantees safe compilation by satisfying array constraints
			actualOffset = Select.ar(jumpProb, [DC.ar(0), frameOffset]);
			
			readPhase = Wrap.ar(writePhase - actualOffset, 0, maxFrames);
			wet = BufRd.ar(2, buf, readPhase, loop: 1, interpolation: 2);
			
			// Anti-click micro envelope for seamless read-head jumps
			duck = 1 - EnvGen.ar(Env.perc(0.005, 0.015), trig);
			wet = wet * duck;
			
			// Destructive IO updates complete right before output stage
			BufWr.ar(sig, buf, writePhase);
			
			// p3: Crossfaded output wet/dry ratio
			Out.ar(out, XFade2.ar(sig, wet, (p3 * 2) - 1));
		}).add;

		SynthDef(\FX_Breeze, { arg in, out, p1=0.5, p2=0.5, p3=0.5, bpmBus;
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

		// ADVANCED CRACKLE: Multi-Velocity Pitch Splinter Engine
		SynthDef(\FX_Crackle, { arg in, out, p1=0.5, p2=0.5, p3=0.5, bpmBus;
			var sig = In.ar(in, 2);
			var bps = In.kr(bpmBus, 1).max(0.1);
			var beatSec = 1.0 / bps;
			
			// 4-second internal micro-buffer array 
			var maxFrames = SampleRate.ir * 4.0; 
			var buf = LocalBuf(maxFrames, 2).clear;
			var writePhase = Phasor.ar(0, 1, 0, maxFrames);
			
			// Micro step-intervals (1/16, 1/8, 3/16, 1/4 beats)
			var trig = TDuty.ar(Drand([0.0625, 0.125, 0.1875, 0.25], inf) * beatSec);
			
			var offsetBeats = Demand.ar(trig, 0, Drand([0, 0.125, 0.25, 0.5, 0.75, 1.0], inf));
			var frameOffset = offsetBeats * beatSec * SampleRate.ir;
			
			// Vari-speed array values: Normal, Reverse, Double, Reverse-Double, Quadruple
			var rates = Drand([1.0, 1.0, 2.0, -1.0, -2.0, 4.0], inf);
			var rate = Demand.ar(trig, 0, rates);
			
			// p1: Probability of active playback velocity mutation
			var shiftProb = TRand.ar(0.0, 1.0, trig) < p1;
			var rateMod = Select.ar(shiftProb, [DC.ar(1.0), rate]);
			
			// Drive head position using absolute anchoring and rate variables
			var readAnchor = Wrap.ar(writePhase - frameOffset, 0, maxFrames);
			var readPhase = Phasor.ar(trig, rateMod, 0, maxFrames, readAnchor);
			var wet = BufRd.ar(2, buf, readPhase, loop: 1, interpolation: 2);
			
			// p2: Gating / Fragment Window Decay Factor
			var decayTime, grainEnv;
			
			decayTime = beatSec * 0.25 * p2.linlin(0, 1, 0.05, 1.0);
			grainEnv = EnvGen.ar(Env([1, 1, 0], [decayTime * 0.8, decayTime * 0.2]), trig);
			
			wet = wet * grainEnv;
			
			// Continuous buffer caching updates
			BufWr.ar(sig, buf, writePhase);
			
			// p3: Crossfaded output wet/dry ratio
			Out.ar(out, XFade2.ar(sig, wet, (p3 * 2) - 1));
		}).add;

		// FX_Pulse Ducking/Sidechain Effect
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
		
		// Tell SuperCollider what the norns tempo is (received in BPM)
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
			var defs = [\FX_Bypass, \FX_Abyss, \FX_Shatter, \FX_Breeze, \FX_Crackle, \FX_Pulse];
			fxSynth.free;
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
