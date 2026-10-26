package com.walkietalkie.wifi;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Random;
import javax.crypto.Cipher;

public class SecTest {
    static int pass = 0, fail = 0;

    static void check(boolean ok, String what) {
        if (ok) pass++;
        else { fail++; System.out.println("FAIL: " + what); }
    }

    public static void main(String[] a) throws Exception {
        Random rnd = new Random(42);
        // 1. two radios agree on keys, one per direction
        Crypto A = new Crypto(), B = new Crypto();
        Crypto.Keys ab = A.pair(B.pub), ba = B.pair(A.pub);
        check(Arrays.equals(ab.send.getEncoded(), ba.recv.getEncoded()), "A->B key matches");
        check(Arrays.equals(ab.recv.getEncoded(), ba.send.getEncoded()), "B->A key matches");
        check(!Arrays.equals(ab.send.getEncoded(), ab.recv.getEncoded()), "directions use different keys");
        Cipher ca = Crypto.newCipher(), cb = Crypto.newCipher();

        // 2. voice round trip
        byte[] pcm = new byte[Wire.FRAME];
        rnd.nextBytes(pcm);
        byte[] pkt = new byte[Wire.MAX_PACKET];
        Wire.header(pkt, Wire.AUDIO, 1234, 3, 0);
        pkt[11] = 7;
        int len = A.seal(ca, ab.send, pkt, pcm, Wire.FRAME);
        check(len == Wire.MAX_PACKET, "audio packet size " + len);
        byte[] out = new byte[Wire.FRAME];
        check(Crypto.open(cb, ba.recv, pkt, len, out) == Wire.FRAME && Arrays.equals(out, pcm), "B decrypts A's voice");
        check(!containsRun(pkt, pcm), "ciphertext does not contain the plain audio");
        byte[] end = new byte[Wire.SEALED_HDR + Wire.TAG];
        Wire.header(end, Wire.END, 1234, 3, 0);
        int el = A.seal(ca, ab.send, end, null, 0);
        check(el == 36 && Crypto.open(cb, ba.recv, end, el, new byte[0]) == 0, "empty END packet round trip");

        // 3. any single-byte change anywhere is rejected (header, counter, ciphertext, tag)
        int rejected = 0;
        for (int i = 0; i < len; i++) {
            byte[] t = pkt.clone();
            t[i] ^= 0x01;
            if (Crypto.open(cb, ba.recv, t, len, out) < 0) rejected++;
        }
        check(rejected == len, "tampering detected at every byte position (" + rejected + "/" + len + ")");

        // 4. wrong direction / outsider keys rejected
        check(Crypto.open(cb, ba.send, pkt, len, out) < 0, "wrong-direction key rejected");
        Crypto E = new Crypto();
        Crypto.Keys eb = E.pair(B.pub);
        check(Crypto.open(cb, eb.recv, pkt, len, out) < 0, "eavesdropper's key cannot decrypt");

        // 5. counters never repeat (nonce safety) and grow (replay detection)
        long last = 0;
        boolean mono = true;
        for (int i = 0; i < 1000; i++) {
            A.seal(ca, ab.send, pkt, pcm, Wire.FRAME);
            long c = Crypto.counterOf(pkt);
            if (c <= last) mono = false;
            last = c;
        }
        check(mono, "counters strictly increase");

        // 6. invalid public keys are refused (invalid-curve attack)
        byte[] bad = B.pub.clone();
        bad[64] ^= 1;
        check(throwsOnPair(A, bad), "off-curve point refused");
        byte[] zero = new byte[65];
        zero[0] = 4;
        check(throwsOnPair(A, zero), "zero point refused");
        byte[] pfx = B.pub.clone();
        pfx[0] = 2;
        check(throwsOnPair(A, pfx), "wrong prefix refused");
        byte[] big = new byte[65];
        big[0] = 4;
        Arrays.fill(big, 1, 65, (byte) 0xFF);
        check(throwsOnPair(A, big), "coordinates >= p refused");

        // 7. hellos: fields round-trip, unknown flags and wrong sizes are refused
        byte[] h = new byte[Wire.MAX_HELLO];
        int hl = Wire.buildHello(h, 99, 5, Wire.F_REPLY, A.pub, Wire.nameBytes("Raven-42"));
        Wire.Hello ph = Wire.parseHello(h, hl);
        check(ph != null && ph.id == 99 && ph.channel == 5 && ph.name.equals("Raven-42")
                && Arrays.equals(ph.pub, A.pub) && ph.flags == Wire.F_REPLY, "hello fields parsed");
        byte[] f = h.clone();
        f[9] = (byte) 0x80;
        check(Wire.parseHello(f, hl) == null, "unknown flag bits refused");
        check(Wire.parseHello(h, hl - 1) == null && Wire.parseHello(h, hl + 1) == null, "wrong length refused");
        f = h.clone();
        f[8] = 17;
        check(Wire.parseHello(f, hl) == null, "channel 17 refused");
        f = h.clone();
        f[2] = 2;
        check(Wire.parseHello(f, hl) == null, "old protocol version ignored");

        // 7b. private call: only the person it's meant for can decrypt it
        Crypto C = new Crypto();
        Crypto.Keys ac = A.pair(C.pub), ca2 = C.pair(A.pub);
        Wire.header(pkt, Wire.AUDIO, 1, 1, Wire.F_DIRECT);
        int dl = A.seal(ca, ab.send, pkt, pcm, Wire.FRAME);              // A -> B privately
        check(Crypto.open(cb, ba.recv, pkt, dl, out) == Wire.FRAME, "B decrypts A's private call");
        check(Crypto.open(cb, ca2.recv, pkt, dl, out) < 0, "C cannot decrypt A's private call to B");
        byte[] g = pkt.clone();
        g[9] = 0;                                                         // try to turn it into a channel call
        check(Crypto.open(cb, ba.recv, g, dl, out) < 0, "the private flag cannot be removed in transit");

        // 8. names
        check(Wire.cleanName("Ra\u202Even").equals("Raven"), "right-to-left override removed");
        check(Wire.cleanName("\u0000Bob\u0007\n").equals("Bob"), "control characters removed");
        check(Wire.cleanName("  A    B  ").equals("A B"), "spaces collapsed");
        check(Wire.cleanName("12345678901234567890EXTRA").length() == 20, "name capped at 20 characters");
        check(Wire.cleanName("\u200B\u200E").equals("Someone"), "invisible-only name replaced");
        check(Wire.cleanName("Ali \uD83D\uDC4B").equals("Ali \uD83D\uDC4B"), "emoji kept");

        // 9. fuzzing: random and mutated packets must never crash and never be accepted
        int crashes = 0;
        byte[] valid = new byte[Wire.MAX_HELLO];
        int vl = Wire.buildHello(valid, 7, 2, 0, A.pub, Wire.nameBytes("Zed"));
        for (int i = 0; i < 300_000; i++) {
            byte[] r;
            int rl;
            if (i % 2 == 0) {
                rl = rnd.nextInt(Wire.MAX_HELLO + 40);
                r = new byte[Math.max(rl, 1)];
                rnd.nextBytes(r);
                if (rl >= 3) { r[0] = 'W'; r[1] = 'T'; r[2] = Wire.VERSION; }
                if (rl > 3) r[3] = Wire.HELLO;
            } else {
                r = valid.clone();
                rl = vl;
                int flips = 1 + rnd.nextInt(3);
                for (int k = 0; k < flips; k++) r[rnd.nextInt(vl)] ^= (byte) (1 + rnd.nextInt(255));
            }
            try {
                Wire.Hello x = Wire.parseHello(r, Math.min(rl, r.length));
                if (x != null && (x.name == null || x.pub.length != Wire.PUB || !Wire.validChannel(x.channel))) crashes++;
            } catch (Throwable t) {
                crashes++;
            }
        }
        check(crashes == 0, "hello parser never crashes on 300,000 fuzzed packets (crashes=" + crashes + ")");
        int sealedCrash = 0, sealedAccept = 0;
        for (int i = 0; i < 20_000; i++) {
            int rl = rnd.nextInt(Wire.MAX_PACKET + 1);
            byte[] r = new byte[Math.max(rl, 1)];
            rnd.nextBytes(r);
            try {
                if (Crypto.open(cb, ba.recv, r, rl, out) >= 0) sealedAccept++;
            } catch (Throwable t) {
                sealedCrash++;
            }
        }
        check(sealedCrash == 0 && sealedAccept == 0, "20,000 random sealed packets: none crash, none accepted");

        // 10. speed (desktop, indicative)
        long t0 = System.nanoTime();
        for (int i = 0; i < 5000; i++) {
            Wire.header(pkt, Wire.AUDIO, 1, 1, 0);
            int n = A.seal(ca, ab.send, pkt, pcm, Wire.FRAME);
            Crypto.open(cb, ba.recv, pkt, n, out);
        }
        double us = (System.nanoTime() - t0) / 5000 / 1000.0;
        long t2 = System.nanoTime();
        for (int i = 0; i < 50; i++) A.pair(new Crypto().pub);
        double pm = (System.nanoTime() - t2) / 50 / 1e6;
        System.out.printf("encrypt+decrypt one voice packet: %.1f us (a packet arrives every 20,000 us)%n", us);
        System.out.printf("key agreement with a new radio: %.2f ms%n", pm);
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static boolean throwsOnPair(Crypto c, byte[] pub) {
        try { c.pair(pub); return false; } catch (GeneralSecurityException e) { return true; }
    }

    static boolean containsRun(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + 16 <= hay.length; i++) {
            for (int j = 0; j < 16; j++) if (hay[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }
}
