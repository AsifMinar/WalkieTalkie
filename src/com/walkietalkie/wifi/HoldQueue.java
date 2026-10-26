package com.walkietalkie.wifi;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Messages that arrive while the line is busy (we are talking, or someone else is) are kept here,
 * in RAM only, and played in arrival order as soon as the line is free. A message can still be
 * growing while it plays (its sender is still talking): playback simply catches up to live.
 *
 * Bounded: at most {@value #MAX_MSGS} messages and the newest {@value #MAX_FRAMES} frames
 * (30 s, about 1 MB) in total; the oldest audio is dropped first, so you always hear the latest.
 * Pure Java and thread-safe, so the rules are tested on a desktop JVM.
 */
final class HoldQueue {

    static final class Msg {
        /** Marks "over" inside the frame queue. */
        static final byte[] END = new byte[0];
        final int id;
        final boolean direct;
        final LinkedBlockingDeque<byte[]> frames = new LinkedBlockingDeque<>();
        volatile boolean closed;
        int lastSeq = -1;

        Msg(int id, boolean direct) {
            this.id = id;
            this.direct = direct;
        }
    }

    interface Recycler {
        void recycle(byte[] frame);
    }

    static final int MAX_FRAMES = 1500;
    static final int MAX_MSGS = 8;
    /** add() results */
    static final int DROPPED = 0, APPENDED = 1, STARTED = 2;

    private final ArrayList<Msg> list = new ArrayList<>();
    private final Recycler recycler;
    private Msg playing;

    HoldQueue(Recycler recycler) {
        this.recycler = recycler;
    }

    /** True if this sender has a held message that is still open (they're still talking). */
    synchronized boolean isHeld(int id) {
        return open(id) != null;
    }

    /** Adds one voice frame for this sender. STARTED if it began a new held message. */
    synchronized int add(int id, boolean direct, int seq, byte[] frame) {
        int result = APPENDED;
        Msg m = open(id);
        if (m == null) {
            if (list.size() >= MAX_MSGS) {
                recycler.recycle(frame);
                return DROPPED;
            }
            m = new Msg(id, direct);
            list.add(m);
            result = STARTED;
        } else if (m.lastSeq >= 0) {
            int d = (seq - m.lastSeq) & 0xffff;
            if (d == 0 || d > 0x8000) {                   // duplicate or late packet
                recycler.recycle(frame);
                return DROPPED;
            }
        }
        m.lastSeq = seq;
        m.frames.offerLast(frame);
        trim();
        return result;
    }

    /** "Over" from a sender. False if nothing of theirs is open here. */
    synchronized boolean close(int id) {
        Msg m = open(id);
        if (m == null) return false;
        m.closed = true;
        m.frames.offerLast(Msg.END);
        return true;
    }

    /** The next message to play, now marked as playing. Null if one is playing or none waits. */
    synchronized Msg next() {
        if (playing != null || list.isEmpty()) return null;
        playing = list.get(0);
        return playing;
    }

    /** A message finished (or was cut short). True if it was the one playing. */
    synchronized boolean done(Msg m) {
        list.remove(m);
        if (playing != m) return false;
        playing = null;
        return true;
    }

    /** Puts a message that couldn't start back in line, unplayed. */
    synchronized void unmark(Msg m) {
        if (playing == m) playing = null;
    }

    /** Messages waiting, not counting the one playing. */
    synchronized int waiting() {
        return list.size() - (playing != null ? 1 : 0);
    }

    /** Forgets held messages: all, or only channel ones (private calls still apply after a channel change). */
    synchronized void drop(boolean keepPrivate) {
        Iterator<Msg> it = list.iterator();
        while (it.hasNext()) {
            Msg m = it.next();
            if (m == playing || (keepPrivate && m.direct)) continue;   // the playing one is cut separately
            it.remove();
            byte[] x;
            while ((x = m.frames.pollFirst()) != null) recycler.recycle(x);
        }
    }

    synchronized int frameCount() {
        int n = 0;
        for (Msg m : list) n += m.frames.size();
        return n;
    }

    private Msg open(int id) {
        for (int i = list.size() - 1; i >= 0; i--) {
            Msg m = list.get(i);
            if (m.id == id && !m.closed) return m;
        }
        return null;
    }

    /** Over the limit: drop the oldest audio first, so the newest is what you hear. */
    private void trim() {
        int total = frameCount();
        while (total > MAX_FRAMES) {
            Msg o = list.get(0);
            byte[] x = o.frames.pollFirst();
            if (x == null || x == Msg.END) {
                if (x != null) o.frames.offerFirst(x);
                if (o != playing && list.size() > 1) {
                    list.remove(0);
                    continue;
                }
                break;
            }
            recycler.recycle(x);
            total--;
        }
    }
}
