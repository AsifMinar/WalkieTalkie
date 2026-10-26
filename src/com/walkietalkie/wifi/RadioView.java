package com.walkietalkie.wifi;

import android.animation.ValueAnimator;
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
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.OverScroller;

import java.util.ArrayList;

/**
 * The entire radio, drawn on one canvas: no view tree, no images, no libraries. It only redraws
 * when something changes, and runs a ~30 fps loop only while someone is talking (60 fps while the
 * talk button's water ripples are moving).
 *
 * The look is pitch black: every surface is black, parts are separated by thin grey lines that
 * stay visible at the lowest screen brightness, and white is kept for what you read or press
 * (text, digits, the talk button, switched-on toggles).
 */
final class RadioView extends View {

    private static final int R_NONE = 0, R_POWER = 1, R_NAME = 2, R_CH_DOWN = 3, R_CH_UP = 4,
            R_SOUNDS = 6, R_BG = 7, R_KNOB = 8, R_STRIP = 9;

    private static final int WHITE = 0xFFFFFFFF, BLACK = 0xFF000000;
    // Pitch-black palette: surfaces stay black, these greys only draw edges, dots and states.
    // High contrast on purpose: phone screens at low brightness swallow dark greys, so anything
    // darker than about #555 vanished in a dark room. These stay visible at the lowest brightness.
    private static final int LINE = 0xFF707070;         // outline of every button, chip and panel
    private static final int LINE_DIM = 0xFF606060;     // speaker grille outline (one step quieter)
    private static final int PRESSED = 0xFF303030;      // fill while a finger is on something
    private static final int AVATAR = 0xFF141414;       // the circle behind a person's initial
    private static final int AVATAR_ON_WHITE = 0xFFE4E4E4;   // ...on the white private-call chip
    private static final int SUB_ON_WHITE = 0xFF444444;      // "Private call" on the white chip
    private static final int GREY_TEXT = 0xFFB0B0B0;    // labels, sub-lines and the hint
    private static final int UNLIT = 0xFF2C2C2C;        // channel digit segments that are off
    private static final int METER_OFF = 0xFF555555;    // level meter bars that are off
    private static final int TRACK_TOP = 0xFF333333, TRACK_BOTTOM = 0xFF0D0D0D, TRACK_EDGE = 0xFF6A6A6A;
    private static final int TRACK_EDGE_LIVE = 0xFFA0A0A0;   // track outline while talking to everyone

    // Water ripples around the talk button: a small fixed pool, so frames never allocate.
    private static final int MAX_WAVES = 12;

    // Seven-segment patterns, bit 0..6 = segments a..g
    private static final int[] DIGITS = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F};
    private static final int[] DOT_ALPHA = {102, 140, 180, 220, 255};   // [0] = unlit grille dot (#666)

    private final MainActivity host;
    private final RadioEngine engine;
    private final float dp, ts;
    private final Geometry g = new Geometry();
    // 1 on normal screens. In a window smaller than Geometry.MIN_W_DP x MIN_H_DP (split screen,
    // pop-up window) the radio is laid out at that minimum size and drawn scaled down by this much;
    // touches are scaled back, so every button answers exactly where it is drawn.
    private float fit = 1f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);

    private final RectF tmp = new RectF();
    private final Path path = new Path();
    private final Path[][] seg = new Path[2][7];
    private final Matrix matrix = new Matrix();

    private Shader trackFill, knobShade, rxGlow;
    private final Path bodyClip = new Path();   // ripples stay inside the radio's body
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

    // your name plate between the channel buttons (tap it to change your name)
    private float plateL, plateR;
    private String plateName = "", plateInitial = "?";

    // people row: everyone else on this Wi-Fi (you are on the name plate, not in this row)
    private ArrayList<PeerTable.Peer> people = new ArrayList<>();
    private int chipCount;
    private int[] chipId = new int[0];
    private float[] chipX = new float[0], chipW = new float[0];
    private String[] chipName = new String[0], chipSub = new String[0], chipInitial = new String[0];
    private String emptyText = "";
    private float stripScroll, stripMax, stripDownX, stripDownScroll;
    private boolean stripScrolling;
    // Which chip is pressed, by position: -1 none, 0+ a person. Never inferred from the id,
    // because ids are random 32-bit numbers and half of them are negative.
    private int pressedChip = -1;
    private int pressedPeer;                    // that person's id (valid when pressedChip >= 0)
    private ValueAnimator stripAnim;            // scrolls a private call's chip fully into view
    private float stripAnimTo;
    // A swipe that ends fast keeps the row gliding and slowing down, like any phone list.
    private final OverScroller glide;
    private VelocityTracker swipe;              // only while a finger is on the row
    private final float minGlide, maxGlide;     // px per second, from the system's touch settings
    private int directTo;                       // person we're talking to privately (0 = none)
    private boolean directStarted;              // a private call began during the current touch
    private int speaker;                        // who is talking to us (0 = nobody)
    private final Runnable holdToTalk = new Runnable() {
        @Override public void run() { startDirect(); }
    };
    private StaticLayout subLayout;

    // knob + touch
    private float knobOffset, downY, downOffset, shakeX, level;
    private long levelAt;                       // the level meter steps at ~30 fps, whatever the frame rate
    private boolean dragging, armed, locked;
    private int pressed = R_NONE;
    private ValueAnimator knobAnim, shakeAnim, dipAnim;

    // water drop: the knob dips when pressed, rings spread from it while you talk to everyone
    private float knobScale = 1f;
    private boolean slid;                       // this press started sliding up: no more waves
    private final long[] waveAt = new long[MAX_WAVES];
    private final int[] waveDur = new int[MAX_WAVES];      // 0 = free slot
    private final float[] waveAlpha = new float[MAX_WAVES], waveWidth = new float[MAX_WAVES],
            waveReach = new float[MAX_WAVES];
    private long nextWaveAt;
    // Set when the screen goes away while you hold or lock the talk button. Leaving stops talking
    // (the mic never stays open off screen), and the knob resets when you come back: that reset is
    // not something you did, so it shouldn't send a wave.
    private boolean awayWhileTalking;

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

        glide = new OverScroller(host);
        ViewConfiguration vc = ViewConfiguration.get(host);
        minGlide = vc.getScaledMinimumFlingVelocity();
        maxGlide = vc.getScaledMaximumFlingVelocity();

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
        if (directTo != 0) revealChip(directTo);     // the list may have re-sorted mid-call

        if (!txOn && (locked || dragging)) {         // transmission ended on its own
            boolean pressing = dragging && !slid;    // a wave only for a press held in place
            locked = false;
            dragging = false;
            armed = false;
            animateKnob(0);
            dipKnob(false);
            if (pressing && !awayWhileTalking) releaseWave();   // leaving the app: no wave on return
        }
        if (!txOn) awayWhileTalking = false;
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
        fit = Geometry.fitScale(w, h, dp);
        g.compute(w / fit, h / fit, dp, ts);       // fit == 1: the real size, as before
        sized = true;

        // the slide track fades from dark grey at the top to black at the bottom
        trackFill = new LinearGradient(0, g.trT, 0, g.trB, TRACK_TOP, TRACK_BOTTOM, Shader.TileMode.CLAMP);
        knobShade = new RadialGradient(-g.knobR * 0.35f, -g.knobR * 0.45f, g.knobR * 1.6f,
                new int[]{0xFFFFFFFF, 0xFFF1F1F1, 0xFFC9C9C9}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        bodyClip.reset();
        bodyClip.addRoundRect(g.bodyL, g.bodyT, g.bodyR, g.bodyB, g.bodyRad, g.bodyRad, Path.Direction.CW);
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
        int saved = c.save();
        if (fit < 1f) c.scale(fit, fit);           // small window: the whole radio, evenly smaller
        long now = SystemClock.uptimeMillis();
        float pulse = (float) (0.5 + 0.5 * Math.sin(now / 260.0));
        // Fast rise, gentle fall: reads like a VU meter. Stepped every ~33 ms so the meter feels the
        // same at 30 fps and while the ripples run at the screen's full frame rate.
        if (now - levelAt >= 30 || now < levelAt) {
            levelAt = now;
            float target = !powered ? 0f : (txOn ? engine.txLevel : (rxOn ? engine.rxLevelNow() : 0f));
            target = Math.min(1f, target * 1.15f);
            level = target > level ? level + (target - level) * 0.6f : level * 0.8f + target * 0.2f;
            if (level < 0.005f) level = 0;
        }
        stepHistory(now, level);
        boolean live = powered && hasNet;

        drawBody(c);
        drawHeader(c);
        if (g.hasGrille) drawGrille(c, now);
        drawDisplay(c, pulse);
        drawRow(c);
        drawStrip(c, pulse, live);
        drawSlider(c, live);
        boolean waves = drawWaves(c, now);
        c.restoreToCount(saved);

        if (waves) postInvalidateOnAnimation();             // smooth ripples, only while they move
        else if (txOn || rxOn || level > 0 || histPeak > 0) postInvalidateDelayed(33);
    }

    /** The body is black on black, so only its outline shows. */
    private void drawBody(Canvas c) {
        float r = g.bodyRad;
        outline(c, g.bodyL, g.bodyT, g.bodyR, g.bodyB, r, LINE);
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
        fill.setColor(down ? PRESSED : BLACK);
        c.drawCircle(cx, cy, r, fill);
        line.setStrokeWidth(dp);
        line.setColor(powered ? LINE : LINE_DIM);
        c.drawCircle(cx, cy, r - dp / 2, line);
        powerIcon(c, cx, cy + 0.5f * dp, r * 0.95f, powered ? WHITE : 0x73FFFFFF);
    }

    private void drawGrille(Canvas c, long now) {
        outline(c, g.grL, g.grT, g.grR, g.grB, g.grRad, LINE_DIM);
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
            int a = k == 0 ? (powered ? DOT_ALPHA[0] : 60) : DOT_ALPHA[k];
            dots.setColor(Color.argb(a, 255, 255, 255));
            c.drawPoints(buckets[k], 0, bucketLen[k], dots);
        }
    }

    private void drawDisplay(Canvas c, float pulse) {
        outline(c, g.lcdL, g.lcdT, g.lcdR, g.lcdB, g.lcdRad, LINE);

        // label
        text.setTypeface(regular);
        text.setTextSize(g.labelSize);
        text.setColor(powered ? GREY_TEXT : 0x66FFFFFF);
        c.drawText("Channel", g.digitL, g.labelBaseline, text);

        // seven-segment channel number
        int m0 = 0, m1 = 0;
        if (powered) {
            m0 = DIGITS[channel / 10];
            m1 = DIGITS[channel % 10];
        }
        fill.setColor(UNLIT);
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
        text.setColor(GREY_TEXT);
        if (!netLine.isEmpty()) {
            c.drawText(ellipsize(netLine, mx - 10 * dp - (g.lcdL + g.lcdPad)), g.lcdL + g.lcdPad, g.footBaseline, text);
        }
        if (powered) {
            int lit = Math.round(level * 10);
            for (int i = 0; i < 10; i++) {
                float bh = 4 * dp + i * (10 * dp / 9f);
                float x = mx + i * (barW + gap);
                fill.setColor(i < lit ? WHITE : METER_OFF);
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
        drawNamePlate(c);
    }

    /** Your initial, your name and a pencil, between the channel buttons. Tap it to rename. */
    private void drawNamePlate(Canvas c) {
        float t = g.nameT, b = g.nameB, r = (b - t) / 2f;
        fill.setColor(pressed == R_NAME ? PRESSED : BLACK);
        c.drawRoundRect(plateL, t, plateR, b, r, r, fill);
        line.setStrokeWidth(dp);
        line.setColor(LINE);
        c.drawRoundRect(plateL + dp / 2, t + dp / 2, plateR - dp / 2, b - dp / 2, r, r, line);

        float ax = plateL + 5 * dp + g.nameAvR;
        fill.setColor(0xFF111111);
        c.drawCircle(ax, g.rowCy, g.nameAvR, fill);
        line.setColor(LINE);
        c.drawCircle(ax, g.rowCy, g.nameAvR - dp / 2, line);
        text.setTypeface(medium);
        text.setTextSize(13 * dp);
        text.setColor(WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.getFontMetrics(fm);
        c.drawText(plateInitial, ax, g.rowCy - (fm.ascent + fm.descent) / 2f, text);
        text.setTextAlign(Paint.Align.LEFT);

        float tx = ax + g.nameAvR + 10 * dp;
        float ix = plateR - 14 * dp - 7 * dp;                       // pencil centre
        text.setTextSize(g.chipNameSize);
        c.drawText(plateName, tx, g.rowCy - 2 * dp, text);
        text.setTypeface(regular);
        text.setTextSize(g.chipSubSize);
        text.setColor(GREY_TEXT);
        c.drawText(ellipsize("Your name", ix - 7 * dp - 10 * dp - tx), tx, g.rowCy + 12 * dp, text);
        pencilIcon(c, ix, g.rowCy, 14 * dp, 0xFFC8C8C8);
    }

    /** Sizes the name plate to your name, centred between - and +, never wider than the gap. */
    private void layoutName() {
        if (!sized) return;
        String name = callsign == null || callsign.isEmpty() ? "Someone" : callsign;
        float maxW = Math.max(0, g.nameMaxR - g.nameMinL);
        float fixed = 5 * dp + 2 * g.nameAvR + 10 * dp + 10 * dp + 14 * dp + 14 * dp;  // avatar, gaps, pencil
        float room = Math.max(0, maxW - fixed);
        text.setTypeface(regular);
        text.setTextSize(g.chipSubSize);
        float w = Math.min(text.measureText("Your name"), room);
        text.setTypeface(medium);
        text.setTextSize(g.chipNameSize);
        plateName = ellipsize(name, room);
        w = Math.max(w, text.measureText(plateName));
        float total = Math.min(maxW, fixed + w);
        float cx = (g.chDownCx + g.chUpCx) / 2f;
        plateL = cx - total / 2f;
        plateR = cx + total / 2f;
        plateInitial = new String(Character.toChars(Character.toUpperCase(name.codePointAt(0))));
    }

    /** Measures every chip once per change, so drawing a frame never measures text. */
    private void layoutChips() {
        if (!sized) return;
        layoutName();
        int n = people.size();
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
            PeerTable.Peer p = people.get(i);
            String name = p.name;
            String subLine = p.channel == channel ? "Hold to talk" : "On channel " + p.channel;
            text.setTypeface(medium);
            text.setTextSize(g.chipNameSize);
            String fitted = ellipsize(name, maxText);
            float w = text.measureText(fitted);
            text.setTypeface(regular);
            text.setTextSize(g.chipSubSize);
            w = Math.max(w, text.measureText(subLine));
            chipId[i] = p.id;
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
        emptyText = people.isEmpty() ? ellipsize("Others on this Wi-Fi show up here", width - 12 * dp) : "";
    }

    private void drawStrip(Canvas c, float pulse, boolean live) {
        c.save();
        c.clipRect(g.stripL - 1, g.stripT - 1, g.stripR + 1, g.stripB + 1);
        float top = g.stripCy - g.chipH / 2f, bottom = g.stripCy + g.chipH / 2f, r = g.chipH / 2f;
        for (int i = 0; i < chipCount; i++) {
            float l = g.stripL + chipX[i] - stripScroll, rr = l + chipW[i];
            if (rr < g.stripL || l > g.stripR) continue;
            int id = chipId[i];
            boolean talkingTo = txOn && id == directTo;
            boolean hearing = id == speaker;
            boolean down = !stripScrolling && pressedChip >= 0 && id == pressedPeer;
            boolean usable = live && (!txOn || talkingTo);
            int ink;
            if (talkingTo) {
                // Private call: the whole chip turns solid white with black ink. Nothing is drawn
                // outside the chip's own edge (the old outer glow got cut flat by the row's edges).
                fill.setColor(WHITE);
                c.drawRoundRect(l, top, rr, bottom, r, r, fill);
                ink = BLACK;
            } else {
                fill.setColor(down ? PRESSED : BLACK);
                c.drawRoundRect(l, top, rr, bottom, r, r, fill);
                line.setStrokeWidth(hearing ? 1.6f * dp : dp);
                line.setColor(hearing ? Color.argb((int) (120 + 135 * pulse), 255, 255, 255) : LINE);
                c.drawRoundRect(l + dp / 2, top + dp / 2, rr - dp / 2, bottom - dp / 2, r, r, line);
                ink = usable ? WHITE : 0x66FFFFFF;
            }
            // avatar with initial
            float ax = l + 6 * dp + 15 * dp;
            fill.setColor(talkingTo ? AVATAR_ON_WHITE : AVATAR);
            c.drawCircle(ax, g.stripCy, 15 * dp, fill);
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
            text.setColor(talkingTo ? SUB_ON_WHITE : (usable ? GREY_TEXT : 0x4DFFFFFF));
            // "Private call" fits the narrowest chip; fitted anyway, since phones can use wider fonts
            String line2 = talkingTo ? ellipsize("Private call", chipW[i] - 86 * dp) : chipSub[i];
            c.drawText(line2, tx, g.stripCy + 12 * dp, text);
            float ix = rr - 12 * dp - 8 * dp;
            micIcon(c, ix, g.stripCy, 17 * dp, talkingTo ? BLACK : (usable ? 0xD9FFFFFF : 0x59FFFFFF));
        }
        if (!emptyText.isEmpty()) {
            text.setTypeface(regular);
            text.setTextSize(g.chipSubSize * 1.1f);
            text.setColor(powered ? 0x99FFFFFF : 0x55FFFFFF);
            text.setTextAlign(Paint.Align.CENTER);
            text.getFontMetrics(fm);
            c.drawText(emptyText, (g.stripL + g.stripR) / 2f, g.stripCy - (fm.ascent + fm.descent) / 2f, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
        c.restore();
    }

    private int chipAt(float x) {
        float cx = x - g.stripL + stripScroll;
        for (int i = 0; i < chipCount; i++) if (cx >= chipX[i] && cx <= chipX[i] + chipW[i]) return i;
        return -1;
    }

    private String chipNameOf(int id) {
        for (int i = 0; i < chipCount; i++) if (chipId[i] == id) return chipName[i];
        return "them";
    }

    /** Holding a person's chip for 90 ms starts a private call (shorter touches can scroll). */
    private void startDirect() {
        if (pressedChip < 0 || stripScrolling || engine.transmitting) return;
        int id = pressedPeer;
        if (host.beginTalk(id)) {
            directTo = id;
            directStarted = true;
            revealChip(id);
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } else {
            performHapticFeedback(HapticFeedbackConstants.REJECT);
        }
        invalidate();
    }

    /**
     * Scrolls the row so this person's chip is fully visible. Used for private calls, where a chip
     * half off the edge used to hide "Private call". The finger can stay put: a private call
     * follows the person, not the finger's position.
     */
    private void revealChip(int id) {
        float width = g.stripR - g.stripL, inset = 2 * dp;
        for (int i = 0; i < chipCount; i++) {
            if (chipId[i] != id) continue;
            float l = chipX[i], r = l + chipW[i], to = stripScroll;
            if (l - inset < stripScroll) to = l - inset;
            else if (r + inset > stripScroll + width) to = r + inset - width;
            to = Math.max(0, Math.min(stripMax, to));
            if (Math.abs(to - stripScroll) < 0.5f) return;
            if (stripAnim != null && stripAnim.isRunning() && stripAnimTo == to) return;
            glide.forceFinished(true);
            cancelStripAnim();
            ValueAnimator a = ValueAnimator.ofFloat(stripScroll, to);
            a.setDuration(220);
            a.setInterpolator(new DecelerateInterpolator());
            a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator v) {
                    stripScroll = Math.max(0, Math.min(stripMax, (Float) v.getAnimatedValue()));
                    invalidate();
                }
            });
            stripAnim = a;
            stripAnimTo = to;
            a.start();
            return;
        }
    }

    /** Advances a glide by one frame (Android calls this as part of every draw). */
    @Override public void computeScroll() {
        if (glide.computeScrollOffset()) {
            stripScroll = Math.max(0, Math.min(stripMax, glide.getCurrX()));
            postInvalidateOnAnimation();
        }
    }

    private void cancelStripAnim() {
        if (stripAnim != null) {
            stripAnim.cancel();
            stripAnim = null;
        }
    }

    private void drawSlider(Canvas c, boolean live) {
        // side buttons
        toggle(c, g.soundsCx, soundsOn, pressed == R_SOUNDS, true, "Sounds");
        toggle(c, g.bgCx, bgOn, pressed == R_BG, false, "Always on");

        // track: fades from dark grey at the top to black at the bottom
        float tr = (g.trR - g.trL) / 2f;
        fill.setColor(WHITE);                     // shaders are multiplied by the paint's alpha
        fill.setShader(trackFill);
        c.drawRoundRect(g.trL, g.trT, g.trR, g.trB, tr, tr, fill);
        fill.setShader(null);
        line.setStrokeWidth(dp);
        line.setColor(txOn && directTo == 0 ? TRACK_EDGE_LIVE : TRACK_EDGE);
        c.drawRoundRect(g.trL + dp / 2, g.trT + dp / 2, g.trR - dp / 2, g.trB - dp / 2, tr, tr, line);

        float travel = g.knobRestY - g.knobTopY;
        float off = Math.max(-4 * dp, Math.min(knobOffset, travel));
        float kx = g.knobCx + shakeX, ky = g.knobRestY - off;

        // lit trail under the knob
        if (off > 1 || locked) {
            fill.setColor(txOn ? 0x55FFFFFF : 0x33FFFFFF);
            float in = 7 * dp;
            c.drawRoundRect(g.trL + in, ky, g.trR - in, g.trB - in, g.knobR, g.knobR, fill);
        }

        // lock at the top of the track
        boolean lockLit = armed || locked;
        lockIcon(c, g.knobCx, g.knobTopY, 22 * dp, lockLit ? WHITE : (live ? 0xAAFFFFFF : 0x55FFFFFF));

        // chevrons pointing up
        if (!dragging && !locked) {
            float top = g.knobTopY + 24 * dp, bottom = g.knobRestY - g.knobR - 10 * dp;
            if (bottom - top > 36 * dp) {
                float step = (bottom - top) / 3f;
                line.setStrokeWidth(2 * dp);
                for (int i = 0; i < 3; i++) {
                    float cy = top + step * (i + 0.5f);
                    int a = live ? (i == 0 ? 0xC8 : (i == 1 ? 0x9A : 0x70)) : 0x50;
                    line.setColor(Color.argb(a, 255, 255, 255));
                    chevron(c, g.knobCx, cy, 14 * dp);
                }
            }
        }

        // the knob (it dips a little while pressed: the water drop; talking shows as ripples)
        float kr = g.knobR * knobScale;
        if (live) {
            matrix.setTranslate(kx, ky);
            matrix.preScale(knobScale, knobScale);
            knobShade.setLocalMatrix(matrix);
            fill.setColor(WHITE);                 // was inheriting 40% from the lock icon: grey knob
            fill.setShader(knobShade);
            c.drawCircle(kx, ky, kr, fill);
            fill.setShader(null);
            micIcon(c, kx, ky, kr * 0.95f, BLACK);
        } else {
            fill.setColor(0xFF111111);
            c.drawCircle(kx, ky, kr, fill);
            line.setStrokeWidth(dp);
            line.setColor(LINE);
            c.drawCircle(kx, ky, kr - dp / 2, line);
            micIcon(c, kx, ky, kr * 0.95f, 0x80FFFFFF);
        }
        if (locked) {
            float bx = kx + kr * 0.68f, by = ky - kr * 0.68f, br = 12 * dp;
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
        text.setColor(GREY_TEXT);
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
            fill.setColor(down ? PRESSED : BLACK);
            c.drawCircle(cx, cy, r, fill);
            line.setStrokeWidth(dp);
            line.setColor(LINE);
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
        text.setColor(on ? 0xE6FFFFFF : GREY_TEXT);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(label, cx, g.sideLabelBaseline, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    /** A black panel shows only as its thin outline (the background is already pitch black). */
    private void outline(Canvas c, float l, float t, float r, float b, float rad, int color) {
        line.setStrokeWidth(dp);
        line.setColor(color);
        float i = dp / 2;
        c.drawRoundRect(l + i, t + i, r - i, b - i, rad, rad, line);
    }

    private void roundButton(Canvas c, float cx, float cy, float r, boolean down) {
        fill.setColor(down ? PRESSED : BLACK);
        c.drawCircle(cx, cy, r, fill);
        line.setStrokeWidth(dp);
        line.setColor(LINE);
        c.drawCircle(cx, cy, r - dp / 2, line);
    }

    // ------------------------------------------------------------------ water drop ripples

    /** Honours "Remove animations" in the phone's accessibility settings. */
    private static boolean motionOn() {
        return ValueAnimator.areAnimatorsEnabled();
    }

    /**
     * Queues one ring. {@code reach} is how far it travels, in knob radii; alpha and width fade as it
     * spreads. When the pool is full the oldest ring makes room.
     */
    private void wave(long delay, float alpha, float widthDp, int dur, float reach) {
        if (!sized || !motionOn()) return;
        int slot = 0;
        for (int i = 0; i < MAX_WAVES; i++) {
            if (waveDur[i] == 0) {
                slot = i;
                break;
            }
            if (waveAt[i] < waveAt[slot]) slot = i;
        }
        waveAt[slot] = SystemClock.uptimeMillis() + delay;
        waveDur[slot] = dur;
        waveAlpha[slot] = alpha;
        waveWidth[slot] = widthDp * dp;
        waveReach[slot] = reach;
        postInvalidateOnAnimation();
    }

    /** The drop lands: three rings, strongest first. */
    private void splash() {
        wave(0, 0.9f, 3f, 900, 3.3f);
        wave(110, 0.55f, 2f, 1050, 2.9f);
        wave(240, 0.3f, 1.5f, 1200, 2.6f);
        nextWaveAt = SystemClock.uptimeMillis() + 600;
    }

    /** Letting go: one last soft ring. */
    private void releaseWave() {
        wave(0, 0.4f, 2f, 700, 2.1f);
    }

    private void clearWaves() {
        for (int i = 0; i < MAX_WAVES; i++) waveDur[i] = 0;
    }

    /**
     * Draws the rings around the knob and, while you hold the talk button in place, adds a new one
     * now and then: louder speech sends them more often, brighter and further. Sliding up to lock
     * stops new rings, and a locked transmission has none. Rings stay where the drop landed (the
     * knob's resting place), so the ones already spreading don't follow the knob up the track.
     * Returns true while anything moves.
     */
    private boolean drawWaves(Canvas c, long now) {
        boolean emitting = dragging && !slid && engine.transmitting && directTo == 0 && motionOn();
        if (emitting && now >= nextWaveAt) {
            float lv = level;
            wave(0, 0.18f + 0.32f * lv, 1.5f, 1500, 2.3f + 0.9f * lv);
            nextWaveAt = now + (long) (1150 - 500 * lv);
        }
        boolean any = false;                                           // finished rings free their slot
        for (int i = 0; i < MAX_WAVES; i++) {
            if (waveDur[i] == 0) continue;
            if (now - waveAt[i] >= waveDur[i]) waveDur[i] = 0;
            else any = true;                                           // running, or queued to start
        }
        if (!any) return emitting;
        c.save();
        c.clipPath(bodyClip);
        float base = g.knobR * knobScale;
        for (int i = 0; i < MAX_WAVES; i++) {
            if (waveDur[i] == 0) continue;
            float t = (now - waveAt[i]) / (float) waveDur[i];
            if (t < 0 || t >= 1f) continue;
            float u = 1 - t;
            float e = 1 - u * u * u;                                   // fast out, slow settle
            float rad = base + (g.knobR * waveReach[i] - base) * e;
            int a = (int) (255 * waveAlpha[i] * u * u);
            if (a <= 0) continue;
            line.setStrokeWidth(Math.max(0.5f * dp, waveWidth[i] * (1 - 0.7f * t)));
            line.setColor(Color.argb(a, 255, 255, 255));
            c.drawCircle(g.knobCx, g.knobRestY, rad, line);
        }
        c.restore();
        return true;
    }

    /** Dips the knob when pressed and springs it back with a little bounce when let go. */
    private void dipKnob(boolean down) {
        if (dipAnim != null) {
            dipAnim.cancel();
            dipAnim = null;
        }
        float to = down ? 0.86f : 1f;
        if (!sized || !motionOn()) {
            knobScale = 1f;
            invalidate();
            return;
        }
        if (knobScale == to) return;
        ValueAnimator a = ValueAnimator.ofFloat(knobScale, to);
        a.setDuration(down ? 120 : 450);
        a.setInterpolator(down ? new DecelerateInterpolator() : new OvershootInterpolator(3f));
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator v) {
                knobScale = (Float) v.getAnimatedValue();
                invalidate();
            }
        });
        dipAnim = a;
        a.start();
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
        float x = e.getX() / fit, y = e.getY() / fit;   // into layout coordinates (fit is 1 normally)
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                pressed = hit(x, y);
                if (pressed == R_STRIP) {
                    cancelStripAnim();                              // the finger takes over the row
                    boolean caught = !glide.isFinished();           // touched to stop a glide
                    glide.forceFinished(true);
                    if (swipe != null) swipe.recycle();
                    swipe = VelocityTracker.obtain();
                    swipe.addMovement(e);
                    // Stopping a glide is never a hold-to-talk: catching the row must not start a call.
                    int i = caught ? -1 : chipAt(x);
                    pressedChip = i;
                    pressedPeer = i >= 0 ? chipId[i] : 0;
                    stripScrolling = false;
                    stripDownX = x;
                    stripDownScroll = stripScroll;
                    directStarted = false;
                    if (pressedChip >= 0) postDelayed(holdToTalk, 90);
                    invalidate();
                    return true;
                }
                if (pressed == R_KNOB && !locked) {
                    if (host.beginTalk(0)) {
                        awayWhileTalking = false;
                        slid = false;
                        dragging = true;
                        armed = false;
                        downY = y;
                        cancelKnobAnim();
                        downOffset = knobOffset;
                        dipKnob(true);
                        splash();
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    } else {
                        pressed = R_NONE;
                        shake();
                    }
                } else if (pressed == R_KNOB) {
                    dipKnob(true);                                  // locked: the tap that stops talking
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (pressed == R_STRIP) {
                    if (swipe != null) swipe.addMovement(e);
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
                    if (!slid && downY - y > 10 * dp) slid = true;  // sliding up: the waves stop
                    knobOffset = Math.max(0, Math.min(travel, downOffset + (downY - y)));
                    boolean a = knobOffset >= travel * 0.8f;
                    if (a != armed) {
                        armed = a;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    invalidate();
                } else if (pressed != R_NONE && hit(x, y) != pressed) {
                    if (pressed == R_KNOB) dipKnob(false);          // slid off: no tap after all
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
                    } else if (up && !stripScrolling && pressedChip >= 0 && !directStarted) {
                        engine.flash("Hold " + chipNameOf(pressedPeer) + " to talk to them only");
                    } else if (up && stripScrolling && swipe != null && stripMax > 0 && motionOn()) {
                        swipe.addMovement(e);
                        swipe.computeCurrentVelocity(1000, maxGlide);
                        float vx = swipe.getXVelocity() / fit;      // into layout units, like the touch
                        if (Math.abs(vx) * fit > minGlide) {
                            glide.fling(Math.round(stripScroll), 0, Math.round(-vx), 0,
                                    0, Math.round(stripMax), 0, 0);
                            postInvalidateOnAnimation();
                        }
                    }
                    if (swipe != null) {
                        swipe.recycle();
                        swipe = null;
                    }
                    pressedChip = -1;
                    stripScrolling = false;
                    pressed = R_NONE;
                    invalidate();
                    return true;
                }
                if (dragging) {
                    dragging = false;
                    dipKnob(false);
                    if (armed && up && engine.transmitting) {
                        locked = true;                              // locked talking has no waves
                        animateKnob(g.knobRestY - g.knobTopY);
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    } else {
                        armed = false;
                        host.endTalk();
                        animateKnob(0);
                        if (!slid) releaseWave();                   // a press let go in place
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE);
                    }
                } else if (pressed == R_KNOB && locked) {
                    dipKnob(false);
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
            case R_NAME: host.editName(); break;
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
        if (x >= plateL - slop / 2 && x <= plateR + slop / 2
                && y >= g.nameT - slop / 2 && y <= g.nameB + slop / 2) return R_NAME;
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
            if (locked || dragging) awayWhileTalking = true;
            removeCallbacks(holdToTalk);
            glide.forceFinished(true);
            if (swipe != null) {
                swipe.recycle();
                swipe = null;
            }
            pressedChip = -1;
            pressed = R_NONE;
            dragging = false;
            if (dipAnim != null) dipAnim.cancel();
            dipAnim = null;
            knobScale = 1f;
            clearWaves();
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
