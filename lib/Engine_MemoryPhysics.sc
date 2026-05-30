#include "daisy_seed.h"
#include "daisysp.h"
#include <cstdlib>
#include <cstdio> // Required for sprintf()
#include "dev/oled_ssd130x.h"
#include "reverbsc.h"

using namespace daisy;
using namespace daisysp;

// --- Hardware Objects ---
DaisySeed hw;
OledDisplay<SSD130xI2c128x64Driver> display;
Switch button_shift, button_rec, button_sync;

// --- Memory Allocation ---
#define MAX_LAYERS 6
#define BUFFER_SECONDS 20 // 20s overhead for FX memory
#define SAMPLE_RATE 48000
#define BUFFER_SIZE (SAMPLE_RATE * BUFFER_SECONDS)

// Replace lines 22 and 23 with these exact lines:
float layer_buffers_l[MAX_LAYERS][BUFFER_SIZE] __attribute__((section(".sdram_bss")));
float layer_buffers_r[MAX_LAYERS][BUFFER_SIZE] __attribute__((section(".sdram_bss")));
// --- Global State ---
struct GeoState {
    bool recording = false;
    int layer_map[MAX_LAYERS] = {0, 1, 2, 3, 4, 5};
    float layer_phases[MAX_LAYERS] = {0.0f};
    int rec_frames = 0;
    bool shift_held = false;
    
    int active_fx = 0; // 0=Bypass, 1=Abyss, 2=Harmony, 3=Breeze, 4=Crackle, 5=Pulse, 6=Shuffle, 7=Filter
    float main_vol = 0.8f;
    float fx_p1 = 0.0f;
    float fx_p2 = 0.0f;
    float fx_p3 = 0.0f;
    
    float duration = 1.0f; // Default 1 second, overwritten dynamically
    int layers_active = 0;
    int surface_cycles = 0;
};

GeoState state;

// Simple volume decay for LIFO layers (Depth 0 is loudest, Depth 5 is quietest)
const float depth_vols[MAX_LAYERS] = {1.0f, 0.85f, 0.7f, 0.5f, 0.3f, 0.15f};

// --- DSP Objects ---

// 1. Abyss
daisysp::ReverbSc abyss_reverb;

// 2. Harmony
PitchShifter harmony_shift_l;
PitchShifter harmony_shift_r;

// 3. Breeze
Chorus breeze_chorus_l;
Chorus breeze_chorus_r;

// 4. Crackle
Decimator crackle_crush_l;
Decimator crackle_crush_r;

// 5. Pulse
Oscillator pulse_lfo;

// 6. Shuffle
#define SHUFFLE_MAX_SAMPLES 96000
DSY_SDRAM_BSS DelayLine<float, SHUFFLE_MAX_SAMPLES> shuffle_delay_l;
DSY_SDRAM_BSS DelayLine<float, SHUFFLE_MAX_SAMPLES> shuffle_delay_r;

// 7. Filter
Svf filter_l;
Svf filter_r;


// --- Helper Functions ---
void ToggleRecording() {
    if (!state.recording) {
        // --- START RECORDING ---
        // 1. Shift the layer map and phases down by 1 (LIFO Push)
        int oldest_map = state.layer_map[MAX_LAYERS - 1];
        
        for (int i = MAX_LAYERS - 1; i > 0; i--) {
            state.layer_map[i] = state.layer_map[i - 1];
            state.layer_phases[i] = state.layer_phases[i - 1];
        }
        
        // 2. Put the recycled buffer at Depth 0
        state.layer_map[0] = oldest_map;
        state.layer_phases[0] = 0.0f; // Reset phase for the new recording
        
        // 3. Reset recording head and flag
        state.rec_frames = 0;
        state.recording = true;
    } else {
        // --- STOP RECORDING ---
        state.recording = false;
        
        // 1. Set the new master loop duration (with 10ms safety debounce)
        if (state.rec_frames > 480) { 
            state.duration = (float)state.rec_frames / (float)SAMPLE_RATE;
        }
        
        // 2. Increment active layers up to the max
        if (state.layers_active < MAX_LAYERS) {
            state.layers_active++;
        }
        
        // Reset surface cycles for UI tracking
        state.surface_cycles = 0; 
    }
}


// --- Audio Callback ---
void AudioCallback(AudioHandle::InputBuffer in, AudioHandle::OutputBuffer out, size_t size) {
    float loop_frames = state.duration * SAMPLE_RATE;

    for (size_t i = 0; i < size; i++) {
        float in_l = in[0][i];
        float in_r = in[1][i];
        float mix_l = 0.0f;
        float mix_r = 0.0f;

        // 1. RECORDING
        if (state.recording) {
            int physical_rec_buf = state.layer_map[0];
            layer_buffers_l[physical_rec_buf][state.rec_frames] = in_l;
            layer_buffers_r[physical_rec_buf][state.rec_frames] = in_r;
            if (state.rec_frames < BUFFER_SIZE - 1) state.rec_frames++;
        }

        // 2. PLAYBACK (Layer Mixing)
        for (int l = 0; l < state.layers_active; l++) {
            int physical_buf = state.layer_map[l];
            int read_idx = (int)state.layer_phases[l];
            
            mix_l += layer_buffers_l[physical_buf][read_idx] * depth_vols[l];
            mix_r += layer_buffers_r[physical_buf][read_idx] * depth_vols[l];
            
            state.layer_phases[l] += 1.0f;
            
            if (state.layer_phases[l] >= loop_frames) {
                state.layer_phases[l] -= loop_frames;
                if (l == 0 && !state.recording) state.surface_cycles++;
            }
        }

        // 3. FX ROUTING MATRIX
        float fx_out_l = mix_l;
        float fx_out_r = mix_r;

        switch (state.active_fx) {
            case 0: // BYPASS
                break;
                
            case 1: { // ABYSS (Massive Reverb)
                abyss_reverb.SetFeedback(0.4f + (state.fx_p1 * 0.58f)); 
                abyss_reverb.SetLpFreq(500.0f + (state.fx_p2 * 14500.0f)); 
                
                float rev_l, rev_r;
                abyss_reverb.Process(mix_l, mix_r, &rev_l, &rev_r);
                
                fx_out_l = (mix_l * (1.0f - state.fx_p3)) + (rev_l * state.fx_p3);
                fx_out_r = (mix_r * (1.0f - state.fx_p3)) + (rev_r * state.fx_p3);
                break;
            }
            
            case 2: { // HARMONY (Granular Pitch Shifting)
                float semitones = (state.fx_p1 * 24.0f) - 12.0f;
                harmony_shift_l.SetTransposition(semitones);
                harmony_shift_r.SetTransposition(semitones + 0.1f); 
                
                harmony_shift_l.SetFun(state.fx_p2);
                harmony_shift_r.SetFun(state.fx_p2);
                
                float wet_l = harmony_shift_l.Process(mix_l);
                float wet_r = harmony_shift_r.Process(mix_r);
                
                fx_out_l = (mix_l * (1.0f - state.fx_p3)) + (wet_l * state.fx_p3);
                fx_out_r = (mix_r * (1.0f - state.fx_p3)) + (wet_r * state.fx_p3);
                break;
            }
            
            case 3: { // BREEZE (Lush Stereo Chorus)
                float rate_l = 0.1f + (state.fx_p1 * 9.9f);
                float rate_r = 0.12f + (state.fx_p1 * 10.0f); 
                breeze_chorus_l.SetLfoFreq(rate_l);
                breeze_chorus_r.SetLfoFreq(rate_r);
                
                breeze_chorus_l.SetLfoDepth(state.fx_p2);
                breeze_chorus_r.SetLfoDepth(state.fx_p2);
                
                float wet_l = breeze_chorus_l.Process(mix_l);
                float wet_r = breeze_chorus_r.Process(mix_r);
                
                fx_out_l = (mix_l * (1.0f - state.fx_p3)) + (wet_l * state.fx_p3);
                fx_out_r = (mix_r * (1.0f - state.fx_p3)) + (wet_r * state.fx_p3);
                break;
            }
            
            case 4: { // CRACKLE (Lo-Fi Vinyl & Tape Degradation)
                crackle_crush_l.SetBitcrushFactor(state.fx_p1 * 0.8f);
                crackle_crush_l.SetDownsampleFactor(state.fx_p1 * 0.9f);
                crackle_crush_r.SetBitcrushFactor(state.fx_p1 * 0.8f);
                crackle_crush_r.SetDownsampleFactor(state.fx_p1 * 0.9f);
                
                float crushed_l = crackle_crush_l.Process(mix_l);
                float crushed_r = crackle_crush_r.Process(mix_r);
                
                float dust = 0.0f;
                float r = (float)rand() / RAND_MAX;
                float crackle_density = 0.9999f - (state.fx_p2 * 0.005f); 
                
                if (r > crackle_density && state.fx_p2 > 0.01f) {
                    dust = (((float)rand() / RAND_MAX) * 2.0f - 1.0f) * state.fx_p2 * 0.5f; 
                }
                
                float wet_l = crushed_l + dust;
                float wet_r = crushed_r + dust;
                
                fx_out_l = (mix_l * (1.0f - state.fx_p3)) + (wet_l * state.fx_p3);
                fx_out_r = (mix_r * (1.0f - state.fx_p3)) + (wet_r * state.fx_p3);
                break;
            }
            
            case 5: { // PULSE (Tremolo & Rhythmic Gating)
                float rate = 0.5f + (state.fx_p1 * 19.5f);
                pulse_lfo.SetFreq(rate);
                
                if (state.fx_p2 < 0.33f) {
                    pulse_lfo.SetWaveform(Oscillator::WAVE_SIN);
                } else if (state.fx_p2 < 0.66f) {
                    pulse_lfo.SetWaveform(Oscillator::WAVE_SAW);
                } else {
                    pulse_lfo.SetWaveform(Oscillator::WAVE_SQUARE);
                }
                
                float lfo_val = pulse_lfo.Process();
                float mod = (lfo_val + 1.0f) * 0.5f;
                
                float wet_l = mix_l * mod;
                float wet_r = mix_r * mod;
                
                fx_out_l = (mix_l * (1.0f - state.fx_p3)) + (wet_l * state.fx_p3);
                fx_out_r = (mix_r * (1.0f - state.fx_p3)) + (wet_r * state.fx_p3);
                break;
            }
            
            case 6: { // SHUFFLE (Ping-Pong Stutter Delay)
                float target_delay = 480.0f + ((state.fx_p1 * state.fx_p1) * 71520.0f); 
                shuffle_delay_l.SetDelay(target_delay);
                shuffle_delay_r.SetDelay(target_delay * 1.33f); 
                
                float feedback = state.fx_p2 * 0.95f;
                float d_l = shuffle_delay_l.Read();
                float d_r = shuffle_delay_r.Read();
                
                shuffle_delay_l.Write(mix_l + (d_r * feedback));
                shuffle_delay_r.Write(mix_r + (d_l * feedback)); 
                
                fx_out_l = (mix_l * (1.0f - state.fx_p3)) + (d_l * state.fx_p3);
                fx_out_r = (mix_r * (1.0f - state.fx_p3)) + (d_r * state.fx_p3);
                break;
            }

            case 7: { // FILTER (DJ-Style LP / HP)
                float wet_l = mix_l;
                float wet_r = mix_r;
                
                float res = state.fx_p2 * 0.95f;
                filter_l.SetRes(res);
                filter_r.SetRes(res);
                
                if (state.fx_p1 < 0.45f) {
                    // LOWPASS
                    float sweep = state.fx_p1 / 0.45f;
                    float cutoff = 40.0f * powf(400.0f, sweep);
                    filter_l.SetFreq(cutoff);
                    filter_r.SetFreq(cutoff);
                    
                    filter_l.Process(mix_l);
                    filter_r.Process(mix_r);
                    wet_l = filter_l.Low();
                    wet_r = filter_r.Low();
                    
                } else if (state.fx_p1 > 0.55f) {
                    // HIGHPASS
                    float sweep = (state.fx_p1 - 0.55f) / 0.45f;
                    float cutoff = 40.0f * powf(200.0f, sweep);
                    filter_l.SetFreq(cutoff);
                    filter_r.SetFreq(cutoff);
                    
                    filter_l.Process(mix_l);
                    filter_r.Process(mix_r);
                    wet_l = filter_l.High();
                    wet_r = filter_r.High();
                }
                
                fx_out_l = (mix_l * (1.0f - state.fx_p3)) + (wet_l * state.fx_p3);
                fx_out_r = (mix_r * (1.0f - state.fx_p3)) + (wet_r * state.fx_p3);
                break;
            }
        }

        // 4. FINAL OUTPUT
        out[0][i] = fx_out_l * state.main_vol;
        out[1][i] = fx_out_r * state.main_vol;
    }
}

// --- Main Program Loop ---
int main(void) {
    // 1. Initialize Hardware
    hw.Init();
    
    // --- DISPLAY INIT ---
    // Safely configure I2C pins mapping to the standard Daisy Seed layout (PB8 / PB9)
    OledDisplay<SSD130xI2c128x64Driver>::Config display_config;
    display_config.driver_config.transport_config.i2c_config.pin_config.scl = {DSY_GPIOB, 8};
    display_config.driver_config.transport_config.i2c_config.pin_config.sda = {DSY_GPIOB, 9};
    display.Init(display_config);
    // --------------------
    
    // 2. Configure ADC (Potentiometers) on Pins 15, 16, and 17
    AdcChannelConfig adc_cfg[3];
    adc_cfg[0].InitSingle(hw.GetPin(15));
    adc_cfg[1].InitSingle(hw.GetPin(16));
    adc_cfg[2].InitSingle(hw.GetPin(17));
    hw.adc.Init(adc_cfg, 3);
    hw.adc.Start();
    
    // 3. Configure Buttons (Assuming Pins 27, 28 for Shift and Rec)
    button_shift.Init(hw.GetPin(27), 1000);
    button_rec.Init(hw.GetPin(28), 1000);
    
    // 4. Clear memory safely
    for(int i = 0; i < MAX_LAYERS; i++) {
        for(int j = 0; j < BUFFER_SIZE; j++) {
            layer_buffers_l[i][j] = 0.0f;
            layer_buffers_r[i][j] = 0.0f;
        }
    }
    
    // 5. Initialize DSP Effects
    abyss_reverb.Init(SAMPLE_RATE);
    abyss_reverb.SetFeedback(0.85f);  
    abyss_reverb.SetLpFreq(10000.0f);
    
    harmony_shift_l.Init(SAMPLE_RATE);
    harmony_shift_r.Init(SAMPLE_RATE);
    
    breeze_chorus_l.Init(SAMPLE_RATE);
    breeze_chorus_l.SetDelay(0.75f);
    breeze_chorus_r.Init(SAMPLE_RATE);
    breeze_chorus_r.SetDelay(0.80f);
    
    crackle_crush_l.Init();
    crackle_crush_r.Init();
    
    pulse_lfo.Init(SAMPLE_RATE);
    pulse_lfo.SetAmp(1.0f);
    
    shuffle_delay_l.Init();
    shuffle_delay_r.Init();

    filter_l.Init(SAMPLE_RATE);
    filter_r.Init(SAMPLE_RATE);

    // 6. Start Audio Processing
    hw.SetAudioBlockSize(48); // Standard block size
    hw.SetAudioSampleRate(SaiHandle::Config::SampleRate::SAI_48KHZ);
    hw.StartAudio(AudioCallback);

    // 7. Infinite Polling Loop
    while (1) {
        // --- 1. Read Hardware ---
        button_shift.Debounce();
        button_rec.Debounce();
        
        // --- 2. Update State ---
        state.shift_held = button_shift.Pressed();
        state.fx_p1 = hw.adc.GetFloat(0);
        state.fx_p2 = hw.adc.GetFloat(1);
        state.fx_p3 = hw.adc.GetFloat(2);
        
        // --- 3. Rec / FX Cycle Logic ---
        if (button_rec.RisingEdge()) {
            if (state.shift_held) {
                state.active_fx = (state.active_fx + 1) % 8;
            } else {
                ToggleRecording();
            }
        }
        
        // --- 4. OLED UI DRAWING ---
        display.Fill(false); 
        char str[32];        

        // Row 1: Status & Layers
        display.SetCursor(0, 0);
        sprintf(str, "%s  L:%d/6", state.recording ? "* REC *" : "  PLAY ", state.layers_active);
        display.WriteString(str, Font_7x10, true);

        // Row 2: Active Effect Name
        const char* fx_names[] = {"BYPASS", "ABYSS", "HARMONY", "BREEZE", "CRACKLE", "PULSE", "SHUFFLE", "FILTER"};
        display.SetCursor(0, 16);
        sprintf(str, "FX: %s", fx_names[state.active_fx]);
        display.WriteString(str, Font_7x10, true);

        // Row 3: Parameters 1 & 2 (scaled to 0-99)
        display.SetCursor(0, 32);
        sprintf(str, "P1:%02d P2:%02d", (int)(state.fx_p1 * 99), (int)(state.fx_p2 * 99));
        display.WriteString(str, Font_7x10, true);

        // Row 4: Parameter 3 (Wet/Dry Mix)
        display.SetCursor(0, 48);
        sprintf(str, "MIX: %02d", (int)(state.fx_p3 * 99));
        display.WriteString(str, Font_7x10, true);

        // Push the buffer to the physical screen
        display.Update();
        
        // Brief pause to prevent hogging the CPU
        System::Delay(10); 
    }
}
