package com.rtrlink.android;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.media.AudioManager;
import android.view.KeyEvent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class BikeBleService extends Service {
    public interface Listener {
        void onBikeUpdated();
        void onDevicesUpdated();
        void onTransferUpdated();
    }

    public static final class DeviceInfo {
        public final String name;
        public final String address;
        public final int rssi;
        DeviceInfo(String name, String address, int rssi) {
            this.name = name;
            this.address = address;
            this.rssi = rssi;
        }
    }

    public final class LocalBinder extends Binder {
        public BikeBleService getService() { return BikeBleService.this; }
    }

    private static final String TAG = "RTR Link BLE";
    private static final String PREFS = "rtr_link_settings";
    private static final String PREF_BIKE_ADDRESS = "preferred_bike_address";
    private static final String PREF_BIKE_NAME = "preferred_bike_name";
    private static final String PREF_RIDER_NAME = "rider_name";
    private static final String PREF_MUSIC_ENTER = "music_action_enter_double";
    private static final String PREF_MUSIC_BACK = "music_action_back_double";
    private static final String PREF_MUSIC_DOWN = "music_action_down_double";
    private static final int NOTIFICATION_ID = 310;

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable welcomeClearTask;
    private final ExecutorService writes = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private final List<DeviceInfo> devices = new ArrayList<>();
    private final java.util.concurrent.ConcurrentHashMap<String, String> telemetry = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Integer, byte[]> lastLoggedPayloadByFrame = new HashMap<>();
    private final Object writeLock = new Object();
    private final Object ackLock = new Object();

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private String connectedName = "";
    private String connectedAddress = "";
    private String connectionMessage = "Starting Bluetooth…";
    private String riderName = "Rider";
    private String preferredAddress = "";
    private String preferredName = "";
    private boolean connected;
    private boolean scanning;
    private boolean manualScanning;
    private boolean wantsConnection = true;
    private boolean phoneSessionStarted;
    private boolean transferActive;
    private long lastStatusWriteAt;
    private int notificationCount;
    private int latestAckFrame;
    private int latestAckStatus;
    private int transferProgress;
    private int negotiatedMtu = 23;
    private boolean serviceDiscoveryStarted;
    private String transferMessage = "";
    private int lastTransferNotificationProgress = -1;
    private CountDownLatch writeLatch;
    private int lastWriteStatus = BluetoothGatt.GATT_FAILURE;
    private FileWriter sessionWriter;
    private String sessionLogPath = "";
    private int lastMusicCommand = -1;
    private long lastMusicCommandAt;

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String address = device.getAddress();
            String name;
            try { name = device.getName(); } catch (SecurityException e) { name = null; }
            if (name == null || name.trim().isEmpty()) name = "TVS bike";
            rememberDevice(new DeviceInfo(name, address, result.getRssi()));
            log("device_discovered", mapOf("name", name, "rssi_dbm", result.getRssi()));
            if (!manualScanning && !connected && gatt == null && (preferredAddress.isEmpty() || preferredAddress.equals(address))) {
                preferredAddress = address;
                preferredName = name;
                getPrefs().edit().putString(PREF_BIKE_ADDRESS, address).putString(PREF_BIKE_NAME, name).apply();
                connect(device, name);
            }
        }

        @Override public void onScanFailed(int errorCode) {
            scanning = false;
            connectionMessage = "BLE scan failed (" + errorCode + "). Tap Scan to retry.";
            log("scan_failed", mapOf("error_code", errorCode));
            notifyBikeUpdated();
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt callbackGatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected = true;
                connectionMessage = "Connected. Discovering bike service…";
                log("gatt_connected", mapOf("name", connectedName, "status", status));
                mainHandler.post(BikeBleService.this::startOngoingNotification);
                notifyBikeUpdated();
                negotiatedMtu = 23;
                serviceDiscoveryStarted = false;
                boolean mtuRequestStarted = false;
                try { mtuRequestStarted = callbackGatt.requestMtu(185); }
                catch (SecurityException e) { log("mtu_request_denied", mapOf("error", e.getMessage())); }
                log("mtu_request", mapOf("requested", 185, "started", mtuRequestStarted));
                if (!mtuRequestStarted) discoverBikeServices(callbackGatt);
            } else {
                connected = false;
                phoneSessionStarted = false;
                writeCharacteristic = null;
                connectionMessage = status == BluetoothGatt.GATT_SUCCESS ? "Bike disconnected" : "BLE disconnected (" + status + ")";
                log("gatt_disconnected", mapOf("status", status, "new_state", newState));
                mainHandler.post(BikeBleService.this::startOngoingNotification);
                notifyBikeUpdated();
                closeGatt(callbackGatt);
                if (wantsConnection) mainHandler.postDelayed(this::restartScan, 1800);
            }
        }

        @Override public void onMtuChanged(BluetoothGatt callbackGatt, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu;
            log("mtu_negotiated", mapOf("mtu", mtu, "status", status, "write_payload_limit", Math.max(0, negotiatedMtu - 3)));
            discoverBikeServices(callbackGatt);
        }

        private void discoverBikeServices(BluetoothGatt callbackGatt) {
            if (serviceDiscoveryStarted) return;
            serviceDiscoveryStarted = true;
            try {
                if (!callbackGatt.discoverServices()) {
                    connectionMessage = "Could not start bike service discovery";
                    notifyBikeUpdated();
                }
            } catch (SecurityException e) {
                log("service_discovery_denied", mapOf("error", e.getMessage()));
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt callbackGatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectionMessage = "Could not discover bike services";
                notifyBikeUpdated();
                return;
            }
            BluetoothGattService service = callbackGatt.getService(java.util.UUID.fromString(BikeProtocol.SERVICE_UUID));
            BluetoothGattCharacteristic notifyCharacteristic = service == null ? null
                    : service.getCharacteristic(java.util.UUID.fromString(BikeProtocol.NOTIFY_UUID));
            writeCharacteristic = service == null ? null
                    : service.getCharacteristic(java.util.UUID.fromString(BikeProtocol.WRITE_UUID));
            if (notifyCharacteristic == null || writeCharacteristic == null) {
                connectionMessage = "Bike BLE characteristics were not found";
                log("characteristics_missing", mapOf("service_found", service != null));
                notifyBikeUpdated();
                return;
            }
            try {
                callbackGatt.setCharacteristicNotification(notifyCharacteristic, true);
                BluetoothGattDescriptor cccd = notifyCharacteristic.getDescriptor(java.util.UUID.fromString(BikeProtocol.CCCD_UUID));
                if (cccd == null) {
                    connectionMessage = "Notification descriptor missing";
                    notifyBikeUpdated();
                    return;
                }
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                callbackGatt.writeDescriptor(cccd);
                connectionMessage = "Subscribing to bike telemetry…";
                log("notification_subscription_requested", mapOf("characteristic", BikeProtocol.NOTIFY_UUID));
            } catch (SecurityException e) {
                connectionMessage = "Bluetooth permission was denied";
                log("notification_setup_failed", mapOf("error", e.getMessage()));
                notifyBikeUpdated();
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt callbackGatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                connectionMessage = "Connected · listening for telemetry";
                log("notification_subscription_active", mapOf("status", status));
                notifyBikeUpdated();
                beginPhoneSession();
            } else {
                connectionMessage = "Could not subscribe to telemetry (" + status + ")";
                log("notification_subscription_failed", mapOf("status", status));
                notifyBikeUpdated();
            }
        }

        @Override public void onCharacteristicChanged(BluetoothGatt callbackGatt, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value != null) handleNotification(value.clone());
        }

        @Override public void onCharacteristicChanged(BluetoothGatt callbackGatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            if (value != null) handleNotification(value.clone());
        }

        @Override public void onCharacteristicWrite(BluetoothGatt callbackGatt, BluetoothGattCharacteristic characteristic, int status) {
            synchronized (writeLock) {
                lastWriteStatus = status;
                if (writeLatch != null) writeLatch.countDown();
            }
            log("write_complete", mapOf("status", status));
        }

        private void restartScan() { BikeBleService.this.startScan(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        SharedPreferences prefs = getPrefs();
        preferredAddress = prefs.getString(PREF_BIKE_ADDRESS, "");
        preferredName = prefs.getString(PREF_BIKE_NAME, "");
        riderName = prefs.getString(PREF_RIDER_NAME, "Rider");
        startSessionLog();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startOngoingNotification();
        wantsConnection = true;
        manualScanning = false;
        if (hasBluetoothPermissions()) startScan();
        else connectionMessage = "Allow Nearby devices permission to connect";
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public void onDestroy() {
        wantsConnection = false;
        stopScan();
        if (gatt != null) closeGatt(gatt);
        try { if (sessionWriter != null) sessionWriter.close(); } catch (IOException ignored) { }
        writes.shutdownNow();
        super.onDestroy();
    }

    void addListener(Listener listener) { listeners.add(listener); }
    void removeListener(Listener listener) { listeners.remove(listener); }
    String getConnectionMessage() { return connectionMessage; }
    boolean isConnected() { return connected; }
    String getConnectedName() { return connectedName; }
    String getRiderName() { return riderName; }
    String getPreferredAddress() { return preferredAddress; }
    String getPreferredName() { return preferredName; }
    int getNotificationCount() { return notificationCount; }
    Map<String, String> getTelemetry() { return telemetry; }
    List<DeviceInfo> getDevices() { synchronized (devices) { return new ArrayList<>(devices); } }
    String getTransferMessage() { return transferMessage; }
    int getTransferProgress() { return transferProgress; }
    boolean isTransferActive() { return transferActive; }
    String getSessionLogPath() { return sessionLogPath; }

    void setRiderName(String name) {
        riderName = name == null ? "" : name.trim();
        getPrefs().edit().putString(PREF_RIDER_NAME, riderName).apply();
        log("rider_name_updated", mapOf("length", riderName.length()));
    }

    String getMusicAction(int command) {
        SharedPreferences prefs = getPrefs();
        if (command == 3) return prefs.getString(PREF_MUSIC_ENTER, "next");
        if (command == 4) return prefs.getString(PREF_MUSIC_BACK, "previous");
        if (command == 2) return prefs.getString(PREF_MUSIC_DOWN, "pause");
        return "none";
    }

    void setMusicAction(int command, String action) {
        String key = command == 3 ? PREF_MUSIC_ENTER : command == 4 ? PREF_MUSIC_BACK : command == 2 ? PREF_MUSIC_DOWN : null;
        if (key == null) return;
        getPrefs().edit().putString(key, action).apply();
        log("music_action_mapping_updated", mapOf("bike_command", command, "action", action));
    }

    void forgetPreferredBike() {
        preferredAddress = "";
        preferredName = "";
        getPrefs().edit().remove(PREF_BIKE_ADDRESS).remove(PREF_BIKE_NAME).apply();
        log("preferred_bike_forgotten", mapOf());
        notifyBikeUpdated();
    }

    void selectBike(DeviceInfo device) {
        preferredAddress = device.address;
        preferredName = device.name;
        getPrefs().edit().putString(PREF_BIKE_ADDRESS, preferredAddress).putString(PREF_BIKE_NAME, preferredName).apply();
        log("preferred_bike_selected", mapOf("name", preferredName));
        manualScanning = false;
        if (connected && !connectedAddress.equals(preferredAddress)) {
            wantsConnection = true;
            try { gatt.disconnect(); } catch (Exception ignored) { }
        }
        else if (!connected) startScan();
    }

    void scanNow() {
        manualScanning = true;
        if (scanner != null && scanning) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) { }
            scanning = false;
        }
        synchronized (devices) { devices.clear(); }
        notifyDevicesUpdated();
        startScan();
    }

    void disconnect() {
        wantsConnection = false;
        manualScanning = false;
        stopScan();
        if (gatt != null) {
            connectionMessage = "Disconnecting…";
            try { gatt.disconnect(); } catch (SecurityException ignored) { }
        }
        notifyBikeUpdated();
    }

    void reconnect() {
        wantsConnection = true;
        manualScanning = false;
        if (gatt != null) closeGatt(gatt);
        startScan();
    }

    void markAction(String action) {
        String value = action == null ? "" : action.trim();
        if (!value.isEmpty()) log("user_action_marker", mapOf("action", value));
    }

    void sendDocument(Bitmap bitmap, String name, int picture) {
        if (!connected || writeCharacteristic == null) {
            transferMessage = "Connect to the bike before sending a document.";
            notifyTransferUpdated();
            return;
        }
        if (transferActive) return;
        transferActive = true;
        transferProgress = 0;
        transferMessage = "Preparing 800 × 480 image…";
        notifyTransferUpdated();
        writes.execute(() -> {
            try {
                byte[] payload = BikeProtocol.documentPixels(bitmap);
                int chunkSize = 128;
                int total = (payload.length + chunkSize - 1) / chunkSize;
                if (total > 6000) throw new IOException("Image exceeds the bike transfer limit");
                latestAckFrame = 0;
                latestAckStatus = 0;
                log("document_transfer_started", mapOf("picture_number", picture, "frames", total, "payload_bytes", payload.length));
                sendAndWait(BikeProtocol.documentInit(picture, total, 1, 1, name), "document_init");
                Thread.sleep(150);
                for (int sequence = 1; sequence <= total; sequence++) {
                    int start = (sequence - 1) * chunkSize;
                    byte[] chunk = java.util.Arrays.copyOfRange(payload, start, Math.min(start + chunkSize, payload.length));
                    sendAndWait(BikeProtocol.documentChunk(sequence, chunk), "document_chunk_" + sequence);
                    final int percent = (sequence * 100) / total;
                    final int currentSequence = sequence;
                    mainHandler.post(() -> {
                        transferProgress = percent;
                        transferMessage = "Sending… " + currentSequence + " / " + total;
                        notifyTransferUpdated();
                    });
                    if (sequence % 20 == 0 && sequence < total) waitForAck(sequence, 1, 8);
                }
                sendAndWait(BikeProtocol.documentInit(picture, total, total, 3, name), "document_complete");
                if (waitForAck(total, 3, 8)) {
                    transferMessage = "Bike confirmed the document.";
                    log("document_transfer_completed", mapOf("acknowledged_frames", latestAckFrame, "status", latestAckStatus));
                    sendDocumentDisplayMessage(name);
                } else {
                    transferMessage = "Sent all image data; bike confirmation was not received.";
                    log("document_transfer_unconfirmed", mapOf("sent_frames", total));
                }
            } catch (Exception e) {
                transferMessage = "Transfer failed: " + e.getMessage();
                log("document_transfer_failed", mapOf("error", e.toString()));
            } finally {
                if (!bitmap.isRecycled()) bitmap.recycle();
                transferActive = false;
                notifyTransferUpdated();
            }
        });
    }

    private void sendDocumentDisplayMessage(String documentName) {
        if (welcomeClearTask != null) {
            mainHandler.removeCallbacks(welcomeClearTask);
            welcomeClearTask = null;
        }
        String shortName = new String(BikeProtocol.ascii(documentName, 10), java.nio.charset.StandardCharsets.US_ASCII);
        String displayText = shortName + " Done";
        try {
            // Marker 0x01 invokes the bike's personalized welcome line;
            // captured free-form text uses marker 0x41.
            sendAndWait(BikeProtocol.customTextFrame(displayText, 0x41), "document_uploaded_display_0x4C");
            String shownText = new String(BikeProtocol.ascii(displayText, 15), java.nio.charset.StandardCharsets.US_ASCII);
            log("document_upload_displayed", mapOf("text", shownText, "frame_id", "4C"));
            Thread.sleep(2000);
            // Match the captured clear frame: all-zero text body.
            sendAndWait(BikeProtocol.customTextFrame("", 0), "document_uploaded_display_clear_0x4C");
            log("document_upload_display_cleared", mapOf("frame_id", "4C"));
        } catch (Exception e) {
            log("document_upload_display_failed", mapOf("error", e.toString()));
        }
    }

    private boolean waitForAck(int frame, int status, int timeoutSeconds) throws InterruptedException, IOException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        synchronized (ackLock) {
            while (System.currentTimeMillis() < deadline) {
                if (latestAckFrame >= frame) {
                    if (latestAckStatus != status) throw new IOException("Bike rejected transfer (status " + latestAckStatus + ")");
                    return true;
                }
                ackLock.wait(100);
            }
        }
        if (status == 3) return false;
        throw new IOException("Timed out waiting for bike acknowledgement at chunk " + frame);
    }

    private void sendAndWait(byte[] data, String label) throws Exception {
        BluetoothGatt activeGatt = gatt;
        BluetoothGattCharacteristic characteristic = writeCharacteristic;
        if (!connected || activeGatt == null || characteristic == null) throw new IOException("Bike disconnected");
        if (data.length > negotiatedMtu - 3) {
            throw new IOException("BLE packet needs MTU " + (data.length + 3) + "; negotiated MTU is " + negotiatedMtu);
        }
        CountDownLatch latch = new CountDownLatch(1);
        synchronized (writeLock) {
            if (writeLatch != null) throw new IOException("Another GATT write is still pending");
            writeLatch = latch;
            lastWriteStatus = BluetoothGatt.GATT_FAILURE;
        }
        boolean started;
        try {
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            characteristic.setValue(data);
            started = activeGatt.writeCharacteristic(characteristic);
        } catch (SecurityException e) {
            started = false;
        }
        if (!started) {
            synchronized (writeLock) { writeLatch = null; }
            throw new IOException("BLE write could not start: " + label);
        }
        log("write_tx", mapOf("label", label, "length", data.length));
        boolean finished = latch.await(8, TimeUnit.SECONDS);
        synchronized (writeLock) { writeLatch = null; }
        if (!finished || lastWriteStatus != BluetoothGatt.GATT_SUCCESS) {
            throw new IOException("BLE write failed: " + label + " (status " + lastWriteStatus + ")");
        }
    }

    private void beginPhoneSession() {
        if (phoneSessionStarted) return;
        phoneSessionStarted = true;
        lastStatusWriteAt = System.currentTimeMillis();
        writes.execute(() -> {
            try {
                sendAndWait(BikeProtocol.textFrame(0x52, riderName, 17), "rider_name_0x52");
                if (!riderName.trim().isEmpty()) {
                    sendAndWait(BikeProtocol.welcomeFrame(riderName), "welcome_0x4C");
                    welcomeClearTask = () -> {
                        if (isConnected()) writes.execute(() -> {
                            try {
                                sendAndWait(BikeProtocol.customTextFrame("", 0), "welcome_display_clear_0x4C");
                                log("welcome_message_cleared", mapOf("frame_id", "4C", "after_seconds", 5));
                            } catch (Exception e) {
                                log("welcome_message_clear_failed", mapOf("error", e.toString()));
                            }
                        });
                        welcomeClearTask = null;
                    };
                    mainHandler.postDelayed(welcomeClearTask, 5000);
                }
                sendAndWait(BikeProtocol.mobileStatusFrame(readBatteryPercent()), "mobile_status_0x4A_initial");
                log("phone_status_cycle_started", mapOf("interval_seconds", 2, "signal_level", 3, "signal_is_mock", true));
            } catch (Exception e) {
                log("phone_session_write_failed", mapOf("error", e.toString()));
            }
        });
    }

    private void handleNotification(byte[] raw) {
        notificationCount++;
        int frameId = raw.length > 1 ? raw[1] & 0xFF : -1;
        logChangedNotification(raw, frameId);
        Map<String, String> fields = BikeProtocol.decodeNotification(raw);
        if (fields.containsKey("ackFrame")) {
            latestAckFrame = Integer.parseInt(fields.get("ackFrame"));
            latestAckStatus = Integer.parseInt(fields.get("ackStatus"));
            synchronized (ackLock) { ackLock.notifyAll(); }
            log("document_ack_received", mapOf("frame", latestAckFrame, "status", latestAckStatus));
            notifyTransferUpdated();
            return;
        }
        if (!fields.isEmpty()) telemetry.putAll(fields);
        if (raw.length >= 3 && raw[0] == 0x5A && (raw[1] & 0xFF) == 0x6B) {
            handleMusicCommand(raw[2] & 0xFF);
        }
        long now = System.currentTimeMillis();
        if (phoneSessionStarted && !transferActive && now - lastStatusWriteAt >= 2000) {
            lastStatusWriteAt = now;
            writes.execute(() -> {
                try { sendAndWait(BikeProtocol.mobileStatusFrame(readBatteryPercent()), "mobile_status_0x4A_periodic"); }
                catch (Exception e) { log("mobile_status_write_failed", mapOf("error", e.toString())); }
            });
        }
        notifyBikeUpdated();
    }

    private void logChangedNotification(byte[] raw, int frameId) {
        if (frameId < 0 || raw.length < 2) return;
        boolean sensitive = (raw[0] == 0x5A && frameId == 0x7D) || (raw[0] == 0x5B && frameId == 0x42);
        boolean plainMusic = raw[0] == 0x5A && frameId == 0x6B;
        boolean knownMasked = !plainMusic && !sensitive && raw.length >= 4
                && ((raw[0] == 0x5A && (frameId == 0x10 || frameId == 0x11 || frameId == 0x12 || frameId == 0x5F))
                || (raw[0] == 0x5A && frameId == 0x54));
        byte[] decoded = knownMasked ? BikeProtocol.unmask(raw) : raw.clone();
        byte[] signature = java.util.Arrays.copyOfRange(decoded, 2, Math.max(2, decoded.length - 1));
        byte[] previous;
        synchronized (lastLoggedPayloadByFrame) {
            previous = lastLoggedPayloadByFrame.get(frameId);
            if (java.util.Arrays.equals(previous, signature)) return;
            lastLoggedPayloadByFrame.put(frameId, signature);
        }

        ArrayList<Map<String, Object>> changedBytes = new ArrayList<>();
        for (int offset = 0; offset < signature.length; offset++) {
            if (previous != null && offset < previous.length && previous[offset] == signature[offset]) continue;
            int byteIndex = offset + 2;
            changedBytes.add(mapOf("byte_index", byteIndex,
                    "value", String.format(Locale.US, "%02X", signature[offset] & 0xFF),
                    "mapping", bikeByteMapping(frameId, byteIndex)));
        }

        Map<String, String> decodedFields = BikeProtocol.decodeNotification(raw);
        decodedFields.remove("frame");
        Map<String, Object> details = new HashMap<>();
        details.put("length", raw.length);
        details.put("frame_id", String.format(Locale.US, "0x%02X", frameId));
        details.put("wire_hex", sensitive ? "REDACTED_IDENTIFIER_FRAME" : BikeProtocol.hex(raw));
        details.put("decoded_hex", sensitive ? "REDACTED_IDENTIFIER_FRAME" : BikeProtocol.hex(decoded));
        details.put("changed_payload_bytes", sensitive ? new ArrayList<>() : changedBytes);
        details.put("mapped_values", sensitive ? new HashMap<>() : decodedFields);
        details.put("mapping_note", "Byte indexes are zero-based offsets in the decoded frame. Repeated identical payloads are suppressed; see per-byte mapping labels.");
        log("bike_data_changed", details);
    }

    private String bikeByteMapping(int frameId, int index) {
        if (frameId == 0x10) {
            switch (index) {
                case 2: return "speedKph";
                case 3: case 4: case 5: return "odometerKm, tenths of km";
                case 6: return "fuelPercent";
                case 7: return "averageSpeedKph";
                case 8: return "mileageRaw, scale unconfirmed";
                case 9: return "topSpeedKph";
                case 10: return "throttlePercent";
                case 11: return "switchStatusRaw and locationTagRaw alias";
                case 12: return "zeroTo60Seconds, tenths";
                case 13: return "averageMileageRaw, scale unconfirmed";
                case 14: case 15: return "tripFMeterRaw";
                case 16: case 17: return "engineRPM";
            }
        } else if (frameId == 0x11) {
            switch (index) {
                case 2: return "vehicleDirectionRaw; bit 0x10 = sideStandSensorStatus";
                case 3: return "vehicleState1Raw; bit 0x40 = sideStandStatus (set Down, clear Up)";
                case 4: return "serviceReminderRaw";
                case 5: return "gear";
                case 6: return "vehicleState2Raw and batteryVoltageRaw alias";
                case 7: return "softwareVersionRaw";
                case 8: return "milBlinkCodeRaw";
                case 9: return "vehicleModelRaw";
                case 10: return "vehicleDiagnosticsRaw";
                case 11: return "reserved";
                case 12: return "turnSignalCode: 0 Off, 1 Right, 2 Left, 3 Hazard";
                case 13: return "engineTemperatureRaw, tellTaleStatusRaw and reserve alias";
                case 14: return "screenMatrixRaw";
                case 15: return "vehicleState3Raw";
                case 16: return "absMilBlinkCodeRaw";
                case 17: return "rideModeCode low nibble; whole byte vehicleModeRaw";
            }
        } else if (frameId == 0x12) {
            switch (index) {
                case 2: return "leanAngleRaw";
                case 3: case 4: return "cruisingRangeRaw, scale unconfirmed";
                case 5: return "wheelAngleOffsetRaw";
                case 6: return "accelerationRaw";
                case 7: return "torqueRaw";
                case 8: case 9: return "Trip A distance, tenths of km";
                case 10: return "Trip A time hours";
                case 11: return "Trip A time minutes";
                case 12: return "tripMileageRaw, scale unconfirmed";
                case 13: case 14: return "tripFuelRaw, scale unconfirmed";
                case 15: return "overspeedThreshold, km/h";
                case 16: return "overspeedSettingRaw";
            }
        } else if (frameId == 0x6B && index == 2) {
            return "music command: 2 Down, 3 Enter, 4 Back";
        } else if (frameId == 0x5F) {
            return "unmapped payload; retained as raw hex";
        }
        return "not currently mapped by the app";
    }

    private void handleMusicCommand(int command) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (command == lastMusicCommand && now - lastMusicCommandAt <= 100) {
            log("music_command_duplicate_ignored", mapOf("bike_command", command));
            return;
        }
        lastMusicCommand = command;
        lastMusicCommandAt = now;
        String action = getMusicAction(command);
        if (action.equals("none")) {
            log("music_command_unmapped", mapOf("bike_command", command));
            return;
        }
        int keyCode;
        switch (action) {
            case "next": keyCode = KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "previous": keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            case "play": keyCode = KeyEvent.KEYCODE_MEDIA_PLAY; break;
            case "pause": keyCode = KeyEvent.KEYCODE_MEDIA_PAUSE; break;
            case "toggle": keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; break;
            case "volumeUp": adjustMediaVolume(AudioManager.ADJUST_RAISE, command, action); return;
            case "volumeDown": adjustMediaVolume(AudioManager.ADJUST_LOWER, command, action); return;
            default:
                log("music_command_invalid_mapping", mapOf("bike_command", command, "action", action));
                return;
        }
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null) {
            log("music_command_failed", mapOf("reason", "audio_service_unavailable"));
            return;
        }
        long eventTime = android.os.SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(android.os.SystemClock.uptimeMillis(), eventTime, KeyEvent.ACTION_UP, keyCode, 0));
        log("music_command_action", mapOf("bike_command", command, "action", action, "key_code", keyCode));
    }

    private void adjustMediaVolume(int direction, int command, String action) {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null) {
            log("music_command_failed", mapOf("reason", "audio_service_unavailable"));
            return;
        }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        log("music_command_action", mapOf("bike_command", command, "action", action));
    }

    private int readBatteryPercent() {
        try {
            BatteryManager batteryManager = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int level = batteryManager == null ? -1 : batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return level >= 0 ? level : 80;
        } catch (Exception e) { return 80; }
    }

    private void startScan() {
        if (!wantsConnection || !hasBluetoothPermissions()) return;
        if (adapter == null || !adapter.isEnabled()) {
            connectionMessage = "Turn on Bluetooth to connect to the bike";
            notifyBikeUpdated();
            return;
        }
        if ((connected || gatt != null) && !manualScanning) return;
        if (scanning) return;
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) return;
            ScanFilter filter = new ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(BikeProtocol.SERVICE_UUID)).build();
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(java.util.Collections.singletonList(filter), settings, scanCallback);
            scanning = true;
            connectionMessage = preferredAddress.isEmpty() ? "Scanning for your TVS bike…" : "Scanning for " + preferredName + "…";
            log("scan_started", mapOf("service_filter", BikeProtocol.SERVICE_UUID));
            notifyBikeUpdated();
        } catch (SecurityException e) {
            connectionMessage = "Allow Nearby devices permission to scan";
            notifyBikeUpdated();
        } catch (Exception e) {
            log("scan_start_failed", mapOf("error", e.toString()));
        }
    }

    private void stopScan() {
        if (scanner != null && scanning && hasBluetoothPermissions()) {
            try { scanner.stopScan(scanCallback); } catch (SecurityException ignored) { }
        }
        scanning = false;
    }

    private void connect(BluetoothDevice device, String name) {
        manualScanning = false;
        stopScan();
        if (!hasBluetoothPermissions()) return;
        connectedName = name;
        connectedAddress = device.getAddress();
        connectionMessage = "Connecting to " + name + "…";
        telemetry.clear();
        synchronized (lastLoggedPayloadByFrame) { lastLoggedPayloadByFrame.clear(); }
        notificationCount = 0;
        notifyBikeUpdated();
        log("connect_requested", mapOf("name", name));
        try {
            if (Build.VERSION.SDK_INT >= 23) gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            else gatt = device.connectGatt(this, false, gattCallback);
        } catch (SecurityException e) {
            connectionMessage = "Bluetooth permission was denied";
            notifyBikeUpdated();
        }
    }

    private void rememberDevice(DeviceInfo candidate) {
        synchronized (devices) {
            for (int i = 0; i < devices.size(); i++) {
                if (devices.get(i).address.equals(candidate.address)) { devices.set(i, candidate); notifyDevicesUpdated(); return; }
            }
            devices.add(candidate);
        }
        notifyDevicesUpdated();
    }

    private void closeGatt(BluetoothGatt oldGatt) {
        try {
            if (hasBluetoothPermissions()) {
                oldGatt.disconnect();
                oldGatt.close();
            }
        } catch (Exception ignored) { }
        if (gatt == oldGatt) gatt = null;
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startOngoingNotification() {
        Notification.Builder builder = new Notification.Builder(this, "bike_connection")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("RTR Link")
                .setContentText(transferActive ? transferMessage : (connected ? "Connected to " + connectedName : "Connecting to your bike"))
                .setOngoing(true);
        if (transferActive) builder.setProgress(100, transferProgress, false);
        Notification notification = builder.build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTIFICATION_ID, notification);
        } catch (Exception e) { log("foreground_start_failed", mapOf("error", e.toString())); }
    }

    private void updateTransferNotification() {
        if (Build.VERSION.SDK_INT < 26) return;
        String status = transferActive ? transferMessage
                : (!transferMessage.isEmpty() ? transferMessage
                : (connected ? "Connected to " + connectedName : "Connecting to your bike"));
        Notification.Builder builder = new Notification.Builder(this, "bike_connection")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(transferActive ? "Sending document to bike" : "RTR Link")
                .setContentText(status)
                .setOngoing(transferActive);
        if (transferActive) builder.setProgress(100, transferProgress, false);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, builder.build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel("bike_connection", "Bike connection", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private SharedPreferences getPrefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private void startSessionLog() {
        try {
            File root = new File(getExternalFilesDir(null), "logs");
            if (!root.exists() && !root.mkdirs()) return;
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            File file = new File(root, "ble_session_" + stamp + ".jsonl");
            sessionWriter = new FileWriter(file, true);
            sessionLogPath = file.getAbsolutePath();
            log("session_started", mapOf("app", "RTR Link Android", "phone_to_bike_writes_enabled", true));
        } catch (IOException e) { Log.w(TAG, "Could not create session log", e); }
    }

    private void log(String event, Map<String, Object> details) {
        try {
            JSONObject record = new JSONObject();
            record.put("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date()));
            record.put("event", event);
            for (Map.Entry<String, Object> entry : details.entrySet()) record.put(entry.getKey(), entry.getValue());
            String json = record.toString();
            Log.i(TAG, json);
            synchronized (this) { if (sessionWriter != null) { sessionWriter.write(json); sessionWriter.write("\n"); sessionWriter.flush(); } }
        } catch (Exception e) { Log.w(TAG, "Could not write event", e); }
    }

    private static Map<String, Object> mapOf(Object... values) {
        java.util.HashMap<String, Object> map = new java.util.HashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) map.put(String.valueOf(values[i]), values[i + 1]);
        return map;
    }

    private void notifyBikeUpdated() { mainHandler.post(() -> listeners.forEach(Listener::onBikeUpdated)); }
    private void notifyDevicesUpdated() { mainHandler.post(() -> listeners.forEach(Listener::onDevicesUpdated)); }
    private void notifyTransferUpdated() {
        mainHandler.post(() -> {
            int progress = transferProgress;
            if (!transferActive || progress == 100 || lastTransferNotificationProgress < 0 || progress - lastTransferNotificationProgress >= 5) {
                updateTransferNotification();
                lastTransferNotificationProgress = progress;
            }
            listeners.forEach(Listener::onTransferUpdated);
        });
    }
}
