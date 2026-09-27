package com.voiceshield.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Process;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class AudioEngine {
    public interface Listener {
        void onStatus(String message, boolean running);
        void onLevels(int riderPercent, int pillionPercent, boolean hybridActive);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME = 320; // 20 ms

    private final Context context;
    private final AudioManager audioManager;
    private final Listener listener;

    private volatile boolean running;
    private volatile float outputGain = 0.80f;
    private volatile float suppressionStrength = 0.60f;
    private volatile boolean windFilterEnabled = true;
    private volatile boolean noiseSuppressorEnabled = true;
    private volatile boolean autoGainEnabled = true;

    private AudioRecord riderRecord;
    private AudioRecord pillionRecord;
    private AudioTrack audioTrack;
    private Thread riderThread;
    private Thread pillionThread;
    private Thread mixerThread;

    private AudioDeviceInfo bluetoothInput;
    private AudioDeviceInfo bluetoothOutput;
    private AudioDeviceInfo riderInput;

    private NoiseSuppressor riderNs;
    private NoiseSuppressor pillionNs;
    private AutomaticGainControl riderAgc;
    private AutomaticGainControl pillionAgc;

    private final ArrayBlockingQueue<short[]> riderQueue = new ArrayBlockingQueue<>(3);
    private final ArrayBlockingQueue<short[]> pillionQueue = new ArrayBlockingQueue<>(3);
    private final DspState riderDsp = new DspState();
    private final DspState pillionDsp = new DspState();

    public AudioEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public boolean isRunning() { return running; }

    public void setSidetoneGain(float value) {
        outputGain = clamp(value, 0f, 1.35f);
        if (audioTrack != null) {
            try { audioTrack.setVolume(Math.min(1f, outputGain)); } catch (Exception ignored) {}
        }
    }

    public void setSuppressionStrength(float value) { suppressionStrength = clamp(value, 0f, 1f); }
    public void setWindFilterEnabled(boolean enabled) { windFilterEnabled = enabled; }

    public void setNoiseSuppressorEnabled(boolean enabled) {
        noiseSuppressorEnabled = enabled;
        try { if (riderNs != null) riderNs.setEnabled(enabled); } catch (Exception ignored) {}
        try { if (pillionNs != null) pillionNs.setEnabled(enabled); } catch (Exception ignored) {}
    }

    public void setAutoGainEnabled(boolean enabled) {
        autoGainEnabled = enabled;
        try { if (riderAgc != null) riderAgc.setEnabled(enabled); } catch (Exception ignored) {}
        try { if (pillionAgc != null) pillionAgc.setEnabled(enabled); } catch (Exception ignored) {}
    }

    public String describeAvailableRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return "Allow Nearby devices so VoiceShield can find the Pixel Buds.";
        }
        try {
            discoverDevices();
            if (bluetoothOutput == null || bluetoothInput == null) {
                return "Connect both Pixel Buds first.";
            }
            String rider = riderInput == null ? "phone mic not found" : safeName(riderInput);
            return "Ready • Pillion: " + safeName(bluetoothInput) + " • Rider: " + rider;
        } catch (Exception e) {
            return "Connect Pixel Buds, then tap Start Hybrid Intercom.";
        }
    }

    public synchronized boolean start() {
        if (running) return true;
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            discoverDevices();
            if (bluetoothOutput == null || bluetoothInput == null) {
                notifyStatus("Pixel Buds communication microphone was not found.", false);
                cleanup();
                return false;
            }
            if (riderInput == null) {
                notifyStatus("Phone/USB rider microphone was not found.", false);
                cleanup();
                return false;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    notifyStatus("Nearby-device permission is required.", false);
                    cleanup();
                    return false;
                }
                if (!audioManager.setCommunicationDevice(bluetoothOutput)) {
                    notifyStatus("Android refused Bluetooth communication routing.", false);
                    cleanup();
                    return false;
                }
            } else {
                audioManager.startBluetoothSco();
                audioManager.setBluetoothScoOn(true);
            }

            riderRecord = buildRecorder(riderInput);
            pillionRecord = buildRecorder(bluetoothInput);
            audioTrack = buildTrack(bluetoothOutput);

            if (!initialized(riderRecord) || !initialized(pillionRecord) ||
                    audioTrack == null || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                notifyStatus("This phone could not open both microphones at the same time.", false);
                cleanup();
                return false;
            }

            riderNs = createNs(riderRecord.getAudioSessionId());
            pillionNs = createNs(pillionRecord.getAudioSessionId());
            riderAgc = createAgc(riderRecord.getAudioSessionId());
            pillionAgc = createAgc(pillionRecord.getAudioSessionId());

            riderDsp.reset();
            pillionDsp.reset();
            riderQueue.clear();
            pillionQueue.clear();

            riderRecord.startRecording();
            pillionRecord.startRecording();
            audioTrack.setVolume(Math.min(1f, outputGain));
            audioTrack.play();
            running = true;

            riderThread = new Thread(() -> captureLoop(riderRecord, riderQueue, riderDsp, true), "VoiceShield-RiderMic");
            pillionThread = new Thread(() -> captureLoop(pillionRecord, pillionQueue, pillionDsp, false), "VoiceShield-PillionMic");
            mixerThread = new Thread(this::mixLoop, "VoiceShield-Mixer");
            riderThread.start();
            pillionThread.start();
            mixerThread.start();

            notifyStatus("HYBRID ACTIVE • Rider=" + safeName(riderInput) + " • Pillion=" + safeName(bluetoothInput), true);
            return true;
        } catch (SecurityException e) {
            notifyStatus("Microphone/Bluetooth permission was denied.", false);
            cleanup();
            return false;
        } catch (Exception e) {
            notifyStatus("Hybrid intercom could not start: " + safeMessage(e), false);
            cleanup();
            return false;
        }
    }

    public synchronized void stop() {
        boolean hadAudio = running || riderRecord != null || pillionRecord != null || audioTrack != null;
        running = false;
        stopRecord(riderRecord);
        stopRecord(pillionRecord);
        try { if (audioTrack != null) { audioTrack.pause(); audioTrack.flush(); } } catch (Exception ignored) {}
        join(riderThread); join(pillionThread); join(mixerThread);
        cleanup();
        if (hadAudio) notifyStatus("Hybrid intercom stopped.", false);
        notifyLevels(0, 0, false);
    }

    private void discoverDevices() {
        bluetoothInput = null;
        bluetoothOutput = null;
        riderInput = null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            List<AudioDeviceInfo> comm = audioManager.getAvailableCommunicationDevices();
            for (AudioDeviceInfo d : comm) {
                if (isBluetooth(d)) {
                    if (bluetoothOutput == null || d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) bluetoothOutput = d;
                }
            }
        }

        AudioDeviceInfo[] inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        for (AudioDeviceInfo d : inputs) {
            if (isBluetooth(d)) {
                if (bluetoothInput == null || d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) bluetoothInput = d;
            }
        }

        // Prefer a USB/wired mic inside the rider helmet if present; otherwise use phone mic.
        for (AudioDeviceInfo d : inputs) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_USB_HEADSET || t == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    t == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                riderInput = d;
                break;
            }
        }
        if (riderInput == null) {
            for (AudioDeviceInfo d : inputs) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    riderInput = d;
                    break;
                }
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && bluetoothOutput == null) bluetoothOutput = bluetoothInput;
    }

    private AudioRecord buildRecorder(AudioDeviceInfo preferred) {
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return null;
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        AudioRecord.Builder b = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(min * 2, FRAME * 12));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) b.setPrivacySensitive(false);
        AudioRecord r = b.build();
        if (preferred != null) {
            try { r.setPreferredDevice(preferred); } catch (Exception ignored) {}
        }
        return r;
    }

    private AudioTrack buildTrack(AudioDeviceInfo preferred) {
        int min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return null;
        AudioAttributes attr = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        AudioTrack.Builder b = new AudioTrack.Builder()
                .setAudioAttributes(attr)
                .setAudioFormat(fmt)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(min * 2, FRAME * 12));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        AudioTrack t = b.build();
        if (preferred != null) {
            try { t.setPreferredDevice(preferred); } catch (Exception ignored) {}
        }
        return t;
    }

    private void captureLoop(AudioRecord record, ArrayBlockingQueue<short[]> queue, DspState state, boolean rider) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] buf = new short[FRAME];
        try {
            while (running) {
                int n = record.read(buf, 0, buf.length, AudioRecord.READ_BLOCKING);
                if (n <= 0) continue;
                float rms = process(buf, n, state);
                short[] copy = new short[FRAME];
                System.arraycopy(buf, 0, copy, 0, Math.min(n, FRAME));
                while (!queue.offer(copy)) queue.poll();
                state.level = toPercent(rms);
                if (rider) notifyLevels(state.level, pillionDsp.level, true);
                else notifyLevels(riderDsp.level, state.level, true);
            }
        } catch (Exception e) {
            if (running) notifyStatus((rider ? "Rider" : "Pillion") + " mic stopped: " + safeMessage(e), false);
        }
    }

    private void mixLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] silence = new short[FRAME];
        short[] out = new short[FRAME];
        try {
            while (running) {
                short[] rider = riderQueue.poll(28, TimeUnit.MILLISECONDS);
                short[] pillion = pillionQueue.poll(8, TimeUnit.MILLISECONDS);
                if (rider == null) rider = silence;
                if (pillion == null) pillion = silence;

                for (int i = 0; i < FRAME; i++) {
                    float mixed = (rider[i] / 32768f) * 0.72f + (pillion[i] / 32768f) * 0.82f;
                    mixed = softLimit(mixed);
                    out[i] = (short) Math.round(mixed * 32767f);
                }
                int written = 0;
                while (running && written < out.length) {
                    int n = audioTrack.write(out, written, out.length - written, AudioTrack.WRITE_BLOCKING);
                    if (n <= 0) break;
                    written += n;
                }
            }
        } catch (Exception e) {
            if (running) notifyStatus("Intercom mixer stopped: " + safeMessage(e), false);
        }
    }

    private float process(short[] samples, int count, DspState state) {
        double energy = 0;
        for (int i = 0; i < count; i++) {
            float x = samples[i] / 32768f;
            energy += x * x;
        }
        float rms = (float) Math.sqrt(energy / Math.max(1, count));
        float s = suppressionStrength;
        float threshold = 0.006f + 0.018f * s;
        float floor = Math.max(0.07f, 1f - 0.90f * s);
        float target = rms < threshold * 0.60f ? floor : (rms > threshold * 1.7f ? 1f : 0.55f + 0.45f * Math.min(1f, rms / Math.max(0.001f, threshold)));
        state.gate += (target - state.gate) * (target > state.gate ? 0.30f : 0.07f);

        float cutoff = 120f + 120f * s;
        float dt = 1f / SAMPLE_RATE;
        float rc = 1f / (2f * (float)Math.PI * cutoff);
        float alpha = rc / (rc + dt);

        for (int i = 0; i < count; i++) {
            float x = samples[i] / 32768f;
            float y = x;
            if (windFilterEnabled) {
                y = alpha * (state.hpOut + x - state.hpIn);
                state.hpIn = x;
                state.hpOut = y;
            }
            y *= state.gate;
            y = softLimit(y);
            samples[i] = (short)Math.round(y * 32767f);
        }
        return rms;
    }

    private NoiseSuppressor createNs(int session) {
        try {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor n = NoiseSuppressor.create(session);
                if (n != null) n.setEnabled(noiseSuppressorEnabled);
                return n;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private AutomaticGainControl createAgc(int session) {
        try {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl a = AutomaticGainControl.create(session);
                if (a != null) a.setEnabled(autoGainEnabled);
                return a;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void cleanup() {
        running = false;
        release(riderNs); riderNs = null;
        release(pillionNs); pillionNs = null;
        release(riderAgc); riderAgc = null;
        release(pillionAgc); pillionAgc = null;
        release(riderRecord); riderRecord = null;
        release(pillionRecord); pillionRecord = null;
        release(audioTrack); audioTrack = null;
        riderQueue.clear(); pillionQueue.clear();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice();
            else { audioManager.setBluetoothScoOn(false); audioManager.stopBluetoothSco(); }
        } catch (Exception ignored) {}
        try { audioManager.setMode(AudioManager.MODE_NORMAL); } catch (Exception ignored) {}
    }

    private static boolean initialized(AudioRecord r) { return r != null && r.getState() == AudioRecord.STATE_INITIALIZED; }
    private static boolean isBluetooth(AudioDeviceInfo d) {
        int t = d.getType();
        return t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET);
    }
    private static String safeName(AudioDeviceInfo d) {
        if (d == null) return "unknown";
        CharSequence n = d.getProductName();
        if (n != null && n.length() > 0) return n.toString();
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "Phone microphone";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB headset microphone";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "Wired headset microphone";
            default: return "Audio device";
        }
    }
    private static int toPercent(float rms) { return Math.min(100, Math.max(0, (int)(rms * 600f))); }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float softLimit(float x) { return (float)Math.tanh(x * 1.15f); }
    private static String safeMessage(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private static void stopRecord(AudioRecord r) { try { if (r != null) r.stop(); } catch (Exception ignored) {} }
    private static void release(AudioRecord r) { try { if (r != null) r.release(); } catch (Exception ignored) {} }
    private static void release(AudioTrack t) { try { if (t != null) t.release(); } catch (Exception ignored) {} }
    private static void release(NoiseSuppressor n) { try { if (n != null) n.release(); } catch (Exception ignored) {} }
    private static void release(AutomaticGainControl a) { try { if (a != null) a.release(); } catch (Exception ignored) {} }
    private static void join(Thread t) { if (t == null || t == Thread.currentThread()) return; try { t.join(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    private void notifyStatus(String msg, boolean active) { if (listener != null) listener.onStatus(msg, active); }
    private void notifyLevels(int rider, int pillion, boolean hybrid) { if (listener != null) listener.onLevels(rider, pillion, hybrid); }

    private static class DspState {
        float hpIn, hpOut, gate = 1f;
        volatile int level;
        void reset() { hpIn = hpOut = 0f; gate = 1f; level = 0; }
    }
}
