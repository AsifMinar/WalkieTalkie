package com.walkietalkie.wifi;

import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Several radios on a simulated Wi-Fi with packet loss and reordering. Each radio runs the same
 * receive/handshake logic as RadioEngine.Receiver (hello -> candidate -> ping -> pong -> promote).
 */
public class NetSim {
    static final Random rnd = new Random(7);
    static long now = 1_000_000;
    static double loss = 0.15;
    static final List<Object[]> air = new ArrayList<>();   // {fromIp, toIp or -1 (broadcast), bytes}
    static final List<Radio> radios = new ArrayList<>();

    static final class Radio {
        final int id, ip; final InetAddress addr; final String name;
        Crypto cr; Cipher cipher; final PeerTable table = new PeerTable();
        final List<byte[]> overheard = new ArrayList<>();      // packets on the air, as a sniffer sees them
        int heard; int channel = 1;
        Radio(int id, int ip, String name) throws Exception {
            this.id = id; this.ip = ip; this.name = name;
            addr = InetAddress.getByAddress(new byte[]{(byte) (ip >>> 24), (byte) (ip >>> 16), (byte) (ip >>> 8), (byte) ip});
            restart();
        }
        void restart() throws Exception { cr = new Crypto(); cipher = Crypto.newCipher(); table.clear(); sendHello(true, -1); }
        void send(byte[] b, int len, int to) { if (rnd.nextDouble() >= loss) air.add(new Object[]{ip, to, Arrays.copyOf(b, len)}); }
        void sendHello(boolean ask, int to) {
            byte[] b = new byte[Wire.MAX_HELLO];
            send(b, Wire.buildHello(b, id, channel, ask ? Wire.F_REPLY : 0, cr.pub, Wire.nameBytes(name)), to);
        }
        void seal(byte type, SecretKeySpec key, int to, byte[] plain, int plainLen) { seal(type, key, to, plain, plainLen, 0); }
        void seal(byte type, SecretKeySpec key, int to, byte[] plain, int plainLen, int flags) {
            byte[] b = new byte[Wire.MAX_PACKET]; Wire.header(b, type, id, channel, flags);
            try { send(b, cr.seal(cipher, key, b, plain, plainLen), to); } catch (GeneralSecurityException e) { throw new RuntimeException(e); }
        }
        void talk() { byte[] pcm = new byte[Wire.FRAME]; rnd.nextBytes(pcm);
            for (PeerTable.Target t : table.targets(channel, now)) seal(Wire.AUDIO, t.key, ipOf(t.addr), pcm, Wire.FRAME); }
        boolean talkTo(int pid) { PeerTable.Target t = table.target(pid, now); if (t == null) return false;
            byte[] pcm = new byte[Wire.FRAME]; rnd.nextBytes(pcm); seal(Wire.AUDIO, t.key, ipOf(t.addr), pcm, Wire.FRAME, Wire.F_DIRECT); return true; }
        void beacon() { sendHello(false, -1); table.prune(now); }
        void retry() { for (PeerTable.Target t : table.duePings(now)) { sendHello(false, ipOf(t.addr)); seal(Wire.PING, t.key, ipOf(t.addr), null, 0); } }

        // ---- same logic as RadioEngine.Receiver ----
        void handle(int src, InetAddress from, byte[] b, int len) {
            if (!Wire.isOurs(b, len) || src == ip) return;
            int pid = Wire.readInt(b, 4);
            if (pid == id || pid == 0) return;
            byte type = b[3];
            if (type == Wire.HELLO) { onHello(from, src, b, len); return; }
            if (type < Wire.AUDIO || type > Wire.PONG) return;
            int ch = b[8] & 0xff;
            if (!Wire.validChannel(ch) || len > Wire.MAX_PACKET) return;
            PeerTable.Sessions ss = table.sessions(pid, src);
            if (ss == null) { sendHello(true, src); return; }
            byte[] out = type == Wire.AUDIO ? new byte[Wire.FRAME] : new byte[0];
            int want = type == Wire.AUDIO ? Wire.FRAME : 0;
            Crypto.Keys used = null;
            if (ss.live != null && Crypto.open(cipher, ss.live.recv, b, len, out) == want) used = ss.live;
            else if (ss.candidate != null && Crypto.open(cipher, ss.candidate.recv, b, len, out) == want) used = ss.candidate;
            int r = used == null ? PeerTable.REJECT : table.accept(pid, used, Crypto.counterOf(b), ch, now);
            if (r == PeerTable.REJECT) return;
            if (type == Wire.AUDIO) heard++;
            else if (type == Wire.BYE) table.remove(pid);
            else if (type == Wire.PING) seal(Wire.PONG, used.send, src, null, 0);
        }
        void onHello(InetAddress from, int src, byte[] b, int len) {
            Wire.Hello h = Wire.parseHello(b, len);
            if (h == null || h.id == id || h.id == 0) return;
            int r = table.hello(h, from, src, now);
            boolean wantsReply = (h.flags & Wire.F_REPLY) != 0;
            if (r == PeerTable.IGNORE) return;
            if (r == PeerTable.NEW_KEY) {
                Crypto.Keys k; try { k = cr.pair(h.pub); } catch (GeneralSecurityException e) { return; }
                if (!table.candidate(h, from, src, k, now)) return;
                sendHello(false, src); ping(h.id); return;
            }
            if (r == PeerTable.REPING) { ping(h.id); if (wantsReply && table.mayReply(h.id, now, 2000)) sendHello(false, src); return; }
            if (wantsReply && table.mayReply(h.id, now, 2000)) sendHello(false, src);
        }
        void ping(int pid) { PeerTable.Target t = table.pingTarget(pid, now); if (t != null) seal(Wire.PING, t.key, ipOf(t.addr), null, 0); }
    }

    static int ipOf(InetAddress a) { return Wire.readInt(a.getAddress(), 0); }
    static InetAddress toAddr(int ip) { try { return InetAddress.getByAddress(new byte[]{(byte) (ip >>> 24), (byte) (ip >>> 16), (byte) (ip >>> 8), (byte) ip}); } catch (Exception e) { throw new RuntimeException(e); } }

    static void deliver(int maxRounds) {
        for (int round = 0; round < maxRounds; round++) {
            List<Object[]> batch = new ArrayList<>(air); air.clear();
            Collections.shuffle(batch, rnd);                         // random reordering
            for (Object[] p : batch) {
                int from = (Integer) p[0], to = (Integer) p[1]; byte[] b = (byte[]) p[2];
                for (Radio r : radios) {
                    if (r.ip != from) r.overheard.add(b);                   // everyone can sniff the air
                    if (r.ip == from || (to != -1 && to != r.ip)) continue;
                    try { r.handle(from, toAddr(from), b, b.length); } catch (RuntimeException e) { throw new IllegalStateException("crash", e); }
                }
            }
            now += 50;
            if (round % 20 == 19) for (Radio r : radios) r.retry();   // the 1 s handshake retry
        }
    }

    static boolean allConnected() {
        for (Radio r : radios) if (r.table.count(1, now) != radios.size() - 1) return false;
        return true;
    }

    public static void main(String[] a) throws Exception {
        int okRuns = 0, runs = 200; long worstMs = 0; int totalBeacons = 0;
        List<Long> times = new ArrayList<>();
        for (int run = 0; run < runs; run++) {
            radios.clear(); air.clear(); now = 1_000_000;
            int n = 2 + rnd.nextInt(4);                              // 2 to 5 radios
            for (int i = 0; i < n; i++) radios.add(new Radio(i % 2 == 0 ? 100 + i : -100 - i, 0xC0A80100 + 10 + i, "R" + i));   // half negative, like real phones
            long start = now;
            for (int step = 1; step <= 1200 && !allConnected(); step++) {    // 50 ms steps, up to 60 s
                List<Object[]> batch = new ArrayList<>(air); air.clear();
                Collections.shuffle(batch, rnd);
                for (Object[] p : batch) {
                    int from = (Integer) p[0], to = (Integer) p[1]; byte[] b = (byte[]) p[2];
                    for (Radio r : radios) { if (r.ip != from) r.overheard.add(b); if (r.ip != from && (to == -1 || to == r.ip)) r.handle(from, toAddr(from), b, b.length); }
                }
                now += 50;
                if (step % 20 == 0) for (Radio r : radios) r.retry();      // 1 s handshake retry task
                if (step % 300 == 0) for (Radio r : radios) r.beacon();    // 15 s beacon
            }
            if (allConnected()) { okRuns++; times.add(now - start); worstMs = Math.max(worstMs, now - start); }
        }
        Collections.sort(times);
        long median = times.isEmpty() ? 0 : times.get(times.size() / 2);
        System.out.printf("handshake with %.0f%% loss + reordering: %d/%d groups fully connected; median %.2f s, worst %.1f s%n", loss * 100, okRuns, runs, median / 1000.0, worstMs / 1000.0);

        // voice after connection, and a restart in the middle
        radios.clear(); air.clear(); loss = 0.0; now = 2_000_000;
        Radio x = new Radio(1, 0xC0A80114, "X"), y = new Radio(-2, 0xC0A80115, "Y");
        radios.add(x); radios.add(y); deliver(40);
        x.talk(); deliver(5);
        System.out.println("voice delivered after handshake: " + (y.heard == 1));
        y.restart(); deliver(40);
        x.talk(); deliver(5);
        System.out.println("voice delivered after the listener restarted (new keys): " + (y.heard == 2) + ", still listed once: " + (x.table.count(1, now) == 1));

        // private call: A talks only to B while C is on the same Wi-Fi and channel
        radios.clear(); air.clear(); now = 3_000_000;
        Radio pa = new Radio(21, 0xC0A80130, "A"), pb = new Radio(-22, 0xC0A80131, "B"), pc = new Radio(23, 0xC0A80132, "C");
        radios.add(pa); radios.add(pb); radios.add(pc); deliver(40);
        pc.overheard.clear();
        boolean sent = pa.talkTo(-22); deliver(5);
        int cracked = 0, sniffed = 0;
        for (byte[] p : pc.overheard) {                                   // C tries every key it has
            if (p[3] != Wire.AUDIO) continue;
            sniffed++;
            PeerTable.Sessions ss = pc.table.sessions(21, 0xC0A80130);
            byte[] o = new byte[Wire.FRAME];
            if (ss != null && ss.live != null && Crypto.open(pc.cipher, ss.live.recv, p, p.length, o) >= 0) cracked++;
        }
        System.out.println("private call: B heard it: " + (sent && pb.heard == 1) + ", C heard: " + pc.heard
                + ", C sniffed " + sniffed + " packet(s) and decrypted " + cracked);
        boolean directOk = sent && pb.heard == 1 && pc.heard == 0 && sniffed > 0 && cracked == 0;
        if (okRuns != runs || y.heard != 2 || !directOk) System.exit(1);
    }
}
