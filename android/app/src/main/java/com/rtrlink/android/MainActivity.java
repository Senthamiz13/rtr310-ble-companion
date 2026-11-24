package com.rtrlink.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MainActivity extends Activity implements BikeBleService.Listener {
    private static final int REQUEST_PERMISSIONS = 20;
    private static final int REQUEST_IMAGE = 21;
    private static final int ORANGE = Color.rgb(233, 89, 55);
    private static final int LIME = Color.rgb(200, 241, 105);
    private static final int PANEL = Color.rgb(14, 16, 18);
    private static final int BORDER = Color.rgb(40, 43, 46);
    private static final String DOCS_PREFS = "rtr_link_documents";
    private static final String DOCS_KEY = "saved_documents";

    private BikeBleService bike;
    private boolean bound;
    private boolean binding;
    private boolean showPickerWhenDiscovered;
    private boolean showingDocs;
    private boolean showingSettings;
    private EditText riderNameInput;
    private LinearLayout settingsDevices;
    private LinearLayout content;
    private TextView connectionText;
    private TextView connectionTitle;
    private TextView notificationText;
    private Button connectionAction;
    private TextView docsMessage;
    private TextView docsCount;
    private ProgressBar transferProgress;
    private Spinner slotSpinner;
    private final Map<Integer, String> musicActionDrafts = new HashMap<>();
    private EditText markerInput;
    private LinearLayout documentsContainer;
    private final Map<String, TextView> metricValues = new HashMap<>();
    private final Map<String, TextView> rawDataValues = new HashMap<>();
    private final List<SavedDocument> documents = new ArrayList<>();
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            bike = ((BikeBleService.LocalBinder) service).getService();
            bound = true;
            binding = false;
            bike.addListener(MainActivity.this);
            refreshAll();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            if (bike != null) bike.removeListener(MainActivity.this);
            bike = null;
            bound = false;
            binding = false;
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        loadDocuments();
        buildScreen();
        requestPermissionsAndStart();
    }

    @Override protected void onStart() {
        super.onStart();
        if (hasPermissions()) startConnectionService();
    }

    @Override protected void onStop() {
        if (bound || binding) {
            if (bike != null) bike.removeListener(this);
            unbindService(serviceConnection);
            bound = false;
            binding = false;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (markerInput != null) markerInput.clearFocus();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (showingDocs || showingSettings) {
            showingDocs = false;
            showingSettings = false;
            buildScreen();
            return;
        }
        super.onBackPressed();
    }

    private void requestPermissionsAndStart() {
        List<String> required = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) required.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) required.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            required.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            required.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (required.isEmpty()) startConnectionService();
        else requestPermissions(required.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (hasPermissions()) startConnectionService();
            else showToast("Nearby devices permission is needed to connect to the bike.");
        }
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startConnectionService() {
        if (!hasPermissions()) return;
        Intent intent = new Intent(this, BikeBleService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        if (!bound && !binding) {
            binding = true;
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        }
    }

    private void buildScreen() {
        metricValues.clear();
        rawDataValues.clear();
        musicActionDrafts.clear();
        docsMessage = null;
        settingsDevices = null;
        transferProgress = null;
        documentsContainer = null;
        slotSpinner = null;
        docsCount = null;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.BLACK);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(28));
        scroll.addView(content);
        setContentView(scroll);

        if (showingDocs) buildDocsPage();
        else if (showingSettings) buildSettingsPage();
        else buildHomePage();
        refreshAll();
    }

    private void buildHomePage() {
        LinearLayout top = row();
        TextView settings = text("⚙  Settings", 14, ORANGE, true);
        settings.setGravity(Gravity.CENTER_VERTICAL);
        settings.setOnClickListener(v -> openSettings());
        top.addView(settings, new LinearLayout.LayoutParams(-2, dp(44)));
        content.addView(top);
        addBrandCard();
        addConnectionCard();
        addTelemetryGrid();
        addAdditionalTelemetry();
        addDigiDocsLink();
    }

    private void buildDocsPage() {
        LinearLayout top = row();
        Button back = button("‹  Home");
        back.setOnClickListener(v -> { showingDocs = false; buildScreen(); });
        top.addView(back);
        TextView title = text("Digi Docs", 18, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        View spacer = new View(this);
        top.addView(spacer, new LinearLayout.LayoutParams(dp(80), 1));
        content.addView(top);
        buildDocumentsSection();
    }

    private void buildSettingsPage() {
        LinearLayout top = row();
        TextView back = text("‹  Back", 14, ORANGE, true);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setOnClickListener(v -> { showingSettings = false; buildScreen(); });
        top.addView(back, new LinearLayout.LayoutParams(dp(80), dp(48)));
        TextView title = text("Settings", 18, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView save = text("Save", 14, ORANGE, true);
        save.setGravity(Gravity.CENTER | Gravity.RIGHT);
        save.setOnClickListener(v -> {
            if (bike != null) {
                if (riderNameInput != null) bike.setRiderName(riderNameInput.getText().toString());
                for (Map.Entry<Integer, String> entry : musicActionDrafts.entrySet()) {
                    bike.setMusicAction(entry.getKey(), entry.getValue());
                }
            }
            showingSettings = false;
            buildScreen();
        });
        top.addView(save, new LinearLayout.LayoutParams(dp(60), dp(48)));
        content.addView(top);

        addSectionLabel("BIKE");
        LinearLayout bikeCard = card();
        bikeCard.addView(text(bike == null || bike.getPreferredName().isEmpty() ? "No bike selected" : bike.getPreferredName(), 15, Color.WHITE, true));
        bikeCard.addView(text(bike == null ? "The app will connect to a TVS bike it discovers." : bike.getConnectionMessage(), 12, Color.LTGRAY, false));
        Button scan = button("Scan for bikes");
        scan.setOnClickListener(v -> {
            if (bike != null) bike.scanNow();
            else requestPermissionsAndStart();
        });
        bikeCard.addView(scan);
        settingsDevices = new LinearLayout(this);
        settingsDevices.setOrientation(LinearLayout.VERTICAL);
        bikeCard.addView(settingsDevices);
        if (bike != null && !bike.getPreferredAddress().isEmpty()) {
            TextView forget = text("Forget configured bike", 13, ORANGE, true);
            forget.setPadding(0, dp(10), 0, dp(4));
            forget.setOnClickListener(v -> {
                bike.forgetPreferredBike();
                buildScreen();
            });
            bikeCard.addView(forget);
        }
        content.addView(bikeCard);

        addSectionLabel("RIDER");
        LinearLayout riderCard = card();
        riderNameInput = new EditText(this);
        riderNameInput.setSingleLine(true);
        riderNameInput.setHint("Rider name");
        riderNameInput.setHintTextColor(Color.GRAY);
        riderNameInput.setTextColor(Color.WHITE);
        riderNameInput.setText(bike == null ? "" : bike.getRiderName());
        riderNameInput.setTextSize(15);
        styleInput(riderNameInput);
        riderCard.addView(riderNameInput, new LinearLayout.LayoutParams(-1, dp(50)));
        riderCard.addView(text("Used for the bike’s rider-name frame and welcome message. The bike name updates at the next connection. Display text supports up to 15 characters.", 12, Color.LTGRAY, false));
        content.addView(riderCard);

        addSectionLabel("D-PAD + VOLUME MAPPING");
        LinearLayout musicCard = card();
        musicCard.addView(text("Tap a detected switch to choose its double-press phone action.", 13, Color.LTGRAY, false));
        LinearLayout dpad = new LinearLayout(this);
        dpad.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams dpadParams = new LinearLayout.LayoutParams(-1, -2);
        dpadParams.topMargin = dp(12);
        dpadParams.bottomMargin = dp(12);
        musicCard.addView(dpad, dpadParams);
        addDpadRow(dpad, emptyDpadCell(), unavailableDpadTile("↑"), emptyDpadCell());
        addDpadRow(dpad, musicActionTile(4, "BACK", "↶", "previous"), emptyDpadCell(), musicActionTile(3, "ENT", "ENT", "next"));
        addDpadRow(dpad, emptyDpadCell(), musicActionTile(2, "DOWN", "↓", "pause"), emptyDpadCell());
        TextView musicNote = text("Only Enter, Back, and Down events are mapped from current captures. Android can change media volume; iOS does not allow apps to change system volume programmatically. Duplicate BLE notifications are combined.", 12, Color.LTGRAY, false);
        LinearLayout.LayoutParams musicNoteParams = new LinearLayout.LayoutParams(-1, -2);
        musicCard.addView(musicNote, musicNoteParams);
        content.addView(musicCard);
        if (bike != null) {
            addSectionLabel("SESSION LOG");
            LinearLayout logCard = card();
            logCard.addView(text(bike.getSessionLogPath(), 11, Color.LTGRAY, false));
            content.addView(logCard);
        }
        renderSettingsDevices();
    }

    private View emptyDpadCell() {
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, dp(78), 1));
        return spacer;
    }

    private View unavailableDpadTile(String icon) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(4), dp(4), dp(4), dp(4));
        tile.setBackground(roundDrawable(Color.rgb(8, 9, 10), 20));
        tile.setAlpha(0.65f);
        TextView symbol = text(icon, 20, Color.GRAY, true);
        TextView hint = text("Not mapped", 9, Color.GRAY, false);
        symbol.setGravity(Gravity.CENTER);
        hint.setGravity(Gravity.CENTER);
        tile.addView(symbol);
        tile.addView(hint);
        return tile;
    }

    private View musicActionTile(int command, String title, String icon, String defaultAction) {
        String action = bike == null ? defaultAction : bike.getMusicAction(command);
        musicActionDrafts.put(command, action);
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable background = new GradientDrawable();
        background.setColor(PANEL);
        background.setCornerRadius(dp(20));
        background.setStroke(dp(2), ORANGE);
        tile.setBackground(background);
        TextView keyLabel = text(icon, icon.equals("ENT") ? 16 : 20, Color.WHITE, true);
        TextView actionLabel = text(musicActionLabel(action), 9, Color.LTGRAY, false);
        keyLabel.setGravity(Gravity.CENTER);
        actionLabel.setGravity(Gravity.CENTER);
        actionLabel.setSingleLine(true);
        tile.addView(keyLabel);
        tile.addView(actionLabel);
        tile.setOnClickListener(v -> {
            String[] labels = {"No action", "Next track", "Previous track", "Play", "Pause", "Play / pause", "Volume up", "Volume down"};
            String[] values = {"none", "next", "previous", "play", "pause", "toggle", "volumeUp", "volumeDown"};
            new AlertDialog.Builder(this)
                    .setTitle(title + " action")
                    .setItems(labels, (dialog, which) -> {
                        String selected = values[which];
                        musicActionDrafts.put(command, selected);
                        if (bike != null) bike.setMusicAction(command, selected);
                        actionLabel.setText(musicActionLabel(selected));
                    })
                    .show();
        });
        return tile;
    }

    private void addDpadRow(LinearLayout parent, View first, View second, View third) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        View[] cells = {first, second, third};
        for (int i = 0; i < cells.length; i++) {
            View cell = cells[i];
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(78), 1);
            if (i > 0) params.leftMargin = dp(6);
            row.addView(cell, params);
        }
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        if (parent.getChildCount() > 0) rowParams.topMargin = dp(8);
        parent.addView(row, rowParams);
    }

    private String musicActionLabel(String value) {
        switch (value) {
            case "next": return "Next track";
            case "previous": return "Previous track";
            case "play": return "Play";
            case "pause": return "Pause";
            case "toggle": return "Play / pause";
            case "volumeUp": return "Volume up";
            case "volumeDown": return "Volume down";
            default: return "No action";
        }
    }

    private void renderSettingsDevices() {
        if (settingsDevices == null || bike == null) return;
        settingsDevices.removeAllViews();
        List<BikeBleService.DeviceInfo> nearby = bike.getDevices();
        if (nearby.isEmpty()) {
            settingsDevices.addView(text("Keep the bike nearby with ignition on while scanning.", 11, Color.GRAY, false));
            return;
        }
        for (BikeBleService.DeviceInfo device : nearby) {
            TextView entry = text(device.name + "\n" + device.rssi + " dBm", 13, Color.WHITE, false);
            entry.setPadding(dp(8), dp(10), dp(8), dp(10));
            entry.setOnClickListener(v -> bike.selectBike(device));
            settingsDevices.addView(entry);
        }
    }

    private void addBrandCard() {
        LinearLayout brand = row();
        brand.setPadding(dp(14), dp(16), dp(14), dp(16));
        ImageView bikeImage = new ImageView(this);
        bikeImage.setImageResource(R.drawable.apache_rtr_310);
        bikeImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        brand.addView(bikeImage, new LinearLayout.LayoutParams(dp(132), dp(90)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = text("RTR Link", 17, Color.WHITE, true);
        TextView description = text("Independent Bluetooth companion", 12, Color.LTGRAY, false);
        TextView fan = text("Fan-made · Open source", 11, LIME, true);
        TextView disclaimer = text("Does not replace TVS app · Use at your own risk", 11, Color.GRAY, false);
        disclaimer.setLineSpacing(dp(2), 1.0f);
        labels.addView(name); addGap(labels, 6); labels.addView(description); addGap(labels, 6); labels.addView(fan); addGap(labels, 6); labels.addView(disclaimer);
        brand.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        styleCard(brand, 22);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(160));
        params.bottomMargin = dp(18);
        content.addView(brand, params);
    }

    private void addConnectionCard() {
        LinearLayout statusCard = card();
        LinearLayout titleRow = row();
        TextView icon = text("◉", 18, LIME, true);
        titleRow.addView(icon);
        connectionTitle = text("Not connected", 16, Color.WHITE, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, dp(32), 1);
        titleParams.leftMargin = dp(8);
        titleRow.addView(connectionTitle, titleParams);
        connectionAction = button("Disconnect");
        connectionAction.setVisibility(View.GONE);
        connectionAction.setOnClickListener(v -> { if (bike != null) bike.disconnect(); });
        titleRow.addView(connectionAction);
        statusCard.addView(titleRow);
        connectionText = text("Keep the bike nearby with ignition on.", 13, Color.LTGRAY, false);
        addGap(statusCard, 4);
        statusCard.addView(connectionText);
        notificationText = text("No telemetry yet", 11, Color.GRAY, false);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.topMargin = dp(6);
        statusCard.addView(notificationText, noteParams);
        content.addView(statusCard);
    }

    private void addTelemetryGrid() {
        addSectionLabel("RIDE OVERVIEW");
        String[][] tiles = {
                {"speedKph", "Speed", "km/h", "speedometer"}, {"engineRPM", "Engine", "rpm", "engine"},
                {"odometerKm", "Odometer", "km", "road"}, {"fuelPercent", "Fuel", "%", "fuel"},
                {"gear", "Gear", "", "gear"}, {"rideModeCode", "Riding mode", "", "mode"},
                {"turnSignalCode", "Indicators", "", "signal"}, {"throttlePercent", "Throttle", "%", "bolt"},
                {"averageSpeedKph", "Average speed", "km/h", "chart"}, {"zeroTo60Seconds", "0–60", "s", "timer"},
                {"topSpeedKph", "Top speed", "km/h", "gauge"}, {"sideStandStatus", "Side stand", "", "stand"}
        };
        for (int i = 0; i < tiles.length; i += 2) {
            LinearLayout tileRow = row();
            addTelemetryTile(tileRow, tiles[i]);
            if (i + 1 < tiles.length) addTelemetryTile(tileRow, tiles[i + 1]);
            else tileRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(12);
            content.addView(tileRow, params);
        }
    }

    private void addTelemetryTile(LinearLayout parent, String[] spec) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView label = text(telemetrySymbol(spec[3]) + "  " + spec[1], 12, Color.LTGRAY, false);
        TextView value = text("—", 22, Color.WHITE, true);
        value.setSingleLine(true);
        value.setGravity(Gravity.CENTER_VERTICAL);
        value.setTag("");
        metricValues.put(spec[0], value);
        tile.addView(label);
        LinearLayout valueLine = row();
        valueLine.addView(value, new LinearLayout.LayoutParams(0, dp(36), 1));
        TextView unit = text(spec[2].isEmpty() ? "" : spec[2], 11, Color.GRAY, false);
        valueLine.addView(unit);
        tile.addView(valueLine);
        styleCard(tile, 14);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(92), 1);
        if (parent.getChildCount() > 0) params.leftMargin = dp(10);
        parent.addView(tile, params);
    }

    private void addActionMarkerCard(LinearLayout parent) {
        TextView heading = text("MARK AN ACTION", 14, Color.WHITE, true);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
        headingParams.topMargin = dp(16);
        headingParams.bottomMargin = dp(6);
        parent.addView(heading, headingParams);
        LinearLayout markerCard = card();
        markerCard.addView(text("Add a timestamped note to the BLE log just before or after you act on the bike.", 12, Color.LTGRAY, false));
        LinearLayout inputRow = row();
        markerInput = new EditText(this);
        markerInput.setSingleLine(true);
        markerInput.setHint("e.g. Pressed D-pad right");
        markerInput.setHintTextColor(Color.GRAY);
        markerInput.setTextColor(Color.WHITE);
        styleInput(markerInput);
        inputRow.addView(markerInput, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button mark = button("Log");
        mark.setOnClickListener(v -> {
            String action = markerInput.getText().toString().trim();
            if (action.isEmpty()) return;
            if (bike != null) bike.markAction(action);
            markerInput.setText("");
            showToast("Action saved to BLE session log");
        });
        inputRow.addView(mark);
        markerCard.addView(inputRow);
        parent.addView(markerCard);
    }

    private void addAdditionalTelemetry() {
        addSectionLabel("CONFIRMED BIKE DATA");
        String[][] confirmed = {
                {"confirmedTripDistance", "Trip A", "km", "road"},
                {"confirmedTripTime", "Trip time", "", "timer"},
                {"confirmedOverspeed", "Overspeed limit", "km/h", "speedometer"}
        };
        for (int i = 0; i < confirmed.length; i += 2) {
            LinearLayout tileRow = row();
            addTelemetryTile(tileRow, confirmed[i]);
            if (i + 1 < confirmed.length) addTelemetryTile(tileRow, confirmed[i + 1]);
            else tileRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(12);
            content.addView(tileRow, params);
        }

        LinearLayout accordion = card();
        LinearLayout header = row();
        TextView title = text("Other bike data & log notes", 15, Color.WHITE, true);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1));
        TextView chevron = text("⌄", 20, ORANGE, true);
        header.addView(chevron, new LinearLayout.LayoutParams(dp(30), dp(42)));
        accordion.addView(header);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        addGap(body, 6);
        body.addView(text("Raw and provisional fields are shown for investigation. A label may be known while its unit or meaning is not.", 12, Color.LTGRAY, false));
        addRawDataSection(body, "0x10 · Additional ride fields", new String[][] {
                {"Mileage (raw)", "mileageRaw", "raw"},
                {"Switch status / location tag (same byte)", "switchStatusRaw", "hex"},
                {"Average mileage (raw)", "averageMileageRaw", "raw"},
                {"Trip-F meter (raw)", "tripFMeterRaw", "raw"},
                {"Checksum", "", "Known frame checksum; not exposed as telemetry"}
        });
        addRawDataSection(body, "0x11 · Vehicle state and diagnostics", new String[][] {
                {"Side-stand sensor bit", "sideStandSensorStatus", "bool"},
                {"Vehicle direction byte", "vehicleDirectionRaw", "hex"},
                {"Vehicle state 1", "vehicleState1Raw", "hex"},
                {"Service reminder", "serviceReminderRaw", "hex"},
                {"Vehicle state 2 / battery field (same byte)", "vehicleState2Raw", "hex"},
                {"Software version field", "softwareVersionRaw", "hex"},
                {"MIL blink code", "milBlinkCodeRaw", "hex"},
                {"Vehicle model", "vehicleModelRaw", "hex"},
                {"Vehicle diagnostics", "vehicleDiagnosticsRaw", "hex"},
                {"Reserved byte", "reserve3Raw", "hex"},
                {"Engine temperature / tell-tale / reserved (same byte)", "engineTemperatureRaw", "hex"},
                {"Screen matrix", "screenMatrixRaw", "hex"},
                {"Vehicle state 3", "vehicleState3Raw", "hex"},
                {"ABS MIL blink code", "absMilBlinkCodeRaw", "hex"},
                {"Full ride-mode byte (low nibble is mode)", "vehicleModeRaw", "hex"},
                {"Checksum", "", "Known frame checksum; not exposed as telemetry"}
        });
        addRawDataSection(body, "0x12 · Additional ride statistics", new String[][] {
                {"Lean angle (raw)", "leanAngleRaw", "raw"},
                {"Cruising range (raw)", "cruisingRangeRaw", "raw"},
                {"Wheel-angle offset (raw)", "wheelAngleOffsetRaw", "raw"},
                {"Acceleration (raw)", "accelerationRaw", "raw"},
                {"Torque (raw)", "torqueRaw", "raw"},
                {"Ride mileage (raw)", "rideMileageRaw", "raw"},
                {"Ride fuel (raw)", "rideFuelRaw", "raw"},
                {"Overspeed setting (raw)", "overspeedSettingRaw", "raw"},
                {"Reserved byte", "", "00 observed; meaning unassigned"},
                {"Checksum", "", "Known frame checksum; not exposed as telemetry"}
        });
        addRawDataSection(body, "0x5F · Unmapped payload", new String[][] {
                {"Payload hex", "unknown5FPayloadHex", "raw"},
                {"Meaning", "", "Unknown; compare with marked actions in the BLE log"},
                {"0x6B · D-pad events", "", "Commands 2/3/4 map to Down/Enter/Back; event values are in the BLE log"},
                {"0x7D / 0x42 identity frames", "", "Contents are redacted in logs; not decoded here"}
        });
        addActionMarkerCard(body);
        accordion.addView(body);
        header.setOnClickListener(v -> {
            boolean expanded = body.getVisibility() != View.VISIBLE;
            body.setVisibility(expanded ? View.VISIBLE : View.GONE);
            chevron.setText(expanded ? "⌃" : "⌄");
        });
        LinearLayout.LayoutParams accordionParams = new LinearLayout.LayoutParams(-1, -2);
        accordionParams.bottomMargin = dp(12);
        content.addView(accordion, accordionParams);
    }

    private void addRawDataSection(LinearLayout parent, String title, String[][] rows) {
        TextView heading = text(title, 14, Color.WHITE, true);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
        headingParams.topMargin = dp(14);
        headingParams.bottomMargin = dp(4);
        parent.addView(heading, headingParams);
        for (String[] spec : rows) {
            LinearLayout line = row();
            line.setPadding(0, dp(4), 0, dp(4));
            TextView label = text(spec[0], 12, Color.LTGRAY, false);
            TextView value = text("—", 12, Color.GRAY, false);
            label.setMinHeight(dp(38));
            value.setMinHeight(dp(38));
            value.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            line.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(value, new LinearLayout.LayoutParams(dp(150), -2));
            parent.addView(line);
            if (!spec[1].isEmpty()) rawDataValues.put(spec[1], value);
            else value.setText(spec[2]);
        }
    }

    private void addDigiDocsLink() {
        LinearLayout link = row();
        link.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text("▣", 25, ORANGE, true);
        icon.setGravity(Gravity.CENTER);
        link.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("Digi Docs", 15, Color.WHITE, true));
        docsCount = text(documents.size() + " saved on this phone", 12, Color.LTGRAY, false);
        labels.addView(docsCount);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelsParams.leftMargin = dp(10);
        link.addView(labels, labelsParams);
        link.addView(text("›", 22, Color.GRAY, false));
        styleCard(link, 16);
        link.setOnClickListener(v -> { showingDocs = true; buildScreen(); });
        content.addView(link);
    }

    private void buildDocumentsSection() {
        addSectionLabel("DOCUMENT");
        LinearLayout documentCard = card();
        TextView explainer = text("Choose a photo for the bike display. It is resized to the 5:3 transfer canvas.", 12, Color.LTGRAY, false);
        documentCard.addView(explainer);
        Button add = button("Choose document image");
        add.setOnClickListener(v -> chooseImage());
        documentCard.addView(add);
        content.addView(documentCard);

        addSectionLabel("BIKE TRANSFER");
        LinearLayout transferCard = card();
        transferCard.addView(text("Connect to the bike before uploading.", 12, Color.LTGRAY, false));
        slotSpinner = new Spinner(this);
        ArrayAdapter<String> slots = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                Arrays.asList("Slot 1", "Slot 2", "Slot 3"));
        slots.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        slotSpinner.setAdapter(slots);
        slotSpinner.setBackground(roundDrawable(PANEL, 10));
        slotSpinner.setPadding(dp(10), 0, dp(10), 0);
        transferCard.addView(slotSpinner);
        transferProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        transferProgress.setMax(100);
        transferProgress.setVisibility(View.GONE);
        transferCard.addView(transferProgress, new LinearLayout.LayoutParams(-1, dp(6)));
        docsMessage = text("", 12, Color.LTGRAY, false);
        transferCard.addView(docsMessage);
        content.addView(transferCard);

        addSectionLabel("SAVED DOCUMENTS");
        LinearLayout savedCard = card();
        documentsContainer = new LinearLayout(this);
        documentsContainer.setOrientation(LinearLayout.VERTICAL);
        savedCard.addView(documentsContainer);
        content.addView(savedCard);
        refreshDocuments();
    }

    private void addMetric(String key, String label, String unit) {
        LinearLayout row = card();
        TextView name = text(label, 13, Color.LTGRAY, false);
        TextView value = text("—", 14, Color.WHITE, true);
        value.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(name, new LinearLayout.LayoutParams(0, dp(34), 1));
        row.addView(value, new LinearLayout.LayoutParams(dp(220), dp(34)));
        if (!unit.isEmpty()) value.setTag(unit);
        metricValues.put(key, value);
        content.addView(row);
    }

    private void addStaticRow(String label, String value) {
        LinearLayout row = card();
        TextView left = text(label, 13, Color.LTGRAY, false);
        TextView right = text(value, 12, Color.GRAY, false);
        right.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(left, new LinearLayout.LayoutParams(0, dp(34), 1));
        row.addView(right, new LinearLayout.LayoutParams(dp(220), dp(34)));
        content.addView(row);
    }

    private void openSettings() {
        showingSettings = true;
        buildScreen();
    }

    private void showBikePicker() {
        if (bike == null) return;
        List<BikeBleService.DeviceInfo> nearby = bike.getDevices();
        if (nearby.isEmpty()) {
            showPickerWhenDiscovered = true;
            bike.scanNow();
            showToast("Scanning for TVS bikes…");
            return;
        }
        String[] names = new String[nearby.size()];
        for (int i = 0; i < nearby.size(); i++) names[i] = nearby.get(i).name + "  ·  " + nearby.get(i).rssi + " dBm";
        new AlertDialog.Builder(this).setTitle("Choose bike")
                .setItems(names, (dialog, which) -> bike.selectBike(nearby.get(which)))
                .setNegativeButton("Close", null).show();
    }

    private void chooseImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_IMAGE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            Bitmap bitmap = decodeImage(uri);
            if (bitmap == null) throw new IllegalArgumentException("Image could not be decoded");
            String suggested = queryDisplayName(uri);
            String dotName = suggested.lastIndexOf('.') > 0 ? suggested.substring(0, suggested.lastIndexOf('.')) : suggested;
            EditText name = new EditText(this);
            name.setText(dotName);
            name.setSingleLine(true);
            CropImageView crop = new CropImageView(bitmap);
            LinearLayout form = new LinearLayout(this);
            form.setOrientation(LinearLayout.VERTICAL);
            form.setPadding(dp(20), dp(4), dp(20), dp(12));
            form.addView(text("Fit the document inside the 5:3 frame. Pinch to zoom and drag to position it.", 12, Color.LTGRAY, false));
            LinearLayout.LayoutParams cropParams = new LinearLayout.LayoutParams(-1, -2);
            cropParams.topMargin = dp(12);
            cropParams.bottomMargin = dp(12);
            form.addView(crop, cropParams);
            form.addView(name, new LinearLayout.LayoutParams(-1, dp(50)));
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Crop bike document")
                    .setView(form).setNegativeButton("Cancel", (d, w) -> bitmap.recycle())
                    .setPositiveButton("Save cropped document", null).create();
            dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    Bitmap cropped = crop.createCroppedBitmap();
                    if (!bitmap.isRecycled()) bitmap.recycle();
                    saveDocument(cropped, name.getText().toString());
                    dialog.dismiss();
                } catch (Exception e) { showToast("Could not crop image: " + e.getMessage()); }
            }));
            dialog.show();
        } catch (Exception e) { showToast("Could not open image: " + e.getMessage()); }
    }

    private Bitmap decodeImage(Uri uri) throws Exception {
        ContentResolver resolver = getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(uri)) { BitmapFactory.decodeStream(input, null, bounds); }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / opts.inSampleSize > 2400 || bounds.outHeight / opts.inSampleSize > 2400) opts.inSampleSize *= 2;
        Bitmap bitmap;
        try (InputStream input = resolver.openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(input, null, opts); }
        if (bitmap == null) return null;
        try (InputStream input = resolver.openInputStream(uri)) {
            android.media.ExifInterface exif = new android.media.ExifInterface(input);
            int orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
            android.graphics.Matrix transform = new android.graphics.Matrix();
            switch (orientation) {
                case android.media.ExifInterface.ORIENTATION_ROTATE_90: transform.setRotate(90); break;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180: transform.setRotate(180); break;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270: transform.setRotate(270); break;
                case android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL: transform.setScale(-1, 1); break;
                case android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL: transform.setScale(1, -1); break;
                default: return bitmap;
            }
            Bitmap oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), transform, true);
            if (oriented != bitmap) bitmap.recycle();
            return oriented;
        } catch (Exception ignored) { return bitmap; }
    }

    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        return "Bike document";
    }

    private void saveDocument(Bitmap bitmap, String name) {
        try {
            File folder = new File(getFilesDir(), "digi_docs");
            if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("Could not create document folder");
            String fileName = java.util.UUID.randomUUID() + ".jpg";
            File file = new File(folder, fileName);
            try (FileOutputStream output = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output); }
            String cleanName = name == null || name.trim().isEmpty() ? "Document " + (documents.size() + 1) : name.trim();
            documents.add(0, new SavedDocument(cleanName.replace('|', ' '), fileName));
            persistDocuments();
            refreshDocuments();
            showToast("Document saved on this phone");
        } catch (Exception e) { showToast("Could not save document: " + e.getMessage()); }
        finally { bitmap.recycle(); }
    }

    private void loadDocuments() {
        Set<String> saved = getSharedPreferences(DOCS_PREFS, MODE_PRIVATE).getStringSet(DOCS_KEY, new HashSet<>());
        for (String record : saved) {
            String[] fields = record.split("\\|", 2);
            if (fields.length == 2 && new File(new File(getFilesDir(), "digi_docs"), fields[1]).isFile()) documents.add(new SavedDocument(fields[0], fields[1]));
        }
    }

    private void persistDocuments() {
        Set<String> saved = new HashSet<>();
        for (SavedDocument doc : documents) saved.add(doc.name.replace('|', ' ') + "|" + doc.fileName);
        getSharedPreferences(DOCS_PREFS, MODE_PRIVATE).edit().putStringSet(DOCS_KEY, saved).apply();
    }

    private void refreshDocuments() {
        if (docsCount != null) docsCount.setText(documents.size() + " saved on this phone");
        if (documentsContainer == null) return;
        documentsContainer.removeAllViews();
        if (documents.isEmpty()) {
            documentsContainer.addView(text("No saved documents", 12, Color.GRAY, false));
            return;
        }
        for (SavedDocument doc : new ArrayList<>(documents)) {
            LinearLayout row = row();
            ImageView thumbnail = new ImageView(this);
            Bitmap thumb = decodeDocumentThumbnail(doc);
            if (thumb != null) thumbnail.setImageBitmap(thumb);
            else thumbnail.setImageResource(android.R.drawable.ic_menu_gallery);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setBackground(roundDrawable(PANEL, 8));
            row.addView(thumbnail, new LinearLayout.LayoutParams(dp(58), dp(70)));
            thumbnail.setOnClickListener(v -> previewSavedDocument(doc));
            TextView label = text(doc.name, 13, Color.WHITE, true);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, dp(70), 1);
            labelParams.leftMargin = dp(10);
            row.addView(label, labelParams);
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.setOnClickListener(v -> previewSavedDocument(doc));
            Button send = button("Send");
            send.setOnClickListener(v -> sendSavedDocument(doc));
            row.addView(send);
            Button delete = button("Delete");
            delete.setTextColor(ORANGE);
            delete.setOnClickListener(v -> deleteDocument(doc));
            row.addView(delete);
            documentsContainer.addView(row);
        }
    }

    private Bitmap decodeDocumentThumbnail(SavedDocument doc) {
        File file = new File(new File(getFilesDir(), "digi_docs"), doc.fileName);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 4;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private void previewSavedDocument(SavedDocument doc) {
        File file = new File(new File(getFilesDir(), "digi_docs"), doc.fileName);
        Bitmap image = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (image == null) { showToast("Saved document image could not be opened"); return; }
        ImageView preview = new ImageView(this);
        preview.setImageBitmap(image);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(Color.BLACK);
        new AlertDialog.Builder(this).setTitle(doc.name).setView(preview).setPositiveButton("Done", null)
                .setOnDismissListener(dialog -> image.recycle()).show();
    }

    private void sendSavedDocument(SavedDocument doc) {
        if (bike == null || !bike.isConnected()) { showToast("Connect to the bike first"); return; }
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(new File(new File(getFilesDir(), "digi_docs"), doc.fileName).getAbsolutePath());
            if (bitmap == null) throw new IllegalArgumentException("Saved image is unreadable");
            bike.sendDocument(bitmap, doc.name, slotSpinner.getSelectedItemPosition() + 1);
        } catch (Exception e) { showToast("Could not send document: " + e.getMessage()); }
    }

    private void deleteDocument(SavedDocument doc) {
        new AlertDialog.Builder(this).setTitle("Delete document?").setMessage("Remove “" + doc.name + "” from this phone?")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, w) -> {
                    documents.remove(doc);
                    new File(new File(getFilesDir(), "digi_docs"), doc.fileName).delete();
                    persistDocuments();
                    refreshDocuments();
                }).show();
    }

    private void refreshAll() {
        if (bike == null) return;
        if (connectionTitle != null) {
            connectionTitle.setText(bike.isConnected() ? "Connected" : "Not connected");
            connectionTitle.setTextColor(bike.isConnected() ? LIME : Color.WHITE);
            connectionText.setText(bike.getConnectionMessage());
            notificationText.setText(bike.getNotificationCount() + " notifications received · Last frame updates continuously");
            if (connectionAction != null) connectionAction.setVisibility(bike.isConnected() ? View.VISIBLE : View.GONE);
        }
        Map<String, String> values = bike.getTelemetry();
        for (Map.Entry<String, TextView> item : metricValues.entrySet()) {
            String key = item.getKey();
            String value = values.get(key);
            String unit = (String) item.getValue().getTag();
            if (key.equals("confirmedTripDistance")) {
                value = values.get("rideDistanceRaw");
                try { if (value != null) value = String.format(Locale.US, "%.1f", Integer.parseInt(value) / 10.0); }
                catch (NumberFormatException ignored) { }
            } else if (key.equals("confirmedTripTime")) {
                String hours = values.get("rideTimeHoursRaw");
                String minutes = values.get("rideTimeMinutesRaw");
                value = hours == null || minutes == null ? null : hours + " h " + minutes + " min";
            } else if (key.equals("confirmedOverspeed")) {
                value = values.get("overspeedThresholdRaw");
            }
            if (value == null) value = "—";
            if (key.equals("rideModeCode") && values.containsKey(key)) value = modeName(value);
            if (key.equals("turnSignalCode") && values.containsKey(key)) value = signalName(value);
            if (key.equals("sideStandStatus")) {
                if (values.containsKey(key)) value = Boolean.parseBoolean(value) ? "Down" : "Up";
            } else if (key.equals("sideStandSensorStatus")) {
                if (values.containsKey(key)) value = Boolean.parseBoolean(value) ? "Active" : "Inactive";
            }
            if (unit != null && !unit.isEmpty() && !value.equals("—")) value += " " + unit;
            item.getValue().setText(value);
        }
        for (Map.Entry<String, TextView> item : rawDataValues.entrySet()) {
            String key = item.getKey();
            String value = values.get(key);
            if (value == null) continue;
            if (key.equals("switchStatusRaw") || key.equals("vehicleDirectionRaw") || key.equals("vehicleState1Raw")
                    || key.equals("serviceReminderRaw") || key.equals("vehicleState2Raw")
                    || key.equals("softwareVersionRaw") || key.equals("milBlinkCodeRaw")
                    || key.equals("vehicleModelRaw") || key.equals("vehicleDiagnosticsRaw")
                    || key.equals("reserve3Raw") || key.equals("engineTemperatureRaw")
                    || key.equals("screenMatrixRaw") || key.equals("vehicleState3Raw")
                    || key.equals("absMilBlinkCodeRaw") || key.equals("vehicleModeRaw")) {
                try { value = String.format(Locale.US, "0x%02X", Integer.parseInt(value)); }
                catch (NumberFormatException ignored) { }
            } else if (key.equals("sideStandSensorStatus")) {
                value = Boolean.parseBoolean(value) ? "Set" : "Clear";
            }
            item.getValue().setText(value);
        }
        refreshDocumentsMessage();
    }

    private void refreshDocumentsMessage() {
        if (docsMessage == null || bike == null) return;
        docsMessage.setText(bike.getTransferMessage());
        if (transferProgress != null) {
            transferProgress.setVisibility(bike.isTransferActive() ? View.VISIBLE : View.GONE);
            transferProgress.setProgress(bike.getTransferProgress());
        }
    }

    private static String modeName(String raw) {
        try {
            int code = Integer.parseInt(raw);
            switch (code) {
                case 0: case 9: return "Urban";
                case 1: case 10: return "Rain";
                case 2: case 11: return "Tour";
                case 3: case 12: return "Rally";
                case 4: return "Supermoto";
                default: return "Mode " + code;
            }
        } catch (NumberFormatException e) { return raw; }
    }

    private static String signalName(String raw) {
        try {
            switch (Integer.parseInt(raw)) {
                case 0: return "Off";
                case 1: return "Right";
                case 2: return "Left";
                case 3: return "Hazard";
                default: return "Unknown";
            }
        } catch (NumberFormatException e) { return raw; }
    }

    @Override public void onBikeUpdated() { refreshAll(); }
    @Override public void onDevicesUpdated() {
        renderSettingsDevices();
        if (showPickerWhenDiscovered && bike != null && !bike.getDevices().isEmpty()) {
            showPickerWhenDiscovered = false;
            showBikePicker();
        }
    }
    @Override public void onTransferUpdated() { refreshDocumentsMessage(); }

    private void addSectionLabel(String value) {
        TextView label = text(value, 12, LIME, true);
        label.setLetterSpacing(0.12f);
        label.setPadding(dp(2), dp(22), dp(2), dp(10));
        content.addView(label);
    }

    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(16), dp(16), dp(16), dp(16));
        styleCard(view, 16);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(10);
        view.setLayoutParams(params);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ORANGE));
        button.setMinHeight(dp(40));
        button.setMinimumHeight(dp(40));
        button.setElevation(0);
        return button;
    }

    private void styleCard(View view, int radiusDp) {
        GradientDrawable shape;
        if (radiusDp >= 20) shape = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {PANEL, Color.BLACK});
        else shape = new GradientDrawable();
        if (radiusDp < 20) shape.setColor(PANEL);
        shape.setCornerRadius(dp(radiusDp));
        shape.setStroke(dp(1), BORDER);
        view.setBackground(shape);
    }

    private GradientDrawable roundDrawable(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        shape.setStroke(dp(1), BORDER);
        return shape;
    }

    private void styleInput(EditText input) {
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        input.setBackground(roundDrawable(PANEL, 10));
    }

    private String telemetrySymbol(String kind) {
        switch (kind) {
            case "speedometer": return "◉";
            case "engine": return "⚙";
            case "road": return "▤";
            case "fuel": return "◌";
            case "gear": return "⚙";
            case "mode": return "◈";
            case "signal": return "↔";
            case "bolt": return "ϟ";
            case "chart": return "⌁";
            case "timer": return "◷";
            case "gauge": return "◉";
            case "stand": return "↧";
            default: return "•";
        }
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private void addGap(LinearLayout parent, int dp) {
        View gap = new View(this);
        parent.addView(gap, new LinearLayout.LayoutParams(1, dp(dp)));
    }

    private int dp(float value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private void showToast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }

    private final class CropImageView extends View {
        private final Bitmap source;
        private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ScaleGestureDetector scaleDetector;
        private float zoom = 1f;
        private float offsetX;
        private float offsetY;
        private float lastX;
        private float lastY;

        CropImageView(Bitmap source) {
            super(MainActivity.this);
            this.source = source;
            setBackgroundColor(Color.BLACK);
            framePaint.setColor(Color.WHITE);
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setStrokeWidth(dp(2));
            scaleDetector = new ScaleGestureDetector(MainActivity.this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    zoom = Math.max(1f, Math.min(4f, zoom * detector.getScaleFactor()));
                    clampOffsets();
                    invalidate();
                    return true;
                }
            });
            setContentDescription("Drag and pinch to crop this image to the bike display frame");
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            if (width == 0) width = getResources().getDisplayMetrics().widthPixels - dp(40);
            setMeasuredDimension(width, Math.round(width * 3f / 5f));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float base = Math.max(getWidth() / (float) source.getWidth(), getHeight() / (float) source.getHeight());
            float drawnWidth = source.getWidth() * base * zoom;
            float drawnHeight = source.getHeight() * base * zoom;
            float left = (getWidth() - drawnWidth) / 2f + offsetX;
            float top = (getHeight() - drawnHeight) / 2f + offsetY;
            canvas.save();
            canvas.clipRect(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(source, null, new RectF(left, top, left + drawnWidth, top + drawnHeight), imagePaint);
            canvas.restore();
            canvas.drawRect(1, 1, getWidth() - 1, getHeight() - 1, framePaint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getX(); lastY = event.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (event.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
                        offsetX += event.getX() - lastX;
                        offsetY += event.getY() - lastY;
                        clampOffsets();
                        invalidate();
                    }
                    lastX = event.getX(); lastY = event.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    performClick();
                    return true;
                default: return true;
            }
        }

        @Override public boolean performClick() { super.performClick(); return true; }

        private void clampOffsets() {
            float base = Math.max(getWidth() / (float) source.getWidth(), getHeight() / (float) source.getHeight());
            float drawnWidth = source.getWidth() * base * zoom;
            float drawnHeight = source.getHeight() * base * zoom;
            offsetX = Math.max(-Math.max(0, (drawnWidth - getWidth()) / 2f), Math.min(Math.max(0, (drawnWidth - getWidth()) / 2f), offsetX));
            offsetY = Math.max(-Math.max(0, (drawnHeight - getHeight()) / 2f), Math.min(Math.max(0, (drawnHeight - getHeight()) / 2f), offsetY));
        }

        Bitmap createCroppedBitmap() {
            Bitmap output = Bitmap.createBitmap(800, 480, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            canvas.drawColor(Color.BLACK);
            float base = Math.max(getWidth() / (float) source.getWidth(), getHeight() / (float) source.getHeight());
            float scale = base * zoom;
            float outScale = 800f / getWidth();
            float left = ((getWidth() - source.getWidth() * scale) / 2f + offsetX) * outScale;
            float top = ((getHeight() - source.getHeight() * scale) / 2f + offsetY) * outScale;
            float width = source.getWidth() * scale * outScale;
            float height = source.getHeight() * scale * outScale;
            canvas.drawBitmap(source, null, new RectF(left, top, left + width, top + height), imagePaint);
            return output;
        }
    }

    private static final class SavedDocument {
        final String name;
        final String fileName;
        SavedDocument(String name, String fileName) { this.name = name; this.fileName = fileName; }
    }
}
