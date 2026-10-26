package com.walkietalkie.wifi;

/**
 * The screen layout on many window sizes, text sizes and densities: sections never overlap, the
 * name plate always has room between the channel buttons and can't be confused with them, and
 * the side buttons stay clear of the talk track. Windows smaller than the minimum design size
 * (split screen, pop-up windows) get the radio scaled down evenly, and still everything fits and
 * the buttons stay big enough to press. Normal phone screens are never scaled. Pure Java.
 */
public class LayoutTest {
    static int pass = 0, fail = 0;

    static void check(boolean ok, String what, String where) {
        if (ok) {
            pass++;
            return;
        }
        fail++;
        if (fail <= 10) System.out.println("FAIL: " + what + " at " + where);
    }

    public static void main(String[] args) {
        float[] densities = {1f, 2f, 2.625f, 3f, 3.5f};
        float[] textScales = {0.85f, 1f, 1.15f, 1.25f};
        int sizes = 0;
        for (float dp : densities) {
            for (float ts : textScales) {
                for (int wDp = 240; wDp <= 900; wDp += 20) {
                    for (int hDp = 300; hDp <= 1300; hDp += 25) {
                        layout(wDp * dp, hDp * dp, dp, ts, wDp + "x" + hDp + "dp @" + dp + " text " + ts);
                        sizes++;
                    }
                }
            }
        }
        System.out.println(sizes + " window sizes checked (240-900 x 300-1300 dp)");
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void layout(float realW, float realH, float dp, float ts, String where) {
        // what RadioView does: lay out at least at the minimum size, then draw scaled by `fit`
        float fit = Geometry.fitScale(realW, realH, dp);
        boolean normal = realW >= Geometry.MIN_W_DP * dp - 0.01f && realH >= Geometry.MIN_H_DP * dp - 0.01f;
        check(normal ? fit == 1f : fit < 1f && fit > 0f, "scaled only below the minimum (fit " + fit + ")", where);
        float w = realW / fit, h = realH / fit;
        Geometry g = new Geometry();
        g.compute(w, h, dp, ts);
        float eps = 0.5f;

        check(g.bodyL >= 0 && g.bodyR <= w && g.bodyT >= 0 && g.bodyB <= h, "body on screen", where);
        check(g.bodyR * fit <= realW + eps && g.bodyB * fit <= realH + eps, "scaled body fits the real window", where);
        // still easy to press when scaled (real size, in dp)
        check(2 * g.knobR * fit >= 44 * dp, "talk button at least 44dp (" + 2 * g.knobR * fit / dp + ")", where);
        check(2 * g.btnR * fit >= 30 * dp, "channel buttons at least 30dp", where);

        // top to bottom, nothing overlaps
        check(g.hdrB <= (g.hasGrille ? g.grT : g.lcdT) + eps, "header above grille/display", where);
        if (g.hasGrille) check(g.grB <= g.lcdT + eps, "grille above display", where);
        check(g.lcdB <= g.rowT + eps, "display above channel row", where);
        check(g.rowB <= g.stripT + eps, "channel row above people row", where);
        check(g.stripB <= g.trT + eps, "people row above talk track", where);
        check(g.trB <= g.hintY - g.hintSize + eps, "talk track above hint", where);
        check(g.hintY <= g.bodyB, "hint inside body", where);

        // name plate between the channel buttons
        float width = g.nameMaxR - g.nameMinL;
        check(width >= 120 * dp, "name plate has room for initial, name and pencil (" + width / dp + "dp)", where);
        float touchSlop = 4 * dp;                         // RadioView: buttons answer 4dp outside their edge
        check(g.nameMinL - touchSlop > g.chDownCx + g.btnR + touchSlop - eps, "plate clear of - button", where);
        check(g.nameMaxR + touchSlop < g.chUpCx - g.btnR - touchSlop + eps, "plate clear of + button", where);
        check(g.nameT >= g.rowT - eps && g.nameB <= g.rowB + eps, "plate inside channel row", where);
        check(g.nameAvR * 2 < g.nameB - g.nameT, "initial fits in plate", where);

        // talk track and the two side buttons
        check(g.knobRestY + g.knobR <= g.trB && g.knobTopY - g.knobR >= g.trT, "knob inside track", where);
        check(g.knobTopY < g.knobRestY, "knob can slide up to lock", where);
        check(g.soundsCx - g.sideR >= g.bodyL && g.soundsCx + g.sideR <= g.trL, "Sounds button clear", where);
        check(g.bgCx + g.sideR <= g.bodyR && g.bgCx - g.sideR >= g.trR, "Always on button clear", where);

        // display contents
        check(g.infoL < g.infoR, "status text has room", where);
        check(g.digitTop >= g.lcdT && g.digitTop + g.digitH <= g.lcdB, "channel digits inside display", where);
    }
}
