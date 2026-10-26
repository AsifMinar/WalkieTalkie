package com.walkietalkie.wifi;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The radio's packet format. Pure Java with strict bounds checks so it can be fuzz-tested on a
 * desktop JVM. Numbers are big-endian.
 *
 * <pre>
 * header (12)  'W' 'T' version type | sender id (4) | channel | flags | sequence (2)
 * HELLO        header | public key (65) | name length | name (UTF-8)
 * sealed       header | counter (8) | AES-GCM ciphertext | GCM tag (16)
 *              AUDIO carries 640 bytes of 16 kHz PCM; END, BYE, PING and PONG carry nothing.
 *              PING/PONG prove a radio really owns the key it announced.
 *              F_DIRECT on AUDIO/END marks a private call: sent to one person only.
 * </pre>
 *
 * The header and counter of a sealed packet are authenticated by AES-GCM, so nobody can change
 * the sender, channel or type of a packet without it being rejected.
 */
final class Wire {

    static final int PORT = 47321;
    static final byte VERSION = 3;
    static final byte HELLO = 1, AUDIO = 2, END = 3, BYE = 4, PING = 5, PONG = 6;
    static final int F_REPLY = 1;        // HELLO: please answer with your hello
    static final int F_DIRECT = 2;       // AUDIO / END: private call to one person
    static final int HDR = 12, CTR = 8, SEALED_HDR = HDR + CTR, TAG = 16;
    static final int FRAME = 640;
    static final int PUB = 65;
    static final int CHANNELS = 16;
    static final int MAX_NAME_CHARS = 20, MAX_NAME_BYTES = 80;
    static final int MAX_HELLO = HDR + PUB + 1 + MAX_NAME_BYTES;
    static final int MAX_PACKET = SEALED_HDR + FRAME + TAG;

    private Wire() { }

    static void header(byte[] b, byte type, int id, int channel, int flags) {
        b[0] = 'W';
        b[1] = 'T';
        b[2] = VERSION;
        b[3] = type;
        putInt(b, 4, id);
        b[8] = (byte) channel;
        b[9] = (byte) flags;
        b[10] = 0;
        b[11] = 0;
    }

    static boolean isOurs(byte[] b, int len) {
        return len >= HDR && b[0] == 'W' && b[1] == 'T' && b[2] == VERSION;
    }

    static boolean validChannel(int ch) {
        return ch >= 1 && ch <= CHANNELS;
    }

    // ------------------------------------------------------------------ HELLO

    static final class Hello {
        int id, channel, flags;
        byte[] pub;
        String name;
    }

    /** Writes a hello into {@code out} (at least MAX_HELLO bytes) and returns its length. */
    static int buildHello(byte[] out, int id, int channel, int flags, byte[] pub, byte[] name) {
        header(out, HELLO, id, channel, flags);
        System.arraycopy(pub, 0, out, HDR, PUB);
        int n = Math.min(name.length, MAX_NAME_BYTES);
        out[HDR + PUB] = (byte) n;
        System.arraycopy(name, 0, out, HDR + PUB + 1, n);
        return HDR + PUB + 1 + n;
    }

    /** Returns the hello, or null if it is malformed in any way. Never throws on any input. */
    static Hello parseHello(byte[] b, int len) {
        if (len > b.length || !isOurs(b, len) || b[3] != HELLO || len > MAX_HELLO) return null;
        int nameAt = HDR + PUB + 1;
        if (len < nameAt) return null;
        int n = b[HDR + PUB] & 0xff;
        if (n > MAX_NAME_BYTES) return null;
        int flags = b[9] & 0xff;
        if ((flags & ~F_REPLY) != 0) return null;                // unknown flags: not ours
        if (len != nameAt + n) return null;                      // exact size only
        int ch = b[8] & 0xff;
        if (!validChannel(ch) || b[HDR] != 0x04) return null;
        Hello h = new Hello();
        h.id = readInt(b, 4);
        h.channel = ch;
        h.flags = flags;
        h.pub = Arrays.copyOfRange(b, HDR, HDR + PUB);
        h.name = cleanName(new String(b, nameAt, n, StandardCharsets.UTF_8));
        return h;
    }

    // ------------------------------------------------------------------ names

    /**
     * Makes a display name safe: drops control and invisible formatting characters (including the
     * right-to-left overrides used to disguise text), collapses spaces, and caps the length.
     */
    static String cleanName(String raw) {
        StringBuilder sb = new StringBuilder(MAX_NAME_CHARS);
        int count = 0;
        boolean pendingSpace = false;
        for (int i = 0; i < raw.length() && count < MAX_NAME_CHARS; ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.PRIVATE_USE || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || (type == Character.FORMAT && cp != 0x200D)) {        // keep the emoji joiner
                continue;
            }
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = sb.length() > 0;
                continue;
            }
            if (pendingSpace) {
                sb.append(' ');
                pendingSpace = false;
                if (++count >= MAX_NAME_CHARS) break;
            }
            sb.appendCodePoint(cp);
            count++;
        }
        return sb.length() == 0 ? "Someone" : sb.toString();
    }

    static byte[] nameBytes(String cleanName) {
        byte[] b = cleanName.getBytes(StandardCharsets.UTF_8);
        return b.length <= MAX_NAME_BYTES ? b : Arrays.copyOf(b, MAX_NAME_BYTES);   // 20 chars * 4 bytes fits
    }

    // ------------------------------------------------------------------ numbers

    static int readInt(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16) | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    static long readLong(byte[] b, int off) {
        return ((long) readInt(b, off) << 32) | (readInt(b, off + 4) & 0xFFFFFFFFL);
    }

    static void putLong(byte[] b, int off, long v) {
        putInt(b, off, (int) (v >>> 32));
        putInt(b, off + 4, (int) v);
    }
}
