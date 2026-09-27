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
    private static final int REQ = 41;
    private static final int BG = Color.rgb(247,248,251);
    private static final int CARD = Color.WHITE;
    private static final int INK = Color.rgb(19,30,53);
    private static final int MUTED = Color.rgb(92,103,122);
    private static final int ACCENT = Color.rgb(43,104,255);
    private static final int GREEN = Color.rgb(25,145,88);
    private static final int RED = Color.rgb(200,40,40);

    private AudioEngine engine;
    private TextView statusText, routeText, volumeValue, suppressionValue;
    private ProgressBar riderMeter, pillionMeter;
    private Button startStop;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(BG); w.setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 23) w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        engine = new AudioEngine(this, this);
        setContentView(buildUi());
        refreshRoute();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(20),dp(22),dp(20),dp(32));
        scroll.addView(page, new ScrollView.LayoutParams(-1,-2));

        TextView eyebrow = text("VOICE SHIELD • V0.3 HYBRID",12,ACCENT,true);
        eyebrow.setLetterSpacing(.10f); page.addView(eyebrow);
        TextView title = text("Rider ↔ Pillion\nBike Intercom",31,INK,true);
        LinearLayout.LayoutParams tlp = lp(); tlp.topMargin=dp(6); page.addView(title,tlp);
        TextView intro = text("Because Pixel Buds expose only one headset microphone on your phone, this mode uses that working Bud mic for the pillion and the phone/USB mic for the rider. Both voices are mixed back into both earbuds.",14,MUTED,false);
        intro.setLineSpacing(dp(3),1f); LinearLayout.LayoutParams ilp=lp(); ilp.topMargin=dp(10); page.addView(intro,ilp);

        LinearLayout how = card(); LinearLayout.LayoutParams hlp=lp(); hlp.topMargin=dp(16); page.addView(how,hlp);
        how.addView(text("SETUP",11,MUTED,true));
        TextView setup = text("1. Give your wife the Pixel Bud whose microphone works.\n2. Keep the other bud for yourself.\n3. Keep the phone near you, or connect a USB-C / wired helmet mic.\n4. Tap Start Hybrid Intercom.",14,INK,false);
        setup.setLineSpacing(dp(4),1f); LinearLayout.LayoutParams slp=lp(); slp.topMargin=dp(7); how.addView(setup,slp);

        LinearLayout status = card(); LinearLayout.LayoutParams stlp=lp(); stlp.topMargin=dp(16); page.addView(status,stlp);
        status.addView(text("STATUS",11,MUTED,true));
        statusText = text("Hybrid intercom stopped.",18,INK,true); LinearLayout.LayoutParams s=lp(); s.topMargin=dp(6); status.addView(statusText,s);
        routeText = text("Checking microphones…",12,MUTED,false); LinearLayout.LayoutParams r=lp(); r.topMargin=dp(6); status.addView(routeText,r);

        addMeter(status,"RIDER • PHONE / USB MIC",true);
        addMeter(status,"PILLION • PIXEL BUD MIC",false);

        startStop = new Button(this); startStop.setText("START HYBRID INTERCOM"); startStop.setAllCaps(false);
        startStop.setTextColor(Color.WHITE); startStop.setTextSize(15); startStop.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        startStop.setBackground(round(ACCENT,18)); startStop.setOnClickListener(v -> startStop());
        LinearLayout.LayoutParams blp=new LinearLayout.LayoutParams(-1,dp(58)); blp.topMargin=dp(16); status.addView(startStop,blp);

        LinearLayout controls=card(); LinearLayout.LayoutParams clp=lp(); clp.topMargin=dp(16); page.addView(controls,clp);
        controls.addView(text("INTERCOM CONTROLS",12,INK,true));
        addTitle(controls,"Earbud playback volume","Controls how loudly the mixed intercom is played into both buds.");
        volumeValue=text("80%",13,ACCENT,true); controls.addView(volumeValue);
        SeekBar vol=new SeekBar(this); vol.setMax(135); vol.setProgress(80); vol.setOnSeekBarChangeListener(new SimpleSeek(){@Override public void onProgressChanged(SeekBar b,int p,boolean f){volumeValue.setText(p+"%");engine.setSidetoneGain(p/100f);}}); controls.addView(vol,lp());
        divider(controls);
        addTitle(controls,"Noise reduction","Increase this during riding; reduce it if voices start sounding clipped.");
        suppressionValue=text("60% • Strong",13,ACCENT,true); controls.addView(suppressionValue);
        SeekBar sup=new SeekBar(this); sup.setMax(100); sup.setProgress(60); sup.setOnSeekBarChangeListener(new SimpleSeek(){@Override public void onProgressChanged(SeekBar b,int p,boolean f){String m=p<35?"Natural":p<65?"Balanced":"Strong";suppressionValue.setText(p+"% • "+m);engine.setSuppressionStrength(p/100f);}}); controls.addView(sup,lp());
        divider(controls);
        controls.addView(makeSwitch("Wind / rumble filter\nCuts low-frequency wind and road rumble.",true,(b,c)->engine.setWindFilterEnabled(c)),lp());
        divider(controls);
        controls.addView(makeSwitch("Android noise suppressor\nUses supported on-device voice suppression for each mic.",true,(b,c)->engine.setNoiseSuppressorEnabled(c)),lp());
        divider(controls);
        controls.addView(makeSwitch("Automatic voice gain\nHelps keep both speakers at a more consistent level.",true,(b,c)->engine.setAutoGainEnabled(c)),lp());

        LinearLayout note=card(); note.setBackground(round(Color.rgb(237,243,255),20)); LinearLayout.LayoutParams nlp=lp(); nlp.topMargin=dp(16); page.addView(note,nlp);
        note.addView(text("Important",15,INK,true));
        TextView nt=text("This version bypasses the single-Pixel-Bud-microphone limitation by using two different input devices. Some Android phones still forbid simultaneous Bluetooth + built-in microphone capture. If that happens, one of the two meters will stay at zero. A USB-C helmet microphone for the rider is the strongest fallback because Android exposes it as a separate physical input.",13,MUTED,false);
        nt.setLineSpacing(dp(3),1f); LinearLayout.LayoutParams ntlp=lp(); ntlp.topMargin=dp(6); note.addView(nt,ntlp);
        TextView privacy=text("Local processing • No account • No audio upload",12,GREEN,true); LinearLayout.LayoutParams plp=lp(); plp.topMargin=dp(18); privacy.setGravity(Gravity.CENTER); page.addView(privacy,plp);
        return scroll;
    }

    private void addMeter(LinearLayout p,String label,boolean rider){
        TextView l=text(label,11,MUTED,true); LinearLayout.LayoutParams ll=lp(); ll.topMargin=dp(14); p.addView(l,ll);
        ProgressBar m=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); m.setMax(100); m.setProgress(0);
        LinearLayout.LayoutParams mlp=new LinearLayout.LayoutParams(-1,dp(9)); mlp.topMargin=dp(6); p.addView(m,mlp);
        if(rider) riderMeter=m; else pillionMeter=m;
    }

    private void startStop(){
        if(engine.isRunning()){engine.stop();refreshRoute();return;}
        if(!hasPerms()){requestPerms();return;}
        startStop.setEnabled(false); boolean ok=engine.start(); startStop.setEnabled(true); if(!ok)refreshRoute();
    }

    private boolean hasPerms(){
        if(Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return false;
        return Build.VERSION.SDK_INT<31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
    }

    private void requestPerms(){
        if(Build.VERSION.SDK_INT<23)return; List<String> n=new ArrayList<>();
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)n.add(Manifest.permission.RECORD_AUDIO);
        if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)n.add(Manifest.permission.BLUETOOTH_CONNECT);
        requestPermissions(n.toArray(new String[0]),REQ);
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] p,int[] g){super.onRequestPermissionsResult(requestCode,p,g);if(requestCode!=REQ)return;if(hasPerms()){refreshRoute();startStop();}else onStatus("Microphone and Nearby devices permissions are required.",false);}
    private void refreshRoute(){if(routeText==null)return; routeText.setText(hasPerms()?engine.describeAvailableRoute():"Tap Start and allow microphone + nearby-device access.");}

    @Override public void onStatus(String msg,boolean active){runOnUiThread(()->{statusText.setText(msg);statusText.setTextColor(active?GREEN:INK);startStop.setText(active?"STOP HYBRID INTERCOM":"START HYBRID INTERCOM");startStop.setBackground(round(active?RED:ACCENT,18));if(!active){riderMeter.setProgress(0);pillionMeter.setProgress(0);}});}
    @Override public void onLevels(int rider,int pillion,boolean hybrid){runOnUiThread(()->{riderMeter.setProgress(rider);pillionMeter.setProgress(pillion);});}
    @Override protected void onDestroy(){if(engine!=null)engine.stop();super.onDestroy();}

    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(18),dp(18),dp(18),dp(18));c.setBackground(round(CARD,22));c.setElevation(dp(2));return c;}
    private void addTitle(LinearLayout p,String t,String d){TextView a=text(t,16,INK,true);LinearLayout.LayoutParams x=lp();x.topMargin=dp(16);p.addView(a,x);TextView b=text(d,12,MUTED,false);b.setLineSpacing(dp(2),1f);LinearLayout.LayoutParams y=lp();y.topMargin=dp(3);y.bottomMargin=dp(5);p.addView(b,y);}
    private void divider(LinearLayout p){Space s=new Space(this);p.addView(s,new LinearLayout.LayoutParams(1,dp(14)));View l=new View(this);l.setBackgroundColor(Color.rgb(233,236,242));p.addView(l,new LinearLayout.LayoutParams(-1,dp(1)));}
    private Switch makeSwitch(String label,boolean checked,CompoundButton.OnCheckedChangeListener listener){Switch s=new Switch(this);s.setText(label);s.setTextSize(14);s.setTextColor(INK);s.setChecked(checked);s.setOnCheckedChangeListener(listener);return s;}
    private TextView text(String v,int sp,int c,boolean bold){TextView t=new TextView(this);t.setText(v);t.setTextSize(sp);t.setTextColor(c);t.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);return t;}
    private GradientDrawable round(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private LinearLayout.LayoutParams lp(){return new LinearLayout.LayoutParams(-1,-2);}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener{public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}}
}
