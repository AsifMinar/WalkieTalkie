package com.walkietalkie.wifi;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Push-to-talk over the local Wi-Fi network only, end-to-end encrypted.
 *
 * Discovery: small "hello" broadcasts on the Wi-Fi subnet carrying each radio's session public
 * key. Voice: encrypted separately for each person on the same channel and sent to them directly.
 * The socket is pinned to the Wi-Fi network (never mobile data) and packets from outside the
 * Wi-Fi subnet are ignored. Nothing is ever recorded: audio exists only in RAM while it plays.
 *
 * Threads: main (public API), "wt-io" (lifecycle, beacons), "wt-rx" (receiving),
 * "wt-tx" (microphone, only while talking) and the AudioOut player (only while sound plays).
 */
final class RadioEngine implements AudioOut.Host {

    interface Listener {
        void onRadioChanged();
    }

    static final int CHANNELS = Wire.CHANNELS;
    static final int NET_NONE = 0, NET_WIFI = 1, NET_HOTSPOT = 2;
    static final int SND_POWER_ON = 1, SND_POWER_OFF = 2, SND_TALK = 3, SND_CHANNEL = 4, SND_BUSY = 5;

    private static final int FRAME = Wire.FRAME;
    private static final long BEACON_FG_MS = 15_000L;   // "I'm here" while the app is open
    private static final long BEACON_BG_MS = 60_000L;   // best effort while closed (never wakes the phone)
    private static final long CHECKIN_MS = 45_000L;     // closed apps answer at most this often
    private static final long MAX_TALK_MS = 180_000L;   // like a real radio's time-out timer
    private static final long TALK_GATE_MS = 120;       // let the talk-permit chirp finish first

    // Abuse limits: what someone hostile on the same Wi-Fi can make us do.
    // (Peer count, address moves and key proof are enforced by PeerTable.)
    private static final long REPLY_GAP_MS = 2_000L;    // at most one answer per radio every 2 s
    private static final int MAX_REPLIES_PER_SEC = 10;  // and 10 answers per second in total
    private static final int MAX_NEW_KEYS_PER_SEC = 20; // key agreements are the costly step


    private static final byte[] NOTHING = new byte[0];

    private static RadioEngine sInstance;

    static synchronized RadioEngine get(Context c) {
        if (sInstance == null) sInstance = new RadioEngine(c.getApplicationContext());
        return sInstance;
    }

    private final Prefs prefs;
    private final Handler io;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ConnectivityManager cm;
    private final WifiManager.MulticastLock mcast;
    private final PowerManager.WakeLock rxWake;
    private final AudioOut audio;
    private final int myId;

    private final ArrayList<Listener> listeners = new ArrayList<>();   // main thread only
    private final PeerTable table = new PeerTable();
    private final HoldQueue held;                                      // talk that arrived while the line was busy

    // io thread only
    private boolean running;
    private boolean callbackRegistered;
    private Network wifi;

    // shared
    private volatile boolean powered, uiVisible, svcHeld;
    private volatile DatagramSocket sock;
    private volatile Crypto crypto;
    private volatile byte[] nameBytes;
    private volatile InetAddress bcast;
    private volatile int myAddr, mask;
    private volatile long lastBeacon;
    private volatile int lastSeq = -1;
    private volatile long speakerSeen;
    private volatile int txGen;
    private volatile String notice;
    private volatile long noticeUntil;
    private Thread txThread;                                           // main thread only

    volatile int net = NET_NONE;
    volatile String localIp = "";
    volatile int channel;
    volatile String callsign;
    volatile boolean sounds;
    volatile boolean transmitting;
    volatile int txTo;                                                 // 0 = whole channel, else one person
    volatile int speakerId;
    volatile boolean speakerDirect;                                    // the current talker called only us
    volatile boolean replaying;                                        // speaker is a held message playing
    volatile float txLevel;

    private RadioEngine(Context c) {
        prefs = new Prefs(c);
        myId = prefs.deviceId();
        channel = prefs.channel();
        callsign = Wire.cleanName(prefs.callsign());
        nameBytes = Wire.nameBytes(callsign);
        sounds = prefs.sounds();
        powered = prefs.power();
        prefs.dropLegacy();

        HandlerThread t = new HandlerThread("wt-io");
        t.start();
        io = new Handler(t.getLooper());

        cm = c.getSystemService(ConnectivityManager.class);
        WifiManager wm = c.getSystemService(WifiManager.class);
        if (wm != null) {
            mcast = wm.createMulticastLock("WalkieTalkie");
            mcast.setReferenceCounted(false);
        } else {
            mcast = null;
        }
        PowerManager pm = c.getSystemService(PowerManager.class);
        rxWake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WalkieTalkie:rx");
        rxWake.setReferenceCounted(false);
        audio = new AudioOut(c, this);
        held = new HoldQueue(new HoldQueue.Recycler() {
            @Override public void recycle(byte[] frame) { audio.recycle(frame); }
        });
    }

    // ------------------------------------------------------------------ public API (main thread)

    Prefs prefs() { return prefs; }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }

    void removeListener(Listener l) { listeners.remove(l); }

    boolean isPowered() { return powered; }

    void setPowered(boolean on) {
        if (!on && powered) playSound(SND_POWER_OFF);
        powered = on;
        prefs.power(on);
        if (!on) stopTx();
        io.post(reconcileTask);
        changed();
    }

    void setUiVisible(boolean v) {
        uiVisible = v;
        io.post(reconcileTask);
    }

    void setServiceHeld(boolean v) {
        svcHeld = v;
        io.post(reconcileTask);
    }

    void setChannel(int ch) {
        if (ch < 1) ch = CHANNELS;
        if (ch > CHANNELS) ch = 1;
        if (ch == channel) return;
        channel = ch;
        prefs.channel(ch);
        held.drop(true);
        clearSpeaker();
        audio.flush();
        playSound(SND_CHANNEL);
        io.post(announceTask);
        changed();
    }

    void setCallsign(String s) {
        String clean = Wire.cleanName(s);
        callsign = clean;
        nameBytes = Wire.nameBytes(clean);
        prefs.callsign(clean);
        io.post(announceTask);
        changed();
    }

    void setSounds(boolean on) {
        sounds = on;
        prefs.sounds(on);
        if (on) playSound(SND_CHANNEL);
        changed();
    }

    /** The low "bonk" a radio makes when you can't talk. */
    void busyTone() {
        playSound(SND_BUSY);
    }

    /**
     * Starts talking. {@code toId} 0 talks to everyone on the channel; any other id is a private
     * call that only that person can hear (their packets are sealed with keys only they hold).
     */
    boolean startTx(int toId) {
        if (transmitting) return txTo == toId;
        if (!powered || sock == null || crypto == null) return false;
        if (toId != 0 && table.target(toId, SystemClock.elapsedRealtime()) == null) return false;
        transmitting = true;
        txTo = toId;
        final int gen = ++txGen;
        final Thread prev = txThread;
        clearSpeaker();
        audio.flush();
        playSound(SND_TALK);
        if (toId == 0) io.post(rollCallTask);
        Thread t = new Thread(new Runnable() {
            @Override public void run() { txLoop(gen, prev, toId); }
        }, "wt-tx");
        txThread = t;
        t.start();
        changed();
        return true;
    }

    String nameOf(int id) {
        String n = table.name(id);
        return n != null ? n : "Someone";
    }

    void stopTx() {
        if (transmitting) {
            transmitting = false;
            changed();
        }
    }

    int peersOnChannel() {
        return table.count(channel, SystemClock.elapsedRealtime());
    }

    ArrayList<PeerTable.Peer> peers() {
        ArrayList<PeerTable.Peer> out = table.snapshot(SystemClock.elapsedRealtime());
        final int mine = channel;
        Collections.sort(out, new Comparator<PeerTable.Peer>() {
            @Override public int compare(PeerTable.Peer a, PeerTable.Peer b) {
                boolean am = a.channel == mine, bm = b.channel == mine;
                if (am != bm) return am ? -1 : 1;
                if (a.channel != b.channel) return a.channel - b.channel;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }

    /** Held messages still waiting to play (not counting the one playing now). */
    int heldWaiting() {
        return held.waiting();
    }

    String speakerName() {
        return nameOf(speakerId);
    }

    float rxLevelNow() {
        return audio.levelNow();
    }

    void flash(String msg) {
        notice = msg;
        noticeUntil = SystemClock.uptimeMillis() + 3500;
        changed();
        ui.postDelayed(fire, 3600);
    }

    String notice() {
        return SystemClock.uptimeMillis() < noticeUntil ? notice : null;
    }

    // ------------------------------------------------------------------ AudioOut.Host

    @Override public boolean soundsOn() {
        return sounds;
    }

    @Override public void onSignalLost() {
        if (!replaying && speakerId != 0 && SystemClock.elapsedRealtime() - speakerSeen > 600) {
            speakerId = 0;
            io.postDelayed(wakeRelease, 1500);
            changed();
        }
        io.post(replayTask);                          // the line is free: play anything held
    }

    @Override public void onMessageDone(HoldQueue.Msg m) {
        if (held.done(m)) {
            replaying = false;
            if (speakerId == m.id) speakerId = 0;
            io.postDelayed(wakeRelease, 1500);
            changed();
        }
        io.post(replayTask);                          // next one, if any
    }

    // ------------------------------------------------------------------ held messages

    /** Starts the oldest held message once nobody is talking (including us). */
    private final Runnable replayTask = new Runnable() {
        @Override public void run() {
            if (transmitting || !powered) return;
            if (!replaying && speakerId != 0 && SystemClock.elapsedRealtime() - speakerSeen < 700) {
                io.removeCallbacks(this);              // someone is live: look again once they go quiet
                if (held.waiting() > 0) io.postDelayed(this, 750);
                return;
            }
            HoldQueue.Msg m = held.next();             // null if one is already playing or none waits
            if (m == null) return;
            replaying = true;                          // state first: playback may end at any moment after this
            speakerId = m.id;
            speakerDirect = m.direct;
            io.removeCallbacks(wakeRelease);
            rxWake.acquire(MAX_TALK_MS + 10_000L);
            if (!audio.playMessage(m)) {               // speaker swamped: keep it and try again shortly
                held.unmark(m);
                replaying = false;
                speakerId = 0;
                io.postDelayed(wakeRelease, 1500);
                io.postDelayed(this, 200);
                return;
            }
            changed();
        }
    };

    // ------------------------------------------------------------------ change notification

    private volatile boolean changePosted;

    private void changed() {
        if (!changePosted) {
            changePosted = true;
            ui.post(fire);
        }
    }

    private final Runnable fire = new Runnable() {
        @Override public void run() {
            changePosted = false;
            for (Listener l : new ArrayList<>(listeners)) l.onRadioChanged();
        }
    };

    // ------------------------------------------------------------------ sounds

    /** Sounds are referenced by id so nothing is synthesized at all while sounds are switched off. */
    private void playSound(int id) {
        if (!sounds) return;
        byte[] pcm;
        switch (id) {
            case SND_POWER_ON: pcm = Sfx.POWER_ON; break;
            case SND_POWER_OFF: pcm = Sfx.POWER_OFF; break;
            case SND_TALK: pcm = Sfx.TALK_PERMIT; break;
            case SND_CHANNEL: pcm = Sfx.CHANNEL; break;
            default: pcm = Sfx.BUSY; break;
        }
        audio.sound(pcm);
    }

    // ------------------------------------------------------------------ lifecycle (io thread)

    private final Runnable reconcileTask = new Runnable() {
        @Override public void run() { reconcile(); }
    };
    private final Runnable announceTask = new Runnable() {
        @Override public void run() { sendHello(false, null); }
    };
    private final Runnable rollCallTask = new Runnable() {
        @Override public void run() { sendHello(true, null); }
    };
    private final Runnable hotspotPoll = new Runnable() {
        @Override public void run() { checkHotspot(); }
    };
    private final Runnable beaconTask = new Runnable() {
        @Override public void run() {
            if (sock != null) sendHello(false, null);
            prunePeers();
            scheduleBeacon();
        }
    };
    /** Re-pings radios that haven't proven their key yet (lost packets happen on Wi-Fi). */
    private final Runnable handshakeTask = new Runnable() {
        private Cipher cipher;
        private final byte[] pkt = new byte[Wire.SEALED_HDR + Wire.TAG];

        @Override public void run() {
            DatagramSocket s = sock;
            Crypto cr = crypto;
            if (s == null || cr == null) return;
            try {
                if (cipher == null) cipher = Crypto.newCipher();
                for (PeerTable.Target t : table.duePings(SystemClock.elapsedRealtime())) {
                    sendHello(false, t.addr);                     // in case they never got our key
                    Wire.header(pkt, Wire.PING, myId, channel, 0);
                    sendPacket(s, pkt, cr.seal(cipher, t.key, pkt, null, 0), t.addr);
                }
            } catch (GeneralSecurityException ignored) {
            }
            if (table.retriesLeft()) io.postDelayed(this, 1000);
        }
    };
    private final Runnable wakeRelease = new Runnable() {
        @Override public void run() {
            if (speakerId == 0 && rxWake.isHeld()) rxWake.release();
        }
    };

    private void reconcile() {
        boolean run = powered && (uiVisible || svcHeld);
        if (run != running) {
            running = run;
            if (run) {
                playSound(SND_POWER_ON);
                startRadio();
            } else {
                stopRadio();
            }
        } else if (run && uiVisible) {
            sendHello(true, null);                  // refresh the list of people right away
            if (net != NET_WIFI) checkHotspot();
        }
        scheduleBeacon();
    }

    private void startRadio() {
        if (!callbackRegistered) {
            try {
                NetworkRequest req = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
                cm.registerNetworkCallback(req, netCallback, io);
                callbackRegistered = true;
            } catch (RuntimeException ignored) {
                // fall back to interface scanning below
            }
        }
        io.removeCallbacks(hotspotPoll);
        io.postDelayed(hotspotPoll, 1500);           // give the Wi-Fi callback a moment first
    }

    private void stopRadio() {
        io.removeCallbacks(hotspotPoll);
        io.removeCallbacks(beaconTask);
        io.removeCallbacks(handshakeTask);
        if (callbackRegistered) {
            try {
                cm.unregisterNetworkCallback(netCallback);
            } catch (RuntimeException ignored) {
            }
            callbackRegistered = false;
        }
        wifi = null;
        closeSocket(true);
    }

    private final ConnectivityManager.NetworkCallback netCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) {
            if (!running) return;
            if (wifi != null && !wifi.equals(n)) return;
            wifi = n;
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp != null) useWifi(n, lp);
        }

        @Override public void onLinkPropertiesChanged(Network n, LinkProperties lp) {
            if (running && n.equals(wifi)) useWifi(n, lp);
        }

        @Override public void onLost(Network n) {
            if (!n.equals(wifi)) return;
            wifi = null;
            closeSocket(false);
            io.removeCallbacks(hotspotPoll);
            io.postDelayed(hotspotPoll, 1000);
        }
    };

    private void useWifi(Network n, LinkProperties lp) {
        Inet4Address a = null;
        int prefix = 24;
        for (LinkAddress la : lp.getLinkAddresses()) {
            InetAddress ia = la.getAddress();
            if (ia instanceof Inet4Address && !ia.isLoopbackAddress()) {
                a = (Inet4Address) ia;
                prefix = la.getPrefixLength();
                break;
            }
        }
        if (a == null) {
            closeSocket(false);
            return;
        }
        io.removeCallbacks(hotspotPoll);
        openSocket(n, a, prefix, NET_WIFI);
    }

    /** When this phone is the hotspot itself there is no Wi-Fi "network" to join, so look for the AP interface. */
    private void checkHotspot() {
        io.removeCallbacks(hotspotPoll);
        if (!running || wifi != null) return;
        Inet4Address found = null;
        int prefix = 24;
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements() && found == null) {
                NetworkInterface ni = all.nextElement();
                String name = ni.getName();
                if (name == null || !ni.isUp() || ni.isLoopback()) continue;
                if (!(name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("wlan")
                        || name.startsWith("softap") || name.startsWith("wigig"))) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address && !ia.getAddress().isLoopbackAddress()) {
                        found = (Inet4Address) ia.getAddress();
                        prefix = ia.getNetworkPrefixLength();
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (found != null) openSocket(null, found, prefix, NET_HOTSPOT);
        else if (net == NET_HOTSPOT) closeSocket(false);
        if (uiVisible || net == NET_HOTSPOT) io.postDelayed(hotspotPoll, uiVisible ? 4000 : 30000);
    }

    private void openSocket(Network n, Inet4Address a, int prefix, int kind) {
        int addr = ipToInt(a);
        if (sock != null && addr == myAddr && kind == net) return;
        closeSocket(true);
        Crypto cr;
        try {
            cr = new Crypto();                      // fresh keys every session (forward secrecy)
        } catch (GeneralSecurityException e) {
            flash("Encryption is not available on this phone");
            return;
        }
        int m = prefix <= 0 ? 0 : (prefix >= 32 ? 0xFFFFFFFF : (int) (0xFFFFFFFFL << (32 - prefix)));
        DatagramSocket s = null;
        try {
            s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.setBroadcast(true);
            s.setReceiveBufferSize(128 * 1024);
            s.bind(new InetSocketAddress(Wire.PORT));
            if (n != null) n.bindSocket(s);          // never let traffic fall back to mobile data
        } catch (IOException | RuntimeException e) {
            if (s != null) s.close();
            flash("Could not open the radio. Try again.");
            return;
        }
        myAddr = addr;
        mask = m;
        bcast = (prefix <= 0 || prefix >= 31) ? intToIp(0xFFFFFFFF) : intToIp(addr | ~m);
        localIp = a.getHostAddress();
        crypto = cr;
        sock = s;
        net = kind;
        if (kind == NET_WIFI && mcast != null) mcast.acquire();
        Thread rx = new Thread(new Receiver(s, cr), "wt-rx");
        rx.setDaemon(true);
        rx.start();
        sendHello(true, null);
        scheduleBeacon();
        changed();
    }

    private void closeSocket(boolean sayBye) {
        DatagramSocket s = sock;
        Crypto cr = crypto;
        if (s != null) {
            if (sayBye && cr != null) sendBye(s, cr);
            sock = null;                            // stops the transmitter before the socket closes
            crypto = null;
            s.close();
        }
        if (mcast != null && mcast.isHeld()) mcast.release();
        net = NET_NONE;
        localIp = "";
        bcast = null;
        myAddr = 0;
        mask = 0;
        table.clear();
        held.drop(false);
        speakerId = 0;
        audio.flush();
        if (rxWake.isHeld()) rxWake.release();
        changed();
    }

    private void scheduleBeacon() {
        io.removeCallbacks(beaconTask);
        if (running && sock != null) io.postDelayed(beaconTask, uiVisible ? BEACON_FG_MS : BEACON_BG_MS);
    }

    private void prunePeers() {
        if (table.prune(SystemClock.elapsedRealtime())) changed();
    }

    // ------------------------------------------------------------------ sending

    private void sendHello(boolean askReply, InetAddress to) {
        DatagramSocket s = sock;
        Crypto cr = crypto;
        InetAddress dst = to != null ? to : bcast;
        if (s == null || cr == null || dst == null) return;
        byte[] b = new byte[Wire.MAX_HELLO];
        int len = Wire.buildHello(b, myId, channel, askReply ? Wire.F_REPLY : 0, cr.pub, nameBytes);
        sendPacket(s, b, len, dst);
        if (to == null) lastBeacon = SystemClock.elapsedRealtime();
    }

    /** "Leaving": encrypted per person, so nobody can fake someone else leaving. */
    private void sendBye(DatagramSocket s, Crypto cr) {
        PeerTable.Target[] to = table.targets(-1, SystemClock.elapsedRealtime());
        if (to.length == 0) return;
        try {
            Cipher c = Crypto.newCipher();
            byte[] pkt = new byte[Wire.SEALED_HDR + Wire.TAG];
            for (PeerTable.Target t : to) {
                Wire.header(pkt, Wire.BYE, myId, channel, 0);
                sendPacket(s, pkt, cr.seal(c, t.key, pkt, null, 0), t.addr);
            }
        } catch (GeneralSecurityException ignored) {
        }
    }

    /** "Over": sent a few times because UDP may drop a packet. Listeners then play the roger beep. */
    private void sendEnd(DatagramSocket s, Crypto cr, Cipher c, byte[] pkt, PeerTable.Target[] to, int ch, int flags,
                         int times) {
        for (int i = 0; i < times; i++) {
            for (PeerTable.Target t : to) {
                Wire.header(pkt, Wire.END, myId, ch, flags);
                try {
                    sendPacket(s, pkt, cr.seal(c, t.key, pkt, null, 0), t.addr);
                } catch (GeneralSecurityException ignored) {
                }
            }
        }
    }

    private static void sendPacket(DatagramSocket s, byte[] b, int len, InetAddress to) {
        try {
            s.send(new DatagramPacket(b, len, to, Wire.PORT));
        } catch (IOException | RuntimeException ignored) {
        }
    }

    // ------------------------------------------------------------------ receiving

    private final class Receiver implements Runnable {
        private final DatagramSocket s;
        private final Crypto cr;
        private Cipher cipher;
        private final byte[] small = new byte[Wire.SEALED_HDR + Wire.TAG];
        private long replyWindow, keyWindow;
        private int replies, newKeys;
        private final HashMap<Integer, Long> asked = new HashMap<>();

        Receiver(DatagramSocket s, Crypto cr) {
            this.s = s;
            this.cr = cr;
        }

        @Override public void run() {
            AudioOut.boost(Process.THREAD_PRIORITY_AUDIO);
            try {
                cipher = Crypto.newCipher();
            } catch (GeneralSecurityException e) {
                return;
            }
            byte[] buf = new byte[2048];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            while (!s.isClosed()) {
                try {
                    p.setLength(buf.length);
                    s.receive(p);              // blocks with zero CPU until a packet arrives
                } catch (IOException e) {
                    if (s.isClosed()) return;
                    SystemClock.sleep(100);
                    continue;
                }
                try {
                    handle(p.getAddress(), buf, p.getLength());
                } catch (RuntimeException ignored) {
                    // a hostile packet must never take the radio down
                }
            }
        }

        private void handle(InetAddress from, byte[] b, int len) {
            if (!Wire.isOurs(b, len) || !(from instanceof Inet4Address)) return;
            int src = ipToInt((Inet4Address) from);
            if (src == myAddr || ((src ^ myAddr) & mask) != 0) return;     // same Wi-Fi only
            int id = Wire.readInt(b, 4);
            if (id == myId || id == 0) return;
            long now = SystemClock.elapsedRealtime();
            byte type = b[3];
            if (type == Wire.HELLO) {
                onHello(from, src, b, len, now);
                return;
            }
            if (type < Wire.AUDIO || type > Wire.PONG) return;
            int ch = b[8] & 0xff;
            if (!Wire.validChannel(ch) || len > Wire.MAX_PACKET) return;

            PeerTable.Sessions ss = table.sessions(id, src);
            if (ss == null) {                                              // unknown sender: ask who it is
                askWho(from, src, now);
                return;
            }
            byte[] out = type == Wire.AUDIO ? audio.obtain() : NOTHING;
            int want = type == Wire.AUDIO ? FRAME : 0;
            Crypto.Keys used = null;                                       // forged or damaged: stays null
            if (ss.live != null && Crypto.open(cipher, ss.live.recv, b, len, out) == want) used = ss.live;
            else if (ss.candidate != null && Crypto.open(cipher, ss.candidate.recv, b, len, out) == want) used = ss.candidate;
            int r = used == null ? PeerTable.REJECT : table.accept(id, used, Crypto.counterOf(b), ch, now);
            if (r == PeerTable.REJECT) {                                   // forged, damaged or replayed
                if (out != NOTHING) audio.recycle(out);
                return;
            }
            if (r != PeerTable.OK) changed();                              // proven just now, or moved channel
            switch (type) {
                case Wire.AUDIO:
                    onAudio(id, ch, b, out, now);
                    break;
                case Wire.END:
                    if (held.close(id)) io.post(replayTask);
                    else if (id == speakerId && !replaying) endRx();
                    break;
                case Wire.BYE:
                    table.remove(id);
                    if (held.close(id)) io.post(replayTask);
                    else if (id == speakerId && !replaying) endRx();
                    changed();
                    break;
                case Wire.PING:
                    seal(Wire.PONG, used.send, from);                      // prove we own our key too
                    break;
                default:                                                   // PONG: the proof was the point
                    break;
            }
        }

        private void onHello(InetAddress from, int src, byte[] b, int len, long now) {
            Wire.Hello h = Wire.parseHello(b, len);
            if (h == null || h.id == myId || h.id == 0) return;
            int r = table.hello(h, from, src, now);
            int gone = table.evicted();
            if (gone != 0 && gone == speakerId) endRx();
            boolean wantsReply = (h.flags & Wire.F_REPLY) != 0;
            switch (r) {
                case PeerTable.IGNORE:
                    return;
                case PeerTable.NEW_KEY:
                    if (!allowNewKey(now)) return;
                    Crypto.Keys k;
                    try {
                        k = cr.pair(h.pub);                                // rejects invalid curve points
                    } catch (GeneralSecurityException e) {
                        return;
                    }
                    if (!table.candidate(h, from, src, k, now)) return;
                    if (replyBudget(now)) sendHello(false, from);          // they need our key to answer
                    ping(h.id, now);                                       // trusted only once they answer
                    io.removeCallbacks(handshakeTask);
                    io.postDelayed(handshakeTask, 1000);                   // retry if a packet gets lost
                    return;
                case PeerTable.REPING:
                    ping(h.id, now);
                    if (wantsReply) reply(h.id, from, now);
                    return;
                default:                                                   // REFRESHED or CHANGED
                    if (r == PeerTable.CHANGED) changed();
                    if (wantsReply) reply(h.id, from, now);
                    else if (now - lastBeacon > CHECKIN_MS) sendHello(false, null);   // awake anyway: check in
            }
        }

        private void ping(int id, long now) {
            PeerTable.Target t = table.pingTarget(id, now);
            if (t != null) seal(Wire.PING, t.key, t.addr);
        }

        private void seal(byte type, SecretKeySpec key, InetAddress to) {
            Wire.header(small, type, myId, channel, 0);
            try {
                sendPacket(s, small, cr.seal(cipher, key, small, null, 0), to);
            } catch (GeneralSecurityException ignored) {
            }
        }

        private void reply(int id, InetAddress to, long now) {
            if (table.mayReply(id, now, REPLY_GAP_MS) && replyBudget(now)) sendHello(false, to);
        }

        private void askWho(InetAddress from, int src, long now) {
            Long last = asked.get(src);
            if (last != null && now - last < 3000) return;
            if (asked.size() >= 32) asked.clear();
            asked.put(src, now);
            if (replyBudget(now)) sendHello(true, from);
        }

        private boolean replyBudget(long now) {
            if (now - replyWindow >= 1000) {
                replyWindow = now;
                replies = 0;
            }
            return ++replies <= MAX_REPLIES_PER_SEC;
        }

        private boolean allowNewKey(long now) {
            if (now - keyWindow >= 1000) {
                keyWindow = now;
                newKeys = 0;
            }
            return ++newKeys <= MAX_NEW_KEYS_PER_SEC;
        }
    }

    private void onAudio(int id, int ch, byte[] b, byte[] frame, long now) {
        boolean direct = (b[9] & Wire.F_DIRECT) != 0;                 // a private call to us: any channel
        if (!powered || (!direct && ch != channel)) {
            audio.recycle(frame);
            return;
        }
        int seq = ((b[10] & 0xff) << 8) | (b[11] & 0xff);
        int cur = speakerId;
        boolean busy = transmitting || replaying || (cur != 0 && cur != id && now - speakerSeen < 700);
        if (busy || held.isHeld(id)) {                // we or someone else is talking: keep it for later
            if (held.add(id, direct, seq, frame) == HoldQueue.STARTED) {
                changed();
                io.post(replayTask);
            }
            return;
        }
        if (cur != id) {
            speakerId = id;
            speakerDirect = direct;
            lastSeq = -1;
            io.removeCallbacks(wakeRelease);
            rxWake.acquire(MAX_TALK_MS + 10_000L);
            changed();
        } else if (lastSeq >= 0) {
            int d = (seq - lastSeq) & 0xffff;
            if (d == 0 || d > 0x8000) {                    // late packet
                audio.recycle(frame);
                return;
            }
        }
        lastSeq = seq;
        speakerSeen = now;
        audio.offer(frame);
    }

    private void endRx() {
        if (speakerId == 0) return;
        speakerId = 0;
        audio.end();
        io.postDelayed(wakeRelease, 1500);
        io.post(replayTask);                          // anyone who talked over them plays next
        changed();
    }

    /** Stop listening to the current talker and let the CPU sleep again shortly after. */
    private void clearSpeaker() {
        if (speakerId != 0) {
            speakerId = 0;
            io.postDelayed(wakeRelease, 1500);
        }
    }

    // ------------------------------------------------------------------ transmitting

    private void txLoop(int gen, Thread prev, int toId) {
        if (prev != null) {
            try {
                prev.join(800);
            } catch (InterruptedException ignored) {
            }
        }
        AudioOut.boost(Process.THREAD_PRIORITY_URGENT_AUDIO);
        final Crypto cr = crypto;
        AudioRecord rec = null;
        NoiseSuppressor ns = null;
        AutomaticGainControl agc = null;
        Cipher cipher = null;
        boolean opened = false;
        long started = SystemClock.elapsedRealtime();
        int sentCh = channel;
        int flags = toId != 0 ? Wire.F_DIRECT : 0;
        final String toName = toId != 0 ? nameOf(toId) : null;
        PeerTable.Target[] to = PeerTable.NO_TARGETS;
        byte[] pkt = new byte[Wire.MAX_PACKET];
        try {
            if (cr == null) throw new IllegalStateException("radio off");
            cipher = Crypto.newCipher();
            int min = AudioRecord.getMinBufferSize(AudioOut.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, AudioOut.RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 4));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("mic");
            int session = rec.getAudioSessionId();
            try {                                   // optional extras: never let them block talking
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(session);
                    if (ns != null) ns.setEnabled(true);
                }
            } catch (RuntimeException ignored) {
            }
            try {
                if (AutomaticGainControl.isAvailable()) {
                    agc = AutomaticGainControl.create(session);
                    if (agc != null) agc.setEnabled(true);
                }
            } catch (RuntimeException ignored) {
            }
            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("busy");
            opened = true;

            byte[] pcm = new byte[FRAME];
            DatagramPacket dp = new DatagramPacket(pkt, pkt.length);
            dp.setPort(Wire.PORT);
            long refresh = 0;
            int seq = 0;
            while (transmitting && gen == txGen) {
                int n = rec.read(pcm, 0, FRAME, AudioRecord.READ_BLOCKING);
                if (n < 0) throw new IllegalStateException("read");
                if (n < FRAME) continue;
                long now = SystemClock.elapsedRealtime();
                if (now - started > MAX_TALK_MS) {
                    flash("Stopped after 3 minutes of talking");
                    playSound(SND_BUSY);
                    break;
                }
                if (now - started < TALK_GATE_MS) continue;          // keep our own chirp off the air
                DatagramSocket s = sock;
                if (s == null || crypto != cr) break;                // radio restarted with new keys
                int ch = channel;
                if (ch != sentCh && toId == 0) {                     // changed channel mid-talk
                    sendEnd(s, cr, cipher, pkt, to, sentCh, 0, 2);
                    sentCh = ch;
                    refresh = 0;
                }
                txLevel = AudioOut.level(pcm, 0, FRAME);
                if (now >= refresh) {
                    if (toId == 0) {
                        to = table.targets(ch, now);
                    } else {
                        PeerTable.Target one = table.target(toId, now);
                        if (one == null) {                           // they left mid-call
                            flash(toName + " left the radio");
                            playSound(SND_BUSY);
                            break;
                        }
                        to = new PeerTable.Target[]{one};
                    }
                    refresh = now + 300;
                }
                for (PeerTable.Target t : to) {
                    Wire.header(pkt, Wire.AUDIO, myId, ch, flags);
                    pkt[10] = (byte) (seq >>> 8);
                    pkt[11] = (byte) seq;
                    try {
                        dp.setLength(cr.seal(cipher, t.key, pkt, pcm, FRAME));
                        dp.setAddress(t.addr);
                        s.send(dp);
                    } catch (IOException | GeneralSecurityException ignored) {
                    }
                }
                seq = (seq + 1) & 0xffff;
            }
        } catch (Throwable t) {
            if (!opened) {
                flash("The microphone is busy in another app");
                playSound(SND_BUSY);
            }
        } finally {
            try {
                if (ns != null) ns.release();
                if (agc != null) agc.release();
            } catch (RuntimeException ignored) {
            }
            if (rec != null) {
                try {
                    rec.stop();
                } catch (RuntimeException ignored) {
                }
                rec.release();
            }
        }
        txLevel = 0;
        DatagramSocket s = sock;
        if (s != null && opened && cipher != null && crypto == cr) {
            long now = SystemClock.elapsedRealtime();
            PeerTable.Target one = toId != 0 ? table.target(toId, now) : null;
            PeerTable.Target[] last = toId == 0 ? table.targets(sentCh, now)
                    : (one != null ? new PeerTable.Target[]{one} : PeerTable.NO_TARGETS);
            sendEnd(s, cr, cipher, pkt, last, sentCh, flags, 3);
        }
        if (gen == txGen) transmitting = false;
        io.post(replayTask);                          // play what people said while we talked
        changed();
    }

    // ------------------------------------------------------------------ helpers

    private static int ipToInt(Inet4Address a) {
        return Wire.readInt(a.getAddress(), 0);
    }

    private static InetAddress intToIp(int v) {
        byte[] b = new byte[4];
        Wire.putInt(b, 0, v);
        try {
            return InetAddress.getByAddress(b);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
