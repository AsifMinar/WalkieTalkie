package com.walkietalkie.wifi;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * End-to-end encryption between every pair of radios. Pure Java (javax.crypto), so it is tested
 * on a desktop JVM with the same API Android provides.
 *
 * <ul>
 * <li>Every radio session makes a fresh P-256 key pair, so a recording of today's traffic can't be
 *     decrypted later even if a phone is lost (forward secrecy).</li>
 * <li>Two radios agree on a shared secret with ECDH. Received public keys are checked to be real
 *     points on the curve, which blocks invalid-curve attacks on our private key.</li>
 * <li>HKDF-SHA256 turns the secret into one AES-128-GCM key per direction.</li>
 * <li>Each packet's nonce is a never-repeating counter; receivers reject old counters (replays).</li>
 * <li>Because every pair of radios has its own keys, a private call can only be decrypted by the
 *     one person it is meant for.</li>
 * </ul>
 */
final class Crypto {

    static final class Keys {
        final SecretKeySpec send, recv;

        Keys(SecretKeySpec send, SecretKeySpec recv) {
            this.send = send;
            this.recv = recv;
        }
    }

    private static final byte[] INFO = "WalkieTalkie v3 pair key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NO_DATA = new byte[0];

    final byte[] pub;
    private final PrivateKey priv;
    private final ECParameterSpec params;
    private final byte[] salt;
    private final AtomicLong counter = new AtomicLong();

    Crypto() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        ECPublicKey pk = (ECPublicKey) kp.getPublic();
        params = pk.getParams();
        priv = kp.getPrivate();
        pub = encode(pk.getW());
        salt = new byte[32];
    }

    /** Shared keys with another radio. Throws if their key is not a valid P-256 point. */
    Keys pair(byte[] theirPub) throws GeneralSecurityException {
        PublicKey their = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(decode(theirPub), params));
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(priv);
        ka.doPhase(their, true);
        byte[] secret = ka.generateSecret();
        byte[] prk = hmac(salt, secret);                         // HKDF extract
        byte[] send = expand(prk, pub, theirPub);                // HKDF expand, one key per direction
        byte[] recv = expand(prk, theirPub, pub);
        Keys k = new Keys(new SecretKeySpec(send, 0, 16, "AES"), new SecretKeySpec(recv, 0, 16, "AES"));
        Arrays.fill(secret, (byte) 0);
        Arrays.fill(prk, (byte) 0);
        Arrays.fill(send, (byte) 0);
        Arrays.fill(recv, (byte) 0);
        return k;
    }

    static Cipher newCipher() throws GeneralSecurityException {
        return Cipher.getInstance("AES/GCM/NoPadding");
    }

    /**
     * Encrypts {@code plain[0, plainLen)} into {@code pkt} right after the header (which must
     * already be written) and returns the packet length. Header and counter are authenticated.
     */
    int seal(Cipher c, SecretKeySpec key, byte[] pkt, byte[] plain, int plainLen) throws GeneralSecurityException {
        long ctr = counter.incrementAndGet();
        Wire.putLong(pkt, Wire.HDR, ctr);
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce(ctr)));
        c.updateAAD(pkt, 0, Wire.SEALED_HDR);
        return Wire.SEALED_HDR + c.doFinal(plain == null ? NO_DATA : plain, 0, plainLen, pkt, Wire.SEALED_HDR);
    }

    /** Decrypts into {@code out}. Returns the plaintext length, or -1 if forged, damaged or oversized. */
    static int open(Cipher c, SecretKeySpec key, byte[] pkt, int len, byte[] out) {
        int plainLen = len - Wire.SEALED_HDR - Wire.TAG;
        if (plainLen < 0 || plainLen > out.length || len > pkt.length) return -1;
        long ctr = Wire.readLong(pkt, Wire.HDR);
        if (ctr <= 0) return -1;
        try {
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce(ctr)));
            c.updateAAD(pkt, 0, Wire.SEALED_HDR);
            return c.doFinal(pkt, Wire.SEALED_HDR, len - Wire.SEALED_HDR, out, 0);
        } catch (GeneralSecurityException | RuntimeException e) {
            return -1;
        }
    }

    static long counterOf(byte[] pkt) {
        return Wire.readLong(pkt, Wire.HDR);
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] nonce(long ctr) {
        byte[] n = new byte[12];
        Wire.putLong(n, 4, ctr);
        return n;
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(data);
    }

    private static byte[] expand(byte[] prk, byte[] first, byte[] second) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(prk, "HmacSHA256"));
        m.update(INFO);
        m.update(first);
        m.update(second);
        m.update((byte) 1);
        return m.doFinal();
    }

    private static byte[] encode(ECPoint w) {
        byte[] out = new byte[Wire.PUB];
        out[0] = 0x04;
        put32(w.getAffineX(), out, 1);
        put32(w.getAffineY(), out, 33);
        return out;
    }

    private static void put32(BigInteger v, byte[] out, int off) {
        byte[] b = v.toByteArray();                               // may carry a sign byte or be short
        int n = Math.min(b.length, 32);
        System.arraycopy(b, b.length - n, out, off + 32 - n, n);
    }

    /** Parses an uncompressed point and proves it lies on P-256 (y^2 = x^3 + ax + b mod p). */
    private ECPoint decode(byte[] b) throws GeneralSecurityException {
        if (b.length != Wire.PUB || b[0] != 0x04) throw new InvalidKeyException("bad key encoding");
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(b, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(b, 33, 65));
        EllipticCurve curve = params.getCurve();
        BigInteger p = ((ECFieldFp) curve.getField()).getP();
        if (x.compareTo(p) >= 0 || y.compareTo(p) >= 0) throw new InvalidKeyException("out of range");
        BigInteger lhs = y.multiply(y).mod(p);
        BigInteger rhs = x.multiply(x).multiply(x).add(curve.getA().multiply(x)).add(curve.getB()).mod(p);
        if (!lhs.equals(rhs)) throw new InvalidKeyException("not on the curve");
        return new ECPoint(x, y);
    }
}
