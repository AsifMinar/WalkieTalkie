package com.walkietalkie.wifi;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.util.ArrayList;

public final class MainActivity extends Activity implements RadioEngine.Listener {

    private static final int REQ_START = 1, REQ_MIC = 2;

    private RadioEngine engine;
    private Prefs prefs;
    private RadioView view;
    private boolean visible;                 // the mic may only open while the app is on screen

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        engine = RadioEngine.get(this);
        prefs = engine.prefs();
        view = new RadioView(this, engine);
        setContentView(view);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        if (saved == null) askStartupPermissions();
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        engine.addListener(this);
        engine.setUiVisible(true);
        view.refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        syncService();
    }

    @Override protected void onStop() {
        visible = false;
        engine.stopTx();                 // never keep the mic open once the app is out of sight
        engine.setUiVisible(false);
        engine.removeListener(this);
        super.onStop();
    }

    @Override public void onRadioChanged() {
        view.refresh();
    }

    // ------------------------------------------------------------------ actions from the view

    boolean backgroundOn() {
        return prefs.background();
    }

    /** {@code toId} 0 talks to everyone on the channel; a person's id makes it a private call. */
    boolean beginTalk(int toId) {
        if (!visible || !engine.isPowered()) return false;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return false;
        }
        if (engine.net == RadioEngine.NET_NONE || !engine.startTx(toId)) {
            engine.busyTone();
            return false;
        }
        return true;
    }

    void endTalk() {
        engine.stopTx();
    }

    void togglePower() {
        engine.setPowered(!engine.isPowered());
        syncService();
    }

    void toggleSounds() {
        boolean on = !engine.sounds;
        engine.setSounds(on);
        engine.flash(on ? "Radio sounds on" : "Radio sounds off");
    }

    void toggleBackground() {
        boolean on = !prefs.background();
        prefs.background(on);
        syncService();
        engine.flash(on ? "Keeps listening when the app is closed" : "Only works while the app is open");
        view.refresh();
    }

    void stepChannel(int d) {
        engine.setChannel(engine.channel + d);
    }

    void editName() {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Wire.MAX_NAME_CHARS)});
        et.setText(engine.callsign);
        et.setSelection(et.length());
        et.setHint("Your name");
        FrameLayout wrap = new FrameLayout(this);
        int p = Math.round(22 * getResources().getDisplayMetrics().density);
        wrap.setPadding(p, p / 3, p, 0);
        wrap.addView(et);
        AlertDialog d = new AlertDialog.Builder(this, R.style.Theme_Walkie_Dialog)
                .setTitle("Your name on the radio")
                .setView(wrap)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String n = et.getText().toString().trim();
                        if (!n.isEmpty()) engine.setCallsign(n);
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    // ------------------------------------------------------------------ service + permissions

    private void syncService() {
        if (engine.isPowered() && prefs.background()) RadioService.start(this);
        else RadioService.stop(this);
    }

    private void askStartupPermissions() {
        ArrayList<String> want = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            want.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            want.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!want.isEmpty()) requestPermissions(want.toArray(new String[0]), REQ_START);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_MIC) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                engine.flash("Microphone ready. Hold to talk.");
            } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                new AlertDialog.Builder(this, R.style.Theme_Walkie_Dialog)
                        .setTitle("Microphone is off")
                        .setMessage("To talk, allow the microphone for Walkie Talkie in Settings.")
                        .setPositiveButton("Open Settings", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", getPackageName(), null)));
                            }
                        })
                        .setNegativeButton("Not now", null)
                        .show();
            } else {
                engine.flash("Allow the microphone to talk");
            }
        }
    }
}
