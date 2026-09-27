package com.voiceshield.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements AudioEngine.Listener {
    private static final int REQUEST_AUDIO_PERMISSIONS = 41;

    private static final int BG = Color.rgb(247, 248, 251);
    private static final int CARD = Color.WHITE;
    private static final int INK = Color.rgb(19, 30, 53);
    private static final int MUTED = Color.rgb(93, 103, 122);
    private static final int ACCENT = Color.rgb(43, 104, 255);
    private static final int GREEN = Color.rgb(26, 145, 88);
    private static final int RED = Color.rgb(200, 40, 40);

    private AudioEngine engine;
    private TextView statusText;
    private TextView routeText;
    private TextView sidetoneValue;
    private TextView suppressionValue;
    private ProgressBar levelMeter;
    private Button startStopButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        engine = new AudioEngine(this, this);
        setContentView(buildUi());
        refreshRouteText();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(22), dp(20), dp(32));
        scroll.addView(page, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView eyebrow = text("VOICE SHIELD • V1", 12, ACCENT, true);
        eyebrow.setLetterSpacing(0.12f);
        page.addView(eyebrow);

        TextView title = text("Hear yourself.\nNot the room.", 32, INK, true);
        title.setLineSpacing(0, 0.96f);
        LinearLayout.LayoutParams titleLp = lpMatchWrap();
        titleLp.topMargin = dp(6);
        page.addView(title, titleLp);

        TextView intro = text(
                "Connect your Pixel Buds, keep ANC enabled in the earbuds, then start the live voice monitor. Audio is processed on this phone only.",
                15, MUTED, false
        );
        intro.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams introLp = lpMatchWrap();
        introLp.topMargin = dp(10);
        page.addView(intro, introLp);

        LinearLayout statusCard = card();
        LinearLayout.LayoutParams cardLp = lpMatchWrap();
        cardLp.topMargin = dp(20);
        page.addView(statusCard, cardLp);

        TextView statusLabel = text("STATUS", 11, MUTED, true);
        statusLabel.setLetterSpacing(0.12f);
        statusCard.addView(statusLabel);

        statusText = text("Voice monitor stopped.", 19, INK, true);
        LinearLayout.LayoutParams statusLp = lpMatchWrap();
        statusLp.topMargin = dp(6);
        statusCard.addView(statusText, statusLp);

        routeText = text("Checking Bluetooth route…", 13, MUTED, false);
        LinearLayout.LayoutParams routeLp = lpMatchWrap();
        routeLp.topMargin = dp(6);
        statusCard.addView(routeText, routeLp);

        TextView levelLabel = text("LIVE INPUT LEVEL", 11, MUTED, true);
        levelLabel.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams levelLabelLp = lpMatchWrap();
        levelLabelLp.topMargin = dp(16);
        statusCard.addView(levelLabel, levelLabelLp);

        levelMeter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        levelMeter.setMax(100);
        levelMeter.setProgress(0);
        LinearLayout.LayoutParams meterLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8)
        );
        meterLp.topMargin = dp(7);
        statusCard.addView(levelMeter, meterLp);

        startStopButton = new Button(this);
        startStopButton.setText("START VOICE MONITOR");
        startStopButton.setTextColor(Color.WHITE);
        startStopButton.setTextSize(15);
        startStopButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        startStopButton.setAllCaps(false);
        startStopButton.setGravity(Gravity.CENTER);
        startStopButton.setBackground(roundRect(ACCENT, 18));
        startStopButton.setPadding(dp(18), 0, dp(18), 0);
        startStopButton.setOnClickListener(v -> onStartStopPressed());
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)
        );
        buttonLp.topMargin = dp(16);
        statusCard.addView(startStopButton, buttonLp);

        TextView controlsHeader = text("VOICE CONTROLS", 13, INK, true);
        controlsHeader.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams controlsHeaderLp = lpMatchWrap();
        controlsHeaderLp.topMargin = dp(24);
        page.addView(controlsHeader, controlsHeaderLp);

        LinearLayout controlCard = card();
        LinearLayout.LayoutParams controlCardLp = lpMatchWrap();
        controlCardLp.topMargin = dp(10);
        page.addView(controlCard, controlCardLp);

        addSectionTitle(controlCard, "Hear myself", "How loudly your cleaned voice is played back into the earbuds.");
        sidetoneValue = text("55%", 13, ACCENT, true);
        controlCard.addView(sidetoneValue);
        SeekBar sidetone = new SeekBar(this);
        sidetone.setMax(135);
        sidetone.setProgress(55);
        sidetone.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                sidetoneValue.setText(progress + "%");
                engine.setSidetoneGain(progress / 100f);
            }
        });
        controlCard.addView(sidetone, lpMatchWrap());

        addDivider(controlCard);
        addSectionTitle(controlCard, "Noise reduction", "Higher values suppress quiet room noise more aggressively.");
        suppressionValue = text("55% • Balanced", 13, ACCENT, true);
        controlCard.addView(suppressionValue);
        SeekBar suppression = new SeekBar(this);
        suppression.setMax(100);
        suppression.setProgress(55);
        suppression.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                String mode = progress < 35 ? "Natural" : progress < 75 ? "Balanced" : "Strong";
                suppressionValue.setText(progress + "% • " + mode);
                engine.setSuppressionStrength(progress / 100f);
            }
        });
        controlCard.addView(suppression, lpMatchWrap());

        addDivider(controlCard);
        Switch androidNs = makeSwitch(
                "Android noise suppressor",
                "Uses the phone's built-in voice communication noise suppression.",
                true,
                (buttonView, isChecked) -> engine.setNoiseSuppressorEnabled(isChecked)
        );
        controlCard.addView(androidNs, lpMatchWrap());

        addDivider(controlCard);
        Switch wind = makeSwitch(
                "Wind / low-rumble filter",
                "Cuts low-frequency wind, handling noise and air-conditioner rumble.",
                true,
                (buttonView, isChecked) -> engine.setWindFilterEnabled(isChecked)
        );
        controlCard.addView(wind, lpMatchWrap());

        addDivider(controlCard);
        Switch agc = makeSwitch(
                "Auto voice gain",
                "Lets Android keep speech level more consistent when you move or speak softly.",
                true,
                (buttonView, isChecked) -> engine.setAutoGainEnabled(isChecked)
        );
        controlCard.addView(agc, lpMatchWrap());

        LinearLayout noteCard = card();
        noteCard.setBackground(roundRect(Color.rgb(237, 243, 255), 20));
        LinearLayout.LayoutParams noteLp = lpMatchWrap();
        noteLp.topMargin = dp(16);
        page.addView(noteCard, noteLp);

        TextView noteTitle = text("What V1 can and cannot do", 15, INK, true);
        noteCard.addView(noteTitle);
        TextView note = text(
                "V1 cleans the microphone audio used by this app and gives you live sidetone. It does not replace Pixel Buds firmware ANC, and it cannot yet become the system-wide microphone for WhatsApp, Meet or normal phone calls. Background speech is reduced mainly when you are not speaking; separating overlapping voices needs the next AI model stage.",
                13, MUTED, false
        );
        note.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams noteTextLp = lpMatchWrap();
        noteTextLp.topMargin = dp(7);
        noteCard.addView(note, noteTextLp);

        TextView privacy = text("No account • No server • No audio upload", 12, GREEN, true);
        LinearLayout.LayoutParams privacyLp = lpMatchWrap();
        privacyLp.topMargin = dp(18);
        privacy.setGravity(Gravity.CENTER_HORIZONTAL);
        page.addView(privacy, privacyLp);

        return scroll;
    }

    private void onStartStopPressed() {
        if (engine.isRunning()) {
            engine.stop();
            refreshRouteText();
            return;
        }

        if (!hasRequiredPermissions()) {
            requestRequiredPermissions();
            return;
        }

        startStopButton.setEnabled(false);
        boolean started = engine.start();
        startStopButton.setEnabled(true);
        if (!started) refreshRouteText();
    }

    private boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        List<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        requestPermissions(needed.toArray(new String[0]), REQUEST_AUDIO_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_AUDIO_PERMISSIONS) return;

        if (hasRequiredPermissions()) {
            refreshRouteText();
            onStartStopPressed();
        } else {
            onStatus("Microphone and nearby-device permission are required.", false);
        }
    }

    private void refreshRouteText() {
        if (routeText == null) return;
        if (!hasRequiredPermissions()) {
            routeText.setText("Tap Start and allow microphone + nearby-device access.");
        } else {
            routeText.setText(engine.describeAvailableRoute());
        }
    }

    @Override
    public void onStatus(String message, boolean running) {
        runOnUiThread(() -> {
            statusText.setText(message);
            statusText.setTextColor(running ? GREEN : INK);
            startStopButton.setText(running ? "STOP VOICE MONITOR" : "START VOICE MONITOR");
            startStopButton.setBackground(roundRect(running ? RED : ACCENT, 18));
            if (!running) levelMeter.setProgress(0);
        });
    }

    @Override
    public void onLevel(int percent) {
        runOnUiThread(() -> levelMeter.setProgress(percent));
    }

    @Override
    protected void onDestroy() {
        if (engine != null) engine.stop();
        super.onDestroy();
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(roundRect(CARD, 22));
        card.setElevation(dp(2));
        return card;
    }

    private void addSectionTitle(LinearLayout parent, String title, String description) {
        TextView t = text(title, 16, INK, true);
        parent.addView(t);
        TextView d = text(description, 12, MUTED, false);
        d.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams dLp = lpMatchWrap();
        dLp.topMargin = dp(3);
        dLp.bottomMargin = dp(5);
        parent.addView(d, dLp);
    }

    private void addDivider(LinearLayout parent) {
        Space space = new Space(this);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(1, dp(18));
        parent.addView(space, sLp);
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(233, 236, 242));
        parent.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ));
        Space after = new Space(this);
        parent.addView(after, new LinearLayout.LayoutParams(1, dp(18)));
    }

    private Switch makeSwitch(String title, String description, boolean checked,
                              CompoundButton.OnCheckedChangeListener listener) {
        Switch sw = new Switch(this);
        sw.setText(title + "\n" + description);
        sw.setTextColor(INK);
        sw.setTextSize(14);
        sw.setChecked(checked);
        sw.setPadding(0, 0, 0, 0);
        sw.setOnCheckedChangeListener(listener);
        return sw;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return tv;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams lpMatchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
