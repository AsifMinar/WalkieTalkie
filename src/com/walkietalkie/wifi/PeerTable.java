package com.walkietalkie.wifi;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;

/**
 * Who is on the radio, and which keys we trust for them. Pure Java so the trust rules are tested
 * on a desktop JVM. All methods are thread-safe.
 *
 * Trust rule: a radio's key is only used after the radio proves it owns it. A new key first
 * becomes a "candidate"; it is promoted when a packet sealed with it decrypts correctly (the
 * reply to our encrypted ping). A replayed or copied hello can never do that, so it can't
 * redirect anyone's audio. On top of that:
 * <ul>
 * <li>an active radio can't be claimed from another address (hijack attempt);</li>
 * <li>a key a radio used before is never accepted again (replay of an old session);</li>
 * <li>one radio per address, at most {@value #MAX_PEERS} radios, unproven keys expire in 30 s.</li>
 * </ul>
 */
final class PeerTable {

    static final int MAX_PEERS = 64;
    static final long TTL_MS = 120_000L;
    static final long MOVE_GRACE_MS = 20_000L;
    static final long PROOF_TIMEOUT_MS = 30_000L;

    /** hello() results */
    static final int IGNORE = 0, REFRESHED = 1, CHANGED = 2, REPING = 3, NEW_KEY = 4;
    /** accept() results */
    static final int REJECT = 0, OK = 1, OK_CHANGED = 2, PROMOTED = 3;

    static final class Peer {
        final int id;
        String name;
        int channel;
        long seen;
        // the proven session
        InetAddress addr;
        int ip;
        byte[] pub;
        Crypto.Keys keys;
        long lastCtr;
        // a newer session waiting for proof
        InetAddress nAddr;
        int nIp;
        byte[] nPub;
        Crypto.Keys nKeys;
        long nLastCtr, nSince, nPinged;
        int nTries;
        String nName;
        int nChannel;
        long lastReply;
        private byte[][] oldPubs;

        Peer(int id) { this.id = id; }

        boolean usedBefore(byte[] key) {
            if (oldPubs != null) for (byte[] k : oldPubs) if (k != null && Arrays.equals(k, key)) return true;
            return false;
        }

        private void retire(byte[] key) {
            if (oldPubs == null) oldPubs = new byte[4][];
            System.arraycopy(oldPubs, 0, oldPubs, 1, oldPubs.length - 1);
            oldPubs[0] = key;
        }

        private void dropCandidate() {
            nAddr = null;
            nIp = 0;
            nPub = null;
            nKeys = null;
            nLastCtr = 0;
        }
    }

    /** Keys usable for packets from one address. */
    static final class Sessions {
        final Crypto.Keys live, candidate;

        Sessions(Crypto.Keys live, Crypto.Keys candidate) {
            this.live = live;
            this.candidate = candidate;
        }
    }

    static final class Target {
        final InetAddress addr;
        final javax.crypto.spec.SecretKeySpec key;

        Target(InetAddress addr, javax.crypto.spec.SecretKeySpec key) {
            this.addr = addr;
            this.key = key;
        }
    }

    static final Target[] NO_TARGETS = new Target[0];

    private final HashMap<Integer, Peer> map = new HashMap<>();
    private int evicted;

    // ------------------------------------------------------------------ hellos

    /** What to do about a hello that passed parsing (and the group check). */
    synchronized int hello(Wire.Hello h, InetAddress from, int src, long now) {
        evicted = 0;
        Peer p = map.get(h.id);
        if (p != null && p.keys != null && Arrays.equals(p.pub, h.pub)) {
            if (p.ip != src) return IGNORE;                       // a copy of their hello, sent from elsewhere
            boolean changed = p.channel != h.channel || !h.name.equals(p.name);
            p.name = h.name;
            p.channel = h.channel;
            p.seen = now;
            return changed ? CHANGED : REFRESHED;
        }
        if (p != null && p.nPub != null && Arrays.equals(p.nPub, h.pub)) {
            if (p.nIp != src) return IGNORE;
            p.nName = h.name;
            p.nChannel = h.channel;
            return now - p.nPinged >= 1000 ? REPING : IGNORE;      // still unproven: ask again, gently
        }
        if (p != null && p.usedBefore(h.pub)) return IGNORE;      // an old session replayed
        if (p != null && p.keys != null && p.ip != src && now - p.seen < MOVE_GRACE_MS) return IGNORE;
        if (p == null) {
            Peer other = null;
            for (Peer q : map.values()) if (q.keys != null && q.ip == src) other = q;
            if (other != null) {                                  // one radio per address
                if (now - other.seen < MOVE_GRACE_MS) return IGNORE;
                map.remove(other.id);
                evicted = other.id;
            }
            if (map.size() >= MAX_PEERS) {
                prune(now);
                if (map.size() >= MAX_PEERS) return IGNORE;
            }
        }
        return NEW_KEY;
    }

    /** The id removed by the last hello() (0 if none). Read right after hello() on the same thread. */
    int evicted() {
        return evicted;
    }

    /** Stores the keys for a new session as a candidate. False if things changed meanwhile. */
    synchronized boolean candidate(Wire.Hello h, InetAddress from, int src, Crypto.Keys keys, long now) {
        Peer p = map.get(h.id);
        if (p == null) {
            if (map.size() >= MAX_PEERS) return false;
            p = new Peer(h.id);
            p.seen = now;
            map.put(h.id, p);
        } else if (p.usedBefore(h.pub) || (p.keys != null && Arrays.equals(p.pub, h.pub))) {
            return false;
        }
        p.nAddr = from;
        p.nIp = src;
        p.nPub = h.pub;
        p.nKeys = keys;
        p.nLastCtr = 0;
        p.nSince = now;
        p.nPinged = 0;
        p.nTries = 0;
        p.nName = h.name;
        p.nChannel = h.channel;
        return true;
    }

    /** Address and keys to ping a candidate with, or null. Marks the ping time. */
    synchronized Target pingTarget(int id, long now) {
        Peer p = map.get(id);
        if (p == null || p.nKeys == null) return null;
        p.nPinged = now;
        return new Target(p.nAddr, p.nKeys.send);
    }

    static final int MAX_RETRIES = 4;

    /**
     * Unproven radios due another ping: retries after 1, 2, 4 and 8 s, then give up (the 30 s
     * expiry cleans up). Bounded so fake hellos can't turn us into a packet hose.
     */
    synchronized Target[] duePings(long now) {
        ArrayList<Target> due = null;
        for (Peer p : map.values()) {
            if (p.nKeys == null || p.nTries >= MAX_RETRIES || now - p.nPinged < (1000L << p.nTries)) continue;
            p.nTries++;
            p.nPinged = now;
            if (due == null) due = new ArrayList<>();
            due.add(new Target(p.nAddr, p.nKeys.send));
        }
        return due == null ? NO_TARGETS : due.toArray(new Target[0]);
    }

    synchronized boolean retriesLeft() {
        for (Peer p : map.values()) if (p.nKeys != null && p.nTries < MAX_RETRIES) return true;
        return false;
    }

    // ------------------------------------------------------------------ sealed packets

    /** Keys that may have sealed a packet from this id at this address, or null if none. */
    synchronized Sessions sessions(int id, int src) {
        Peer p = map.get(id);
        if (p == null) return null;
        Crypto.Keys live = p.keys != null && p.ip == src ? p.keys : null;
        Crypto.Keys cand = p.nKeys != null && p.nIp == src ? p.nKeys : null;
        return live == null && cand == null ? null : new Sessions(live, cand);
    }

    /**
     * A packet decrypted with {@code used}. Checks the counter (no replays) and promotes a
     * candidate that just proved it owns its key.
     */
    synchronized int accept(int id, Crypto.Keys used, long ctr, int channel, long now) {
        Peer p = map.get(id);
        if (p == null) return REJECT;
        if (used == p.keys) {
            if (ctr <= p.lastCtr) return REJECT;
            p.lastCtr = ctr;
            p.seen = now;
            if (p.channel != channel) {
                p.channel = channel;
                return OK_CHANGED;
            }
            return OK;
        }
        if (used == p.nKeys) {
            if (ctr <= p.nLastCtr) return REJECT;
            if (p.pub != null) p.retire(p.pub);
            p.addr = p.nAddr;
            p.ip = p.nIp;
            p.pub = p.nPub;
            p.keys = p.nKeys;
            p.lastCtr = ctr;
            p.name = p.nName;
            p.channel = channel;
            p.seen = now;
            p.dropCandidate();
            return PROMOTED;
        }
        return REJECT;
    }

    synchronized boolean remove(int id) {
        return map.remove(id) != null;
    }

    // ------------------------------------------------------------------ queries

    synchronized Target[] targets(int channel, long now) {
        int n = 0;
        for (Peer p : map.values()) if (isTarget(p, channel, now)) n++;
        if (n == 0) return NO_TARGETS;
        Target[] out = new Target[n];
        int i = 0;
        for (Peer p : map.values()) if (isTarget(p, channel, now)) out[i++] = new Target(p.addr, p.keys.send);
        return out;
    }

    /** One proven radio, for a private call (any channel), or null if it isn't here. */
    synchronized Target target(int id, long now) {
        Peer p = map.get(id);
        return p != null && isTarget(p, -1, now) ? new Target(p.addr, p.keys.send) : null;
    }

    private static boolean isTarget(Peer p, int channel, long now) {
        return p.keys != null && (channel < 0 || p.channel == channel) && now - p.seen < TTL_MS;
    }

    synchronized int count(int channel, long now) {
        int n = 0;
        for (Peer p : map.values()) if (isTarget(p, channel, now)) n++;
        return n;
    }

    /** Copies of the proven radios (name and channel only). */
    synchronized ArrayList<Peer> snapshot(long now) {
        ArrayList<Peer> out = new ArrayList<>();
        for (Peer p : map.values()) {
            if (!isTarget(p, -1, now)) continue;
            Peer c = new Peer(p.id);
            c.name = p.name;
            c.channel = p.channel;
            out.add(c);
        }
        return out;
    }

    synchronized String name(int id) {
        Peer p = map.get(id);
        return p != null && p.keys != null ? p.name : null;
    }

    /** Rate limit for answering a radio: true at most once per {@code gapMs}. */
    synchronized boolean mayReply(int id, long now, long gapMs) {
        Peer p = map.get(id);
        if (p == null || now - p.lastReply < gapMs) return false;
        p.lastReply = now;
        return true;
    }

    // ------------------------------------------------------------------ housekeeping

    /** Drops radios gone quiet and keys never proven. True if anyone visible left. */
    synchronized boolean prune(long now) {
        boolean visibleGone = false;
        Iterator<Peer> it = map.values().iterator();
        while (it.hasNext()) {
            Peer p = it.next();
            if (p.nKeys != null && now - p.nSince > PROOF_TIMEOUT_MS) p.dropCandidate();
            if (p.keys == null) {
                if (p.nKeys == null) it.remove();
            } else if (now - p.seen > TTL_MS) {
                it.remove();
                visibleGone = true;
            }
        }
        return visibleGone;
    }

    synchronized void clear() {
        map.clear();
    }

    synchronized int size() {
        return map.size();
    }
}
