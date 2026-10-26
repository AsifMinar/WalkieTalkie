package com.walkietalkie.wifi;

import java.net.InetAddress;

public class TrustTest {
    static int pass = 0, fail = 0;

    static void check(boolean ok, String what) {
        if (ok) pass++;
        else { fail++; System.out.println("FAIL: " + what); }
    }

    static Wire.Hello hello(int id, byte[] pub, String name, int ch) {
        Wire.Hello h = new Wire.Hello();
        h.id = id; h.pub = pub; h.name = name; h.channel = ch;
        return h;
    }

    public static void main(String[] a) throws Exception {
        Crypto me = new Crypto();
        InetAddress ipA = InetAddress.getByName("192.168.1.10"), ipX = InetAddress.getByName("192.168.1.66");
        int A = 0xC0A8010A, X = 0xC0A80142;
        PeerTable t = new PeerTable();
        long now = 1_000_000;

        // --- a new radio is invisible until it proves its key
        Crypto bob1 = new Crypto();
        Wire.Hello h1 = hello(7, bob1.pub, "Bob", 1);
        check(t.hello(h1, ipA, A, now) == PeerTable.NEW_KEY, "new radio: derive keys");
        Crypto.Keys k1 = me.pair(bob1.pub);
        check(t.candidate(h1, ipA, A, k1, now), "candidate stored");
        check(t.count(1, now) == 0 && t.targets(1, now).length == 0, "unproven radio gets no audio and isn't listed");
        check(t.pingTarget(7, now) != null, "we can ping the candidate");
        PeerTable.Sessions s = t.sessions(7, A);
        check(s != null && s.live == null && s.candidate == k1, "packets from its address try the candidate key");
        check(t.sessions(7, X) == null, "same id from another address has no keys");
        check(t.accept(7, k1, 1, 1, now) == PeerTable.PROMOTED, "proof (decrypted pong) promotes it");
        check(t.count(1, now) == 1 && t.targets(1, now).length == 1, "now listed and receives audio");

        // --- replays
        check(t.accept(7, k1, 1, 1, now) == PeerTable.REJECT, "same counter again: replay rejected");
        check(t.accept(7, k1, 2, 1, now) == PeerTable.OK, "next counter accepted");
        check(t.hello(h1, ipX, X, now + 10) == PeerTable.IGNORE, "their hello copied from another address: ignored");
        check(t.hello(h1, ipA, A, now + 10) == PeerTable.REFRESHED, "their hello from their address: refresh");

        // --- hijack: someone else claims id 7 with their own key
        Crypto mallory = new Crypto();
        Wire.Hello hm = hello(7, mallory.pub, "Bob", 1);
        check(t.hello(hm, ipX, X, now + 5_000) == PeerTable.IGNORE, "active radio can't be claimed from another address");
        // even after Bob goes quiet, Mallory only becomes a candidate; Bob's proven keys stay in use
        long later = now + 25_000;
        check(t.hello(hm, ipX, X, later) == PeerTable.NEW_KEY, "after 20 s quiet, a move is allowed to start");
        Crypto.Keys km = me.pair(mallory.pub);
        t.candidate(hm, ipX, X, km, later);
        check(t.targets(1, later)[0].addr.equals(ipA), "audio still goes to the proven address until proof");

        // --- restart: Bob comes back with a new key on the same address
        PeerTable t2 = new PeerTable();
        t2.hello(h1, ipA, A, now);
        t2.candidate(h1, ipA, A, k1, now);
        t2.accept(7, k1, 1, 1, now);
        Crypto bob2 = new Crypto();
        Wire.Hello h2 = hello(7, bob2.pub, "Bob", 1);
        check(t2.hello(h2, ipA, A, now + 100) == PeerTable.NEW_KEY, "restart on same address: new key considered");
        Crypto.Keys k2 = me.pair(bob2.pub);
        t2.candidate(h2, ipA, A, k2, now + 100);
        PeerTable.Sessions s2 = t2.sessions(7, A);
        check(s2.live == k1 && s2.candidate == k2, "both old and new keys accepted until proof");
        check(t2.accept(7, k2, 1, 1, now + 200) == PeerTable.PROMOTED, "new session proven and promoted");
        check(t2.hello(h1, ipA, A, now + 300) == PeerTable.IGNORE, "replay of Bob's OLD session refused");
        check(t2.accept(7, k1, 99, 1, now + 300) == PeerTable.REJECT, "packets under the retired key refused");

        // --- replayed hello for a session whose private key is gone never gets trusted
        PeerTable t3 = new PeerTable();
        t3.hello(h1, ipA, A, now);
        t3.candidate(h1, ipA, A, k1, now);
        check(t3.count(-1, now) == 0, "replayed hello alone gives nothing");
        t3.prune(now + PeerTable.PROOF_TIMEOUT_MS + 1);
        check(t3.size() == 0, "unproven key expires after 30 s");

        // --- one radio per address
        PeerTable t4 = new PeerTable();
        t4.hello(h1, ipA, A, now);
        t4.candidate(h1, ipA, A, k1, now);
        t4.accept(7, k1, 1, 1, now);
        Crypto other = new Crypto();
        Wire.Hello ho = hello(8, other.pub, "Eve", 1);
        check(t4.hello(ho, ipA, A, now + 1000) == PeerTable.IGNORE, "a second id at an active address is refused");
        check(t4.hello(ho, ipA, A, now + 25_000) == PeerTable.NEW_KEY && t4.evicted() == 7,
                "after the old one went quiet, the new one replaces it");

        // --- flood: memory stays bounded
        PeerTable t5 = new PeerTable();
        int accepted = 0;
        for (int i = 0; i < 500; i++) {
            Crypto c = new Crypto();
            Wire.Hello hf = hello(1000 + i, c.pub, "Flood", 1);
            int ip = 0x0A000000 + i;
            InetAddress ia = InetAddress.getByAddress(new byte[]{10, 0, (byte) (i >> 8), (byte) i});
            if (t5.hello(hf, ia, ip, now) == PeerTable.NEW_KEY && t5.candidate(hf, ia, ip, me.pair(c.pub), now)) accepted++;
        }
        check(t5.size() <= PeerTable.MAX_PEERS, "500 fake radios: table capped at " + t5.size());
        t5.prune(now + PeerTable.PROOF_TIMEOUT_MS + 1);
        check(t5.size() == 0, "fakes that never prove anything are cleared");

        // --- reply rate limit
        check(t.mayReply(7, now, 2000) && !t.mayReply(7, now + 500, 2000) && t.mayReply(7, now + 2500, 2000),
                "answers to one radio limited to one per 2 s");

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
