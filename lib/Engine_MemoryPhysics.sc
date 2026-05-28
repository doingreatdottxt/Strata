// lib/Engine_MemoryPhysics.sc
Engine_MemoryPhysics : CroneEngine {
	var <buffers, <synths, <recSynth, <maxLayers = 6;
	var <recBuffer, <tempBuffer, <volBus, <tempoBus;
	var <fxBus, <eqBus, <fxSynth, <eqSynth;

	*new { arg context, doneCallback;
		^super.new(context, doneCallback);
	}

	alloc {
		// --- 1. Allocations ---
		buffers = Array.fill(maxLayers, {
			Buffer.alloc(context.server, (context.server.sampleRate * 30.0).asInteger, 2);
		});
		recBuffer = Buffer.alloc(context.server, (context.server.sampleRate * 30.0).asInteger, 2);
		
		// NEW: Dedicated temp buffer for the high-speed rhythmic shuffler
		tempBuffer = Buffer.alloc(context.server, (context.server.sampleRate * 30.0).asInteger, 2);
		
		volBus = Bus.control(context.server, 1).set(1.0);
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
			Out.ar(out, sig * layer_vol * In.kr(out, 1));
		}).add;

		// NEW: 100x Speed Offline Shuffler
		// Renders a sliced version of a buffer instantly into a destination buffer
		SynthDef(\FastShuffler, { arg srcBuf, dstBuf, dur=2.0;
			var speed = 100.0;
			var frames = dur * BufSampleRate.ir(srcBuf);
			var writePhase = Line.ar(0, frames, dur / speed, doneAction: 2);
			
			var sliceCount = 8;
			var sliceFrames = frames / sliceCount;
			
			var trig = Impulse.ar((BufSampleRate.ir(srcBuf) / sliceFrames) * speed);
			var sliceIdx = TRand.ar(0, sliceCount, trig).floor;
			
			var readPhase = Phasor.ar(trig, speed, 0, sliceFrames) + (sliceIdx * sliceFrames);
			var sig = BufRd.ar(2, srcBuf, Wrap.ar(readPhase, 0, frames), loop: 1);
			
			BufWr.ar(sig, dstBuf, writePhase, loop: 0);
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
			var lowDb = lowGain.max(0.01).ampdb;
			var midDb = midGain.max(0.01).ampdb;
			var highDb = highGain.max(0.01).ampdb;
			
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
					wetAbyss, 0.1, 
					LFNoise2.kr(0.1 + (sp3 * 0.5)).range(0.01, 0.05 + (sp3 * 0.04)), 
					1.0 + (sp1_slow * 5.0)
				);
				badFirewall = CheckBadValues.ar(wetAbyss, id: 0, post: 0);
				wetAbyss = Select.ar(badFirewall.min(1), [wetAbyss, DC.ar(0.0)]);
			};
			
			wetAbyss = LPF.ar(wetAbyss, 12000 - (sp1_fast * 8000));
			wetAbyss = wetAbyss.tanh; 
			wetAbyss = Limiter.ar(wetAbyss * 1.4, 0.95, 0.01);
			
			// fixed: pass two signals to XFade2 (no array)
			Out.ar(out, XFade2.ar(sig, DelayC.ar(wetAbyss, 0.02, 0.015), (sp1_fast * 2) - 1));
		}).add;

		// NEW: Harmony Phase-Locked Pitch & Resonance Engine
		SynthDef(\FX_Harmony, { arg in, out, p1=0.5, p2=0.5, p3=0.5, bpmBus;
			var sig = In.ar(in, 2);
			var bps = In.kr(bpmBus, 1).max(0.1);
			
			// Phase-locked interval snapping: Unison, minor 3rd, perfect 4th, perfect 5th, Octave
			var intervals = Select.kr((p1 * 4).round, [1.0, 1.1892, 1.3348, 1.4983, 2.0]);
			var shifted = PitchShift.ar(sig, 0.15, intervals, 0.0, 0.004);
			
			// Secondary buffer harmonic resonance
			var fb = LocalIn.ar(2) + shifted;
			
			// Delay lines tuned to subdivisions of the tempo for rhythmic harmonic feedback
			var delayTime = (1.0 / bps) * Select.kr((p2 * 3).round, [0.25, 0.5, 0.75, 1.0]);
			var resonated = DelayC.ar(fb, 2.0, delayTime);
			
			// Filter and feed back the resonance
			resonated = LPF.ar(resonated, 5000 + (p2 * 5000));
			LocalOut.ar(resonated * (0.3 + (p2 * 0.5))); 
			
			var wet = Limiter.ar(resonated * 1.5, 0.95, 0.01);
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

		SynthDef(\FX_Crackle, { arg in, out, p1=0.5, p2=0.5, p3=0.5, bpmBus;
			var sig = In.ar(in, 2);
			var bps = In.kr(bpmBus, 1).max(0.1);
			var beatSec = 1.0 / bps;
			
			var maxFrames = SampleRate.ir * 4.0; 
			var buf = LocalBuf(maxFrames, 2).clear;
			var writePhase = Phasor.ar(0, 1, 0, maxFrames);
			
			var trig = TDuty.ar(Drand([0.0625, 0.125, 0.1875, 0.25], inf) * beatSec);
			var offsetBeats = Demand.ar(trig, 0, Drand([0, 0.125, 0.25, 0.5, 0.75, 1.0], inf));
			var frameOffset = offsetBeats * beatSec * SampleRate.ir;
			
			var rates = Drand([1.0, 1.0, 2.0, -1.0, -2.0, 4.0], inf);
			var rate = Demand.ar(trig, 0, rates);
			
			var shiftProb = TRand.ar(0.0, 1.0, trig) < p1;
			var rateMod = Select.ar(shiftProb, [DC.ar(1.0), rate]);
			
			var readAnchor = Wrap.ar(writePhase - frameOffset, 0, maxFrames);
			var readPhase = Phasor.ar(trig, rateMod, 0, maxFrames, readAnchor);
			var wet = BufRd.ar(2, buf, readPhase, loop: 1, interpolation: 2);
			
			var decayTime = beatSec * 0.25 * p2.linlin(0, 1, 0.05, 1.0);
			var grainEnv = EnvGen.ar(Env([1, 1, 0], [decayTime * 0.8, decayTime * 0.2]), trig);
			
			wet = wet * grainEnv;
			BufWr.ar(sig, buf, writePhase);
			Out.ar(out, XFade2.ar(sig, wet, (p3 * 2) - 1));
		}).add;

		SynthDef(\FX_Pulse, { arg in, out, p1=0.5, p2=0.5, p3=0.5, bpmBus;
			var sig = In.ar(in, 2);
			var sp1 = Lag.kr(p1, 0.05);
			var sp2 = Lag.kr(p2, 0.05);
			var sp3 = Lag.kr(p3, 0.05);
			var bps = In.kr(bpmBus, 1).max(0.1); 
			var multIdx = (sp1 * 5).round;
			var mult = Select.kr(multIdx, [0.25, 0.5, 1.0, 2.0, 4.0, 8.0]);
			var freq = bps * mult;
			var cycle = 1.0 / freq;
			var trg = Impulse.ar(freq);

			var smoothEnv = EnvGen.ar(Env([1, 0, 1], [0.5, 0.5], [\sin, \sin]), trg, timeScale: cycle);
			var pumpEnv = EnvGen.ar(Env([1, 0, 1], [0.02, 0.98], [\lin, 4]), trg, timeScale: cycle);
			var duckCurve = SelectX.ar(sp2, [smoothEnv, pumpEnv]);

			var depth = 0.6 + (sp2 * 0.4); 
			var duckedSig = sig * (1.0 - (depth * (1.0 - duckCurve)));
			var sweepFilter = LPF.ar(duckedSig, 800 + (duckCurve * 14000));
			var wetPulse = SelectX.ar(sp2, [duckedSig, sweepFilter]);
			wetPulse = Limiter.ar(wetPulse * 1.6, 0.95, 0.01);

			Out.ar(out, XFade2.ar(sig, wetPulse, (sp3 * 2) - 1));
		}).add;

		// --- 3. Sync and Instantiate ---
		context.server.sync;
		
		// Input tracker: pass bus index
		Synth(\InputTracker, [\in, context.in_b[0].index], context.xg);
		
		// instantiate strata layers with numeric buffer/bus ids
		synths = Array.fill(maxLayers, { arg i;
			Synth(\StrataLayer, [\buf, buffers[i].bufnum, \out, fxBus.index, \depth, i], context.xg);
		});
		
		// create fx and eq synths using numeric bus indices
		fxSynth = Synth.new(\FX_Bypass, [\in, fxBus.index, \out, eqBus.index], context.xg, \addToTail);
		eqSynth = Synth.new(\MasterEQ, [\in, eqBus.index, \out, context.out_b.index], context.xg, \addToTail);

		// --- 4. Lua API Commands ---
		this.addCommand(\shift_layers, "ff", { arg msg;
			var dur = msg[1];
			var shiftOffset = msg[2];
			
			// 1. Shuffle Layer 0 into the Temp Buffer BEFORE rotating
			Synth(\FastShuffler, [\srcBuf, buffers[0].bufnum, \dstBuf, tempBuffer.bufnum, \dur, dur], context.xg);
			
			// 2. Standard layer rotation
			buffers = buffers.rotate(1);
			synths = synths.rotate(1);
			synths.do { arg syn, i; syn.set(\depth, i); };
			
			// 3. Record fresh data to the new buffers[0]
			// copy recorded data INTO buffers[0]
			buffers[0].copyData(recBuffer);
			synths[0].set(\duration, dur, \t_reset, 1, \shift_offset, shiftOffset);
			
			// 4. Destructively overwrite buffers[1] with the shuffled data
			// We delay by 0.1s to guarantee the FastShuffler synth has finished its 100x render
			SystemClock.sched(0.1, {
				tempBuffer.copyData(buffers[1]);
				nil;
			});
		});

		this.addCommand(\erode_layer, "", {
			buffers = buffers.rotate(-1);
			synths = synths.rotate(-1);
			synths.do { arg syn, i; syn.set(\depth, i); };
			SystemClock.sched(0.6, { buffers[buffers.size-1].zero; nil; });
		});

		this.addCommand(\clear_layers, "", { buffers.do(_.zero); tempBuffer.zero; });
		this.addCommand(\set_volume, "f", { arg msg; volBus.set(msg[1]); });
		this.addCommand(\set_bpm, "f", { arg msg; tempoBus.set(msg[1] / 60.0); });

		this.addCommand(\record_start, "", {
			recBuffer.zero;
			recSynth = Synth(\SurfaceRecorder, [\buf, recBuffer.bufnum, \in, context.in_b[0].index], context.xg);
		});

		this.addCommand(\record_stop, "", { recSynth.ifNotNil({ recSynth.free }); });
		this.addCommand(\main_vol, "f", { arg msg; eqSynth.ifNotNil({ eqSynth.set(\amp, msg[1]) }); });
		this.addCommand(\set_eq_low, "f", { arg msg; eqSynth.ifNotNil({ eqSynth.set(\lowGain, msg[1]) }); });
		this.addCommand(\set_eq_mid, "f", { arg msg; eqSynth.ifNotNil({ eqSynth.set(\midGain, msg[1]) }); });
		this.addCommand(\set_eq_high, "f", { arg msg; eqSynth.ifNotNil({ eqSynth.set(\highGain, msg[1]) }); });

		this.addCommand(\select_fx, "i", { arg msg;
			var fx_type = msg[1];
			var defs = [\FX_Bypass, \FX_Abyss, \FX_Harmony, \FX_Breeze, \FX_Crackle, \FX_Pulse];
			fxSynth.ifNotNil({ fxSynth.free });
			fxSynth = Synth.before(eqSynth, defs[fx_type], [\in, fxBus.index, \out, eqBus.index, \bpmBus, tempoBus.index]);
		});

		this.addCommand(\set_fx_p1, "f", { arg msg; fxSynth.ifNotNil({ fxSynth.set(\p1, msg[1]) }); });
		this.addCommand(\set_fx_p2, "f", { arg msg; fxSynth.ifNotNil({ fxSynth.set(\p2, msg[1]) }); });
		this.addCommand(\set_fx_p3, "f", { arg msg; fxSynth.ifNotNil({ fxSynth.set(\p3, msg[1]) }); });
	}

	free {
		fxBus.ifNotNil({ fxBus.free });
		eqBus.ifNotNil({ eqBus.free });
		tempoBus.ifNotNil({ tempoBus.free });
		fxSynth.ifNotNil({ fxSynth.free });
		eqSynth.ifNotNil({ eqSynth.free });
	}
}
