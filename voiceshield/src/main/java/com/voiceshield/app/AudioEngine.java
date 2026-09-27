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
        void onLevels(int leftPercent, int rightPercent, boolean stereoInput);
    }

    private final Context context;
    private final AudioManager audioManager;
    private final Listener listener;

    private volatile boolean running = false;
    private volatile float sidetoneGain = 0.75f;
    private volatile float suppressionStrength = 0.55f;
    private volatile boolean windFilterEnabled = true;
    private volatile boolean noiseSuppressorEnabled = true;
    private volatile boolean autoGainEnabled = true;
    private volatile boolean crossFeedEnabled = true;

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private NoiseSuppressor noiseSuppressor;
    private AcousticEchoCanceler echoCanceler;
    private AutomaticGainControl automaticGainControl;
    private Thread audioThread;

    private AudioDeviceInfo selectedOutputDevice;
    private AudioDeviceInfo selectedInputDevice;
    private boolean stereoInput = false;
    private boolean stereoOutput = false;
    private int sampleRate = 16000;
    private int frameFrames = 320;

    private float hpPrevInputLeft = 0f;
    private float hpPrevOutputLeft = 0f;
    private float hpPrevInputRight = 0f;
    private float hpPrevOutputRight = 0f;
    private float gateGainLeft = 1f;
    private float gateGainRight = 1f;

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

    public void setCrossFeedEnabled(boolean enabled) {
        crossFeedEnabled = enabled;
    }

    public String describeAvailableRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return "Bluetooth permission is required to detect your earbuds.";
            }
            try {
                List<AudioDeviceInfo> outputs = audioManager.getAvailableCommunicationDevices();
                for (AudioDeviceInfo output : outputs) {
                    if (isBluetoothCommunicationDevice(output)) {
                        AudioDeviceInfo input = findMatchingBluetoothInput(output);
                        String name = safeDeviceName(output);
                        if (input == null) {
                            return "Ready: " + name + " • headset mic route pending";
                        }
                        return "Ready: " + name + " • mic channels reported: " + maxChannels(input);
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

            selectedInputDevice = findMatchingBluetoothInput(selectedOutputDevice);
            stereoInput = selectedInputDevice != null && maxChannels(selectedInputDevice) >= 2;
            stereoOutput = stereoInput && selectedOutputDevice != null && maxChannels(selectedOutputDevice) >= 2;

            sampleRate = chooseSampleRate(selectedInputDevice, selectedOutputDevice);
            frameFrames = Math.max(160, sampleRate / 50); // ~20 ms

            int inputMask = stereoInput ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
            int outputMask = stereoOutput ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;

            int recordMin = AudioRecord.getMinBufferSize(sampleRate, inputMask, AudioFormat.ENCODING_PCM_16BIT);
            int trackMin = AudioTrack.getMinBufferSize(sampleRate, outputMask, AudioFormat.ENCODING_PCM_16BIT);

            if (recordMin <= 0 || trackMin <= 0) {
                if (stereoInput) {
                    // Some Bluetooth stacks advertise 2 channels but reject a stereo communication stream.
                    stereoInput = false;
                    stereoOutput = false;
                    inputMask = AudioFormat.CHANNEL_IN_MONO;
                    outputMask = AudioFormat.CHANNEL_OUT_MONO;
                    recordMin = AudioRecord.getMinBufferSize(sampleRate, inputMask, AudioFormat.ENCODING_PCM_16BIT);
                    trackMin = AudioTrack.getMinBufferSize(sampleRate, outputMask, AudioFormat.ENCODING_PCM_16BIT);
                }
            }

            if (recordMin <= 0 || trackMin <= 0) {
                notifyStatus("This phone rejected the Bluetooth communication audio format.", false);
                clearBluetoothRoute();
                audioManager.setMode(AudioManager.MODE_NORMAL);
                return false;
            }

            int inputChannels = stereoInput ? 2 : 1;
            int outputChannels = stereoOutput ? 2 : 1;
            int recordBuffer = Math.max(recordMin * 2, frameFrames * inputChannels * 6 * 2);
            int trackBuffer = Math.max(trackMin * 2, frameFrames * outputChannels * 6 * 2);

            AudioFormat inputFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(stereoInput ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO)
                    .build();

            int source = stereoInput ? MediaRecorder.AudioSource.MIC : MediaRecorder.AudioSource.VOICE_COMMUNICATION;
            audioRecord = new AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(inputFormat)
                    .setBufferSizeInBytes(recordBuffer)
                    .build();

            if (selectedInputDevice != null) {
                try {
                    audioRecord.setPreferredDevice(selectedInputDevice);
                } catch (Exception ignored) {
                }
            }

            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();

            AudioFormat outputFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(stereoOutput ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO)
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

            if (selectedOutputDevice != null) {
                try {
                    audioTrack.setPreferredDevice(selectedOutputDevice);
                } catch (Exception ignored) {
                }
            }

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED ||
                    audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                // Final safety fallback to mono if stereo construction was accepted but did not initialize.
                if (stereoInput) {
                    releaseAudioObjects();
                    stereoInput = false;
                    stereoOutput = false;
                    return startMonoFallback();
                }
                notifyStatus("Audio hardware could not initialize. Try reconnecting the earbuds.", false);
                releaseAudioObjects();
                clearBluetoothRoute();
                audioManager.setMode(AudioManager.MODE_NORMAL);
                return false;
            }

            attachAudioEffects(audioRecord.getAudioSessionId());
            audioTrack.setVolume(Math.min(1f, sidetoneGain));
            resetDspState();

            audioRecord.startRecording();
            audioTrack.play();
            running = true;

            String routeName = safeDeviceName(selectedOutputDevice);
            if (stereoInput) {
                String mode = stereoOutput && crossFeedEnabled
                        ? "2-channel Rider ↔ Pillion cross-feed active"
                        : "2-channel headset microphones active";
                notifyStatus(mode + " • " + routeName, true);
            } else {
                notifyStatus("MONO headset mic only • Android is not exposing both bud microphones • " + routeName, true);
            }

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

    private boolean startMonoFallback() {
        try {
            int recordMin = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int trackMin = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (recordMin <= 0 || trackMin <= 0) return false;

            int recordBuffer = Math.max(recordMin * 2, frameFrames * 6 * 2);
            int trackBuffer = Math.max(trackMin * 2, frameFrames * 6 * 2);

            AudioFormat inputFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();

            audioRecord = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(inputFormat)
                    .setBufferSizeInBytes(recordBuffer)
                    .build();
            if (selectedInputDevice != null) audioRecord.setPreferredDevice(selectedInputDevice);

            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            AudioFormat outputFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(outputFormat)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(trackBuffer)
                    .build();
            if (selectedOutputDevice != null) audioTrack.setPreferredDevice(selectedOutputDevice);

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                notifyStatus("Bluetooth mono fallback could not initialize.", false);
                cleanupAfterFailure();
                return false;
            }

            attachAudioEffects(audioRecord.getAudioSessionId());
            audioTrack.setVolume(Math.min(1f, sidetoneGain));
            resetDspState();
            audioRecord.startRecording();
            audioTrack.play();
            running = true;
            notifyStatus("MONO headset mic only • Android is not exposing both bud microphones • " + safeDeviceName(selectedOutputDevice), true);
            audioThread = new Thread(this::audioLoop, "VoiceShieldAudio");
            audioThread.start();
            return true;
        } catch (Exception e) {
            notifyStatus("Mono fallback failed: " + safeMessage(e), false);
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
        notifyLevels(0, 0, false);
    }

    private void audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        int inputChannels = stereoInput ? 2 : 1;
        int outputChannels = stereoOutput ? 2 : 1;
        short[] input = new short[frameFrames * inputChannels];
        short[] output = new short[frameFrames * outputChannels];
        long lastMeterUpdate = 0L;

        try {
            while (running) {
                int count = audioRecord.read(input, 0, input.length, AudioRecord.READ_BLOCKING);
                if (count <= 0) continue;

                Levels levels;
                int outputCount;
                if (stereoInput) {
                    int frames = count / 2;
                    levels = processStereoInPlace(input, frames);
                    if (stereoOutput) {
                        for (int f = 0; f < frames; f++) {
                            short leftMic = input[f * 2];
                            short rightMic = input[f * 2 + 1];
                            if (crossFeedEnabled) {
                                output[f * 2] = rightMic;      // wife/right mic -> rider/left ear
                                output[f * 2 + 1] = leftMic;  // rider/left mic -> wife/right ear
                            } else {
                                output[f * 2] = leftMic;
                                output[f * 2 + 1] = rightMic;
                            }
                        }
                        outputCount = frames * 2;
                    } else {
                        for (int f = 0; f < frames; f++) {
                            int mixed = (input[f * 2] + input[f * 2 + 1]) / 2;
                            output[f] = (short) mixed;
                        }
                        outputCount = frames;
                    }
                } else {
                    float rms = processMonoInPlace(input, count);
                    levels = new Levels(rms, 0f);
                    System.arraycopy(input, 0, output, 0, count);
                    outputCount = count;
                }

                int written = 0;
                while (running && written < outputCount) {
                    int n = audioTrack.write(output, written, outputCount - written, AudioTrack.WRITE_BLOCKING);
                    if (n <= 0) break;
                    written += n;
                }

                long now = System.nanoTime();
                if (now - lastMeterUpdate > 90_000_000L) {
                    notifyLevels(levelToPercent(levels.left), levelToPercent(levels.right), stereoInput);
                    lastMeterUpdate = now;
                }
            }
        } catch (Exception e) {
            if (running) notifyStatus("Audio stream stopped: " + safeMessage(e), false);
        }
    }

    private Levels processStereoInPlace(short[] samples, int frames) {
        double leftEnergy = 0.0;
        double rightEnergy = 0.0;
        for (int f = 0; f < frames; f++) {
            float l = samples[f * 2] / 32768f;
            float r = samples[f * 2 + 1] / 32768f;
            leftEnergy += l * l;
            rightEnergy += r * r;
        }
        float leftRms = (float) Math.sqrt(leftEnergy / Math.max(1, frames));
        float rightRms = (float) Math.sqrt(rightEnergy / Math.max(1, frames));
        gateGainLeft = updateGate(gateGainLeft, leftRms);
        gateGainRight = updateGate(gateGainRight, rightRms);

        float alpha = highPassAlpha();
        float softwareGain = sidetoneGain > 1f ? sidetoneGain : 1f;
        for (int f = 0; f < frames; f++) {
            float left = samples[f * 2] / 32768f;
            float right = samples[f * 2 + 1] / 32768f;

            if (windFilterEnabled) {
                float filteredLeft = alpha * (hpPrevOutputLeft + left - hpPrevInputLeft);
                hpPrevInputLeft = left;
                hpPrevOutputLeft = filteredLeft;
                left = filteredLeft;

                float filteredRight = alpha * (hpPrevOutputRight + right - hpPrevInputRight);
                hpPrevInputRight = right;
                hpPrevOutputRight = filteredRight;
                right = filteredRight;
            }

            left = softLimit(left * gateGainLeft * softwareGain);
            right = softLimit(right * gateGainRight * softwareGain);
            samples[f * 2] = (short) Math.round(left * 32767f);
            samples[f * 2 + 1] = (short) Math.round(right * 32767f);
        }
        return new Levels(leftRms, rightRms);
    }

    private float processMonoInPlace(short[] samples, int count) {
        double energy = 0.0;
        for (int i = 0; i < count; i++) {
            float x = samples[i] / 32768f;
            energy += x * x;
        }
        float rms = (float) Math.sqrt(energy / Math.max(1, count));
        gateGainLeft = updateGate(gateGainLeft, rms);
        float alpha = highPassAlpha();
        float softwareGain = sidetoneGain > 1f ? sidetoneGain : 1f;

        for (int i = 0; i < count; i++) {
            float x = samples[i] / 32768f;
            float y = x;
            if (windFilterEnabled) {
                y = alpha * (hpPrevOutputLeft + x - hpPrevInputLeft);
                hpPrevInputLeft = x;
                hpPrevOutputLeft = y;
            }
            y = softLimit(y * gateGainLeft * softwareGain);
            samples[i] = (short) Math.round(y * 32767f);
        }
        return rms;
    }

    private float updateGate(float current, float rms) {
        float strength = suppressionStrength;
        float threshold = 0.0055f + (0.0185f * strength);
        float floorGain = Math.max(0.08f, 1f - (0.88f * strength));
        float target;
        if (rms <= threshold * 0.55f) {
            target = floorGain;
        } else if (rms >= threshold * 1.8f) {
            target = 1f;
        } else {
            float t = (rms - threshold * 0.55f) / (threshold * 1.25f);
            target = floorGain + (1f - floorGain) * clamp(t, 0f, 1f);
        }
        float attack = target > current ? 0.34f : 0.08f;
        return current + (target - current) * attack;
    }

    private float highPassAlpha() {
        float cutoffHz = 115f + (95f * suppressionStrength);
        float dt = 1f / sampleRate;
        float rc = 1f / (2f * (float) Math.PI * cutoffHz);
        return rc / (rc + dt);
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
            if (!audioManager.setCommunicationDevice(best)) {
                notifyStatus("Android could not route communication audio to the earbuds.", false);
                return false;
            }
            selectedOutputDevice = best;
            return true;
        }

        try {
            audioManager.startBluetoothSco();
            audioManager.setBluetoothScoOn(true);
            selectedOutputDevice = null;
            return true;
        } catch (Exception e) {
            notifyStatus("Could not activate Bluetooth headset audio.", false);
            return false;
        }
    }

    private AudioDeviceInfo findMatchingBluetoothInput(AudioDeviceInfo output) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
        try {
            AudioDeviceInfo fallback = null;
            String outputName = output == null ? "" : safeDeviceName(output);
            int outputType = output == null ? -1 : output.getType();
            for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (!device.isSource() || !isBluetoothCommunicationDevice(device)) continue;
                if (fallback == null) fallback = device;
                String inputName = safeDeviceName(device);
                if (outputType == device.getType() && outputName.equals(inputName)) return device;
                if (outputType == device.getType()) fallback = device;
            }
            return fallback;
        } catch (Exception ignored) {
            return null;
        }
    }

    private int chooseSampleRate(AudioDeviceInfo input, AudioDeviceInfo output) {
        if (supportsSampleRate(input, 32000) && supportsSampleRate(output, 32000)) return 32000;
        if (supportsSampleRate(input, 16000) || input == null) return 16000;
        int[] rates = input.getSampleRates();
        if (rates != null) {
            for (int rate : rates) {
                if (rate >= 16000 && rate <= 48000) return rate;
            }
        }
        return 16000;
    }

    private boolean supportsSampleRate(AudioDeviceInfo device, int target) {
        if (device == null) return false;
        int[] rates = device.getSampleRates();
        if (rates == null || rates.length == 0) return true;
        for (int rate : rates) if (rate == target) return true;
        return false;
    }

    private static int maxChannels(AudioDeviceInfo device) {
        if (device == null) return 0;
        int max = 0;
        int[] counts = device.getChannelCounts();
        if (counts != null) for (int c : counts) max = Math.max(max, c);
        return max == 0 ? 1 : max;
    }

    private static String safeDeviceName(AudioDeviceInfo device) {
        if (device == null) return "Bluetooth headset";
        CharSequence name = device.getProductName();
        return name == null || name.length() == 0 ? "Bluetooth headset" : name.toString();
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
        selectedOutputDevice = null;
        selectedInputDevice = null;
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

    private void resetDspState() {
        hpPrevInputLeft = 0f;
        hpPrevOutputLeft = 0f;
        hpPrevInputRight = 0f;
        hpPrevOutputRight = 0f;
        gateGainLeft = 1f;
        gateGainRight = 1f;
    }

    private void releaseEffects() {
        try { if (noiseSuppressor != null) noiseSuppressor.release(); } catch (Exception ignored) {}
        try { if (echoCanceler != null) echoCanceler.release(); } catch (Exception ignored) {}
        try { if (automaticGainControl != null) automaticGainControl.release(); } catch (Exception ignored) {}
        noiseSuppressor = null;
        echoCanceler = null;
        automaticGainControl = null;
    }

    private void releaseAudioObjects() {
        try { if (audioRecord != null) audioRecord.release(); } catch (Exception ignored) {}
        try { if (audioTrack != null) audioTrack.release(); } catch (Exception ignored) {}
        audioRecord = null;
        audioTrack = null;
        audioThread = null;
    }

    private void cleanupAfterFailure() {
        running = false;
        releaseEffects();
        releaseAudioObjects();
        clearBluetoothRoute();
        try { audioManager.setMode(AudioManager.MODE_NORMAL); } catch (Exception ignored) {}
    }

    private void notifyStatus(String message, boolean active) {
        if (listener != null) listener.onStatus(message, active);
    }

    private void notifyLevels(int left, int right, boolean stereo) {
        if (listener != null) listener.onLevels(left, right, stereo);
    }

    private static int levelToPercent(float rms) {
        return Math.min(100, Math.max(0, (int) (rms * 520f)));
    }

    private static float softLimit(float x) {
        if (x > 1f) return 1f - (1f / (1f + (x - 1f) * 4f));
        if (x < -1f) return -1f + (1f / (1f + (-x - 1f) * 4f));
        return x;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static class Levels {
        final float left;
        final float right;
        Levels(float left, float right) {
            this.left = left;
            this.right = right;
        }
    }
}
