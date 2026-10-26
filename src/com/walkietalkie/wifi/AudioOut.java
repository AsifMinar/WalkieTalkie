package com.walkietalkie.wifi;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The speaker: voice, static and beeps through one AudioTrack on one thread.
 *
 * Voice frames live only in RAM, in a small recycled pool, and are dropped right after playing:
 * nothing is ever recorded. The audio output is released after 4 s of silence and the thread
 * exits after 12 s, so an idle radio holds no audio resources at all.
 */
final class AudioOut implements Runnable {

    interface Host {
        boolean soundsOn();

        /** The talker went silent without saying "over" (out of range, Wi-Fi dropped...). */
        void onSignalLost();

        /** A held message finished playing (or was cut short because we started talking). */
        void onMessageDone(HoldQueue.Msg m);
    }

    static final int RATE = 16000;
    private static final int FRAME = Wire.FRAME;

    private final Host host;
    private final AudioManager am;
    private final AudioAttributes attrs;
    private final AudioFocusRequest focus;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable abandonFocus = new Runnable() {
        @Override public void run() { am.abandonAudioFocusRequest(focus); }
    };

    private final ArrayBlockingQueue<byte[]> q = new ArrayBlockingQueue<>(96);
    private final ArrayBlockingQueue<byte[]> pool = new ArrayBlockingQueue<>(32);
    private final byte[] endMark = new byte[0];
    private final byte[] msgMark = new byte[1];         // "play the pending held message"
    private volatile HoldQueue.Msg pendingMsg;
    private volatile boolean flushReq;
    private Thread thread;
    private int prime = 3;          // frames buffered before starting (60 ms); grows if Wi-Fi is jittery
    private long playAt;            // when the next written frame will be heard

    // Loudness of each frame, stamped with when it will be heard, so visuals match the voice.
    private final long[] lvAt = new long[32];
    private final float[] lvVal = new float[32];
    private volatile int lvHead;

    AudioOut(Context c, Host host) {
        this.host = host;
        am = c.getSystemService(AudioManager.class);
        attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .build();
    }

    // ------------------------------------------------------------------ called from other threads

    /** A frame buffer from the pool: no garbage per packet. */
    byte[] obtain() {
        byte[] b = pool.poll();
        return b != null ? b : new byte[FRAME];
    }

    void recycle(byte[] b) {
        if (b.length == FRAME) pool.offer(b);
    }

    void offer(byte[] f) {
        if (!q.offer(f)) {                          // badly behind: drop the oldest voice frame
            byte[] head = q.peek();
            if (head != null && head.length == FRAME && q.remove(head)) recycle(head);
            if (!q.offer(f)) {
                recycle(f);
                return;
            }
        }
        ensure();
    }

    void end() {
        q.offer(endMark);
        ensure();
    }

    void sound(byte[] pcm) {
        if (q.offer(pcm)) ensure();
    }

    /** Plays a held message next, after anything already queued. False if the player is swamped. */
    boolean playMessage(HoldQueue.Msg m) {
        pendingMsg = m;
        if (!q.offer(msgMark)) {
            pendingMsg = null;
            return false;
        }
        ensure();
        return true;
    }

    /** Drops queued voice (e.g. when we start talking) but keeps queued sounds like the chirp. */
    void flush() {
        synchronized (this) {
            if (thread != null) flushReq = true;
        }
        Iterator<byte[]> it = q.iterator();
        HoldQueue.Msg dropped = null;
        while (it.hasNext()) {
            byte[] b = it.next();
            if (!isSound(b)) {
                it.remove();
                if (b == msgMark) {                     // a replay that never started: hand it back
                    dropped = pendingMsg;
                    pendingMsg = null;
                } else {
                    recycle(b);
                }
            }
        }
        if (dropped != null) {
            drain(dropped);
            host.onMessageDone(dropped);
        }
    }

    /** Loudness (0..1) of what is coming out of the speaker right now. */
    float levelNow() {
        long now = SystemClock.uptimeMillis();
        int h = lvHead;
        for (int k = 0; k < lvAt.length; k++) {
            int i = (h - k) & 31;
            long t = lvAt[i];
            if (t != 0 && t <= now) return now - t < 60 ? lvVal[i] : 0f;
        }
        return 0f;
    }

    // ------------------------------------------------------------------ playback thread

    private boolean isSound(byte[] b) {
        return b.length > 1 && b.length != FRAME;     // effect buffers are never one frame long
    }

    private synchronized void ensure() {
        if (thread == null) {
            thread = new Thread(this, "wt-play");
            thread.start();
        }
    }

    private synchronized boolean retire() {
        if (q.isEmpty()) {
            thread = null;
            return true;
        }
        return false;
    }

    @Override public void run() {
        boost(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioTrack track = null;
        boolean streaming = false, buffering = false;
        ArrayList<byte[]> pending = new ArrayList<>(24);
        long lastData = SystemClock.elapsedRealtime();
        while (true) {
            byte[] f;
            try {
                f = q.poll(streaming ? 60 : 1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                f = null;
            }
            long now = SystemClock.elapsedRealtime();

            if (flushReq) {
                flushReq = false;
                for (byte[] x : pending) recycle(x);
                pending.clear();
                if (streaming) {
                    track.pause();
                    track.flush();
                    streaming = false;
                    ui.post(abandonFocus);
                }
                Arrays.fill(lvAt, 0L);                  // nothing queued is going to play
                if (f != null && !isSound(f)) {         // stale voice from before the flush
                    if (f == msgMark) {                 // a replay that was about to start: hand it back
                        HoldQueue.Msg m = pendingMsg;
                        pendingMsg = null;
                        if (m != null) {
                            drain(m);
                            host.onMessageDone(m);
                        }
                    } else {
                        recycle(f);
                    }
                    f = null;
                }
                if (f == null) continue;
            }

            if (f == null) {
                if (streaming) {
                    if (now - lastData > 700) {         // signal lost without an "over"
                        finish(track, pending, false);
                        streaming = false;
                        host.onSignalLost();
                    } else if (!buffering) {
                        buffering = true;               // ran dry: rebuffer a little deeper
                        prime = Math.min(prime + 3, 20);
                    }
                } else {
                    if (track != null && now - lastData > 4000) {
                        track.release();
                        track = null;
                    }
                    if (track == null && now - lastData > 12000 && retire()) return;
                }
                continue;
            }

            if (f == msgMark) {
                HoldQueue.Msg m = pendingMsg;
                pendingMsg = null;
                if (m != null) {
                    if (streaming) {                    // shouldn't happen, but never mix two voices
                        finish(track, pending, false);
                        streaming = false;
                    }
                    if (track == null) track = newTrack();
                    replay(track, m);
                }
                lastData = SystemClock.elapsedRealtime();
                continue;
            }

            if (isSound(f)) {
                if (track == null) track = newTrack();
                if (track != null) {
                    if (streaming) {
                        track.write(f, 0, f.length);
                    } else {
                        track.play();
                        track.write(f, 0, f.length);
                        track.stop();                   // plays out, then stops
                    }
                }
                lastData = SystemClock.elapsedRealtime();
                continue;
            }

            if (f == endMark) {
                if (streaming) {
                    finish(track, pending, true);
                    streaming = false;
                }
                continue;
            }

            lastData = now;
            if (!streaming) {
                if (track == null) track = newTrack();
                if (track == null) {
                    recycle(f);
                    continue;
                }
                streaming = true;
                buffering = true;
                prime = Math.max(3, prime - 2);
                ui.removeCallbacks(abandonFocus);
                am.requestAudioFocus(focus);
                track.play();
                playAt = SystemClock.uptimeMillis() + 40;
                if (host.soundsOn()) {
                    track.write(Sfx.SQUELCH_OPEN, 0, Sfx.SQUELCH_OPEN.length);
                    playAt += Sfx.SQUELCH_OPEN.length / 32;     // 32 bytes per ms at 16 kHz
                }
            }
            if (buffering) {
                pending.add(f);
                if (pending.size() >= prime) {
                    for (byte[] x : pending) writeVoice(track, x);
                    pending.clear();
                    buffering = false;
                }
                continue;
            }
            writeVoice(track, f);
            while (q.size() > prime + 10) {                     // keep delay low: drop stale voice
                byte[] head = q.peek();
                if (head == null || head.length != FRAME || !q.remove(head)) break;
                recycle(head);
            }
        }
    }

    /**
     * Plays a held message like a normal transmission: squelch, voice, roger beep. Waits for
     * more frames while the sender is still talking. Stops at once if we start talking.
     */
    private void replay(AudioTrack track, HoldQueue.Msg m) {
        if (track == null) {
            drain(m);
            host.onMessageDone(m);
            return;
        }
        ui.removeCallbacks(abandonFocus);
        am.requestAudioFocus(focus);
        track.play();
        playAt = SystemClock.uptimeMillis() + 40;
        if (host.soundsOn()) {
            track.write(Sfx.SQUELCH_OPEN, 0, Sfx.SQUELCH_OPEN.length);
            playAt += Sfx.SQUELCH_OPEN.length / 32;
        }
        boolean over = false, cut = false;
        while (true) {
            if (flushReq) {                             // we pressed talk: stop now (the main loop clears the flag)
                cut = true;
                break;
            }
            byte[] f;
            try {
                f = m.frames.poll(700, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                f = null;
            }
            if (f == null) break;                       // they went silent without "over"
            if (f == HoldQueue.Msg.END) {
                over = true;
                break;
            }
            writeVoice(track, f);
        }
        if (cut) {
            track.pause();
            track.flush();
            Arrays.fill(lvAt, 0L);
            ui.post(abandonFocus);
            drain(m);
        } else {
            if (host.soundsOn()) {
                byte[] s = over ? Sfx.ROGER_TAIL : Sfx.TAIL;
                track.write(s, 0, s.length);
            }
            track.stop();
            ui.postDelayed(abandonFocus, 500);
        }
        host.onMessageDone(m);
    }

    private void drain(HoldQueue.Msg m) {
        byte[] x;
        while ((x = m.frames.pollFirst()) != null) recycle(x);
    }

    /** End of a transmission: roger beep + squelch tail, or just the tail if the signal dropped. */
    private void finish(AudioTrack track, ArrayList<byte[]> pending, boolean over) {
        for (byte[] x : pending) writeVoice(track, x);
        pending.clear();
        if (host.soundsOn()) {
            byte[] s = over ? Sfx.ROGER_TAIL : Sfx.TAIL;
            track.write(s, 0, s.length);
        }
        track.stop();
        ui.postDelayed(abandonFocus, 500);
    }

    private void writeVoice(AudioTrack t, byte[] f) {
        long now = SystemClock.uptimeMillis();
        if (playAt < now + 20) playAt = now + 20;              // caught up after a gap
        int i = (lvHead + 1) & 31;
        lvVal[i] = level(f, 0, f.length);
        lvAt[i] = playAt;
        lvHead = i;
        playAt += 20;                                          // each frame is 20 ms long
        t.write(f, 0, f.length);
        recycle(f);
    }

    private AudioTrack newTrack() {
        try {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, FRAME * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (t.getState() != AudioTrack.STATE_INITIALIZED) {
                t.release();
                return null;
            }
            return t;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ shared helpers

    /** Peak loudness of 16-bit little-endian PCM, mapped to 0..1 on a perceptual curve. */
    static float level(byte[] b, int off, int len) {
        int peak = 0;
        for (int i = off, end = off + len - 1; i < end; i += 2) {
            int v = (short) ((b[i] & 0xff) | (b[i + 1] << 8));
            if (v < 0) v = -v;
            if (v > peak) peak = v;
        }
        float l = (float) Math.sqrt(peak / 24000.0);
        l = (l - 0.08f) / 0.92f;
        return l < 0 ? 0 : (l > 1 ? 1 : l);
    }

    static void boost(int priority) {
        try {
            Process.setThreadPriority(priority);
        } catch (RuntimeException ignored) {
        }
    }
}
