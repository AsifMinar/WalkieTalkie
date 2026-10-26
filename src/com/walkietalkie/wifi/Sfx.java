package com.walkietalkie.wifi;

/**
 * Walkie-talkie sounds, synthesized once in memory on first use (about 50 KB in total).
 * No audio files ship with the app. All sounds are 16 kHz mono 16-bit PCM.
 *
 * Static is band-limited noise (roughly 300 Hz to 1.8 kHz, like a small radio speaker) with
 * random "crackle" in its loudness. Beeps are slightly square-ish sines, like radio tones.
 */
final class Sfx {

    static final int RATE = 16000;

    /** Radio switching on: a crackle of static, then three rising beeps. */
    static final byte[] POWER_ON;
    /** Radio switching off: two falling beeps and a short burst of static. */
    static final byte[] POWER_OFF;
    /** You pressed to talk: a quick double chirp, the "talk permit" tone. */
    static final byte[] TALK_PERMIT;
    /** Someone starts talking: the squelch opens with a short crackle. */
    static final byte[] SQUELCH_OPEN;
    /** Someone finishes: the two-tone roger beep, then the squelch tail "kshhh". */
    static final byte[] ROGER_TAIL;
    /** Signal dropped without a proper ending: just the squelch tail. */
    static final byte[] TAIL;
    /** Channel changed: one short blip. */
    static final byte[] CHANNEL;
    /** You can't talk right now: a low "bonk". */
    static final byte[] BUSY;

    static {
        Gen g;

        g = new Gen(560);
        g.noise(0, 130, 0.30f, 3, 40, 11);
        g.tone(190, 60, 880, 0.24f);
        g.tone(265, 60, 1320, 0.24f);
        g.tone(340, 110, 1760, 0.24f);
        POWER_ON = g.bytes();

        g = new Gen(330);
        g.tone(0, 70, 1320, 0.22f);
        g.tone(85, 110, 880, 0.22f);
        g.noise(215, 110, 0.22f, 2, 70, 23);
        POWER_OFF = g.bytes();

        g = new Gen(95);
        g.tone(0, 30, 1950, 0.17f);
        g.tone(55, 30, 1950, 0.17f);
        TALK_PERMIT = g.bytes();

        g = new Gen(50);
        g.noise(0, 50, 0.22f, 2, 20, 37);
        SQUELCH_OPEN = g.bytes();

        g = new Gen(430);
        g.tone(0, 70, 1250, 0.22f);
        g.tone(95, 90, 1650, 0.22f);
        g.noise(215, 215, 0.30f, 2, 40, 53);
        ROGER_TAIL = g.bytes();

        g = new Gen(220);
        g.noise(0, 220, 0.30f, 2, 40, 71);
        TAIL = g.bytes();

        g = new Gen(45);
        g.tone(0, 45, 1050, 0.16f);
        CHANNEL = g.bytes();

        g = new Gen(190);
        g.tone(0, 190, 420, 0.22f);
        BUSY = g.bytes();
    }

    private Sfx() { }

    /** Tiny sample-buffer builder. */
    private static final class Gen {
        final float[] s;

        Gen(int ms) {
            int n = RATE * ms / 1000;
            if (n * 2 == 640) n++;          // never collide with the size of a voice frame
            s = new float[n];
        }

        void tone(int atMs, int ms, double hz, float amp) {
            int off = RATE * atMs / 1000, n = RATE * ms / 1000, fade = RATE * 4 / 1000;
            for (int i = 0; i < n && off + i < s.length; i++) {
                double ph = 2 * Math.PI * hz * i / RATE;
                double v = Math.sin(ph) + 0.22 * Math.sin(3 * ph);      // a touch of square
                float env = Math.min(1f, Math.min(i, n - 1 - i) / (float) fade);
                s[off + i] += (float) (v * amp * env * 0.85);
            }
        }

        /** Static: attack in ms, decay (fade-out) in ms. */
        void noise(int atMs, int ms, float amp, int attackMs, int decayMs, int seed) {
            int off = RATE * atMs / 1000, n = RATE * ms / 1000;
            int att = Math.max(1, RATE * attackMs / 1000), dec = Math.max(1, RATE * decayMs / 1000);
            int x = 0x9E3779B9 ^ seed;
            float lp = 0, lp2 = 0, lpSlow = 0, crackle = 1;
            int block = RATE / 500;                                     // new crackle level every 2 ms
            for (int i = 0; i < n && off + i < s.length; i++) {
                x ^= x << 13;
                x ^= x >>> 17;
                x ^= x << 5;
                float white = (x & 0xFFFF) / 32768f - 1f;
                lp += 0.5f * (white - lp);                              // two-pole low-pass, about 1.8 kHz:
                lp2 += 0.5f * (lp - lp2);                               // the dull hiss of a small speaker
                lpSlow += 0.1f * (lp2 - lpSlow);                        // high-pass, about 270 Hz
                float band = lp2 - lpSlow;
                if (i % block == 0) {
                    int r = (x >>> 8) & 0xFF;
                    crackle = 0.55f + r / 255f * 0.8f;
                    if (r > 244) crackle = 2.1f;                        // the occasional pop
                }
                float env = Math.min(1f, i / (float) att);
                if (i > n - dec) env *= (n - i) / (float) dec;
                s[off + i] += band * crackle * amp * env * 2.4f;
            }
        }

        byte[] bytes() {
            byte[] out = new byte[s.length * 2];
            for (int i = 0; i < s.length; i++) {
                float v = s[i];
                if (v > 0.95f) v = 0.95f;
                if (v < -0.95f) v = -0.95f;
                short q = (short) (v * 32767);
                out[2 * i] = (byte) q;
                out[2 * i + 1] = (byte) (q >> 8);
            }
            return out;
        }
    }
}
