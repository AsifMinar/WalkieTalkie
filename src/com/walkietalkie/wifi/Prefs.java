package com.walkietalkie.wifi;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/** Tiny settings store. Only preferences live here; audio is never written anywhere. */
final class Prefs {

    private static final String[] WORDS = {
            "Alpha", "Bravo", "Comet", "Delta", "Echo", "Falcon", "Ghost", "Hawk", "Kilo",
            "Lima", "Nova", "Orbit", "Raven", "Sierra", "Tango", "Viper", "Wolf", "Zulu"
    };

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("radio", Context.MODE_PRIVATE);
    }

    int deviceId() {
        int id = sp.getInt("id", 0);
        if (id == 0) {
            id = new SecureRandom().nextInt();
            if (id == 0) id = 1;
            sp.edit().putInt("id", id).apply();
        }
        return id;
    }

    String callsign() {
        String s = sp.getString("callsign", null);
        if (s == null || s.trim().isEmpty()) {
            SecureRandom r = new SecureRandom();
            s = WORDS[r.nextInt(WORDS.length)] + "-" + (10 + r.nextInt(90));
            sp.edit().putString("callsign", s).apply();
        }
        return s;
    }

    void callsign(String v) { sp.edit().putString("callsign", v).apply(); }

    int channel() {
        int c = sp.getInt("channel", 1);
        return (c < 1 || c > RadioEngine.CHANNELS) ? 1 : c;
    }

    void channel(int v) { sp.edit().putInt("channel", v).apply(); }

    boolean sounds() { return sp.getBoolean("beep", true); }    // key kept from v1.0

    void sounds(boolean v) { sp.edit().putBoolean("beep", v).apply(); }

    boolean background() { return sp.getBoolean("background", true); }

    void background(boolean v) { sp.edit().putBoolean("background", v).apply(); }

    boolean power() { return sp.getBoolean("power", true); }

    void power(boolean v) { sp.edit().putBoolean("power", v).apply(); }

    /** Removes settings from older versions that are no longer used (the private group code). */
    void dropLegacy() {
        if (sp.contains("group")) sp.edit().remove("group").apply();
    }
}
