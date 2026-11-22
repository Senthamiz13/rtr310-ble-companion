#!/usr/bin/env python3
"""Read-only BLE probe for the TVS Apache RTR 310.

Scans for the RTR 310, connects, prints its GATT layout, and subscribes to the
known ST notification characteristic. It deliberately sends no GATT writes.
Install dependencies with: python3 -m pip install -r requirements.txt
"""

import argparse
import asyncio
from datetime import datetime
import re

from bleak import BleakClient, BleakScanner
from bleak.exc import BleakError
from bike_protocol import mobile_status_packet

SERVICE_UUID = "5456534d-5647-5341-5342-454e544f5251"
NOTIFY_UUID = "00005354-0000-1000-8000-00805f9b34fb"
WRITE_UUID = "00005352-0000-1000-8000-00805f9b34fb"
FRAME_COUNTS: dict[str, int] = {}
LATEST_TELEMETRY: dict[str, float | int] = {}
DEVICE_INFO_READS = {
    "00002a24-0000-1000-8000-00805f9b34fb": "Model Number",
    "00002a26-0000-1000-8000-00805f9b34fb": "Firmware Revision",
    "00002a27-0000-1000-8000-00805f9b34fb": "Hardware Revision",
    "00002a28-0000-1000-8000-00805f9b34fb": "Software Revision",
    "00002a29-0000-1000-8000-00805f9b34fb": "Manufacturer",
}


def on_notification(_sender, data: bytearray) -> None:
    stamp = datetime.now().astimezone().isoformat(timespec="milliseconds")
    raw = bytes(data)
    kind = f" frame=0x{raw[1]:02x}" if len(raw) >= 2 else ""
    if raw[:2] in (b"\x5a\x7d", b"\x5b\x42"):
        print(f"{stamp} RX len={len(raw)}{kind} [vehicle identifier/MAC payload hidden]", flush=True)
        return
    decoded = bytearray(raw)
    if len(decoded) >= 3:
        # Apache decryptDataForApache preserves header/trailer and XORs the
        # payload with the repeated key byte 0xEA.
        for index in range(2, len(decoded) - 1):
            decoded[index] ^= 0xEA
    if len(decoded) >= 2 and decoded[0] == 0x5A:
        frame_id = f"0x{decoded[1]:02X}"
        FRAME_COUNTS[frame_id] = FRAME_COUNTS.get(frame_id, 0) + 1
        if decoded[1] == 0x10 and len(decoded) >= 20:
            LATEST_TELEMETRY.update({
                "speed_kph": decoded[2],
                "odometer_km": int.from_bytes(decoded[3:6], "big") / 10,
                "fuel_percent": decoded[6],
                "average_speed_kph": decoded[7],
                "recorded_top_speed_kph": decoded[9],
                "throttle_percent": decoded[10],
                "zero_to_60_seconds": decoded[12] / 10,
                "engine_rpm": int.from_bytes(decoded[16:18], "big"),
            })
        elif decoded[1] == 0x11 and len(decoded) >= 20:
            LATEST_TELEMETRY["gear"] = decoded[5]
    print(
        f"{stamp} RX len={len(raw)} wire={raw.hex(' ')} decoded={bytes(decoded).hex(' ')}{kind}",
        flush=True,
    )


def rider_name_packet(name: str) -> bytes:
    """Build the APK's 20-byte Apache-U400 rider-name wire packet."""
    safe_name = re.sub(r"[^A-Za-z0-9 ]", "X", name)[:17].strip()
    encoded = safe_name.encode("ascii")
    packet = bytearray(bytes((0x5B, 0x52)) + encoded + bytes(17 - len(encoded)) + b"\xff")
    # The APK's encryptDataApache() keeps the first two and final bytes and
    # XORs every byte in between with the repeated key byte 0xEA.
    for index in range(2, len(packet) - 1):
        packet[index] ^= 0xEA
    return bytes(packet)


async def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--address", help="BLE address / CoreBluetooth UUID; omit to scan")
    parser.add_argument("--scan-seconds", type=float, default=12)
    parser.add_argument("--listen-seconds", type=float, default=30)
    parser.add_argument(
        "--send-rider-name",
        metavar="TEXT",
        help="send one documented APK rider-name frame (20 bytes); sends nothing by default",
    )
    args = parser.parse_args()
    FRAME_COUNTS.clear()
    LATEST_TELEMETRY.clear()

    target = args.address
    if args.send_rider_name is None:
        write_mode = "no writes will be sent"
    else:
        write_mode = "will send one APK-derived rider-name frame"
    if not target:
        print(f"Scanning for {args.scan_seconds:g}s; keep the bike nearby and ignition on…", flush=True)
        try:
            found = await BleakScanner.discover(timeout=args.scan_seconds, return_adv=True)
        except BleakError as exc:
            print(f"BLE scan unavailable: {exc}")
            print("Check macOS Bluetooth availability and Privacy & Security permissions for the Python host.")
            return
        matches = []
        for device, adv in found.values():
            services = {s.lower() for s in (adv.service_uuids or [])}
            name = (adv.local_name or device.name or "").lower()
            if SERVICE_UUID in services or "tvsrtr310" in name or "apache" in name:
                matches.append(device)
        if not matches:
            print("No matching bike found. Discovered devices:")
            for device, adv in found.values():
                print(f"  {device.address}  {adv.local_name or device.name or '(unnamed)'}")
            return
        if len(matches) > 1:
            print("More than one matching device; pass its address with --address:")
            for device in matches:
                print(f"  {device.address}  {device.name or '(unnamed)'}")
            return
        target = matches[0].address

    print(f"Connecting to {target}; {write_mode}…", flush=True)
    loop = asyncio.get_running_loop()
    disconnected = asyncio.Event()
    connected_at: float | None = None

    def on_disconnect(_client) -> None:
        elapsed = loop.time() - connected_at if connected_at is not None else 0.0
        print(f"BLE link disconnected after {elapsed:.1f}s.", flush=True)
        loop.call_soon_threadsafe(disconnected.set)

    async with BleakClient(target, timeout=20, disconnected_callback=on_disconnect) as client:
        connected_at = loop.time()
        print(f"Connected: {client.is_connected}")
        services = client.services
        for service in services:
            print(f"SERVICE {service.uuid} {service.description}")
            for char in service.characteristics:
                print(f"  CHAR {char.uuid} properties={','.join(char.properties)}")
        info_service = services.get_service("0000180a-0000-1000-8000-00805f9b34fb")
        if info_service:
            print("Reading standard device info (excluding serial/system identifiers):", flush=True)
            for uuid, label in DEVICE_INFO_READS.items():
                char = services.get_characteristic(uuid)
                if char and "read" in char.properties:
                    try:
                        value = bytes(await client.read_gatt_char(char))
                        decoded = value.decode("utf-8", errors="replace").rstrip("\x00")
                        print(f"INFO {label}: {decoded!r} (hex={value.hex(' ')})", flush=True)
                    except BleakError as exc:
                        print(f"INFO {label}: read failed: {exc}", flush=True)
        if not services.get_service(SERVICE_UUID):
            print("Expected TVS service is absent; closing without subscribing.")
            return
        try:
            await client.start_notify(NOTIFY_UUID, on_notification)
        except Exception as exc:
            print(f"Could not subscribe to ST notifications: {type(exc).__name__}: {exc}")
            return
        if args.send_rider_name is not None:
            packet = rider_name_packet(args.send_rider_name)
            print(f"TX rider-name frame (APK-derived): {packet.hex(' ')}", flush=True)
            try:
                await client.write_gatt_char(WRITE_UUID, packet, response=True)
                print("Rider-name frame written; continuing to listen for ST notifications.", flush=True)
            except BleakError as exc:
                print(f"Rider-name write failed: {exc}", flush=True)
        print(f"Subscribed to ST. Listening for up to {args.listen_seconds:g}s (Ctrl-C to stop).", flush=True)
        try:
            await asyncio.wait_for(disconnected.wait(), timeout=args.listen_seconds)
        except asyncio.TimeoutError:
            print(f"Listen limit reached after {args.listen_seconds:g}s.", flush=True)
        else:
            print("Stopping because the BLE link dropped.", flush=True)
        elapsed = loop.time() - connected_at if connected_at is not None else 0.0
        print(
            f"Capture summary: duration={elapsed:.1f}s frame_counts={FRAME_COUNTS} telemetry={LATEST_TELEMETRY}",
            flush=True,
        )
        if client.is_connected:
            try:
                await client.stop_notify(NOTIFY_UUID)
            except BleakError as exc:
                print(f"Could not unsubscribe cleanly: {exc}")
        print("Stopped notification subscription; disconnecting.")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("Stopped by user.")
    except BleakError as exc:
        print(f"BLE operation failed: {exc}")
