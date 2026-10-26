package com.walkietalkie.wifi;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Random;

/**
 * The entire radio, drawn on one canvas: no view tree, no images, no libraries. It only redraws
 * when something changes, and runs a ~30 fps loop only while someone is talking.
 */
final class RadioView extends View {

    private static final int R_NONE = 0, R_POWER = 1, R_CH_DOWN = 3, R_CH_UP = 4,
            R_SOUNDS = 6, R_BG = 7, R_KNOB = 8, R_STRIP = 9;

    private static final int WHITE = 0xFFFFFFFF, BLACK = 0xFF000000;

    // Seven-segment patterns, bit 0..6 = segments a..g
    private static final int[] DIGITS = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F};
    private static final int[] DOT_ALPHA = {34, 95, 155, 215, 255};

    private final MainActivity host;
    private final RadioEngine engine;
    private final float dp, ts;
    private final Geometry g = new Geometry();

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grain = new Paint();
    private final Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);

    private final RectF tmp = new RectF();
    private final Path path = new Path();
    private final Path[][] seg = new Path[2][7];
    private final Matrix matrix = new Matrix();

    private Shader bodyFill, bodyEdge, ambientA, ambientB, lcdSheen, knobShade, txGlow, rxGlow;
    private final Paint.FontMetrics fm = new Paint.FontMetrics();
    private float[] dotPts = new float[0];
    private int[] dotCol = new int[0];          // columns away from the centre column
    private float[] dotRow = new float[0];      // rows away from the middle row
    private float maxRow;
    private int halfCols = 1;
    private final float[] hist = new float[64]; // recent loudness: [0] = now, spreading outwards
    private long histAt;
    private float histPeak;
    private float[][] buckets = new float[5][0];
    private final int[] bucketLen = new int[5];
    private int dotN;
    private boolean sized;

    // what we show
    private boolean powered, hasNet, txOn, rxOn, soundsOn, bgOn;
    private int channel = 1;
    private String callsign = "", title = "", netLine = "", hintDirect = "";
    private String sub = "";

    // people row: chip 0 is you, then everyone else on this Wi-Fi
    private ArrayList<PeerTable.Peer> people = new ArrayList<>();
    private int chipCount;
    private int[] chipId = new int[0];
    private float[] chipX = new float[0], chipW = new float[0];
    private String[] chipName = new String[0], chipSub = new String[0], chipInitial = new String[0];
    private String emptyText = "";
    private float stripScroll, stripMax, stripDownX, stripDownScroll;
    private boolean stripScrolling;
    // Which chip is pressed, by position: -1 none, 0 you, 1+ a person. Never inferred from the id,
    // because ids are random 32-bit numbers and half of them are negative.
    private int pressedChip = -1;
    private int pressedPeer;                    // that person's id (valid when pressedChip >= 1)
    private int directTo;                       // person we're talking to privately (0 = none)
    private boolean directStarted;              // a private call began during the current touch
    private int speaker;                        // who is talking to us (0 = nobody)
    private final Runnable holdToTalk = new Runnable() {
        @Override public void run() { startDirect(); }
    };
    private StaticLayout subLayout;

    // knob + touch
    private float knobOffset, downY, downOffset, shakeX, level;
    private boolean dragging, armed, locked;
    private int pressed = R_NONE;
    private ValueAnimator knobAnim, shakeAnim;

    RadioView(MainActivity host, RadioEngine engine) {
        super(host);
        this.host = host;
        this.engine = engine;
        dp = getResources().getDisplayMetrics().density;
        float fs = getResources().getConfiguration().fontScale;
        ts = Math.max(0.85f, Math.min(fs, 1.25f));   // follow the user's text size, within reason

        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        dots.setStyle(Paint.Style.STROKE);
        dots.setStrokeCap(Paint.Cap.ROUND);
        dots.setStrokeWidth(3.4f * dp);
        text.setColor(WHITE);
        subText.setColor(0x9EFFFFFF);
        subText.setTypeface(regular);

        // Fine frosted grain: a 96x96 alpha-only tile (9 KB) repeated over the glass.
        int n = 96;
        byte[] px = new byte[n * n];
        Random r = new Random(7);
        for (int i = 0; i < px.length; i++) px[i] = (byte) r.nextInt(16);
        Bitmap noise = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8);
        noise.copyPixelsFromBuffer(ByteBuffer.wrap(px));
        BitmapShader bs = new BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        Matrix gm = new Matrix();
        gm.setScale(0.75f * dp, 0.75f * dp);
        bs.setLocalMatrix(gm);
        grain.setShader(bs);
        grain.setColor(WHITE);

        setContentDescription("Walkie Talkie");
        setFilterTouchesWhenObscured(true);       // ignore taps passed through another app's overlay
        setHapticFeedbackEnabled(true);
    }

    // ------------------------------------------------------------------ state

    void refresh() {
        powered = engine.isPowered();
        int net = engine.net;
        hasNet = net != RadioEngine.NET_NONE;
        txOn = engine.transmitting;
        rxOn = !txOn && engine.speakerId != 0;
        soundsOn = engine.sounds;
        bgOn = host.backgroundOn();
        channel = engine.channel;
        callsign = engine.callsign;
        int n = engine.peersOnChannel();

        if (!powered) {
            title = "Off";
            sub = "The radio is switched off";
        } else if (!hasNet) {
            title = "No Wi-Fi";
            sub = "Connect to Wi-Fi to talk";
        } else if (txOn && engine.txTo != 0) {
            title = "Private";
            sub = "Only " + engine.nameOf(engine.txTo) + " can hear you";
        } else if (txOn) {
            title = "Talking";
            sub = n == 0 ? "No one is listening yet" : (n == 1 ? "1 person can hear you" : n + " people can hear you");
        } else if (rxOn && engine.replaying) {
            title = "Replay";
            sub = engine.speakerName() + (engine.speakerDirect ? " talked to you meanwhile" : " talked at the same time");
        } else if (rxOn) {
            String who = engine.speakerName();
            title = engine.speakerDirect ? "Private" : "Listening";
            sub = who + (engine.speakerDirect ? " is talking to you only" : " is talking");
        } else {
            title = "Standby";
            sub = n == 0 ? "No one else here yet" : (n == 1 ? "1 other person here" : n + " others here");
        }
        int waiting = powered ? engine.heldWaiting() : 0;
        if (waiting > 0 && (txOn || rxOn)) {
            sub += waiting == 1 ? " (1 message waiting)" : " (" + waiting + " messages waiting)";
        }
        String note = engine.notice();
        if (note != null && !rxOn && !txOn) sub = note;

        if (!powered) netLine = "";
        else if (net == RadioEngine.NET_WIFI) netLine = "Wi-Fi  " + engine.localIp;
        else if (net == RadioEngine.NET_HOTSPOT) netLine = "Hotspot  " + engine.localIp;
        else netLine = "Wi-Fi only";

        speaker = rxOn ? engine.speakerId : 0;
        if (!txOn) directTo = 0;
        hintDirect = directTo != 0 ? "Talking to " + engine.nameOf(directTo) + " only" : "";
        people = engine.peers();
        layoutChips();

        if (!txOn && (locked || dragging)) {         // transmission ended on its own
            locked = false;
            dragging = false;
            armed = false;
            animateKnob(0);
        }
        setKeepScreenOn(txOn);
        buildSubLayout();
        invalidate();
    }

    private void buildSubLayout() {
        if (!sized) return;
        int w = (int) (g.infoR - g.infoL);
        if (w <= 0) {
            subLayout = null;
            return;
        }
        subText.setTextSize(g.subSize);
        subLayout = StaticLayout.Builder.obtain(sub, 0, sub.length(), subText, w)
                .setMaxLines(g.subMaxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .setLineSpacing(0, 1.08f)
                .build();
    }

    // ------------------------------------------------------------------ layout

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        g.compute(w, h, dp, ts);
        sized = true;

        bodyFill = new LinearGradient(g.bodyL, g.bodyT, g.bodyR, g.bodyB, 0x1CFFFFFF, 0x08FFFFFF, Shader.TileMode.CLAMP);
        bodyEdge = new LinearGradient(g.bodyL, g.bodyT, g.bodyL + (g.bodyR - g.bodyL) * 0.5f, g.bodyB,
                0x70FFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP);
        ambientA = new RadialGradient(g.bodyR - 20 * dp, g.lcdT, w * 0.8f, 0x1EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        ambientB = new RadialGradient(g.bodyL + 10 * dp, g.trB, w * 0.7f, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        lcdSheen = new LinearGradient(0, g.lcdT, 0, g.lcdT + (g.lcdB - g.lcdT) * 0.55f, 0x16FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        knobShade = new RadialGradient(-g.knobR * 0.35f, -g.knobR * 0.45f, g.knobR * 1.6f,
                new int[]{0xFFFFFFFF, 0xFFF1F1F1, 0xFFC9C9C9}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        txGlow = new RadialGradient(0, 0, g.knobR * 2.6f,
                new int[]{0x70FFFFFF, 0x22FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        rxGlow = new RadialGradient((g.grL + g.grR) / 2f, (g.grT + g.grB) / 2f,
                Math.max(1f, (g.grR - g.grL) * 0.6f), 0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);

        buildDigit(seg[0], g.digitL, g.digitTop, g.digitW, g.digitH);
        buildDigit(seg[1], g.digitL + g.digitW + g.digitGap, g.digitTop, g.digitW, g.digitH);
        buildDots();
        buildSubLayout();
        layoutChips();
    }

    private void buildDots() {
        dotN = 0;
        if (!g.hasGrille) return;
        float sp = 9.5f * dp, inset = 15 * dp;
        float l = g.grL + inset, r = g.grR - inset, t = g.grT + inset * 0.8f, b = g.grB - inset * 0.8f;
        int cols = Math.max(1, (int) ((r - l) / sp) + 1), rows = Math.max(1, (int) ((b - t) / sp) + 1);
        float ox = (l + r - (cols - 1) * sp) / 2f, oy = (t + b - (rows - 1) * sp) / 2f;
        float midCol = (cols - 1) / 2f, midRow = (rows - 1) / 2f;
        float halfW = (cols - 1) * sp / 2f, halfH = (rows - 1) * sp / 2f, rr = 10 * dp;
        float cx = ox + halfW, cy = oy + halfH;
        dotPts = new float[cols * rows * 2];
        dotCol = new int[cols * rows];
        dotRow = new float[cols * rows];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                float x = ox + j * sp, y = oy + i * sp;
                float dx = Math.max(0, Math.abs(x - cx) - (halfW - rr));
                float dy = Math.max(0, Math.abs(y - cy) - (halfH - rr));
                if (dx * dx + dy * dy > rr * rr) continue;     // round the corners of the grid
                dotPts[dotN * 2] = x;
                dotPts[dotN * 2 + 1] = y;
                dotCol[dotN] = (int) Math.abs(j - midCol);       // both middle columns share index 0
                dotRow[dotN] = Math.abs(i - midRow);
                dotN++;
            }
        }
        maxRow = midRow;
        halfCols = Math.min(hist.length, (int) Math.ceil(midCol) + 1);
        for (int k = 0; k < buckets.length; k++) buckets[k] = new float[dotN * 2];
    }

    /**
     * Pushes the current loudness into the centre of the grille every 33 ms; older values move
     * outwards, so speech spreads from the middle like sound leaving a speaker.
     */
    private void stepHistory(long now, float v) {
        int n = halfCols;
        int steps = histAt == 0 ? n : (int) ((now - histAt) / 33);
        if (steps <= 0) {
            if (v > hist[0]) hist[0] = v;                        // same time slot: keep the peak
            return;
        }
        if (steps >= n) {                                        // long pause: start from a clean slate
            java.util.Arrays.fill(hist, 0, n, 0f);
            histAt = now;
        } else {
            System.arraycopy(hist, 0, hist, steps, n - steps);
            for (int i = 1; i < steps; i++) hist[i] = v;
            histAt += steps * 33L;
        }
        hist[0] = v;
        float peak = 0;
        for (int i = 0; i < n; i++) if (hist[i] > peak) peak = hist[i];
        histPeak = peak < 0.01f ? 0 : peak;
    }

    private void buildDigit(Path[] out, float x, float y, float w, float h) {
        float t = h * 0.13f, gp = Math.max(0.8f * dp, t * 0.12f), ht = t / 2f;
        float l = x + ht, r = x + w - ht, top = y + ht, mid = y + h / 2f, bot = y + h - ht;
        out[0] = hSeg(l, r, top, t, gp);
        out[1] = vSeg(r, top, mid, t, gp);
        out[2] = vSeg(r, mid, bot, t, gp);
        out[3] = hSeg(l, r, bot, t, gp);
        out[4] = vSeg(l, mid, bot, t, gp);
        out[5] = vSeg(l, top, mid, t, gp);
        out[6] = hSeg(l, r, mid, t, gp);
        Matrix m = new Matrix();
        m.setSkew(-0.07f, 0, x, y + h);                 // the slight lean of a real LCD
        for (Path p : out) p.transform(m);
    }

    private static Path hSeg(float x1, float x2, float yc, float t, float gp) {
        float a = x1 + gp, b = x2 - gp, h = t / 2f;
        Path p = new Path();
        p.moveTo(a, yc);
        p.lineTo(a + h, yc - h);
        p.lineTo(b - h, yc - h);
        p.lineTo(b, yc);
        p.lineTo(b - h, yc + h);
        p.lineTo(a + h, yc + h);
        p.close();
        return p;
    }

    private static Path vSeg(float xc, float y1, float y2, float t, float gp) {
        float a = y1 + gp, b = y2 - gp, h = t / 2f;
        Path p = new Path();
        p.moveTo(xc, a);
        p.lineTo(xc + h, a + h);
        p.lineTo(xc + h, b - h);
        p.lineTo(xc, b);
        p.lineTo(xc - h, b - h);
        p.lineTo(xc - h, a + h);
        p.close();
        return p;
    }

    // ------------------------------------------------------------------ drawing

    @Override protected void onDraw(Canvas c) {
        if (!sized) return;
        c.drawColor(BLACK);
        long now = SystemClock.uptimeMillis();
        float pulse = (float) (0.5 + 0.5 * Math.sin(now / 260.0));
        float target = !powered ? 0f : (txOn ? engine.txLevel : (rxOn ? engine.rxLevelNow() : 0f));
        target = Math.min(1f, target * 1.15f);
        // fast rise, gentle fall: reads like a VU meter
        level = target > level ? level + (target - level) * 0.6f : level * 0.8f + target * 0.2f;
        if (level < 0.005f) level = 0;
        stepHistory(now, level);
        boolean live = powered && hasNet;

        // ambient light behind the glass
        glow.setShader(ambientA);
        glow.setAlpha(255);
        c.drawCircle(g.bodyR - 20 * dp, g.lcdT, g.w * 0.8f, glow);
        glow.setShader(ambientB);
        c.drawCircle(g.bodyL + 10 * dp, g.trB, g.w * 0.7f, glow);
        glow.setShader(null);

        drawBody(c);
        drawHeader(c);
        if (g.hasGrille) drawGrille(c, now);
        drawDisplay(c, pulse);
        drawRow(c);
        drawStrip(c, pulse, live);
        drawSlider(c, pulse, live);

        if (txOn || rxOn || level > 0 || histPeak > 0) postInvalidateDelayed(33);
    }

    private void drawBody(Canvas c) {
        float r = g.bodyRad;
        fill.setColor(WHITE);                     // shaders are multiplied by the paint's alpha
        fill.setShader(bodyFill);
        c.drawRoundRect(g.bodyL, g.bodyT, g.bodyR, g.bodyB, r, r, fill);
        fill.setShader(null);
        c.drawRoundRect(g.bodyL, g.bodyT, g.bodyR, g.bodyB, r, r, grain);
        line.setColor(WHITE);
        line.setShader(bodyEdge);
        line.setStrokeWidth(1.2f * dp);
        float i = 0.6f * dp;
        c.drawRoundRect(g.bodyL + i, g.bodyT + i, g.bodyR - i, g.bodyB - i, r, r, line);
        line.setShader(null);
    }

    /** The radio's printed name on the left and the power button on the right, inside the body. */
    private void drawHeader(Canvas c) {
        text.setTypeface(medium);
        text.setTextSize(g.brandSize);
        text.setLetterSpacing(0.03f);
        text.setColor(powered ? 0xD9FFFFFF : 0x66FFFFFF);
        text.getFontMetrics(fm);
        c.drawText("Walkie Talkie", g.brandX, g.hdrCy - (fm.ascent + fm.descent) / 2f, text);
        text.setLetterSpacing(0f);

        boolean down = pressed == R_POWER;
        float cx = g.pwrCx, cy = g.hdrCy, r = g.pwrR;
        fill.setColor(down ? 0x40FFFFFF : (powered ? 0x1FFFFFFF : 0x0DFFFFFF));
        c.drawCircle(cx, cy, r, fill);
        line.setStrokeWidth(dp);
        line.setColor(powered ? 0x5CFFFFFF : 0x2EFFFFFF);
        c.drawCircle(cx, cy, r - dp / 2, line);
        powerIcon(c, cx, cy + 0.5f * dp, r * 0.95f, powered ? WHITE : 0x73FFFFFF);
    }

    private void drawGrille(Canvas c, long now) {
        glass(c, g.grL, g.grT, g.grR, g.grB, g.grRad, 0x0D, 0x1F, false);
        boolean active = powered && histPeak > 0;
        if (active) {                                            // soft white bloom behind loud speech
            glow.setShader(rxGlow);
            glow.setAlpha((int) (255 * Math.min(1f, histPeak * 1.1f)));
            c.drawRoundRect(g.grL, g.grT, g.grR, g.grB, g.grRad, g.grRad, glow);
            glow.setShader(null);
            glow.setAlpha(255);
        }
        for (int k = 0; k < bucketLen.length; k++) bucketLen[k] = 0;
        float reach = maxRow + 1f;
        for (int i = 0; i < dotN; i++) {
            int b = 0;
            if (active) {
                float v = hist[dotCol[i]];
                if (v > 0.02f) {
                    // the column lights from the middle row outwards; full loudness lights it all
                    float lit = v * reach - dotRow[i];
                    if (lit > 0) b = 1 + (int) (Math.min(1f, lit) * 3.999f);
                }
            }
            float[] arr = buckets[b];
            int at = bucketLen[b];
            arr[at] = dotPts[i * 2];
            arr[at + 1] = dotPts[i * 2 + 1];
            bucketLen[b] = at + 2;
        }
        // brightest dots get a faint halo so they read as glowing, not just grey
        for (int k = 3; k < 5; k++) {
            if (bucketLen[k] == 0) continue;
            dots.setStrokeWidth(7.5f * dp);
            dots.setColor(k == 4 ? 0x30FFFFFF : 0x18FFFFFF);
            c.drawPoints(buckets[k], 0, bucketLen[k], dots);
        }
        dots.setStrokeWidth(3.4f * dp);
        for (int k = 0; k < bucketLen.length; k++) {
            if (bucketLen[k] == 0) continue;
            int a = k == 0 ? (powered ? DOT_ALPHA[0] : 20) : DOT_ALPHA[k];
            dots.setColor(Color.argb(a, 255, 255, 255));
            c.drawPoints(buckets[k], 0, bucketLen[k], dots);
        }
    }

    private void drawDisplay(Canvas c, float pulse) {
        float r = g.lcdRad;
        boolean down = false;
        fill.setColor(0xDB000000);
        c.drawRoundRect(g.lcdL, g.lcdT, g.lcdR, g.lcdB, r, r, fill);
        fill.setColor(WHITE);
        fill.setShader(lcdSheen);
        c.drawRoundRect(g.lcdL, g.lcdT, g.lcdR, g.lcdB, r, r, fill);
        fill.setShader(null);
        line.setStrokeWidth(dp);
        line.setColor(down ? 0x4DFFFFFF : 0x24FFFFFF);
        c.drawRoundRect(g.lcdL + dp / 2, g.lcdT + dp / 2, g.lcdR - dp / 2, g.lcdB - dp / 2, r, r, line);

        // label
        text.setTypeface(regular);
        text.setTextSize(g.labelSize);
        text.setColor(powered ? 0x80FFFFFF : 0x4DFFFFFF);
        c.drawText("Channel", g.digitL, g.labelBaseline, text);

        // seven-segment channel number
        int m0 = 0, m1 = 0;
        if (powered) {
            m0 = DIGITS[channel / 10];
            m1 = DIGITS[channel % 10];
        }
        fill.setColor(0x14FFFFFF);
        for (int d = 0; d < 2; d++) for (int s = 0; s < 7; s++) c.drawPath(seg[d][s], fill);
        if (powered) {
            fill.setColor(WHITE);
            fill.setShadowLayer(6 * dp, 0, 0, 0x8CFFFFFF);
            for (int s = 0; s < 7; s++) {
                if ((m0 >> s & 1) != 0) c.drawPath(seg[0][s], fill);
                if ((m1 >> s & 1) != 0) c.drawPath(seg[1][s], fill);
            }
            fill.clearShadowLayer();
        }

        // status light + title
        float ledR = 4 * dp;
        float ledX = g.infoL + ledR, ledY = g.statusBaseline - g.titleSize * 0.36f;
        int ledA = !powered ? 0x1A : (txOn ? 0xFF : (rxOn ? (int) (90 + 165 * pulse) : (hasNet ? 0x59 : 0x26)));
        fill.setColor(Color.argb(ledA, 255, 255, 255));
        if (txOn || rxOn) fill.setShadowLayer(6 * dp, 0, 0, 0xB3FFFFFF);
        c.drawCircle(ledX, ledY, ledR, fill);
        fill.clearShadowLayer();

        text.setTypeface(medium);
        text.setTextSize(g.titleSize);
        text.setColor(powered ? WHITE : 0x66FFFFFF);
        if (txOn || rxOn) text.setShadowLayer(8 * dp, 0, 0, 0x66FFFFFF);
        float tx = ledX + ledR + 8 * dp;
        c.drawText(ellipsize(title, g.infoR - tx), tx, g.statusBaseline, text);
        text.clearShadowLayer();

        if (subLayout != null) {
            subText.setColor(powered ? 0x9EFFFFFF : 0x59FFFFFF);
            c.save();
            c.translate(g.infoL, g.subTop);
            subLayout.draw(c);
            c.restore();
        }

        // footer: network + level meter
        float barW = 3 * dp, gap = 2.5f * dp, total = 10 * barW + 9 * gap;
        float mx = g.lcdR - g.lcdPad - total;
        text.setTypeface(regular);
        text.setTextSize(g.footSize);
        text.setColor(0x73FFFFFF);
        if (!netLine.isEmpty()) {
            c.drawText(ellipsize(netLine, mx - 10 * dp - (g.lcdL + g.lcdPad)), g.lcdL + g.lcdPad, g.footBaseline, text);
        }
        if (powered) {
            int lit = Math.round(level * 10);
            for (int i = 0; i < 10; i++) {
                float bh = 4 * dp + i * (10 * dp / 9f);
                float x = mx + i * (barW + gap);
                fill.setColor(i < lit ? WHITE : 0x24FFFFFF);
                c.drawRoundRect(x, g.footBaseline - bh, x + barW, g.footBaseline, barW / 2, barW / 2, fill);
            }
        }
    }

    private void drawRow(Canvas c) {
        roundButton(c, g.chDownCx, g.rowCy, g.btnR, pressed == R_CH_DOWN);
        roundButton(c, g.chUpCx, g.rowCy, g.btnR, pressed == R_CH_UP);
        line.setColor(WHITE);
        line.setStrokeWidth(2 * dp);
        float a = 7 * dp;
        c.drawLine(g.chDownCx - a, g.rowCy, g.chDownCx + a, g.rowCy, line);
        c.drawLine(g.chUpCx - a, g.rowCy, g.chUpCx + a, g.rowCy, line);
        c.drawLine(g.chUpCx, g.rowCy - a, g.chUpCx, g.rowCy + a, line);

        // the stepper says what it does: these buttons change the channel
        text.setTypeface(regular);
        text.setTextSize(g.rowLabelSize);
        text.setColor(powered ? 0xA6FFFFFF : 0x59FFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.getFontMetrics(fm);
        c.drawText("Change channel", (g.bodyL + g.bodyR) / 2f, g.rowCy - (fm.ascent + fm.descent) / 2f, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    /** Measures every chip once per change, so drawing a frame never measures text. */
    private void layoutChips() {
        if (!sized) return;
        int n = people.size() + 1;
        if (chipId.length < n) {
            int cap = n + 4;
            chipId = new int[cap];
            chipX = new float[cap];
            chipW = new float[cap];
            chipName = new String[cap];
            chipSub = new String[cap];
            chipInitial = new String[cap];
        }
        chipCount = n;
        float maxText = 120 * dp, x = 0, gap = 8 * dp;
        for (int i = 0; i < n; i++) {
            PeerTable.Peer p = i == 0 ? null : people.get(i - 1);
            String name = i == 0 ? callsign : p.name;
            String subLine = i == 0 ? "You" : (p.channel == channel ? "Hold to talk" : "On channel " + p.channel);
            text.setTypeface(medium);
            text.setTextSize(g.chipNameSize);
            String fitted = ellipsize(name, maxText);
            float w = text.measureText(fitted);
            text.setTypeface(regular);
            text.setTextSize(g.chipSubSize);
            w = Math.max(w, text.measureText(subLine));
            chipId[i] = i == 0 ? 0 : p.id;
            chipName[i] = fitted;
            chipSub[i] = subLine;
            int cp = name.isEmpty() ? '?' : name.codePointAt(0);
            chipInitial[i] = new String(Character.toChars(Character.toUpperCase(cp)));
            chipW[i] = 6 * dp + 30 * dp + 10 * dp + w + 12 * dp + 16 * dp + 12 * dp;   // avatar, text, icon
            chipX[i] = x;
            x += chipW[i] + gap;
        }
        float width = g.stripR - g.stripL;
        stripMax = Math.max(0, x - gap - width);
        if (stripScroll > stripMax) stripScroll = stripMax;
        text.setTypeface(regular);
        text.setTextSize(g.chipSubSize * 1.1f);
        emptyText = people.isEmpty()
                ? ellipsize("Others on this Wi-Fi show up here", width - chipW[0] - 14 * dp) : "";
    }

    private void drawStrip(Canvas c, float pulse, boolean live) {
        c.save();
        c.clipRect(g.stripL - 1, g.stripT - 1, g.stripR + 1, g.stripB + 1);
        float top = g.stripCy - g.chipH / 2f, bottom = g.stripCy + g.chipH / 2f, r = g.chipH / 2f;
        for (int i = 0; i < chipCount; i++) {
            float l = g.stripL + chipX[i] - stripScroll, rr = l + chipW[i];
            if (rr < g.stripL || l > g.stripR) continue;
            int id = chipId[i];
            boolean me = i == 0;
            boolean talkingTo = !me && txOn && id == directTo;
            boolean hearing = !me && id == speaker;
            boolean down = !stripScrolling && (me ? pressedChip == 0 : pressedChip >= 1 && id == pressedPeer);
            boolean usable = me || (live && (!txOn || talkingTo));
            int ink;
            if (talkingTo) {                                     // solid white while you talk to them
                glow.setShader(null);
                glow.setColor(Color.argb((int) (60 + 50 * pulse), 255, 255, 255));
                glow.setMaskFilter(null);
                c.drawRoundRect(l - 3 * dp, top - 3 * dp, rr + 3 * dp, bottom + 3 * dp, r + 3 * dp, r + 3 * dp, glow);
                glow.setColor(WHITE);
                fill.setColor(WHITE);
                c.drawRoundRect(l, top, rr, bottom, r, r, fill);
                ink = BLACK;
            } else {
                fill.setColor(down ? 0x3DFFFFFF : (me ? 0x0AFFFFFF : 0x17FFFFFF));
                c.drawRoundRect(l, top, rr, bottom, r, r, fill);
                line.setStrokeWidth(hearing ? 1.6f * dp : dp);
                line.setColor(hearing ? Color.argb((int) (120 + 135 * pulse), 255, 255, 255) : (me ? 0x2EFFFFFF : 0x40FFFFFF));
                c.drawRoundRect(l + dp / 2, top + dp / 2, rr - dp / 2, bottom - dp / 2, r, r, line);
                ink = usable ? WHITE : 0x66FFFFFF;
            }
            // avatar with initial
            float ax = l + 6 * dp + 15 * dp;
            if (talkingTo) fill.setColor(0x1F000000);
            else fill.setColor(me ? 0x0FFFFFFF : 0x24FFFFFF);
            c.drawCircle(ax, g.stripCy, 15 * dp, fill);
            if (me) {
                line.setStrokeWidth(dp);
                line.setColor(0x4DFFFFFF);
                c.drawCircle(ax, g.stripCy, 15 * dp - dp / 2, line);
            }
            text.setTypeface(medium);
            text.setTextSize(13 * dp);
            text.setColor(ink);
            text.setTextAlign(Paint.Align.CENTER);
            text.getFontMetrics(fm);
            c.drawText(chipInitial[i], ax, g.stripCy - (fm.ascent + fm.descent) / 2f, text);
            text.setTextAlign(Paint.Align.LEFT);
            // name and the line under it
            float tx = ax + 15 * dp + 10 * dp;
            text.setTextSize(g.chipNameSize);
            c.drawText(chipName[i], tx, g.stripCy - 2 * dp, text);
            text.setTypeface(regular);
            text.setTextSize(g.chipSubSize);
            text.setColor(talkingTo ? 0xB3000000 : (usable ? 0x99FFFFFF : 0x4DFFFFFF));
            c.drawText(talkingTo ? "Only they hear you" : chipSub[i], tx, g.stripCy + 12 * dp, text);
            // the voice button icon: mic for people, pencil for you
            float ix = rr - 12 * dp - 8 * dp;
            if (me) pencilIcon(c, ix, g.stripCy, 14 * dp, 0x80FFFFFF);
            else micIcon(c, ix, g.stripCy, 17 * dp, talkingTo ? BLACK : (usable ? 0xD9FFFFFF : 0x59FFFFFF));
        }
        if (!emptyText.isEmpty()) {
            text.setTypeface(regular);
            text.setTextSize(g.chipSubSize * 1.1f);
            text.setColor(powered ? 0x66FFFFFF : 0x33FFFFFF);
            text.getFontMetrics(fm);
            float x = g.stripL + chipX[0] + chipW[0] + 14 * dp - stripScroll;
            c.drawText(emptyText, x, g.stripCy - (fm.ascent + fm.descent) / 2f, text);
        }
        c.restore();
    }

    private int chipAt(float x) {
        float cx = x - g.stripL + stripScroll;
        for (int i = 0; i < chipCount; i++) if (cx >= chipX[i] && cx <= chipX[i] + chipW[i]) return i;
        return -1;
    }

    private String chipNameOf(int id) {
        for (int i = 1; i < chipCount; i++) if (chipId[i] == id) return chipName[i];
        return "them";
    }

    /** Holding a person's chip for 90 ms starts a private call (shorter touches can scroll). */
    private void startDirect() {
        if (pressedChip < 1 || stripScrolling || engine.transmitting) return;
        int id = pressedPeer;
        if (host.beginTalk(id)) {
            directTo = id;
            directStarted = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } else {
            performHapticFeedback(HapticFeedbackConstants.REJECT);
        }
        invalidate();
    }

    private void drawSlider(Canvas c, float pulse, boolean live) {
        // side buttons
        toggle(c, g.soundsCx, soundsOn, pressed == R_SOUNDS, true, "Sounds");
        toggle(c, g.bgCx, bgOn, pressed == R_BG, false, "Always on");

        // track
        float tr = (g.trR - g.trL) / 2f;
        fill.setColor(0x8C000000);
        c.drawRoundRect(g.trL, g.trT, g.trR, g.trB, tr, tr, fill);
        fill.setColor(0x0FFFFFFF);
        c.drawRoundRect(g.trL, g.trT, g.trR, g.trB, tr, tr, fill);
        line.setStrokeWidth(dp);
        line.setColor(0x33FFFFFF);
        c.drawRoundRect(g.trL + dp / 2, g.trT + dp / 2, g.trR - dp / 2, g.trB - dp / 2, tr, tr, line);

        float travel = g.knobRestY - g.knobTopY;
        float off = Math.max(-4 * dp, Math.min(knobOffset, travel));
        float kx = g.knobCx + shakeX, ky = g.knobRestY - off;

        // lit trail under the knob
        if (off > 1 || locked) {
            fill.setColor(txOn ? 0x33FFFFFF : 0x1AFFFFFF);
            float in = 7 * dp;
            c.drawRoundRect(g.trL + in, ky, g.trR - in, g.trB - in, g.knobR, g.knobR, fill);
        }

        // lock at the top of the track
        boolean lockLit = armed || locked;
        lockIcon(c, g.knobCx, g.knobTopY, 22 * dp, lockLit ? WHITE : (live ? 0x66FFFFFF : 0x33FFFFFF));

        // chevrons pointing up
        if (!dragging && !locked) {
            float top = g.knobTopY + 24 * dp, bottom = g.knobRestY - g.knobR - 10 * dp;
            if (bottom - top > 36 * dp) {
                float step = (bottom - top) / 3f;
                line.setStrokeWidth(2 * dp);
                for (int i = 0; i < 3; i++) {
                    float cy = top + step * (i + 0.5f);
                    int a = live ? (i == 0 ? 0x80 : (i == 1 ? 0x59 : 0x33)) : 0x1F;
                    line.setColor(Color.argb(a, 255, 255, 255));
                    chevron(c, g.knobCx, cy, 14 * dp);
                }
            }
        }

        // the knob
        if (txOn && directTo == 0) {                            // the big button glows only for "everyone"
            matrix.setTranslate(kx, ky);
            txGlow.setLocalMatrix(matrix);
            glow.setShader(txGlow);
            glow.setAlpha((int) (255 * (0.65f + 0.35f * pulse)));
            c.drawCircle(kx, ky, g.knobR * 2.6f, glow);
            glow.setShader(null);
            glow.setAlpha(255);
        }
        if (live) {
            matrix.setTranslate(kx, ky);
            knobShade.setLocalMatrix(matrix);
            fill.setColor(WHITE);                 // was inheriting 40% from the lock icon: grey knob
            fill.setShader(knobShade);
            c.drawCircle(kx, ky, g.knobR, fill);
            fill.setShader(null);
            micIcon(c, kx, ky, g.knobR * 0.95f, BLACK);
        } else {
            fill.setColor(0x1FFFFFFF);
            c.drawCircle(kx, ky, g.knobR, fill);
            line.setStrokeWidth(dp);
            line.setColor(0x4DFFFFFF);
            c.drawCircle(kx, ky, g.knobR - dp / 2, line);
            micIcon(c, kx, ky, g.knobR * 0.95f, 0x80FFFFFF);
        }
        if (locked) {
            float bx = kx + g.knobR * 0.68f, by = ky - g.knobR * 0.68f, br = 12 * dp;
            fill.setColor(BLACK);
            c.drawCircle(bx, by, br, fill);
            line.setStrokeWidth(dp);
            line.setColor(0x99FFFFFF);
            c.drawCircle(bx, by, br, line);
            lockIcon(c, bx, by + dp, 13 * dp, WHITE);
        }

        // hint under the track
        String hint;
        if (!powered) hint = "Tap the power button to turn on";
        else if (!hasNet) hint = "Works on a shared Wi-Fi network only";
        else if (directTo != 0 && txOn) hint = hintDirect;
        else if (locked) hint = "Tap to stop talking";
        else if (dragging) hint = armed ? "Let go to keep talking" : "Slide up to lock";
        else hint = "Hold to talk to everyone, slide up to lock";
        text.setTypeface(regular);
        text.setTextSize(g.hintSize);
        text.setColor(0x8CFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(ellipsize(hint, g.bodyR - g.bodyL - 40 * dp), (g.bodyL + g.bodyR) / 2f, g.hintY, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void toggle(Canvas c, float cx, boolean on, boolean down, boolean isSounds, String label) {
        float cy = g.sideCy, r = g.sideR;
        if (on) {
            fill.setColor(down ? 0xFFD6D6D6 : WHITE);
            c.drawCircle(cx, cy, r, fill);
        } else {
            fill.setColor(down ? 0x38FFFFFF : 0x17FFFFFF);
            c.drawCircle(cx, cy, r, fill);
            line.setStrokeWidth(dp);
            line.setColor(0x47FFFFFF);
            c.drawCircle(cx, cy, r - dp / 2, line);
        }
        int ic = on ? BLACK : 0xBFFFFFFF;
        if (isSounds) speakerIcon(c, cx, cy, r * 0.9f, ic);
        else wavesIcon(c, cx, cy, r * 0.9f, ic);
        text.setTypeface(regular);
        text.setTextSize(g.sideLabelSize);
        float room = 2 * Math.min(cx - (g.bodyL + 18 * dp), Math.abs(cx - g.knobCx) - (g.trR - g.trL) / 2f) - 6 * dp;
        float tw = text.measureText(label);
        if (tw > room && room > 0) text.setTextSize(g.sideLabelSize * room / tw);   // fit narrow screens
        text.setColor(on ? 0xE6FFFFFF : 0x80FFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(label, cx, g.sideLabelBaseline, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void glass(Canvas c, float l, float t, float r, float b, float rad, int fillA, int edgeA, boolean grainy) {
        fill.setColor(Color.argb(fillA, 255, 255, 255));
        c.drawRoundRect(l, t, r, b, rad, rad, fill);
        if (grainy) c.drawRoundRect(l, t, r, b, rad, rad, grain);
        line.setStrokeWidth(dp);
        line.setColor(Color.argb(edgeA, 255, 255, 255));
        float i = dp / 2;
        c.drawRoundRect(l + i, t + i, r - i, b - i, rad, rad, line);
    }

    private void roundButton(Canvas c, float cx, float cy, float r, boolean down) {
        fill.setColor(down ? 0x40FFFFFF : 0x14FFFFFF);
        c.drawCircle(cx, cy, r, fill);
        line.setStrokeWidth(dp);
        line.setColor(0x38FFFFFF);
        c.drawCircle(cx, cy, r - dp / 2, line);
    }

    /** Remembers the last few fitted strings so animation frames never re-measure text. */
    private final String[] fitIn = new String[6], fitOut = new String[6];
    private final float[] fitKey = new float[6];

    private String ellipsize(String s, float width) {
        if (width <= 0) return "";
        float key = width * 31 + text.getTextSize();
        for (int i = 0; i < fitIn.length; i++) {
            if (s.equals(fitIn[i]) && fitKey[i] == key) return fitOut[i];
        }
        String out = TextUtils.ellipsize(s, text, width, TextUtils.TruncateAt.END).toString();
        System.arraycopy(fitIn, 0, fitIn, 1, fitIn.length - 1);
        System.arraycopy(fitOut, 0, fitOut, 1, fitOut.length - 1);
        System.arraycopy(fitKey, 0, fitKey, 1, fitKey.length - 1);
        fitIn[0] = s;
        fitOut[0] = out;
        fitKey[0] = key;
        return out;
    }

    // ------------------------------------------------------------------ icons (drawn, not bitmaps)

    private void micIcon(Canvas c, float cx, float cy, float s, int color) {
        fill.setColor(color);
        float w = s * 0.16f;
        c.drawRoundRect(cx - w, cy - s * 0.42f, cx + w, cy + s * 0.1f, w, w, fill);
        line.setColor(color);
        line.setStrokeWidth(s * 0.075f);
        tmp.set(cx - s * 0.29f, cy - s * 0.3f, cx + s * 0.29f, cy + s * 0.22f);
        c.drawArc(tmp, 0, 180, false, line);
        c.drawLine(cx, cy + s * 0.22f, cx, cy + s * 0.37f, line);
        c.drawLine(cx - s * 0.14f, cy + s * 0.37f, cx + s * 0.14f, cy + s * 0.37f, line);
    }

    private void lockIcon(Canvas c, float cx, float cy, float s, int color) {
        fill.setColor(color);
        c.drawRoundRect(cx - s * 0.3f, cy - s * 0.06f, cx + s * 0.3f, cy + s * 0.4f, s * 0.07f, s * 0.07f, fill);
        line.setColor(color);
        line.setStrokeWidth(s * 0.09f);
        tmp.set(cx - s * 0.19f, cy - s * 0.42f, cx + s * 0.19f, cy - s * 0.04f);
        c.drawArc(tmp, 180, 180, false, line);
        c.drawLine(cx - s * 0.19f, cy - s * 0.23f, cx - s * 0.19f, cy - s * 0.06f, line);
        c.drawLine(cx + s * 0.19f, cy - s * 0.23f, cx + s * 0.19f, cy - s * 0.06f, line);
    }

    private void powerIcon(Canvas c, float cx, float cy, float s, int color) {
        line.setColor(color);
        line.setStrokeWidth(s * 0.11f);
        tmp.set(cx - s * 0.34f, cy - s * 0.3f, cx + s * 0.34f, cy + s * 0.38f);
        c.drawArc(tmp, -60, 300, false, line);
        c.drawLine(cx, cy - s * 0.44f, cx, cy - s * 0.02f, line);
    }

    private void speakerIcon(Canvas c, float cx, float cy, float s, int color) {
        float ox = cx - s * 0.06f;
        path.reset();
        path.moveTo(ox - s * 0.3f, cy - s * 0.11f);
        path.lineTo(ox - s * 0.15f, cy - s * 0.11f);
        path.lineTo(ox + s * 0.03f, cy - s * 0.28f);
        path.lineTo(ox + s * 0.03f, cy + s * 0.28f);
        path.lineTo(ox - s * 0.15f, cy + s * 0.11f);
        path.lineTo(ox - s * 0.3f, cy + s * 0.11f);
        path.close();
        fill.setColor(color);
        c.drawPath(path, fill);
        line.setColor(color);
        line.setStrokeWidth(s * 0.07f);
        for (int k = 0; k < 2; k++) {
            float rad = s * (0.16f + 0.14f * k);
            tmp.set(ox + s * 0.03f - rad, cy - rad, ox + s * 0.03f + rad, cy + rad);
            c.drawArc(tmp, -42, 84, false, line);
        }
    }

    /** A dot with radio waves on both sides: "keeps listening". */
    private void wavesIcon(Canvas c, float cx, float cy, float s, int color) {
        fill.setColor(color);
        c.drawCircle(cx, cy, s * 0.08f, fill);
        line.setColor(color);
        line.setStrokeWidth(s * 0.07f);
        for (int k = 0; k < 2; k++) {
            float rad = s * (0.18f + 0.15f * k);
            tmp.set(cx - rad, cy - rad, cx + rad, cy + rad);
            c.drawArc(tmp, -40, 80, false, line);
            c.drawArc(tmp, 140, 80, false, line);
        }
    }

    private void personIcon(Canvas c, float cx, float cy, float s, int color) {
        line.setColor(color);
        line.setStrokeWidth(s * 0.1f);
        c.drawCircle(cx, cy - s * 0.17f, s * 0.17f, line);
        tmp.set(cx - s * 0.32f, cy + s * 0.12f, cx + s * 0.32f, cy + s * 0.68f);
        c.drawArc(tmp, 180, 180, false, line);
    }

    private void pencilIcon(Canvas c, float cx, float cy, float s, int color) {
        line.setColor(color);
        line.setStrokeWidth(s * 0.1f);
        c.save();
        c.rotate(-45, cx, cy);
        c.drawRoundRect(cx - s * 0.4f, cy - s * 0.12f, cx + s * 0.2f, cy + s * 0.12f, s * 0.03f, s * 0.03f, line);
        path.reset();
        path.moveTo(cx + s * 0.2f, cy - s * 0.12f);
        path.lineTo(cx + s * 0.42f, cy);
        path.lineTo(cx + s * 0.2f, cy + s * 0.12f);
        c.drawPath(path, line);
        c.restore();
    }

    private void chevron(Canvas c, float cx, float cy, float s) {
        path.reset();
        path.moveTo(cx - s * 0.5f, cy + s * 0.22f);
        path.lineTo(cx, cy - s * 0.22f);
        path.lineTo(cx + s * 0.5f, cy + s * 0.22f);
        c.drawPath(path, line);
    }

    // ------------------------------------------------------------------ touch

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!sized) return false;
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                pressed = hit(x, y);
                if (pressed == R_STRIP) {
                    int i = chipAt(x);
                    pressedChip = i;
                    pressedPeer = i >= 1 ? chipId[i] : 0;
                    stripScrolling = false;
                    stripDownX = x;
                    stripDownScroll = stripScroll;
                    directStarted = false;
                    if (pressedChip >= 1) postDelayed(holdToTalk, 90);
                    invalidate();
                    return true;
                }
                if (pressed == R_KNOB && !locked) {
                    if (host.beginTalk(0)) {
                        dragging = true;
                        armed = false;
                        downY = y;
                        cancelKnobAnim();
                        downOffset = knobOffset;
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    } else {
                        pressed = R_NONE;
                        shake();
                    }
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (pressed == R_STRIP) {
                    if (directTo != 0) return true;                 // talking: ignore finger drift
                    if (!stripScrolling && Math.abs(x - stripDownX) > 8 * dp) {
                        stripScrolling = true;                      // it's a swipe, not a hold
                        removeCallbacks(holdToTalk);
                    }
                    if (stripScrolling && stripMax > 0) {
                        stripScroll = Math.max(0, Math.min(stripMax, stripDownScroll - (x - stripDownX)));
                    }
                    invalidate();
                    return true;
                }
                if (dragging) {
                    float travel = g.knobRestY - g.knobTopY;
                    knobOffset = Math.max(0, Math.min(travel, downOffset + (downY - y)));
                    boolean a = knobOffset >= travel * 0.8f;
                    if (a != armed) {
                        armed = a;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    invalidate();
                } else if (pressed != R_NONE && hit(x, y) != pressed) {
                    pressed = R_NONE;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                if (pressed == R_STRIP) {
                    removeCallbacks(holdToTalk);
                    if (directTo != 0) {                            // let go: end the private call
                        directTo = 0;
                        host.endTalk();
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE);
                    } else if (up && !stripScrolling && pressedChip == 0) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        host.editName();
                    } else if (up && !stripScrolling && pressedChip >= 1 && !directStarted) {
                        engine.flash("Hold " + chipNameOf(pressedPeer) + " to talk to them only");
                    }
                    pressedChip = -1;
                    stripScrolling = false;
                    pressed = R_NONE;
                    invalidate();
                    return true;
                }
                if (dragging) {
                    dragging = false;
                    if (armed && up && engine.transmitting) {
                        locked = true;
                        animateKnob(g.knobRestY - g.knobTopY);
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    } else {
                        armed = false;
                        host.endTalk();
                        animateKnob(0);
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE);
                    }
                } else if (pressed == R_KNOB && locked) {
                    if (up && hit(x, y) == R_KNOB) {
                        locked = false;
                        armed = false;
                        host.endTalk();
                        animateKnob(0);
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE);
                    }
                } else if (up && pressed != R_NONE && hit(x, y) == pressed) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    click(pressed);
                }
                pressed = R_NONE;
                invalidate();
                return true;
            }
            default:
                return true;
        }
    }

    private void click(int region) {
        switch (region) {
            case R_POWER: host.togglePower(); break;
            case R_CH_DOWN: host.stepChannel(-1); break;
            case R_CH_UP: host.stepChannel(1); break;
            case R_SOUNDS: host.toggleSounds(); break;
            case R_BG: host.toggleBackground(); break;
            default: break;
        }
    }

    private int hit(float x, float y) {
        float slop = 8 * dp;
        if (dist(x, y, g.pwrCx, g.hdrCy) <= g.pwrR + 1.5f * slop) return R_POWER;
        float ky = g.knobRestY - knobOffset;
        if (dist(x, y, g.knobCx, ky) <= g.knobR + slop) return R_KNOB;
        if (dist(x, y, g.chDownCx, g.rowCy) <= g.btnR + slop / 2) return R_CH_DOWN;
        if (dist(x, y, g.chUpCx, g.rowCy) <= g.btnR + slop / 2) return R_CH_UP;
        if (x >= g.stripL && x <= g.stripR && y >= g.stripT && y <= g.stripB) return R_STRIP;
        if (inToggle(x, y, g.soundsCx)) return R_SOUNDS;
        if (inToggle(x, y, g.bgCx)) return R_BG;
        return R_NONE;
    }

    private boolean inToggle(float x, float y, float cx) {
        float r = g.sideR + 8 * dp;
        if (dist(x, y, cx, g.sideCy) <= r) return true;
        return Math.abs(x - cx) <= r && y >= g.sideCy && y <= g.sideLabelBaseline + 6 * dp;
    }

    private static float dist(float x, float y, float cx, float cy) {
        return (float) Math.hypot(x - cx, y - cy);
    }

    /** Leaving the screen cancels any half-started press, so the mic can't open in the background. */
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) {
            removeCallbacks(holdToTalk);
            pressedChip = -1;
            pressed = R_NONE;
            dragging = false;
        }
    }

    // ------------------------------------------------------------------ motion

    private void cancelKnobAnim() {
        if (knobAnim != null) {
            knobAnim.cancel();
            knobAnim = null;
        }
    }

    private void animateKnob(float to) {
        cancelKnobAnim();
        if (!sized) {
            knobOffset = to;
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(knobOffset, to);
        a.setDuration(to > knobOffset ? 160 : 280);
        a.setInterpolator(to > 0 ? new DecelerateInterpolator() : new OvershootInterpolator(1.1f));
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator v) {
                knobOffset = (Float) v.getAnimatedValue();
                invalidate();
            }
        });
        knobAnim = a;
        a.start();
    }

    private void shake() {
        if (shakeAnim != null) shakeAnim.cancel();
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(380);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator v) {
                float t = (Float) v.getAnimatedValue();
                shakeX = (float) Math.sin(t * Math.PI * 6) * (1 - t) * 7 * dp;
                invalidate();
            }
        });
        shakeAnim = a;
        a.start();
        performHapticFeedback(HapticFeedbackConstants.REJECT);
    }
}
