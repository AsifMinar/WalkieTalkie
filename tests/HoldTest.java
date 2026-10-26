package com.walkietalkie.wifi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Talking over each other: nothing is lost, order is kept, memory is bounded. */
public class HoldTest {
    static int pass = 0, fail = 0;
    static final AtomicInteger recycled = new AtomicInteger();
    static final HoldQueue.Recycler R = new HoldQueue.Recycler() {
        @Override public void recycle(byte[] f) { recycled.incrementAndGet(); }
    };

    static void check(boolean ok, String what) {
        if (ok) pass++;
        else { fail++; System.out.println("FAIL: " + what); }
    }

    static byte[] frame(int seq) {
        byte[] f = new byte[Wire.FRAME];
        f[0] = (byte) (seq >>> 8);
        f[1] = (byte) seq;
        return f;
    }

    static int seqOf(byte[] f) { return ((f[0] & 0xff) << 8) | (f[1] & 0xff); }

    /** Plays a message like AudioOut.replay: frames until END, or 700 ms of silence. */
    static List<Integer> play(HoldQueue.Msg m, boolean[] over) throws InterruptedException {
        List<Integer> got = new ArrayList<>();
        while (true) {
            byte[] f = m.frames.poll(700, TimeUnit.MILLISECONDS);
            if (f == null) { over[0] = false; return got; }
            if (f == HoldQueue.Msg.END) { over[0] = true; return got; }
            got.add(seqOf(f));
        }
    }

    static boolean inOrder(List<Integer> s, int from, int count) {
        if (s.size() != count) return false;
        for (int i = 0; i < count; i++) if (s.get(i) != ((from + i) & 0xffff)) return false;
        return true;
    }

    public static void main(String[] a) throws Exception {
        boolean[] over = new boolean[1];
        final int B = -1_234_567, C = 98_765, D = 4_242;     // negative ids too, like real phones

        // 1. Your report: I talk, B talks at the same time and finishes first.
        HoldQueue q = new HoldQueue(R);
        for (int s = 0; s < 100; s++) q.add(B, false, s, frame(s));   // 2 s of B while I'm talking
        q.close(B);                                                    // B says "over"
        check(q.waiting() == 1, "B's message is waiting while I talk");
        HoldQueue.Msg m = q.next();                                    // I let go: line is free
        List<Integer> heard = play(m, over);
        check(m.id == B && inOrder(heard, 0, 100) && over[0], "after I finish, I hear all of B's message, in order, then the roger beep");
        check(q.done(m) && q.waiting() == 0, "queue is empty afterwards");

        // 2. B is still talking when I finish: the held part plays, then it continues live.
        q = new HoldQueue(R);
        for (int s = 0; s < 100; s++) q.add(B, false, s, frame(s));
        final HoldQueue.Msg growing = q.next();
        final HoldQueue fq = q;
        Thread sender = new Thread(new Runnable() {                     // B keeps talking for 2 more seconds
            @Override public void run() {
                try {
                    for (int s = 100; s < 200; s++) { fq.add(B, false, s, frame(s)); Thread.sleep(2); }
                    fq.close(B);
                } catch (InterruptedException ignored) { }
            }
        });
        sender.start();
        heard = play(growing, over);
        sender.join();
        check(inOrder(heard, 0, 200) && over[0], "B still talking: I hear the held part and the rest without a gap (" + heard.size() + " frames)");

        // 3. A very long overlap keeps only the newest 30 s.
        q = new HoldQueue(R);
        recycled.set(0);
        for (int s = 0; s < 2250; s++) q.add(B, false, s & 0xffff, frame(s));   // 45 s
        q.close(B);
        check(q.frameCount() == HoldQueue.MAX_FRAMES + 1, "45 s overlap: memory capped at 30 s (" + q.frameCount() + " items)");
        heard = play(q.next(), over);
        check(inOrder(heard, 750, 1500) && over[0], "...and it's the LAST 30 s you hear, not the first");
        check(recycled.get() == 750, "dropped audio returned to the pool (" + recycled.get() + ")");

        // 4. Two people talk over me: they play in the order they started.
        q = new HoldQueue(R);
        for (int s = 0; s < 10; s++) q.add(B, false, s, frame(s));
        for (int s = 0; s < 10; s++) q.add(C, false, s, frame(s));
        q.close(C);
        q.close(B);
        HoldQueue.Msg first = q.next();
        check(first.id == B && q.next() == null, "B first; nothing else starts while B plays");
        play(first, over);
        q.done(first);
        HoldQueue.Msg second = q.next();
        check(second != null && second.id == C && inOrder(play(second, over), 0, 10), "then C, complete");

        // 5. Same person talks twice while I'm busy: two separate messages, in order.
        q = new HoldQueue(R);
        for (int s = 0; s < 5; s++) q.add(D, false, s, frame(s));
        q.close(D);
        for (int s = 5; s < 9; s++) q.add(D, false, s, frame(s));
        q.close(D);
        check(q.waiting() == 2, "two messages from the same person kept separately");
        HoldQueue.Msg d1 = q.next();
        check(inOrder(play(d1, over), 0, 5), "first message intact");
        q.done(d1);
        check(inOrder(play(q.next(), over), 5, 4), "second message intact");

        // 6. Duplicates and late packets are ignored.
        q = new HoldQueue(R);
        q.add(B, false, 10, frame(10));
        check(q.add(B, false, 10, frame(10)) == HoldQueue.DROPPED, "duplicate dropped");
        check(q.add(B, false, 9, frame(9)) == HoldQueue.DROPPED, "late packet dropped");
        check(q.add(B, false, 11, frame(11)) == HoldQueue.APPENDED, "next one kept");

        // 7. Changing channel forgets channel talk but keeps private calls.
        q = new HoldQueue(R);
        q.add(B, false, 0, frame(0));
        q.add(C, true, 0, frame(0));
        q.drop(true);
        HoldQueue.Msg left = q.next();
        check(left != null && left.id == C && left.direct && q.waiting() == 0, "channel change: private call kept, channel talk dropped");

        // 8. Bounded number of messages.
        q = new HoldQueue(R);
        int started = 0;
        for (int id = 1; id <= 12; id++) if (q.add(id, false, 0, frame(0)) == HoldQueue.STARTED) started++;
        check(started == HoldQueue.MAX_MSGS, "at most " + HoldQueue.MAX_MSGS + " held messages (" + started + ")");

        // 9. Stress: three senders and the player on real threads at once, 50 rounds.
        int ok = 0;
        for (int round = 0; round < 50; round++) {
            final HoldQueue sq = new HoldQueue(R);
            final int[] ids = {B, C, D};
            Thread[] ts = new Thread[3];
            for (int k = 0; k < 3; k++) {
                final int id = ids[k];
                ts[k] = new Thread(new Runnable() {
                    @Override public void run() {
                        for (int s = 0; s < 150; s++) {
                            sq.add(id, false, s, frame(s));
                            if (s % 25 == 0) Thread.yield();
                        }
                        sq.close(id);
                    }
                });
                ts[k].start();
            }
            int complete = 0;
            for (int k = 0; k < 3; k++) {
                HoldQueue.Msg sm = null;
                for (int w = 0; w < 1000 && (sm = sq.next()) == null; w++) Thread.sleep(1);
                if (sm == null) break;
                if (inOrder(play(sm, over), 0, 150) && over[0]) complete++;
                sq.done(sm);
            }
            for (Thread t : ts) t.join();
            if (complete == 3 && sq.waiting() == 0) ok++;
        }
        check(ok == 50, "concurrent stress: all 3 messages complete and in order in " + ok + "/50 rounds");

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
