package com.steprivo.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;

public final class MainActivity extends Activity implements SensorEventListener, LocationListener {
  private static final int REQ_ACTIVITY=201, REQ_LOCATION=202, REQ_EXPORT=203;
  private WebView web;
  private SensorManager sensors;
  private Sensor stepCounter;
  private LocationManager locations;
  private boolean gpsActive=false;
  private boolean loaded=false;
  private String awaitingExport=null;
  private final Handler handler=new Handler();

  @Override public void onCreate(Bundle savedInstanceState){
    super.onCreate(savedInstanceState);
    getWindow().setStatusBarColor(Color.rgb(10,22,40));
    getWindow().setNavigationBarColor(Color.rgb(10,22,40));
    getWindow().getDecorView().setSystemUiVisibility(0);
    sensors=(SensorManager)getSystemService(SENSOR_SERVICE);
    locations=(LocationManager)getSystemService(LOCATION_SERVICE);
    stepCounter=sensors==null?null:sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
    web=new WebView(this);
    web.setBackgroundColor(Color.rgb(245,248,246));
    WebSettings settings=web.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(true);
    settings.setAllowContentAccess(false);
    settings.setJavaScriptCanOpenWindowsAutomatically(false);
    settings.setBuiltInZoomControls(false);
    settings.setDisplayZoomControls(false);
    settings.setCacheMode(WebSettings.LOAD_DEFAULT);
    settings.setBlockNetworkLoads(true); // Entire MVP works without network access.
    web.setWebChromeClient(new WebChromeClient());
    web.setWebViewClient(new WebViewClient(){
      @Override public void onPageFinished(WebView v,String url){loaded=true;checkActivitySensor();}
      @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return !url.startsWith("file:///android_asset/www/");}
    });
    web.addJavascriptInterface(new DeviceBridge(),"SteprivoNative");
    setContentView(web);
    web.loadUrl("file:///android_asset/www/index.html");
  }

  private void js(String expression){
    if(!loaded||web==null)return;
    runOnUiThread(()->web.evaluateJavascript(expression,null));
  }
  private void checkActivitySensor(){
    if(stepCounter==null){js("window.SteprivoDevice&&window.SteprivoDevice.onSensorUnavailable()");return;}
    if(Build.VERSION.SDK_INT>=29&&checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)!=PackageManager.PERMISSION_GRANTED)return;
    sensors.registerListener(this,stepCounter,SensorManager.SENSOR_DELAY_NORMAL);
  }
  private void requestStepPermission(){
    if(Build.VERSION.SDK_INT>=29&&checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)!=PackageManager.PERMISSION_GRANTED){
      requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION},REQ_ACTIVITY);
    }else checkActivitySensor();
  }
  private void startLocation(){
    if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
      requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},REQ_LOCATION);return;
    }
    try{gpsActive=true;locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,2200,2,this);
      locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,4000,5,this);
    }catch(Exception ignored){gpsActive=false;}
  }
  private void stopLocation(){gpsActive=false;try{locations.removeUpdates(this);}catch(Exception ignored){}}

  @Override public void onSensorChanged(SensorEvent event){
    if(event.sensor.getType()!=Sensor.TYPE_STEP_COUNTER)return;
    double val=event.values[0];
    js("window.SteprivoDevice&&window.SteprivoDevice.onSensorStep("+((long)val)+")");
  }
  @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}
  @Override public void onLocationChanged(Location l){
    if(!gpsActive||l==null)return;
    double lat=l.getLatitude(),lon=l.getLongitude(),acc=l.getAccuracy();
    js("window.SteprivoDevice&&window.SteprivoDevice.onGps("+lat+","+lon+","+acc+")");
  }
  @Override public void onProviderEnabled(String provider){}
  @Override public void onProviderDisabled(String provider){}
  @Override public void onStatusChanged(String provider,int status,Bundle extras){}

  @Override public void onRequestPermissionsResult(int req,String[] permissions,int[] results){
    super.onRequestPermissionsResult(req,permissions,results);
    if(req==REQ_ACTIVITY){
      if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)checkActivitySensor();
      else js("window.SteprivoDevice&&window.SteprivoDevice.onPermissionDenied()");
    }
    if(req==REQ_LOCATION&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startLocation();
  }
  @Override protected void onResume(){super.onResume();if(loaded)checkActivitySensor();}
  @Override protected void onPause(){try{sensors.unregisterListener(this);}catch(Exception ignored){}stopLocation();super.onPause();}
  @Override protected void onDestroy(){if(web!=null){web.destroy();web=null;}super.onDestroy();}
  @Override public void onBackPressed(){
    if(web!=null)web.evaluateJavascript("(function(){if(document.querySelector('.sheet-wrap.show')){closeSheets();return;}if(typeof go==='function')go('home');})()",null);
    else super.onBackPressed();
  }
  @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
    super.onActivityResult(requestCode,resultCode,data);
    if(requestCode==REQ_EXPORT&&resultCode==RESULT_OK&&data!=null&&awaitingExport!=null){
      try(OutputStream os=getContentResolver().openOutputStream(data.getData())){os.write(awaitingExport.getBytes(StandardCharsets.UTF_8));}
      catch(Exception ignored){}finally{awaitingExport=null;}
    }
  }
  public final class DeviceBridge {
    @JavascriptInterface public void requestSteps(){runOnUiThread(()->requestStepPermission());}
    @JavascriptInterface public void startGps(){runOnUiThread(()->startLocation());}
    @JavascriptInterface public void stopGps(){runOnUiThread(()->stopLocation());}
    @JavascriptInterface public void exportData(String json){
      runOnUiThread(()->{
        awaitingExport=json;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE,"steprivo-local-backup.json");
        startActivityForResult(intent,REQ_EXPORT);
      });
    }
  }
}