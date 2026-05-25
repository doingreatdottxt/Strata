// Engine_MemoryPhysics.sc
// Structural 3-Band Parametric EQ and Destructive Micro-Fragmentation Array

Engine_MemoryPhysics : CroneEngine {

	*new { arg context, doneCallback;
		^super.new(context, doneCallback);
	}

	alloc {
		
		// ==========================================
		// 1. MASTER PARAMETRIC EQ SYNTHDEF
		// ==========================================
		SynthDef(\MasterEQ, { arg in, out, lowGain=1.0, midGain=1.0, highGain=1.0, amp=0.8;
			var sig = In.ar(in, 2);
			
			// Convert linear gain (0.0 to 2.0) safely to Decibels.
			var lowDb = lowGain.max(0.01).ampdb;
			var midDb = midGain.max(0.01).ampdb;
			var highDb = highGain.max(0.01).ampdb;
			
			// Sequential processing avoids the overlapping frequency pile-ups of parallel summing
			sig = BLowShelf.ar(sig, freq: 300, rs: 1.0, db: lowDb);
			sig = BPeakEQ.ar(sig, freq: 1200, rq: 1.2, db: midDb);
			sig = BHiShelf.ar(sig, freq: 3500, rs: 1.0, db: highDb);
			
			Out.ar(out, sig * amp);
		}).add;


		// ==========================================
		// 2. SHATTER SYNTHDEF (Macro Beat-Leaping)
		// ==========================================
		SynthDef(\Shatter, { arg in, out, fx_p1=0.5, fx_p2=0.5, fx_p3=0.5, bpm=60, amp=1.0;
			var sig = In.ar(in, 2);
			var beatSec = 60.0 / bpm;
			
			// Secondary buffer holding 8 seconds of loop history
			var maxFrames = SampleRate.ir * 8.0; 
			var buf = LocalBuf(maxFrames, 2).clear;
			var writePhase = Phasor.ar(0, 1, 0, maxFrames);
			
			// Sync clock explicitly to coarse step lengths
			var trig = TDuty.ar(Drand([0.25, 0.5, 1.0, 2.0, 3.0], inf) * beatSec);
			
			// fx_p1: Chaos engine (evaluation probability)
			var jumpProb = TRand.ar(0.0, 1.0, trig) < fx_p1;
			
			// Determine buffer leap distances in steps/beats
			var offsetBeats = Demand.ar(trig, 0, Drand([0.5, 1.0, 1.5, 2.0, 3.0], inf));
			
			// fx_p2: Maximum fracture distance ceiling
			var maxJump = fx_p2.linlin(0, 1, 0.5, 4.0);
			var frameOffset, actualOffset, readPhase, wet, duck, output;
			
			offsetBeats = offsetBeats.min(maxJump);
			frameOffset = offsetBeats * beatSec * SampleRate.ir;
			
			// Direct audio-rate binary evaluation instead of problematic Select.ar array inputs
			actualOffset = jumpProb * frameOffset;
			
			readPhase = Wrap.ar(writePhase - actualOffset, 0, maxFrames);
			wet = BufRd.ar(2, buf, readPhase, loop: 1, interpolation: 2);
			
			// Anti-click micro envelope for seamless read-head jumps
			duck = 1 - EnvGen.ar(Env.perc(0.005, 0.015), trig);
			wet = wet * duck;
			
			// fx_p3: Wet/Dry Mix
			output = (sig * (1 - fx_p3)) + (wet * fx_p3);
			
			// Keep destructive I/O execution nodes safely at the bottom of the graph
			BufWr.ar(sig, buf, writePhase);
			Out.ar(out, output * amp);
		}).add;


		// ==========================================
		// 3. CRACKLE SYNTHDEF (Micro Speed-Splintering)
		// ==========================================
		SynthDef(\Crackle, { arg in, out, fx_p1=0.5, fx_p2=0.5, fx_p3=0.5, bpm=60, amp=1.0;
			var sig = In.ar(in, 2);
			var beatSec = 60.0 / bpm;
			
			// Micro-buffer array handling rapid frame capture
			var maxFrames = SampleRate.ir * 4.0; 
			var buf = LocalBuf(maxFrames, 2).clear;
			var writePhase = Phasor.ar(0, 1, 0, maxFrames);
			
			// Strict micro step-intervals (1/16, 1/8, 3/16, 1/4 beats)
			var trig = TDuty.ar(Drand([0.0625, 0.125, 0.1875, 0.25], inf) * beatSec);
			
			var offsetBeats = Demand.ar(trig, 0, Drand([0, 0.125, 0.25, 0.5, 0.75, 1.0], inf));
			var frameOffset = offsetBeats * beatSec * SampleRate.ir;
			
			// Vari-speed array contents: Normal, Reverse, Double, Reverse-Double, Quadruple
			var rates = Drand([1.0, 1.0, 2.0, -1.0, -2.0, 4.0], inf);
			var rate = Demand.ar(trig, 0, rates);
			
			// fx_p1: Probability of playback direction/speed mutation
			var shiftProb = TRand.ar(0.0, 1.0, trig) < fx_p1;
			var rateMod = (shiftProb * rate) + (1.0 - shiftProb);
			
			// Lock read-head to time anchor and drive velocity via modified speed variables
			var readAnchor = Wrap.ar(writePhase - frameOffset, 0, maxFrames);
			var readPhase = Phasor.ar(trig, rateMod, 0, maxFrames, readAnchor);
			var wet = BufRd.ar(2, buf, readPhase, loop: 1, interpolation: 2);
			
			// fx_p2: Choppiness / Audio Gating factor
			var decayTime, grainEnv, output;
			
			decayTime = beatSec * 0.25 * fx_p2.linlin(0, 1, 0.05, 1.0);
			grainEnv = EnvGen.ar(Env([1, 1, 0], [decayTime * 0.8, decayTime * 0.2]), trig);
			
			wet = wet * grainEnv;
			
			// fx_p3: Wet/Dry Mix
			output = (sig * (1 - fx_p3)) + (wet * fx_p3);
			
			BufWr.ar(sig, buf, writePhase);
			Out.ar(out, output * amp);
		}).add;

		// ==========================================
		// LUA TO ENGINE COMMAND HANDLERS
		// ==========================================
		// Add your standard norns command bindings here to route to synth instances
		// e.g., this.addCommand("eq_low", "f", { arg msg; ... });

	}

	free {
		// Cleanup engine synthesis objects here on engine swap
	}
}
