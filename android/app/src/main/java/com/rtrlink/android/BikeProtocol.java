package com.rtrlink.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class BikeProtocol {
    static final String SERVICE_UUID = "5456534d-5647-5341-5342-454e544f5251";
    static final String NOTIFY_UUID = "00005354-0000-1000-8000-00805f9b34fb";
    static final String WRITE_UUID = "00005352-0000-1000-8000-00805f9b34fb";
    static final String CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb";

    private BikeProtocol() { }

    static byte[] appFrame(int id, byte[] body) {
        byte[] frame = new byte[20];
        frame[0] = 0x5B;
        frame[1] = (byte) id;
        for (int i = 0; i < 17; i++) frame[i + 2] = (byte) ((i < body.length ? body[i] : 0) ^ 0xEA);
        frame[19] = (byte) 0xFF;
        return frame;
    }

    static byte[] textFrame(int id, String text, int maxBytes) {
        byte[] textBytes = ascii(text, maxBytes);
        byte[] body = new byte[Math.min(17, textBytes.length)];
        System.arraycopy(textBytes, 0, body, 0, body.length);
        return appFrame(id, body);
    }

    static byte[] welcomeFrame(String riderName) {
        return customTextFrame(riderName.trim(), 1);
    }

    static byte[] customTextFrame(String text, int marker) {
        byte[] textBytes = ascii(text, 15);
        byte[] body = new byte[2 + textBytes.length];
        body[0] = 0;
        body[1] = (byte) marker;
        System.arraycopy(textBytes, 0, body, 2, textBytes.length);
        return appFrame(0x4C, body);
    }

    static byte[] mobileStatusFrame(int batteryPercent) {
        int clamped = Math.max(0, Math.min(100, batteryPercent));
        int batteryBucket = clamped < 10 ? 0 : clamped < 20 ? 1 : clamped < 40 ? 2
                : clamped < 60 ? 3 : clamped < 80 ? 4 : 5;
        java.util.Calendar now = java.util.Calendar.getInstance();
        byte[] body = new byte[17];
        body[0] = (byte) ((3 << 4) | batteryBucket); // signal 3 is the current observed placeholder
        body[1] = 0x78; // captured default overspeed value; configurable mapping remains under analysis
        int hour24 = now.get(java.util.Calendar.HOUR_OF_DAY);
        body[4] = (byte) ((hour24 % 12) == 0 ? 12 : (hour24 % 12));
        body[5] = (byte) now.get(java.util.Calendar.MINUTE);
        body[6] = (byte) now.get(java.util.Calendar.SECOND);
        body[7] = (byte) (hour24 >= 12 ? 1 : 0);
        body[9] = 0; // network type unknown in this client
        body[10] = (byte) now.get(java.util.Calendar.DAY_OF_MONTH);
        body[11] = (byte) (now.get(java.util.Calendar.MONTH) + 1);
        body[12] = (byte) (now.get(java.util.Calendar.YEAR) % 100);
        return appFrame(0x4A, body);
    }

    static byte[] documentInit(int picture, int total, int current, int status, String name) {
        byte[] body = new byte[20];
        body[0] = (byte) picture;
        body[1] = (byte) (total >> 8);
        body[2] = (byte) total;
        body[3] = (byte) (current >> 8);
        body[4] = (byte) current;
        body[5] = (byte) status;
        body[6] = (byte) (picture == 4 ? 5 : picture == 5 ? 10 : 0);
        byte[] nameBytes = ascii(name, 10);
        System.arraycopy(nameBytes, 0, body, 7, nameBytes.length);
        byte[] frame = new byte[23];
        frame[0] = 0x5A;
        frame[1] = 0x53;
        for (int i = 0; i < body.length; i++) frame[i + 2] = (byte) (body[i] ^ 0xEA);
        frame[22] = (byte) 0xFF;
        return frame;
    }

    static byte[] documentChunk(int sequence, byte[] chunk) {
        byte[] decoded = new byte[131];
        decoded[0] = (byte) (sequence >> 8);
        decoded[1] = (byte) sequence;
        System.arraycopy(chunk, 0, decoded, 2, Math.min(128, chunk.length));
        int sum = 0x5A + 0x55 + (decoded[0] & 0xFF) + (decoded[1] & 0xFF);
        for (int i = 2; i < decoded.length; i++) sum = (sum + (decoded[i] & 0xFF)) & 0xFF;
        decoded[130] = (byte) ((0xFF - sum) & 0xFF);
        byte[] frame = new byte[134];
        frame[0] = 0x5A;
        frame[1] = 0x55;
        for (int i = 0; i < decoded.length; i++) frame[i + 2] = (byte) (decoded[i] ^ 0xEA);
        frame[133] = (byte) 0xFF;
        return frame;
    }

    static byte[] documentPixels(Bitmap source) {
        final int width = 800, height = 480;
        Bitmap canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(canvasBitmap);
        canvas.drawColor(Color.BLACK);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float targetRatio = (float) width / height;
        int cropWidth = source.getWidth();
        int cropHeight = source.getHeight();
        if ((float) cropWidth / cropHeight > targetRatio) {
            cropWidth = Math.round(cropHeight * targetRatio);
        } else {
            cropHeight = Math.round(cropWidth / targetRatio);
        }
        int left = (source.getWidth() - cropWidth) / 2;
        int top = (source.getHeight() - cropHeight) / 2;
        RectF dest = new RectF(0, 0, width, height);
        canvas.drawBitmap(source,
                new android.graphics.Rect(left, top, left + cropWidth, top + cropHeight), dest, paint);

        ByteArrayOutputStream out = new ByteArrayOutputStream(width * height * 2);
        // Bike display consumes complete scanlines in reverse vertical order, not mirrored horizontally.
        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                int color = canvasBitmap.getPixel(x, y);
                int rgb565 = (((Color.red(color) >> 3) & 0x1F) << 11)
                        | (((Color.green(color) >> 2) & 0x3F) << 5)
                        | ((Color.blue(color) >> 3) & 0x1F);
                out.write(rgb565 & 0xFF);
                out.write((rgb565 >> 8) & 0xFF);
            }
        }
        canvasBitmap.recycle();
        return out.toByteArray();
    }

    static Map<String, String> decodeNotification(byte[] raw) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (raw.length < 2) return fields;
        int id = raw[1] & 0xFF;
        fields.put("frame", String.format(Locale.US, "0x%02X", id));
        if (raw[0] == 0x5A && id == 0x6B && raw.length >= 3) {
            fields.put("musicCommand", Integer.toString(raw[2] & 0xFF));
            return fields;
        }
        if (raw[0] == 0x5A && id == 0x54 && raw.length >= 9) {
            byte[] f = unmask(raw);
            fields.put("ackFrame", Integer.toString(((f[5] & 0xFF) << 8) | (f[6] & 0xFF)));
            fields.put("ackStatus", Integer.toString(f[7] & 0xFF));
            return fields;
        }
        if (raw.length != 20 || (raw[19] & 0xFF) != 0xFF || raw[0] != 0x5A) return fields;
        byte[] f = unmask(raw);
        switch (id) {
            case 0x10:
                put(fields, "speedKph", f[2] & 0xFF);
                put(fields, "odometerKm", (((f[3] & 0xFF) << 16) | ((f[4] & 0xFF) << 8) | (f[5] & 0xFF)) / 10.0);
                put(fields, "fuelPercent", f[6] & 0xFF);
                put(fields, "averageSpeedKph", f[7] & 0xFF);
                put(fields, "mileageRaw", f[8] & 0xFF);
                put(fields, "topSpeedKph", f[9] & 0xFF);
                put(fields, "throttlePercent", f[10] & 0xFF);
                put(fields, "switchStatusRaw", f[11] & 0xFF);
                put(fields, "locationTagRaw", f[11] & 0xFF);
                put(fields, "zeroTo60Seconds", (f[12] & 0xFF) / 10.0);
                put(fields, "averageMileageRaw", f[13] & 0xFF);
                put(fields, "tripFMeterRaw", ((f[14] & 0xFF) << 8) | (f[15] & 0xFF));
                put(fields, "engineRPM", ((f[16] & 0xFF) << 8) | (f[17] & 0xFF));
                break;
            case 0x11:
                put(fields, "vehicleDirectionRaw", f[2] & 0xFF);
                put(fields, "vehicleState1Raw", f[3] & 0xFF);
                put(fields, "serviceReminderRaw", f[4] & 0xFF);
                put(fields, "gear", f[5] & 0xFF);
                put(fields, "vehicleState2Raw", f[6] & 0xFF);
                put(fields, "batteryVoltageRaw", f[6] & 0xFF);
                put(fields, "softwareVersionRaw", f[7] & 0xFF);
                put(fields, "milBlinkCodeRaw", f[8] & 0xFF);
                put(fields, "vehicleModelRaw", f[9] & 0xFF);
                put(fields, "vehicleDiagnosticsRaw", f[10] & 0xFF);
                put(fields, "reserve3Raw", f[11] & 0xFF);
                put(fields, "turnSignalCode", f[12] & 0xFF);
                put(fields, "reserve14Raw", f[13] & 0xFF);
                put(fields, "engineTemperatureRaw", f[13] & 0xFF);
                put(fields, "tellTaleStatusRaw", f[13] & 0xFF);
                put(fields, "screenMatrixRaw", f[14] & 0xFF);
                put(fields, "vehicleState3Raw", f[15] & 0xFF);
                put(fields, "absMilBlinkCodeRaw", f[16] & 0xFF);
                put(fields, "rideModeCode", f[17] & 0x0F);
                put(fields, "vehicleModeRaw", f[17] & 0xFF);
                put(fields, "sideStandStatus", (f[3] & 0x40) != 0);
                put(fields, "sideStandSensorStatus", (f[2] & 0x10) != 0);
                break;
            case 0x12:
                put(fields, "leanAngleRaw", f[2] & 0xFF);
                put(fields, "cruisingRangeRaw", ((f[3] & 0xFF) << 8) | (f[4] & 0xFF));
                put(fields, "wheelAngleOffsetRaw", f[5] & 0xFF);
                put(fields, "accelerationRaw", f[6] & 0xFF);
                put(fields, "torqueRaw", f[7] & 0xFF);
                put(fields, "rideDistanceRaw", ((f[8] & 0xFF) << 8) | (f[9] & 0xFF));
                put(fields, "rideTimeHoursRaw", f[10] & 0xFF);
                put(fields, "rideTimeMinutesRaw", f[11] & 0xFF);
                put(fields, "rideMileageRaw", f[12] & 0xFF);
                put(fields, "rideFuelRaw", ((f[13] & 0xFF) << 8) | (f[14] & 0xFF));
                put(fields, "overspeedThresholdRaw", f[15] & 0xFF);
                put(fields, "overspeedSettingRaw", f[16] & 0xFF);
                break;
            case 0x5F:
                fields.put("unknown5FPayloadHex", hex(f, 2, 19));
                break;
            default:
                break;
        }
        return fields;
    }

    static byte[] unmask(byte[] raw) {
        byte[] decoded = raw.clone();
        for (int i = 2; i < raw.length - 1; i++) decoded[i] ^= (byte) 0xEA;
        return decoded;
    }

    static String hex(byte[] bytes) { return hex(bytes, 0, bytes.length); }

    static String hex(byte[] bytes, int start, int end) {
        StringBuilder result = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (i > start) result.append(' ');
            result.append(String.format(Locale.US, "%02X", bytes[i] & 0xFF));
        }
        return result.toString();
    }

    static byte[] ascii(String text, int maxBytes) {
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < text.length() && clean.length() < maxBytes; i++) {
            char c = text.charAt(i);
            clean.append(c >= 0x20 && c <= 0x7E ? c : 'X');
        }
        byte[] bytes = clean.toString().getBytes(StandardCharsets.US_ASCII);
        return bytes.length <= maxBytes ? bytes : java.util.Arrays.copyOf(bytes, maxBytes);
    }

    private static void put(Map<String, String> map, String key, Object value) {
        map.put(key, String.valueOf(value));
    }
}
