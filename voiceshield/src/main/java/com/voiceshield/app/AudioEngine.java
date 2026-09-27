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
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Process;

import java.util.List;

public class AudioEngine {
    public interface Listener {
        void onStatus(String message, boolean running);
        void onLevel(int percent);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_SAMPLES = 320; // 20 ms at 16 kHz

    private final Context context;
    private final AudioManager audioManager;
    private final Listener listener;

    private volatile boolean running = false;
    private volatile float sidetoneGain = 0.55f;
    private volatile float suppressionStrength = 0.55f;
    private volatile boolean windFilterEnabled = true;
    private volatile boolean noiseSuppressorEnabled = true;
    private volatile boolean autoGainEnabled = true;

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private NoiseSuppressor noiseSuppressor;
    private AcousticEchoCanceler echoCanceler;
    private AutomaticGainControl automaticGainControl;
    private Thread audioThread;
    private AudioDeviceInfo selectedDevice;

    private float hpPrevInput = 0f;
    private float hpPrevOutput = 0f;
    private float gateGain = 1f;

    public AudioEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public boolean isRunning() {
        return running;
    }

    public void setSidetoneGain(float value) {
        sidetoneGain = clamp(value, 0f, 1.35f);
        AudioTrack track = audioTrack;
        if (track != null) {
            try {
                track.setVolume(Math.min(1f, sidetoneGain));
            } catch (Exception ignored) {
            }
        }
    }

    public void setSuppressionStrength(float value) {
        suppressionStrength = clamp(value, 0f, 1f);
    }

    public void setWindFilterEnabled(boolean enabled) {
        windFilterEnabled = enabled;
    }

    public void setNoiseSuppressorEnabled(boolean enabled) {
        noiseSuppressorEnabled = enabled;
        NoiseSuppressor effect = noiseSuppressor;
        if (effect != null) {
            try {
                effect.setEnabled(enabled);
            } catch (Exception ignored) {
            }
        }
    }

    public void setAutoGainEnabled(boolean enabled) {
        autoGainEnabled = enabled;
        AutomaticGainControl effect = automaticGainControl;
        if (effect != null) {
            try {
                effect.setEnabled(enabled);
            } catch (Exception ignored) {
            }
        }
    }

    public String describeAvailableRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return "Bluetooth permission is required to detect your earbuds.";
            }
            try {
                List<AudioDeviceInfo> devices = audioManager.getAvailableCommunicationDevices();
                for (AudioDeviceInfo device : devices) {
                    if (isBluetoothCommunicationDevice(device)) {
                        CharSequence name = device.getProductName();
                        return "Ready: " + (name == null ? "Bluetooth headset" : name.toString());
                    }
                }
                return "Connect Pixel Buds or another Bluetooth headset first.";
            } catch (Exception e) {
                return "Bluetooth route check unavailable on this phone.";
            }
        }
        return "Ready to use the connected Bluetooth headset.";
    }

    public synchronized boolean start() {
        if (running) return true;

        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

            if (!routeToBluetooth()) {
                audioManager.setMode(AudioManager.MODE_NORMAL);
                return false;
            }

            int recordMin = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            int trackMin = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );

            if (recordMin <= 0 || trackMin <= 0) {
                notifyStatus("This phone rejected the low-latency audio format.", false);
                clearBluetoothRoute();
                audioManager.setMode(AudioManager.MODE_NORMAL);
                return false;
            }

            int recordBuffer = Math.max(recordMin * 2, FRAME_SAMPLES * 6);
            int trackBuffer = Math.max(trackMin * 2, FRAME_SAMPLES * 6);

            AudioFormat inputFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();

            audioRecord = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(inputFormat)
                    .setBufferSizeInBytes(recordBuffer)
                    .build();

            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();

            AudioFormat outputFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();

            AudioTrack.Builder trackBuilder = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(outputFormat)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(trackBuffer);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }
            audioTrack = trackBuilder.build();

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED ||
                    audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                notifyStatus("Audio hardware could not initialize. Try reconnecting the earbuds.", false);
                releaseAudioObjects();
                clearBluetoothRoute();
                audioManager.setMode(AudioManager.MODE_NORMAL);
                return false;
            }

            attachAudioEffects(audioRecord.getAudioSessionId());
            audioTrack.setVolume(Math.min(1f, sidetoneGain));

            hpPrevInput = 0f;
            hpPrevOutput = 0f;
            gateGain = 1f;

            audioRecord.startRecording();
            audioTrack.play();
            running = true;

            String routeName = selectedDevice != null && selectedDevice.getProductName() != null
                    ? selectedDevice.getProductName().toString()
                    : "Bluetooth headset";
            notifyStatus("Live voice monitor active • " + routeName, true);

            audioThread = new Thread(this::audioLoop, "VoiceShieldAudio");
            audioThread.start();
            return true;
        } catch (SecurityException e) {
            notifyStatus("Microphone/Bluetooth permission was denied.", false);
            cleanupAfterFailure();
            return false;
        } catch (Exception e) {
            notifyStatus("Could not start audio: " + safeMessage(e), false);
            cleanupAfterFailure();
            return false;
        }
    }

    public synchronized void stop() {
        if (!running && audioRecord == null && audioTrack == null) return;
        running = false;

        try {
            if (audioRecord != null) audioRecord.stop();
        } catch (Exception ignored) {
        }
        try {
            if (audioTrack != null) {
                audioTrack.pause();
                audioTrack.flush();
            }
        } catch (Exception ignored) {
        }

        if (audioThread != null && audioThread != Thread.currentThread()) {
            try {
                audioThread.join(350);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        releaseEffects();
        releaseAudioObjects();
        clearBluetoothRoute();
        try {
            audioManager.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception ignored) {
        }
        notifyStatus("Voice monitor stopped.", false);
        notifyLevel(0);
    }

    private void audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] buffer = new short[FRAME_SAMPLES];
        long lastMeterUpdate = 0L;

        try {
            while (running) {
                int count = audioRecord.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (count <= 0) continue;

                float rms = processInPlace(buffer, count);
                int written = 0;
                while (running && written < count) {
                    int n = audioTrack.write(buffer, written, count - written, AudioTrack.WRITE_BLOCKING);
                    if (n <= 0) break;
                    written += n;
                }

                long now = System.nanoTime();
                if (now - lastMeterUpdate > 90_000_000L) {
                    int percent = Math.min(100, Math.max(0, (int) (rms * 520f)));
                    notifyLevel(percent);
                    lastMeterUpdate = now;
                }
            }
        } catch (Exception e) {
            if (running) {
                notifyStatus("Audio stream stopped: " + safeMessage(e), false);
            }
        }
    }

    private float processInPlace(short[] samples, int count) {
        double energy = 0.0;
        for (int i = 0; i < count; i++) {
            float x = samples[i] / 32768f;
            energy += x * x;
        }
        float rms = (float) Math.sqrt(energy / Math.max(1, count));

        float strength = suppressionStrength;
        float threshold = 0.0055f + (0.0185f * strength);
        float floorGain = 1f - (0.88f * strength);
        floorGain = Math.max(0.08f, floorGain);

        float targetGate;
        if (rms <= threshold * 0.55f) {
            targetGate = floorGain;
        } else if (rms >= threshold * 1.8f) {
            targetGate = 1f;
        } else {
            float t = (rms - threshold * 0.55f) / (threshold * 1.25f);
            targetGate = floorGain + (1f - floorGain) * clamp(t, 0f, 1f);
        }

        float attack = targetGate > gateGain ? 0.34f : 0.08f;
        gateGain += (targetGate - gateGain) * attack;

        float cutoffHz = 115f + (95f * strength);
        float dt = 1f / SAMPLE_RATE;
        float rc = 1f / (2f * (float) Math.PI * cutoffHz);
        float hpAlpha = rc / (rc + dt);

        float requestedGain = sidetoneGain;
        float softwareGain = requestedGain > 1f ? requestedGain : 1f;

        for (int i = 0; i < count; i++) {
            float x = samples[i] / 32768f;
            float y = x;

            if (windFilterEnabled) {
                y = hpAlpha * (hpPrevOutput + x - hpPrevInput);
                hpPrevInput = x;
                hpPrevOutput = y;
            }

            y *= gateGain;
            y *= softwareGain;
            y = softLimit(y);
            samples[i] = (short) Math.round(y * 32767f);
        }
        return rms;
    }

    private boolean routeToBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                notifyStatus("Bluetooth permission is required.", false);
                return false;
            }

            List<AudioDeviceInfo> devices = audioManager.getAvailableCommunicationDevices();
            AudioDeviceInfo best = null;
            for (AudioDeviceInfo device : devices) {
                if (device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    best = device;
                    break;
                }
                if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    best = device;
                }
            }

            if (best == null) {
                notifyStatus("No Bluetooth communication headset found. Connect your Pixel Buds first.", false);
                return false;
            }

            boolean routed = audioManager.setCommunicationDevice(best);
            if (!routed) {
                notifyStatus("Android could not route communication audio to the earbuds.", false);
                return false;
            }
            selectedDevice = best;
            return true;
        }

        try {
            audioManager.startBluetoothSco();
            audioManager.setBluetoothScoOn(true);
            selectedDevice = null;
            return true;
        } catch (Exception e) {
            notifyStatus("Could not activate Bluetooth headset audio.", false);
            return false;
        }
    }

    private void clearBluetoothRoute() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice();
            } else {
                audioManager.setBluetoothScoOn(false);
                audioManager.stopBluetoothSco();
            }
        } catch (Exception ignored) {
        }
        selectedDevice = null;
    }

    private static boolean isBluetoothCommunicationDevice(AudioDeviceInfo device) {
        int type = device.getType();
        return type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
    }

    private void attachAudioEffects(int sessionId) {
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId);
                if (noiseSuppressor != null) noiseSuppressor.setEnabled(noiseSuppressorEnabled);
            }
        } catch (Exception ignored) {
        }
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId);
                if (echoCanceler != null) echoCanceler.setEnabled(true);
            }
        } catch (Exception ignored) {
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl = AutomaticGainControl.create(sessionId);
                if (automaticGainControl != null) automaticGainControl.setEnabled(autoGainEnabled);
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseEffects() {
        try {
            if (noiseSuppressor != null) noiseSuppressor.release();
        } catch (Exception ignored) {
        }
        try {
            if (echoCanceler != null) echoCanceler.release();
        } catch (Exception ignored) {
        }
        try {
            if (automaticGainControl != null) automaticGainControl.release();
        } catch (Exception ignored) {
        }
        noiseSuppressor = null;
        echoCanceler = null;
        automaticGainControl = null;
    }

    private void releaseAudioObjects() {
        try {
            if (audioRecord != null) audioRecord.release();
        } catch (Exception ignored) {
        }
        try {
            if (audioTrack != null) audioTrack.release();
        } catch (Exception ignored) {
        }
        audioRecord = null;
        audioTrack = null;
        audioThread = null;
    }

    private void cleanupAfterFailure() {
        running = false;
        releaseEffects();
        releaseAudioObjects();
        clearBluetoothRoute();
        try {
            audioManager.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception ignored) {
        }
    }

    private void notifyStatus(String message, boolean active) {
        if (listener != null) listener.onStatus(message, active);
    }

    private void notifyLevel(int percent) {
        if (listener != null) listener.onLevel(percent);
    }

    private static float softLimit(float x) {
        if (x > 1f) return 1f - (1f / (1f + (x - 1f) * 4f)) * 0.08f;
        if (x < -1f) return -1f + (1f / (1f + (-x - 1f) * 4f)) * 0.08f;
        return x;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
