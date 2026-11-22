#!/usr/bin/env python3
"""Local-only live telemetry dashboard for a TVS Apache RTR 310."""

import asyncio
from collections import deque
from contextlib import asynccontextmanager
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys
import time
from typing import Any
from uuid import uuid4

from bleak import BleakClient, BleakScanner
from bleak.exc import BleakError
from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import FileResponse, HTMLResponse, StreamingResponse
from pydantic import BaseModel, Field

from bike_protocol import (
    CLOCK_FRAME_FIRST_DELAY_SECONDS,
    CLOCK_FRAME_INTERVAL_SECONDS,
    DOC_CHUNK_SIZE,
    DOC_MAX_FRAMES,
    ID_CUSTOM_TEXT,
    ID_DOC_CHUNK,
    ID_DOC_INIT,
    ID_CLOCK_STATUS,
    ID_RIDER_NAME,
    NOTIFY_UUID,
    SERVICE_UUID,
    WRITE_UUID,
    display_name_packet,
    custom_text_packet,
    decode_document_ack,
    document_chunk_packet,
    document_frame_count,
    document_init_packet,
    mobile_status_packet,
    rider_name_packet,
)

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SESSION_LOG_DIR = PROJECT_ROOT / "local_data" / "session_logs"
# Set BIKE_LOG_RAW=1 to put every raw frame (hex) into the session log, so it can
# be compared line by line with a btsnoop capture of the phone app.
LOG_RAW_FRAMES = os.environ.get("BIKE_LOG_RAW") == "1"
# The phone needed ~10 s to finish passkey pairing before notifications worked.
PAIRING_TIMEOUT_SECONDS = 90
MUSIC_COMMANDS = {
    0: "Play",
    1: "Pause",
    2: "Play / pause toggle",
    3: "Next track",
    4: "Previous track",
    5: "Volume up",
    6: "Volume down",
}


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def decrypt_apache(frame: bytes) -> bytes:
    """Reverse the APK's Apache XOR transform, preserving header and trailer."""
    if len(frame) < 3:
        return frame
    decoded = bytearray(frame)
    for index in range(2, len(decoded) - 1):
        decoded[index] ^= 0xEA
    return bytes(decoded)


def decode_apache_frame(frame: bytes) -> bytes:
    """Decode Apache frames, preserving the un-obfuscated 0x6B music command."""
    if len(frame) >= 2 and frame[0] == 0x5A and frame[1] == 0x6B:
        return frame
    return decrypt_apache(frame)


def decode_telemetry(frame: bytes) -> dict[str, Any]:
    """Decode fields with known offsets from the APK's Apache data parser."""
    if len(frame) < 20 or frame[0] != 0x5A:
        return {}

    frame_id = frame[1]
    values: dict[str, Any] = {}
    if frame_id == 0x6B and len(frame) >= 3:
        command = frame[2]
        values = {
            "music_command_code": command,
            "music_command": MUSIC_COMMANDS.get(command, f"Unknown command {command}"),
        }
    elif frame_id == 0x10:
        values = {
            "speed_kph": frame[2],
            "odometer_km": int.from_bytes(frame[3:6], "big") / 10,
            "fuel_percent": frame[6],
            "average_speed_kph": frame[7],
            "recorded_top_speed_kph": frame[9],
            "throttle_percent": frame[10],
            "zero_to_60_seconds": frame[12] / 10,
            "engine_rpm": int.from_bytes(frame[16:18], "big"),
        }
    elif frame_id == 0x11:
        values = {
            "gear": frame[5],
            # ApacheDataParser names index 12 turnSignalLampStatus.
            "turn_signal_status": frame[12],
        }
    return values


class ConnectRequest(BaseModel):
    address: str = Field(min_length=1, max_length=128)
    rider_name: str = Field(default="Rider", max_length=64)
    send_startup_frame: bool = True
    cellular_signal_level: int = Field(default=4, ge=0, le=4)
    phone_battery_percent: int = Field(default=80, ge=0, le=100)
    network_type: int = Field(default=13, ge=0, le=255)


class MobileStatusRequest(BaseModel):
    cellular_signal_level: int = Field(ge=0, le=4)
    phone_battery_percent: int = Field(ge=0, le=100)
    network_type: int = Field(ge=0, le=255)


class CallerNameTestRequest(BaseModel):
    caller_name: str = Field(min_length=1, max_length=17)


class CustomTextRequest(BaseModel):
    text: str = Field(min_length=1, max_length=120)


class BikeBridge:
    def __init__(self) -> None:
        self.client: BleakClient | None = None
        self.address: str | None = None
        self.connected_at: float | None = None
        self.session_id: str | None = None
        self.session_log_path: Path | None = None
        self.last_session_log_path: Path | None = None
        self.local_disconnect_reason: str | None = None
        self.disconnect_context: dict[str, str | None] | None = None
        self.link_monitor_task: asyncio.Task[None] | None = None
        self.cyclic_write_task: asyncio.Task[None] | None = None
        self.notification_count = 0
        self.last_notification_at: str | None = None
        self.last_notification_monotonic: float | None = None
        self.status = "disconnected"
        self.message = "Scan for the bike to begin."
        self.telemetry: dict[str, Any] = {
            "speed_kph": None,
            "engine_rpm": None,
            "odometer_km": None,
            "fuel_percent": None,
            "gear": None,
            "turn_signal_status": None,
            "average_speed_kph": None,
            "recorded_top_speed_kph": None,
            "throttle_percent": None,
            "zero_to_60_seconds": None,
            "music_command_code": None,
            "music_command": None,
        }
        self.frame_counts: dict[str, int] = {}
        self.last_update: str | None = None
        self.last_frame: str | None = None
        self.event_queues: set[asyncio.Queue[str]] = set()
        self.lock = asyncio.Lock()
        self.write_lock = asyncio.Lock()
        self.loop: asyncio.AbstractEventLoop | None = None
        self.mobile_status_inputs = {
            "cellular_signal_level": 4,
            "phone_battery_percent": 80,
            "network_type": 13,
        }
        self.last_music_command_code: int | None = None
        self.last_music_command_at: float | None = None
        self.dpad_events: deque[dict[str, Any]] = deque(maxlen=20)
        self.document_transfer: dict[str, Any] = {
            "active": False,
            "sent_frames": 0,
            "total_frames": 0,
            "acknowledged_frames": 0,
            "status": "idle",
            "message": "No document transfer in this session.",
        }
        self.document_ack_queue: asyncio.Queue[tuple[int, int]] | None = None
        self.document_transfer_lock = asyncio.Lock()

    def snapshot(self) -> dict[str, Any]:
        connected = bool(self.client and self.client.is_connected)
        return {
            "status": self.status,
            "message": self.message,
            "connected": connected,
            "telemetry": self.telemetry,
            "frame_counts": self.frame_counts,
            "last_update": self.last_update,
            "last_frame": self.last_frame,
            "session_id": self.session_id,
            "session_log_path": str(self.session_log_path or self.last_session_log_path)
            if (self.session_log_path or self.last_session_log_path)
            else None,
            "mobile_status_inputs": dict(self.mobile_status_inputs),
            "last_music_command_code": self.last_music_command_code,
            "dpad_events": list(self.dpad_events),
            "document_transfer": dict(self.document_transfer),
        }

    @staticmethod
    def _append_session_event(
        path: Path | None, session_id: str | None, event: str, **details: Any
    ) -> None:
        if path is None:
            return
        path.parent.mkdir(parents=True, exist_ok=True)
        record = {
            "timestamp": datetime.now().astimezone().isoformat(timespec="milliseconds"),
            "session_id": session_id,
            "event": event,
            **details,
        }
        with path.open("a", encoding="utf-8") as log_file:
            log_file.write(json.dumps(record, separators=(",", ":"), default=str) + "\n")

    def _log_session(self, event: str, **details: Any) -> None:
        self._append_session_event(self.session_log_path, self.session_id, event, **details)

    async def broadcast(self) -> None:
        payload = json.dumps(self.snapshot())
        for queue in tuple(self.event_queues):
            try:
                if queue.full():
                    queue.get_nowait()
                queue.put_nowait(payload)
            except asyncio.QueueEmpty:
                pass

    def _notification_received(self, raw: bytes, session_id: str, log_path: Path) -> None:
        if session_id != self.session_id:
            self._append_session_event(
                log_path, session_id, "late_notification_ignored", length=len(raw)
            )
            return
        decoded = decode_apache_frame(raw)
        self.notification_count += 1
        self.last_notification_at = now_iso()
        self.last_notification_monotonic = time.monotonic()
        values = decode_telemetry(decoded)
        frame_key = f"0x{decoded[1]:02X}" if len(decoded) >= 2 else "unknown"
        self.frame_counts[frame_key] = self.frame_counts.get(frame_key, 0) + 1
        self._log_session(
            "notification",
            frame_id=frame_key,
            length=len(raw),
            notification_number=self.notification_count,
            telemetry=values or None,
            raw_hex=raw.hex() if LOG_RAW_FRAMES else None,
        )
        ack = decode_document_ack(decoded)
        if ack is not None:
            latest_frame, transfer_status = ack
            if self.document_ack_queue is not None:
                try:
                    self.document_ack_queue.put_nowait((latest_frame, transfer_status))
                except asyncio.QueueFull:
                    pass
            self.document_transfer["acknowledged_frames"] = max(
                self.document_transfer.get("acknowledged_frames", 0), latest_frame
            )
            self.document_transfer["bike_status"] = transfer_status
            self._log_session(
                "document_transfer_ack",
                acknowledged_frame=latest_frame,
                bike_status=transfer_status,
            )
            asyncio.create_task(self.broadcast())
        if len(decoded) >= 3 and decoded[0] == 0x5A and decoded[1] == 0x6B:
            command = decoded[2]
            command_at = time.monotonic()
            duplicate = (
                command == self.last_music_command_code
                and self.last_music_command_at is not None
                and command_at - self.last_music_command_at < 0.1
            )
            self.last_music_command_code = command
            self.last_music_command_at = command_at
            if command in MUSIC_COMMANDS and not duplicate:
                event = {
                    "timestamp": datetime.now().astimezone().isoformat(timespec="milliseconds"),
                    "code": command,
                    "label": MUSIC_COMMANDS[command],
                }
                self.dpad_events.appendleft(event)
                self._log_session("music_control_command", **event)
        if not values:
            # These include identity and accessory frames. Keep only their
            # type/count; do not place identifiers or raw packets in the log/UI.
            asyncio.create_task(self.broadcast())
            return
        self.last_frame = frame_key
        self.last_update = now_iso()
        self.telemetry.update(values)
        self.status = "streaming"
        self.message = "Live telemetry is arriving from the bike."
        asyncio.create_task(self.broadcast())

    def _peer_disconnected(
        self, session_id: str, log_path: Path, disconnect_context: dict[str, str | None]
    ) -> None:
        is_current_session = session_id == self.session_id
        elapsed = (
            time.monotonic() - self.connected_at
            if is_current_session and self.connected_at is not None
            else None
        )
        reason = disconnect_context.get("reason") or (
            self.local_disconnect_reason if is_current_session else None
        )
        self._append_session_event(
            log_path,
            session_id,
            "ble_disconnected_callback",
            elapsed_seconds=round(elapsed, 3) if elapsed is not None else None,
            classification="app_requested" if reason else "peer_or_host_initiated_or_unknown",
            app_disconnect_reason=reason,
            last_notification_at=self.last_notification_at if is_current_session else None,
            last_telemetry_at=self.last_update if is_current_session else None,
            notification_count=self.notification_count if is_current_session else None,
        )
        print(
            "BLE link disconnected"
            + (f" after {elapsed:.1f}s" if elapsed is not None else "")
            + (f" (app requested: {reason})" if reason else " (no app disconnect request recorded)")
            + f"; last telemetry update: {self.last_update or 'none'}",
            flush=True,
        )
        if not is_current_session:
            return
        self.status = "disconnected"
        self.message = "BLE link disconnected. You can reconnect from this page."
        asyncio.create_task(self.broadcast())

    async def _monitor_link(
        self, client: BleakClient, session_id: str, log_path: Path
    ) -> None:
        last_stall_logged = False
        while self.session_id == session_id and self.client is client:
            await asyncio.sleep(1)
            is_connected = client.is_connected
            now_monotonic = time.monotonic()
            notification_age = (
                round(now_monotonic - self.last_notification_monotonic, 3)
                if self.last_notification_monotonic is not None
                else None
            )
            self._append_session_event(
                log_path,
                session_id,
                "link_state_sample",
                bleak_is_connected=is_connected,
                elapsed_seconds=round(now_monotonic - self.connected_at, 3)
                if self.connected_at is not None
                else None,
                notification_count=self.notification_count,
                seconds_since_notification=notification_age,
            )
            if not is_connected:
                self._append_session_event(
                    log_path,
                    session_id,
                    "link_state_poll_detected_disconnect",
                    notification_count=self.notification_count,
                    last_notification_at=self.last_notification_at,
                    last_telemetry_at=self.last_update,
                )
                if self.session_id == session_id:
                    self.status = "disconnected"
                    self.message = "BLE link is no longer connected (confirmed by link-state polling)."
                    await self.broadcast()
                return
            if notification_age is not None and notification_age >= 2 and not last_stall_logged:
                self._append_session_event(
                    log_path,
                    session_id,
                    "notifications_stalled_while_link_connected",
                    seconds_since_notification=notification_age,
                    last_notification_at=self.last_notification_at,
                )
                last_stall_logged = True
            elif notification_age is not None and notification_age < 2 and last_stall_logged:
                self._append_session_event(log_path, session_id, "notifications_resumed")
                last_stall_logged = False

    async def scan(self) -> list[dict[str, str]]:
        try:
            found = await BleakScanner.discover(timeout=8, return_adv=True)
        except BleakError as exc:
            raise HTTPException(status_code=503, detail=f"BLE scan unavailable: {exc}") from exc

        matches: list[dict[str, str]] = []
        fallback: list[dict[str, str]] = []
        for device, advertisement in found.values():
            name = advertisement.local_name or device.name or "Unnamed BLE device"
            services = {uuid.lower() for uuid in (advertisement.service_uuids or [])}
            item = {"address": device.address, "name": name}
            fallback.append(item)
            if SERVICE_UUID in services or "tvs" in name.lower() or "apache" in name.lower():
                matches.append(item)
        # If the bike does not include a recognizable advertisement name/service,
        # let the user choose from nearby devices rather than hiding it.
        return matches or fallback

    async def connect(self, request: ConnectRequest) -> None:
        async with self.lock:
            if self.document_transfer["active"]:
                raise HTTPException(status_code=409, detail="Wait for the document transfer to finish before reconnecting.")
            # A second browser tab or a repeated click must not tear down an
            # already healthy BLE session to the same bike.
            if (
                self.client
                and self.client.is_connected
                and self.address
                and self.address.lower() == request.address.lower()
            ):
                self._log_session("repeat_connect_request_ignored", already_connected=True)
                self.status = "streaming" if self.last_update else "connected"
                self.message = "Already connected; continuing the live telemetry stream."
                await self.broadcast()
                return
            await self._disconnect_locked("replaced_by_new_connect_request")
            self.session_id = uuid4().hex[:12]
            SESSION_LOG_DIR.mkdir(parents=True, exist_ok=True)
            timestamp = datetime.now().astimezone().strftime("%Y%m%d_%H%M%S")
            self.session_log_path = SESSION_LOG_DIR / f"ble_session_{timestamp}_{self.session_id}.jsonl"
            self.last_session_log_path = self.session_log_path
            self.notification_count = 0
            self.last_notification_at = None
            self.last_notification_monotonic = None
            self.last_music_command_code = None
            self.last_music_command_at = None
            self.dpad_events.clear()
            self.document_transfer = {
                "active": False,
                "sent_frames": 0,
                "total_frames": 0,
                "acknowledged_frames": 0,
                "status": "idle",
                "message": "No document transfer in this session.",
            }
            self.mobile_status_inputs = {
                "cellular_signal_level": request.cellular_signal_level,
                "phone_battery_percent": request.phone_battery_percent,
                "network_type": request.network_type,
            }
            self.local_disconnect_reason = None
            self.disconnect_context = {"reason": None}
            self._log_session(
                "session_started",
                startup_frame_enabled=request.send_startup_frame,
                mobile_status_inputs={
                    "cellular_signal_level": request.cellular_signal_level,
                    "phone_battery_percent": request.phone_battery_percent,
                    "network_type": request.network_type,
                },
                transport="Bleak/CoreBluetooth",
                service_uuid=SERVICE_UUID,
                notify_uuid=NOTIFY_UUID,
                write_uuid=WRITE_UUID,
                log_file=str(self.session_log_path),
            )
            self._log_session("connect_requested")
            self.status = "connecting"
            self.message = "Connecting and discovering the TVS service…"
            self.telemetry = {key: None for key in self.telemetry}
            self.frame_counts = {}
            self.last_update = None
            await self.broadcast()

            self.loop = asyncio.get_running_loop()

            callback_session_id = self.session_id
            callback_log_path = self.session_log_path
            callback_disconnect_context = self.disconnect_context or {"reason": None}

            def disconnected(_client: BleakClient) -> None:
                loop = self.loop
                if loop and not loop.is_closed():
                    loop.call_soon_threadsafe(
                        self._peer_disconnected,
                        callback_session_id,
                        callback_log_path,
                        callback_disconnect_context,
                    )

            client = BleakClient(
                request.address,
                timeout=20,
                disconnected_callback=disconnected,
            )
            self.client = client
            self.address = request.address
            try:
                await client.connect()
                self.connected_at = time.monotonic()
                self._log_session("gatt_connected", elapsed_from_request_seconds=0)
                if not client.services.get_service(SERVICE_UUID):
                    self._log_session("required_service_missing", service_uuid=SERVICE_UUID)
                    raise BleakError("The connected device does not expose the TVS Apache service.")
                self._log_session("required_service_found", service_uuid=SERVICE_UUID)

                def notification(_sender: int, data: bytearray) -> None:
                    loop = self.loop
                    if loop and not loop.is_closed():
                        loop.call_soon_threadsafe(
                            self._notification_received,
                            bytes(data),
                            callback_session_id,
                            callback_log_path,
                        )

                await self._enable_notifications(
                    client, notification, callback_session_id, callback_log_path
                )
                self.link_monitor_task = asyncio.create_task(
                    self._monitor_link(client, callback_session_id, callback_log_path)
                )
                self.status = "connected"
                self.message = "Connected and listening for bike telemetry."
                await self.broadcast()

                if request.send_startup_frame:
                    # This is the APK-derived rider-name data frame observed to
                    # precede telemetry. It is not a display-control command.
                    packet = rider_name_packet(request.rider_name or "Rider")
                    self._log_session(
                        "write_started",
                        frame_id=f"0x{ID_RIDER_NAME:02X}",
                        length=len(packet),
                        response=True,
                        raw_hex=packet.hex() if LOG_RAW_FRAMES else None,
                    )
                    await client.write_gatt_char(WRITE_UUID, packet, response=True)
                    self._log_session(
                        "write_succeeded", frame_id=f"0x{ID_RIDER_NAME:02X}", length=len(packet)
                    )
                    self.message = "Startup rider-name frame sent; waiting for telemetry."
                    await self.broadcast()
                else:
                    self._log_session("startup_write_skipped")

                # In the phone capture the first clock/status frame (0x4A) went out 2.0 s
                # after the rider-name frame, then repeated every 2 s.
                self.cyclic_write_task = asyncio.create_task(
                    self._send_cyclic_mobile_status(
                        client,
                        callback_session_id,
                        callback_log_path,
                        cellular_signal_level=request.cellular_signal_level,
                        phone_battery_percent=request.phone_battery_percent,
                        network_type=request.network_type,
                    )
                )
            except Exception as exc:
                self._log_session(
                    "connection_setup_failed",
                    error_type=type(exc).__name__,
                    error=str(exc),
                    connected=client.is_connected,
                )
                self.status = "error"
                self.message = f"Connection failed: {exc}"
                if client.is_connected:
                    try:
                        self.local_disconnect_reason = "connection_setup_failed_cleanup"
                        if self.disconnect_context is not None:
                            self.disconnect_context["reason"] = self.local_disconnect_reason
                        await client.disconnect()
                    except Exception:
                        pass
                self._log_session("session_ended", reason="connection_setup_failed")
                if self.session_log_path is not None:
                    self.last_session_log_path = self.session_log_path
                self.client = None
                self.address = None
                self.connected_at = None
                if self.link_monitor_task is not None:
                    self.link_monitor_task.cancel()
                    self.link_monitor_task = None
                if self.cyclic_write_task is not None:
                    self.cyclic_write_task.cancel()
                    self.cyclic_write_task = None
                self.session_id = None
                self.session_log_path = None
                self.disconnect_context = None
                await self.broadcast()
                raise HTTPException(status_code=502, detail=self.message) from exc

    async def _enable_notifications(
        self,
        client: BleakClient,
        handler: Any,
        session_id: str,
        log_path: Path,
    ) -> None:
        """Enable notifications the way the phone did, after the link is bonded.

        In the capture the bike sends an SMP Security Request right after connecting
        and requires passkey pairing (LE Secure Connections, bike shows the passkey,
        phone types it) before the notify subscription (CCCD write) is accepted.
        Encryption completed about 15 s after connect; notifications were enabled
        around 18 s after connect, after service setup.
        """
        if sys.platform == "darwin":
            # CoreBluetooth cannot start pairing on request; macOS shows the passkey
            # dialog by itself when the bike asks for security or an encrypted
            # attribute is touched, i.e. during start_notify below.
            self._append_session_event(log_path, session_id, "pairing_left_to_macos")
        else:
            try:
                await client.pair()
                self._append_session_event(log_path, session_id, "pair_call_completed")
            except Exception as exc:
                self._append_session_event(
                    log_path,
                    session_id,
                    "pair_call_failed",
                    error_type=type(exc).__name__,
                    error=str(exc),
                )
        self.status = "pairing"
        self.message = "Pairing: enter the passkey shown on the bike display if a prompt appears."
        await self.broadcast()

        last_error: BaseException | None = None
        for attempt in range(1, 4):
            try:
                await asyncio.wait_for(
                    client.start_notify(NOTIFY_UUID, handler), timeout=PAIRING_TIMEOUT_SECONDS
                )
                self._append_session_event(
                    log_path,
                    session_id,
                    "notification_subscription_started",
                    notify_uuid=NOTIFY_UUID,
                    attempt=attempt,
                )
                return
            except (BleakError, asyncio.TimeoutError) as exc:
                last_error = exc
                self._append_session_event(
                    log_path,
                    session_id,
                    "notification_subscription_failed",
                    attempt=attempt,
                    error_type=type(exc).__name__,
                    error=str(exc),
                    connected=client.is_connected,
                )
                if not client.is_connected:
                    break
                await asyncio.sleep(2)
        raise BleakError(
            f"Could not enable notifications; pairing with the bike probably did not "
            f"complete ({type(last_error).__name__}: {last_error})"
        )

    async def _send_cyclic_mobile_status(
        self,
        client: BleakClient,
        session_id: str,
        log_path: Path,
        *,
        cellular_signal_level: int,
        phone_battery_percent: int,
        network_type: int,
    ) -> None:
        sequence = 0
        CLOCK_FRAME_ID = f"0x{ID_CLOCK_STATUS:02X}"
        self._append_session_event(
            log_path,
            session_id,
            "cyclic_mobile_status_started",
            interval_seconds=CLOCK_FRAME_INTERVAL_SECONDS,
            first_delay_seconds=CLOCK_FRAME_FIRST_DELAY_SECONDS,
            frame_id=CLOCK_FRAME_ID,
            response=True,
            cellular_signal_level=cellular_signal_level,
            phone_battery_percent=phone_battery_percent,
            network_type=network_type,
        )
        await asyncio.sleep(CLOCK_FRAME_FIRST_DELAY_SECONDS)
        while self.session_id == session_id and self.client is client and client.is_connected:
            sequence += 1
            status_inputs = dict(self.mobile_status_inputs)
            packet = mobile_status_packet(
                cellular_signal_level=status_inputs["cellular_signal_level"],
                phone_battery_percent=status_inputs["phone_battery_percent"],
                network_type=status_inputs["network_type"],
            )
            self._append_session_event(
                log_path,
                session_id,
                "mobile_status_write_started",
                sequence=sequence,
                frame_id=CLOCK_FRAME_ID,
                length=len(packet),
                response=True,
                raw_hex=packet.hex() if LOG_RAW_FRAMES else None,
                **status_inputs,
            )
            try:
                async with self.write_lock:
                    if self.session_id != session_id or self.client is not client or not client.is_connected:
                        return
                    await client.write_gatt_char(WRITE_UUID, packet, response=True)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                self._append_session_event(
                    log_path,
                    session_id,
                    "mobile_status_write_failed",
                    sequence=sequence,
                    error_type=type(exc).__name__,
                    error=str(exc),
                    connected=client.is_connected,
                )
                return
            self._append_session_event(
                log_path,
                session_id,
                "mobile_status_write_succeeded",
                sequence=sequence,
                frame_id=CLOCK_FRAME_ID,
                length=len(packet),
            )
            await asyncio.sleep(CLOCK_FRAME_INTERVAL_SECONDS)

    async def set_mobile_status(self, request: MobileStatusRequest) -> dict[str, Any]:
        self.mobile_status_inputs = request.dict()
        self._log_session("mobile_status_inputs_updated", **self.mobile_status_inputs)
        client = self.client
        session_id = self.session_id
        if client and session_id and client.is_connected:
            packet = mobile_status_packet(
                cellular_signal_level=self.mobile_status_inputs["cellular_signal_level"],
                phone_battery_percent=self.mobile_status_inputs["phone_battery_percent"],
                network_type=self.mobile_status_inputs["network_type"],
            )
            self._log_session(
                "mobile_status_test_write_started",
                frame_id=f"0x{ID_CLOCK_STATUS:02X}",
                length=len(packet),
                response=True,
                raw_hex=packet.hex() if LOG_RAW_FRAMES else None,
            )
            try:
                written = False
                async with self.write_lock:
                    if self.session_id == session_id and self.client is client and client.is_connected:
                        await client.write_gatt_char(WRITE_UUID, packet, response=True)
                        written = True
                if written:
                    self._log_session(
                        "mobile_status_test_write_succeeded",
                        frame_id=f"0x{ID_CLOCK_STATUS:02X}",
                        **self.mobile_status_inputs,
                    )
                else:
                    self._log_session("mobile_status_test_write_skipped", reason="connection_changed")
            except Exception as exc:
                self._log_session(
                    "mobile_status_test_write_failed",
                    error_type=type(exc).__name__,
                    error=str(exc),
                )
                raise HTTPException(status_code=502, detail=f"Could not write mobile status: {exc}") from exc
        await self.broadcast()
        return self.snapshot()

    async def send_caller_name_test(self, request: CallerNameTestRequest) -> dict[str, Any]:
        client = self.client
        session_id = self.session_id
        if not client or not session_id or not client.is_connected:
            raise HTTPException(status_code=409, detail="Connect to the bike before sending the caller-name test.")
        if self.document_transfer["active"]:
            raise HTTPException(status_code=409, detail="Wait for the document transfer to finish first.")

        packet = display_name_packet(request.caller_name)
        repeat_count = 8
        interval_seconds = 0.5
        self._log_session(
            "caller_name_test_started",
            frame_id="0x43",
            repeat_count=repeat_count,
            interval_seconds=interval_seconds,
            caller_name_length=len(request.caller_name),
            length=len(packet),
        )
        for sequence in range(1, repeat_count + 1):
            if self.session_id != session_id or self.client is not client or not client.is_connected:
                raise HTTPException(status_code=409, detail="BLE disconnected before the caller-name burst finished.")
            self._log_session(
                "caller_name_test_write_started",
                sequence=sequence,
                frame_id="0x43",
                length=len(packet),
                raw_hex=packet.hex() if LOG_RAW_FRAMES else None,
            )
            try:
                async with self.write_lock:
                    if self.session_id != session_id or self.client is not client or not client.is_connected:
                        raise HTTPException(status_code=409, detail="BLE disconnected before the caller-name write.")
                    await client.write_gatt_char(WRITE_UUID, packet, response=True)
            except HTTPException:
                raise
            except Exception as exc:
                self._log_session(
                    "caller_name_test_write_failed",
                    sequence=sequence,
                    error_type=type(exc).__name__,
                    error=str(exc),
                )
                raise HTTPException(status_code=502, detail=f"Caller-name write {sequence} failed: {exc}") from exc
            self._log_session("caller_name_test_write_succeeded", sequence=sequence, frame_id="0x43")
            if sequence < repeat_count:
                await asyncio.sleep(interval_seconds)

        self._log_session("caller_name_test_completed", repeat_count=repeat_count)
        await self.broadcast()
        return {
            **self.snapshot(),
            "call_test": {
                "sent": True,
                "repeat_count": repeat_count,
                "frame_id": "0x43",
                "note": "Sent the observed caller-name frame burst; this does not create a real phone call.",
            },
        }

    async def send_custom_text(self, request: CustomTextRequest) -> dict[str, Any]:
        client = self.client
        session_id = self.session_id
        if not client or not session_id or not client.is_connected:
            raise HTTPException(status_code=409, detail="Connect to the bike before sending custom text.")
        if self.document_transfer["active"]:
            raise HTTPException(status_code=409, detail="Wait for the document transfer to finish first.")
        try:
            packet = custom_text_packet(request.text)
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        if len(request.text.encode("ascii", "replace")) > 15:
            raise HTTPException(status_code=422, detail="Custom text must be 15 ASCII characters or fewer.")
        self._log_session("custom_text_write_started", frame_id=f"0x{ID_CUSTOM_TEXT:02X}", text_length=len(request.text.strip()))
        try:
            async with self.write_lock:
                if self.session_id != session_id or self.client is not client or not client.is_connected:
                    raise HTTPException(status_code=409, detail="BLE disconnected before the custom-text write.")
                await client.write_gatt_char(WRITE_UUID, packet, response=True)
        except HTTPException:
            raise
        except Exception as exc:
            self._log_session("custom_text_write_failed", error_type=type(exc).__name__, error=str(exc))
            raise HTTPException(status_code=502, detail=f"Custom-text write failed: {exc}") from exc
        self._log_session("custom_text_write_succeeded", frame_id=f"0x{ID_CUSTOM_TEXT:02X}")
        return {**self.snapshot(), "custom_text_sent": True}

    async def _write_transfer_packet(
        self, client: BleakClient, session_id: str, packet: bytes, frame_id: int, sequence: int | None = None
    ) -> None:
        if self.session_id != session_id or self.client is not client or not client.is_connected:
            raise HTTPException(status_code=409, detail="BLE disconnected during the document transfer.")
        self._log_session(
            "document_transfer_write_started",
            frame_id=f"0x{frame_id:02X}",
            sequence=sequence,
            length=len(packet),
        )
        try:
            async with self.write_lock:
                if self.session_id != session_id or self.client is not client or not client.is_connected:
                    raise HTTPException(status_code=409, detail="BLE disconnected before the document write.")
                await client.write_gatt_char(WRITE_UUID, packet, response=True)
        except HTTPException:
            raise
        except Exception as exc:
            self._log_session(
                "document_transfer_write_failed",
                frame_id=f"0x{frame_id:02X}",
                sequence=sequence,
                error_type=type(exc).__name__,
                error=str(exc),
            )
            raise HTTPException(status_code=502, detail=f"Document frame write failed: {exc}") from exc

    async def _wait_for_document_ack(self, minimum_frame: int, timeout: float = 8.0) -> tuple[int, int] | None:
        queue = self.document_ack_queue
        if queue is None:
            return None
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                ack_frame, status = await asyncio.wait_for(queue.get(), timeout=max(0.05, deadline - time.monotonic()))
            except asyncio.TimeoutError:
                return None
            if ack_frame >= minimum_frame:
                return ack_frame, status
        return None

    async def send_document_image(
        self, image_data: bytes, *, name: str, picture_number: int
    ) -> dict[str, Any]:
        client = self.client
        session_id = self.session_id
        if not client or not session_id or not client.is_connected:
            raise HTTPException(status_code=409, detail="Connect to the bike before uploading a document.")
        try:
            total_frames = document_frame_count(len(image_data))
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        if not 1 <= picture_number <= 5:
            raise HTTPException(status_code=422, detail="Picture number must be between 1 and 5.")
        cleaned_name = "".join(char if 32 <= ord(char) <= 126 else " " for char in name).strip()[:10]

        async with self.document_transfer_lock:
            if self.document_transfer["active"]:
                raise HTTPException(status_code=409, detail="A document transfer is already in progress.")
            queue: asyncio.Queue[tuple[int, int]] = asyncio.Queue(maxsize=128)
            while not queue.empty():
                queue.get_nowait()
            self.document_ack_queue = queue
            self.document_transfer = {
                "active": True,
                "sent_frames": 0,
                "total_frames": total_frames,
                "acknowledged_frames": 0,
                "status": "starting",
                "message": "Starting the document transfer…",
                "picture_number": picture_number,
            }
            self._log_session(
                "document_transfer_started",
                frame_id=f"0x{ID_DOC_INIT:02X}",
                total_frames=total_frames,
                image_bytes=len(image_data),
                picture_number=picture_number,
                label_length=len(cleaned_name),
            )
            await self.broadcast()
            try:
                await self._write_transfer_packet(
                    client,
                    session_id,
                    document_init_packet(
                        picture_number=picture_number,
                        total_frames=total_frames,
                        frame_number=1,
                        status=1,
                        name=cleaned_name,
                    ),
                    ID_DOC_INIT,
                )
                await asyncio.sleep(0.15)
                self.document_transfer["status"] = "sending"
                self.document_transfer["message"] = "Sending image chunks to the bike…"
                for sequence in range(1, total_frames + 1):
                    start = (sequence - 1) * DOC_CHUNK_SIZE
                    chunk = image_data[start : start + DOC_CHUNK_SIZE]
                    packet = document_chunk_packet(sequence, chunk)
                    await self._write_transfer_packet(client, session_id, packet, ID_DOC_CHUNK, sequence)
                    self.document_transfer["sent_frames"] = sequence
                    if sequence % 20 == 0 and sequence < total_frames:
                        await self.broadcast()
                        ack = await self._wait_for_document_ack(sequence)
                        if ack is None:
                            raise HTTPException(
                                status_code=504,
                                detail=f"Bike did not acknowledge document frame {sequence} within 8 seconds.",
                            )
                        ack_frame, ack_status = ack
                        if ack_status != 1:
                            raise HTTPException(
                                status_code=502,
                                detail=f"Bike reported transfer status {ack_status} at frame {ack_frame}.",
                            )
                        self.document_transfer["acknowledged_frames"] = ack_frame
                        await self.broadcast()
                    await asyncio.sleep(0.03)

                await self._write_transfer_packet(
                    client,
                    session_id,
                    document_init_packet(
                        picture_number=picture_number,
                        total_frames=total_frames,
                        frame_number=total_frames,
                        status=3,
                        name=cleaned_name,
                    ),
                    ID_DOC_INIT,
                )
                final_ack = await self._wait_for_document_ack(total_frames)
                if final_ack and final_ack[1] == 3:
                    self.document_transfer.update(
                        status="completed",
                        active=False,
                        acknowledged_frames=final_ack[0],
                        message="Bike acknowledged the document transfer.",
                    )
                    self._log_session("document_transfer_completed", acknowledged_frame=final_ack[0])
                else:
                    self.document_transfer.update(
                        status="sent_unconfirmed",
                        active=False,
                        message="All image chunks were sent, but the final bike confirmation was not received.",
                    )
                    self._log_session("document_transfer_final_ack_missing")
                await self.broadcast()
                return {**self.snapshot(), "document_transfer_result": dict(self.document_transfer)}
            except Exception as exc:
                self.document_transfer.update(status="failed", active=False, message=str(exc))
                self._log_session(
                    "document_transfer_failed",
                    sent_frames=self.document_transfer["sent_frames"],
                    error_type=type(exc).__name__,
                    error=str(exc),
                )
                await self.broadcast()
                raise
            finally:
                self.document_ack_queue = None

    async def _disconnect_locked(self, reason: str) -> None:
        client = self.client
        session_id = self.session_id
        log_path = self.session_log_path
        self.local_disconnect_reason = reason
        if self.disconnect_context is not None:
            self.disconnect_context["reason"] = reason
        if client:
            self._log_session(
                "app_disconnect_requested",
                reason=reason,
                is_connected=client.is_connected,
                elapsed_seconds=round(time.monotonic() - self.connected_at, 3)
                if self.connected_at is not None
                else None,
            )
        self.client = None
        self.address = None
        self.connected_at = None
        monitor_task = self.link_monitor_task
        self.link_monitor_task = None
        if monitor_task is not None and monitor_task is not asyncio.current_task():
            monitor_task.cancel()
        cyclic_task = self.cyclic_write_task
        self.cyclic_write_task = None
        if cyclic_task is not None and cyclic_task is not asyncio.current_task():
            cyclic_task.cancel()
        if client and client.is_connected:
            async with self.write_lock:
                try:
                    await client.stop_notify(NOTIFY_UUID)
                    self._append_session_event(log_path, session_id, "notification_subscription_stopped")
                except Exception as exc:
                    self._append_session_event(
                        log_path,
                        session_id,
                        "stop_notify_failed",
                        error_type=type(exc).__name__,
                        error=str(exc),
                    )
                try:
                    await client.disconnect()
                    self._append_session_event(log_path, session_id, "app_disconnect_call_completed")
                except Exception as exc:
                    self._append_session_event(
                        log_path,
                        session_id,
                        "app_disconnect_call_failed",
                        error_type=type(exc).__name__,
                        error=str(exc),
                    )
        elif client:
            self._append_session_event(log_path, session_id, "already_disconnected_before_cleanup")
        if log_path is not None:
            self._append_session_event(log_path, session_id, "session_ended", reason=reason)
            self.last_session_log_path = log_path
        self.session_id = None
        self.session_log_path = None
        self.disconnect_context = None
        self.local_disconnect_reason = None

    async def disconnect(self, reason: str = "disconnect_endpoint_requested") -> None:
        async with self.lock:
            self.status = "disconnecting"
            self.message = "Closing the BLE connection…"
            self._log_session("disconnect_endpoint_called", reason=reason)
            await self.broadcast()
            await self._disconnect_locked(reason)
            self.status = "disconnected"
            self.message = "Disconnected."
            await self.broadcast()


bridge = BikeBridge()


@asynccontextmanager
async def lifespan(_app: FastAPI):
    yield
    await bridge.disconnect(reason="server_shutdown")


app = FastAPI(title="TVS Bike Telemetry", lifespan=lifespan)


@app.get("/", response_class=HTMLResponse)
async def home() -> str:
    return PAGE


@app.get("/api/state")
async def get_state() -> dict[str, Any]:
    return bridge.snapshot()


@app.get("/api/scan")
async def scan() -> list[dict[str, str]]:
    return await bridge.scan()


@app.post("/api/connect")
async def connect(request: ConnectRequest) -> dict[str, Any]:
    await bridge.connect(request)
    return bridge.snapshot()


@app.post("/api/mobile-status")
async def update_mobile_status(request: MobileStatusRequest) -> dict[str, Any]:
    return await bridge.set_mobile_status(request)


@app.post("/api/call-test")
async def call_name_test(request: CallerNameTestRequest) -> dict[str, Any]:
    return await bridge.send_caller_name_test(request)


@app.post("/api/custom-text")
async def custom_text_test(request: CustomTextRequest) -> dict[str, Any]:
    return await bridge.send_custom_text(request)


@app.post("/api/documents/upload")
async def upload_document(
    request: Request,
    name: str = Query(default="Document", max_length=64),
    picture_number: int = Query(default=1, ge=1, le=5),
) -> dict[str, Any]:
    max_bytes = DOC_CHUNK_SIZE * DOC_MAX_FRAMES
    received = bytearray()
    async for part in request.stream():
        if len(received) + len(part) > max_bytes:
            raise HTTPException(status_code=413, detail="Image exceeds the 768 KB first-pass limit.")
        received.extend(part)
    image_data = bytes(received)
    if not image_data:
        raise HTTPException(status_code=422, detail="Choose a PNG image first.")
    if not image_data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise HTTPException(status_code=415, detail="For this first pass, upload a PNG image.")
    try:
        document_frame_count(len(image_data))
    except ValueError as exc:
        raise HTTPException(status_code=413, detail=str(exc)) from exc
    return await bridge.send_document_image(image_data, name=name, picture_number=picture_number)


@app.post("/api/disconnect")
async def disconnect() -> dict[str, Any]:
    await bridge.disconnect(reason="disconnect_button")
    return bridge.snapshot()


@app.get("/api/session-log")
async def download_session_log() -> FileResponse:
    path = bridge.session_log_path or bridge.last_session_log_path
    if path is None or not path.is_file():
        raise HTTPException(status_code=404, detail="No BLE session log has been created yet.")
    return FileResponse(path, filename=path.name, media_type="application/x-ndjson")


@app.get("/events")
async def event_stream(request: Request) -> StreamingResponse:
    queue: asyncio.Queue[str] = asyncio.Queue(maxsize=1)
    bridge.event_queues.add(queue)

    async def events():
        try:
            yield f"data: {json.dumps(bridge.snapshot())}\n\n"
            while not await request.is_disconnected():
                try:
                    payload = await asyncio.wait_for(queue.get(), timeout=15)
                    yield f"data: {payload}\n\n"
                except asyncio.TimeoutError:
                    yield ": keep-alive\n\n"
        finally:
            bridge.event_queues.discard(queue)

    return StreamingResponse(
        events(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


PAGE = r"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>TVS Bike Telemetry</title>
  <style>
    :root { color-scheme: dark; --bg:#09111a; --panel:#111d2a; --line:#243648; --muted:#93a4b6; --text:#edf4fb; --green:#48d597; --blue:#65b8ff; }
    * { box-sizing:border-box; }
    body { margin:0; background:radial-gradient(ellipse at top,#14283a,var(--bg) 55%); color:var(--text); font:15px/1.45 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif; min-height:100vh; }
    main { width:min(1050px,94vw); margin:0 auto; padding:36px 0 56px; }
    h1 { margin:0; font-size:clamp(28px,5vw,42px); letter-spacing:-.04em; }
    .sub { color:var(--muted); margin:8px 0 25px; }
    .panel { background:rgba(17,29,42,.94); border:1px solid var(--line); border-radius:16px; padding:20px; box-shadow:0 16px 48px #0003; }
    .controls { display:grid; grid-template-columns:minmax(180px,1fr) minmax(160px,1fr) auto auto auto; gap:12px; align-items:end; }
    label { display:block; color:var(--muted); font-size:12px; margin-bottom:6px; }
    select,input[type=text],input[type=number] { width:100%; min-height:42px; padding:9px 11px; color:var(--text); background:#0a1420; border:1px solid var(--line); border-radius:9px; }
    button { border:0; border-radius:9px; padding:11px 16px; min-height:42px; font-weight:650; color:#07140f; background:var(--green); cursor:pointer; }
    button.secondary { color:var(--text); background:#243648; }
    button:disabled { opacity:.5; cursor:wait; }
    .startup { display:flex; align-items:center; gap:9px; color:var(--muted); margin-top:14px; font-size:13px; }
    .startup input { accent-color:var(--green); }
    .mobile-status-controls { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:12px; margin-top:16px; }
    .test-tools { display:grid; grid-template-columns:minmax(180px,1fr) auto; gap:12px; align-items:end; margin-top:18px; }
    .upload-tools { display:grid; grid-template-columns:minmax(180px,1fr) 130px auto; gap:12px; align-items:end; margin-top:12px; }
    input[type=file] { width:100%; min-height:42px; padding:8px; color:var(--text); background:#0a1420; border:1px solid var(--line); border-radius:9px; }
    .subpanel { margin-top:18px; padding:16px; border:1px solid var(--line); border-radius:13px; background:#0b1622; }
    .subpanel h2 { margin:0 0 10px; font-size:17px; }
    .activity { color:var(--muted); margin:8px 0 0; }
    .event-list { list-style:none; margin:10px 0 0; padding:0; max-height:180px; overflow:auto; }
    .event-list li { padding:6px 0; border-top:1px solid var(--line); font-size:13px; }
    .hint { margin:12px 0 0; color:var(--muted); font-size:12px; }
    .status { display:flex; align-items:center; gap:10px; margin:20px 0 16px; color:var(--muted); }
    .dot { width:9px; height:9px; border-radius:50%; background:#677687; }
    .dot.connected,.dot.streaming { background:var(--green); box-shadow:0 0 14px #48d59788; }
    .dot.error { background:#ff7373; }
    .grid { display:grid; grid-template-columns:repeat(4,1fr); gap:12px; margin-top:14px; }
    .card { background:#0b1622; border:1px solid var(--line); border-radius:13px; padding:17px; min-height:112px; }
    .label { color:var(--muted); font-size:12px; }
    .value { font-size:27px; font-weight:700; letter-spacing:-.03em; margin-top:9px; font-variant-numeric:tabular-nums; }
    .unit { color:var(--muted); font-size:13px; font-weight:500; letter-spacing:0; }
    .foot { color:var(--muted); font-size:12px; margin-top:18px; display:flex; justify-content:space-between; gap:10px; flex-wrap:wrap; }
    .notice { border-left:2px solid var(--blue); padding:9px 12px; margin-top:18px; background:#0a1420; color:var(--muted); font-size:12px; }
    #error { color:#ff9999; min-height:20px; margin-top:10px; font-size:13px; }
    @media(max-width:760px) { .controls { grid-template-columns:1fr 1fr; } .mobile-status-controls { grid-template-columns:repeat(2,minmax(0,1fr)); } .grid { grid-template-columns:repeat(2,1fr); } }
    @media(max-width:440px) { .controls,.mobile-status-controls,.test-tools,.upload-tools { grid-template-columns:1fr; } .grid { gap:8px; } .card { padding:13px; min-height:100px; } .value { font-size:23px; } }
  </style>
</head>
<body>
<main>
  <h1>Bike telemetry</h1>
  <p class="sub">Live BLE dashboard · TVS Apache RTR 310</p>
  <section class="panel">
    <div class="controls">
      <div><label for="devices">Nearby bike</label><select id="devices"><option value="">Scan to find the bike</option></select></div>
      <div><label for="rider">Startup rider name</label><input id="rider" type="text" maxlength="17" value="Rider"></div>
      <button id="scan">Scan</button>
      <button id="connect">Connect</button>
      <button id="disconnect" class="secondary">Disconnect</button>
    </div>
    <label class="startup"><input id="startup" type="checkbox" checked> Send the APK-derived rider-name frame after connecting</label>
    <p class="hint">That frame preceded the telemetry stream in our capture. It sends the name above to the bike; it is not a display-control command.</p>
    <div class="mobile-status-controls">
      <div><label for="signalLevel">Mock cellular signal (0–4)</label><input id="signalLevel" type="number" min="0" max="4" step="1" value="4"></div>
      <div><label for="phoneBattery">Mock phone battery (%)</label><input id="phoneBattery" type="number" min="0" max="100" step="1" value="80"></div>
      <div><label for="networkType">Mock Android network type code</label><input id="networkType" type="number" min="0" max="255" step="1" value="13"></div>
    </div>
    <button id="sendStatus" type="button">Send status now</button>
    <p class="hint">These are synthetic phone values. Send status now transmits a `0x4A` frame immediately; the repeating status writer then uses the updated values. Battery is sent as the APK's 0–5 bucket.</p>
    <p id="statusPreview" class="hint">Encoded phone-status fields: —</p>
    <div class="test-tools">
      <div><label for="callerName">Caller name for test</label><input id="callerName" type="text" maxlength="17" value="Test Call"></div>
      <button id="callTest" type="button">Trigger call-name test</button>
    </div>
    <p class="hint">Sends the observed `0x43` caller-name frame 8 times, 0.5 s apart. This sends caller-name data; it does not create a real phone call or prove the bike will show a ringing screen.</p>
    <section class="subpanel">
      <h2>Custom display text</h2>
      <div class="test-tools">
        <div><label for="customText">Message for the bike display (15 ASCII characters max)</label><input id="customText" type="text" maxlength="15" placeholder="Have a safe ride"></div>
        <button id="sendCustomText" type="button">Send text</button>
      </div>
      <p class="hint">Sends one first-pass `0x4C` custom-text frame. Character rendering and the bike’s response should be checked on the cluster.</p>
    </section>
    <section class="subpanel">
      <h2>Digi Docs image upload</h2>
      <div class="upload-tools">
        <div><label for="documentImage">PNG image (up to 768 KB)</label><input id="documentImage" type="file" accept="image/png,.png"></div>
        <div><label for="documentSlot">Picture slot</label><select id="documentSlot"><option value="1">1</option><option value="2">2</option><option value="3">3</option><option value="4">4</option><option value="5">5</option></select></div>
        <button id="uploadDocument" type="button">Send image to bike</button>
      </div>
      <p id="documentProgress" class="activity" aria-live="polite">No document transfer in this session.</p>
      <p class="hint">The captured transfer uses 128-byte chunks and bike acknowledgements. This first pass supports PNGs up to the largest confirmed capture (6,000 chunks / 768,000 bytes); slot behavior beyond the captured first picture is not verified. Upload only starts after confirmation.</p>
    </section>
    <div class="status"><span id="dot" class="dot"></span><span id="status">Disconnected</span></div>
    <div class="grid">
      <div class="card"><div class="label">Speed</div><div class="value" id="speed">— <span class="unit">km/h</span></div></div>
      <div class="card"><div class="label">Engine RPM</div><div class="value" id="rpm">— <span class="unit">rpm</span></div></div>
      <div class="card"><div class="label">Odometer</div><div class="value" id="odo">— <span class="unit">km</span></div></div>
      <div class="card"><div class="label">Fuel level</div><div class="value" id="fuel">— <span class="unit">%</span></div></div>
      <div class="card"><div class="label">Gear</div><div class="value" id="gear">—</div></div>
      <div class="card"><div class="label">Turn signal status</div><div class="value" id="turnSignal">—</div></div>
      <div class="card"><div class="label">Throttle</div><div class="value" id="throttle">— <span class="unit">%</span></div></div>
      <div class="card"><div class="label">Average speed</div><div class="value" id="average">— <span class="unit">km/h</span></div></div>
      <div class="card"><div class="label">0–60 time</div><div class="value" id="zero60">— <span class="unit">s</span></div></div>
    </div>
    <section class="subpanel" aria-live="polite">
      <h2>Handlebar music controls</h2>
      <div id="dpadState" class="activity">Waiting for a music-control frame</div>
      <ul id="dpadEvents" class="event-list"><li>No music commands observed yet.</li></ul>
      <p class="hint">The bike reports music commands in frame `0x6B`. The app maps play, pause, toggle, next/previous track, and volume. Physical D-pad directions are not identified in this capture.</p>
    </section>
    <div class="foot"><span id="frames">Frames: —</span><span id="updated">Waiting for first telemetry frame</span><span>Session log: <a id="sessionLog" href="/api/session-log">not started</a></span></div>
    <div id="error" role="status"></div>
  </section>
  <div class="notice">Values are decoded from APK-derived frame offsets and XOR decoding. Verify them against the instrument cluster; some frame fields and units still need cross-checking.</div>
</main>
<script>
const $ = id => document.getElementById(id);
function val(id, value, digits=0) { $(id).childNodes[0].nodeValue = value == null ? '— ' : `${Number(value).toLocaleString(undefined,{maximumFractionDigits:digits})} `; }
function render(s) {
  $('status').textContent = s.message || s.status;
  $('dot').className = `dot ${s.status || ''}`;
  const inputs = s.mobile_status_inputs || {};
  for (const [key, id] of [['cellular_signal_level','signalLevel'],['phone_battery_percent','phoneBattery'],['network_type','networkType']]) {
    if (inputs[key] != null && document.activeElement !== $(id)) $(id).value = inputs[key];
  }
  renderStatusPreview();
  const t = s.telemetry || {};
  val('speed', t.speed_kph);
  val('rpm', t.engine_rpm);
  val('odo', t.odometer_km, 1);
  val('fuel', t.fuel_percent);
  val('gear', t.gear);
  const signalNames = {0:'Off',1:'Right',2:'Left',3:'Hazard'};
  const signalCode = t.turn_signal_status;
  $('turnSignal').textContent = signalCode == null ? '—' : `${signalNames[signalCode] || 'Unknown'} (${signalCode})`;
  val('throttle', t.throttle_percent);
  val('average', t.average_speed_kph);
  val('zero60', t.zero_to_60_seconds, 1);
  const command = s.last_music_command_code;
  const commandNames = {0:'Play',1:'Pause',2:'Play / pause toggle',3:'Next track',4:'Previous track',5:'Volume up',6:'Volume down'};
  $('dpadState').textContent = command == null ? 'Waiting for a music-control command' : `Last command: ${commandNames[command] || `Unknown code ${command}`}`;
  const dpadEvents = s.dpad_events || [];
  const dpadList = $('dpadEvents');
  dpadList.replaceChildren();
  if (!dpadEvents.length) {
    const empty = document.createElement('li'); empty.textContent = 'No music commands observed yet.'; dpadList.append(empty);
  } else {
    dpadEvents.forEach(item => {
      const row = document.createElement('li');
      row.textContent = `${new Date(item.timestamp).toLocaleTimeString()} · ${item.label}`;
      dpadList.append(row);
    });
  }
  const transfer = s.document_transfer || {};
  if (transfer.total_frames) {
    const sent = transfer.sent_frames || 0;
    const total = transfer.total_frames;
    $('documentProgress').textContent = `${transfer.message || transfer.status} · ${sent}/${total} chunks sent · ${transfer.acknowledged_frames || 0} acknowledged`;
  } else if (transfer.message) {
    $('documentProgress').textContent = transfer.message;
  }
  const counts = s.frame_counts || {};
  $('frames').textContent = 'Frames: ' + (Object.keys(counts).length ? Object.entries(counts).map(([k,v]) => `${k} ${v}`).join(' · ') : '—');
  $('updated').textContent = s.last_update ? `Last telemetry: ${new Date(s.last_update).toLocaleTimeString()}` : 'Waiting for first telemetry frame';
  const logName = s.session_log_path ? s.session_log_path.split('/').pop() : null;
  $('sessionLog').textContent = logName || 'not started';
  $('sessionLog').href = '/api/session-log';
  $('sessionLog').title = s.session_log_path || '';
  if (s.status === 'error') $('error').textContent = s.message || '';
  else if (s.status === 'streaming' || s.status === 'connected') $('error').textContent = '';
}
function renderStatusPreview() {
  const signal = Number($('signalLevel').value);
  const battery = Number($('phoneBattery').value);
  const network = Number($('networkType').value);
  const bucket = battery < 10 ? 0 : battery < 20 ? 1 : battery < 40 ? 2 : battery < 60 ? 3 : battery < 80 ? 4 : 5;
  const packed = ((signal & 0x0f) << 4) | bucket;
  $('statusPreview').textContent = `Encoded frame 0x4A: byte 2 = 0x${packed.toString(16).padStart(2,'0')} (signal ${signal}, battery bucket ${bucket}); byte 11 = ${network} (network type)`;
}
async function callApi(path, options={}) {
  const response = await fetch(path, {headers:{'Content-Type':'application/json'}, ...options});
  const data = await response.json();
  if (!response.ok) throw new Error(data.detail || response.statusText);
  return data;
}
const events = new EventSource('/events');
events.onmessage = event => render(JSON.parse(event.data));
events.onerror = () => { $('status').textContent = 'Live update connection interrupted; reconnecting…'; };
callApi('/api/state').then(render).catch(e => $('error').textContent = e.message);
$('scan').onclick = async () => {
  $('error').textContent = 'Scanning nearby BLE devices…';
  $('scan').disabled = true;
  try {
    const devices = await callApi('/api/scan');
    const select = $('devices'); select.replaceChildren(new Option(devices.length ? 'Select the bike' : 'No devices found', ''));
    devices.forEach((device, index) => select.add(new Option(`${device.name}${devices.length > 1 ? ` · ${index + 1}` : ''}`, device.address)));
    $('error').textContent = devices.length ? 'Scan complete. Select the bike and connect.' : 'No BLE devices found. Keep the bike nearby with ignition on and scan again.';
  } catch (e) { $('error').textContent = e.message; }
  finally { $('scan').disabled = false; }
};
$('connect').onclick = async () => {
  const address = $('devices').value;
  if (!address) { $('error').textContent = 'Scan and select a device first.'; return; }
  $('error').textContent = 'Connecting…'; $('connect').disabled = true;
  try {
    const state = await callApi('/api/connect', {method:'POST', body:JSON.stringify({address, rider_name:$('rider').value || 'Rider', send_startup_frame:$('startup').checked, cellular_signal_level:Number($('signalLevel').value), phone_battery_percent:Number($('phoneBattery').value), network_type:Number($('networkType').value)})});
    render(state); $('error').textContent = '';
  } catch (e) { $('error').textContent = e.message; }
  finally { $('connect').disabled = false; }
};
$('sendStatus').onclick = async () => {
  $('sendStatus').disabled = true;
  $('error').textContent = 'Sending phone-status values…';
  try {
    const state = await callApi('/api/mobile-status', {method:'POST', body:JSON.stringify({cellular_signal_level:Number($('signalLevel').value), phone_battery_percent:Number($('phoneBattery').value), network_type:Number($('networkType').value)})});
    render(state); $('error').textContent = state.connected ? 'Status frame sent; repeating writes now use these values.' : 'Values saved for the next connection.';
  } catch (e) { $('error').textContent = e.message; }
  finally { $('sendStatus').disabled = false; }
};
$('signalLevel').oninput = renderStatusPreview;
$('phoneBattery').oninput = renderStatusPreview;
$('networkType').oninput = renderStatusPreview;
$('callTest').onclick = async () => {
  $('callTest').disabled = true;
  $('error').textContent = 'Sending the caller-name frame burst…';
  try {
    const result = await callApi('/api/call-test', {method:'POST', body:JSON.stringify({caller_name:$('callerName').value})});
    render(result); $('error').textContent = 'Caller-name test frames sent. Check whether the bike display reacts.';
  } catch (e) { $('error').textContent = e.message; }
  finally { $('callTest').disabled = false; }
};
$('sendCustomText').onclick = async () => {
  $('sendCustomText').disabled = true;
  $('error').textContent = 'Sending custom text…';
  try {
    const state = await callApi('/api/custom-text', {method:'POST', body:JSON.stringify({text:$('customText').value})});
    render(state); $('error').textContent = 'Custom-text frame sent; check the bike display.';
  } catch (e) { $('error').textContent = e.message; }
  finally { $('sendCustomText').disabled = false; }
};
$('uploadDocument').onclick = async () => {
  const file = $('documentImage').files[0];
  if (!file) { $('error').textContent = 'Choose a PNG image first.'; return; }
  if (file.size > 768000) { $('error').textContent = 'Image exceeds the 768 KB first-pass limit.'; return; }
  if (!confirm(`Send “${file.name}” to picture slot ${$('documentSlot').value}? The transfer may take a few minutes.`)) return;
  $('uploadDocument').disabled = true;
  $('error').textContent = 'Uploading image to the bike…';
  $('documentProgress').textContent = 'Starting image transfer…';
  try {
    const query = new URLSearchParams({name:file.name.replace(/\.png$/i,''), picture_number:$('documentSlot').value});
    const response = await fetch(`/api/documents/upload?${query}`, {method:'POST', headers:{'Content-Type':'image/png'}, body:file});
    const state = await response.json();
    if (!response.ok) throw new Error(state.detail || response.statusText);
    render(state);
    $('error').textContent = state.document_transfer?.status === 'completed' ? 'Bike confirmed the document transfer.' : (state.document_transfer?.message || 'Image transfer finished.');
  } catch (e) { $('error').textContent = e.message; }
  finally { $('uploadDocument').disabled = false; }
};
$('disconnect').onclick = async () => {
  try { render(await callApi('/api/disconnect', {method:'POST'})); }
  catch (e) { $('error').textContent = e.message; }
};
</script>
</body>
</html>"""


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=8765, log_level="info")
