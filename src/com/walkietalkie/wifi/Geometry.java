package com.walkietalkie.wifi;

/**
 * Every position on screen, in pixels. Pure Java (no Android types) so the layout can be
 * checked on a desktop JVM across many screen sizes.
 *
 * Top to bottom inside the body: header (name + power button), speaker grille, display,
 * channel stepper with your name plate between - and +, people row (one hold-to-talk button per
 * other person), slide-to-talk track with the two side buttons, hint line.
 */
final class Geometry {

    /**
     * The smallest window the radio is laid out for. The layout itself needs 448dp of height and
     * 300dp of width; these add a little margin. Smaller windows (split screen, pop-up windows)
     * draw the radio at this size and scale it down evenly, so nothing overlaps or falls off.
     */
    static final float MIN_W_DP = 320, MIN_H_DP = 460;

    /** 1 on every normal phone screen; below 1 only when the window is smaller than the minimum. */
    static float fitScale(float w, float h, float dp) {
        if (w <= 0 || h <= 0 || dp <= 0) return 1f;
        return Math.min(1f, Math.min(w / (MIN_W_DP * dp), h / (MIN_H_DP * dp)));
    }

    float w, h;
    float bodyL, bodyT, bodyR, bodyB, bodyRad;

    float hdrT, hdrB, hdrCy, brandX, brandSize, pwrCx, pwrR;

    boolean hasGrille;
    float grL, grT, grR, grB, grRad;

    float lcdL, lcdT, lcdR, lcdB, lcdRad, lcdPad;
    float labelSize, labelBaseline, footSize, footBaseline;
    float digitL, digitTop, digitW, digitH, digitGap;
    float infoL, infoR, titleSize, statusBaseline, subTop, subSize;
    int subMaxLines;

    float rowT, rowB, rowCy, btnR, chDownCx, chUpCx;
    float nameMinL, nameMaxR, nameT, nameB, nameAvR;           // your name plate, between - and +
    float stripL, stripR, stripT, stripB, stripCy, chipH, chipNameSize, chipSubSize;

    float trL, trT, trR, trB, knobR, knobCx, knobRestY, knobTopY;
    float sideR, sideCy, soundsCx, bgCx, sideLabelSize, sideLabelBaseline;
    float hintSize, hintY;

    void compute(float W, float H, float dp, float ts) {
        w = W;
        h = H;
        float margin = 14 * dp;
        float colW = Math.min(W - 2 * margin, 440 * dp);
        float left = (W - colW) / 2f;
        float usedH = Math.min(H, 940 * dp);
        float top = (H - usedH) / 2f;

        bodyL = left;
        bodyR = left + colW;
        bodyT = top + 12 * dp;                  // same gap above and below the body
        bodyB = top + usedH - 12 * dp;
        bodyRad = Math.min(40 * dp, colW * 0.12f);

        float pad = 18 * dp;
        float inL = bodyL + pad, inR = bodyR - pad;

        // Spacing between sections. On short screens it tightens (up to 42dp in total) before any
        // section shrinks, so the speaker grille (the voice visualizer) survives on small phones.
        float bodyH = bodyB - bodyT;
        float sq = 0;                                   // 0 = roomy, 1 = compact
        float content = (36 + 48 + 48 + 18) * dp;       // header, channel row, people row, hint
        float roomy = 116 * dp, compact = 74 * dp;      // sum of all gaps and paddings
        float want = content + roomy + (160 + 108 + 44) * dp;
        if (bodyH < want) sq = Math.min(1f, (want - bodyH) / (roomy - compact));
        float gTop = mix(14, 8, sq, dp), gHdr = mix(12, 8, sq, dp), gLcd = mix(14, 8, sq, dp);
        float gRow = mix(12, 8, sq, dp), gStrip = mix(16, 10, sq, dp), gHint = mix(10, 6, sq, dp);
        float gBottom = mix(20, 14, sq, dp);
        float gap1 = mix(14, 8, sq, dp);

        // Header: printed name on the left, power button on the right, both inside the body.
        hdrT = bodyT + gTop;
        hdrB = hdrT + 36 * dp;
        hdrCy = (hdrT + hdrB) / 2f;
        pwrR = 18 * dp;
        pwrCx = inR - pwrR;
        brandX = inL + 2 * dp;
        brandSize = 15 * dp * ts;

        float avail = bodyH - content - (gTop + gHdr + gap1 + gLcd + gRow + gStrip + gHint + gBottom);
        float sh = clamp(avail * 0.44f, 160 * dp, 320 * dp);
        float lh = clamp((avail - sh) * 0.6f, 108 * dp, 168 * dp);
        float gh = avail - sh - lh;
        if (gh < 44 * dp) {                     // still short: shrink track, then display
            float need = 44 * dp - gh;
            float take = Math.max(0, Math.min(need, sh - 150 * dp));
            sh -= take;
            gh += take;
            need -= take;
            take = Math.max(0, Math.min(need, lh - 100 * dp));
            lh -= take;
            gh += take;
        }
        if (gh < 40 * dp) {                     // tiny screen only: no room for a grille at all
            sh = Math.max(130 * dp, sh + gh + gap1);
            gh = 0;
            gap1 = 0;
        } else if (gh > 120 * dp) {
            sh += gh - 120 * dp;
            gh = 120 * dp;
        }
        float extra = 0;
        if (sh > 340 * dp) {                    // very tall screen: centre the stack instead
            extra = sh - 340 * dp;
            sh = 340 * dp;
        }

        float y = hdrB + gHdr + extra / 2f;
        hasGrille = gh > 0;
        grL = inL;
        grR = inR;
        grT = y;
        grB = y + gh;
        grRad = 20 * dp;
        y += gh + gap1;

        lcdL = inL;
        lcdR = inR;
        lcdT = y;
        lcdB = y + lh;
        lcdRad = 22 * dp;
        y = lcdB + gLcd;

        rowT = y;
        rowB = y + 48 * dp;
        rowCy = (rowT + rowB) / 2f;
        btnR = 24 * dp;
        chDownCx = inL + btnR;
        chUpCx = inR - btnR;
        // The plate is centred between the buttons and may grow up to these edges; the gap keeps a
        // tap on the plate from landing on a channel button (their touch area reaches 4dp out).
        nameMinL = chDownCx + btnR + 10 * dp;
        nameMaxR = chUpCx - btnR - 10 * dp;
        nameT = rowCy - 20 * dp;
        nameB = rowCy + 20 * dp;
        nameAvR = 15 * dp;
        y = rowB + gRow;

        stripL = inL;
        stripR = inR;
        stripT = y;
        stripB = y + 48 * dp;
        stripCy = (stripT + stripB) / 2f;
        chipH = 46 * dp;
        chipNameSize = 14 * dp * Math.min(ts, 1.15f);
        chipSubSize = 11 * dp * Math.min(ts, 1.15f);
        y = stripB + gStrip;

        float tw = clamp(colW * 0.27f, 88 * dp, 112 * dp);
        float cx = (bodyL + bodyR) / 2f;
        trL = cx - tw / 2f;
        trR = cx + tw / 2f;
        trT = y;
        trB = y + sh;
        knobR = tw / 2f - 7 * dp;
        knobCx = cx;
        knobRestY = trB - 7 * dp - knobR;
        knobTopY = trT + 7 * dp + knobR;

        sideR = 27 * dp;
        sideCy = (trT + trB) / 2f - 10 * dp;
        soundsCx = (inL + trL) / 2f;
        bgCx = (trR + inR) / 2f;
        sideLabelSize = 12 * dp * ts;
        sideLabelBaseline = sideCy + sideR + 20 * dp;

        hintSize = 13 * dp * ts;
        hintY = trB + gHint + 14 * dp;

        // Inside the display
        lcdPad = 16 * dp;
        labelSize = 12 * dp * ts;
        footSize = 11 * dp * ts;
        labelBaseline = lcdT + lcdPad + labelSize * 0.78f;
        footBaseline = lcdB - lcdPad;
        float areaTop = labelBaseline + 10 * dp;
        float areaBot = footBaseline - footSize - 10 * dp;
        float lcdW = lcdR - lcdL;
        digitH = Math.max(24 * dp, Math.min(areaBot - areaTop, lcdW * 0.24f));
        digitTop = areaTop + Math.max(0, (areaBot - areaTop) - digitH) / 2f;
        digitW = digitH * 0.54f;
        digitGap = digitH * 0.16f;
        digitL = lcdL + lcdPad + 2 * dp;
        infoL = digitL + 2 * digitW + digitGap + 22 * dp;
        infoR = lcdR - lcdPad;
        titleSize = Math.min(22 * dp * ts, Math.max(15 * dp, digitH * 0.34f));
        statusBaseline = areaTop + titleSize * 0.8f;
        subSize = 13 * dp * ts;
        subTop = statusBaseline + 9 * dp;
        float subBottom = footBaseline - footSize - 8 * dp;
        subMaxLines = (subBottom - subTop) >= 2 * subSize * 1.3f ? 2 : 1;
    }

    private static float mix(float roomyDp, float compactDp, float t, float dp) {
        return (roomyDp + (compactDp - roomyDp) * t) * dp;
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
