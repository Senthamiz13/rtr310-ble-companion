"""Wire protocol for the TVS bike BLE link.

Reverse engineered from a Bluetooth HCI snoop capture of the official phone
app talking to the bike (device name <bike BLE name>). Representative rider,
display-name, and clock/status frames are compared with captured bytes in
tests/test_bike_protocol.py.

Frame layout (20 bytes, both directions):

    [0]      header   0x5A (bike -> app) or 0x5B (app -> bike; some bike frames too)
    [1]      frame id (NOT obfuscated)
    [2..18]  body, every byte XOR 0xEA (so zero padding shows up as 0xEA on the wire)
    [19]     trailer  0xFF
"""

from __future__ import annotations

from datetime import datetime
import re

# GATT identifiers, read from the capture's service/characteristic discovery.
SERVICE_UUID = "5456534d-5647-5341-5342-454e544f5251"  # ASCII "TVSMVGSASBENTORQ"
NOTIFY_UUID = "00005354-0000-1000-8000-00805f9b34fb"  # handle 0x22, property: notify
WRITE_UUID = "00005352-0000-1000-8000-00805f9b34fb"  # handle 0x26, property: write

XOR_KEY = 0xEA
FRAME_LEN = 20
BODY_LEN = 17
APP_HEADER = 0x5B
TRAILER = 0xFF

# Frame ids written by the phone app in the capture.
ID_RIDER_NAME = 0x52  # sent once, right after notifications are enabled
ID_DISPLAY_NAME = 0x43  # caller/contact-info text frame; do not retain personal payloads
ID_CLOCK_STATUS = 0x4A  # periodic frame carrying the phone clock and date
ID_CUSTOM_TEXT = 0x4C  # app-to-cluster custom text
ID_DOC_INIT = 0x53
ID_DOC_PROGRESS = 0x54
ID_DOC_CHUNK = 0x55

DOC_CHUNK_SIZE = 128
DOC_MAX_FRAMES = 6000  # largest confirmed transfer in the supplied capture

# What the phone does, in order (seconds are relative to the first CCCD write):
#   0.0  write 0x0001 to the notify CCCD (handle 0x23); bike starts streaming at once
#   0.1  write rider-name frame (0x52) with a Write Request
#   2.1  first clock/status frame (0x4A), then one every 2 s
CLOCK_FRAME_FIRST_DELAY_SECONDS = 2.0
CLOCK_FRAME_INTERVAL_SECONDS = 2.0


def build_frame(frame_id: int, body: bytes = b"", header: int = APP_HEADER) -> bytes:
    """Build one 20-byte frame: raw header and id, XOR-obfuscated zero-padded body."""
    if len(body) > BODY_LEN:
        raise ValueError(f"body is {len(body)} bytes, maximum is {BODY_LEN}")
    padded = bytes(body).ljust(BODY_LEN, b"\x00")
    return bytes([header, frame_id]) + bytes(b ^ XOR_KEY for b in padded) + bytes([TRAILER])


def text_frame(frame_id: int, text: str) -> bytes:
    return build_frame(frame_id, text.encode("ascii", "replace")[:BODY_LEN])


def rider_name_packet(name: str) -> bytes:
    """Frame 0x52: the name the phone app sends first (capture: "sen")."""
    return text_frame(ID_RIDER_NAME, name or "Rider")


def display_name_packet(name: str) -> bytes:
    """Frame 0x43: captured caller/contact-name text; direction/state is unproven."""
    return text_frame(ID_DISPLAY_NAME, name)


def clock_status_packet(
    now: datetime | None = None,
    signal_level: int = 3,
    phone_battery_percent: int = 50,
    network_type: int = 0,
) -> bytes:
    """Frame 0x4A: the periodic frame carrying the phone's clock and date.

    Body layout, as frame byte index (decoded value in the capture):
      2   (signal_level << 4) | battery_bucket; both fields are packed by the APK
      3   0x78                         APK's default over-speed limit (120)
      4,5 0                            zero in the idle capture; purpose unresolved
      6   hour, 12-hour clock          5 and 6 in the capture (it was 5:58 PM to 6:00 PM)
      7   minute
      8   second
      9   1 if PM else 0               inferred from the capture (always PM there)
      10  0                            zero in the capture; missed-call related in APK
      11  network_type                 APK reads this from its NETWORK_TYPE preference
      12  day of month
      13  month
      14  year % 100

    The APK maps battery percentage to buckets 0..5: 0–9, 10–19, 20–39,
    40–59, 60–79, and 80–100 percent. The capture's low nibble is 3, so the
    exact phone percentage is not recoverable from this trace.
    """
    if not 0 <= signal_level <= 4:
        raise ValueError("signal_level must be between 0 and 4")
    if not 0 <= phone_battery_percent <= 100:
        raise ValueError("phone_battery_percent must be between 0 and 100")
    if not 0 <= network_type <= 255:
        raise ValueError("network_type must be between 0 and 255")

    now = now or datetime.now()
    body = bytearray(BODY_LEN)  # body[i] is frame byte index i + 2
    if phone_battery_percent < 10:
        battery_bucket = 0
    elif phone_battery_percent < 20:
        battery_bucket = 1
    elif phone_battery_percent < 40:
        battery_bucket = 2
    elif phone_battery_percent < 60:
        battery_bucket = 3
    elif phone_battery_percent < 80:
        battery_bucket = 4
    else:
        battery_bucket = 5
    body[0] = (signal_level << 4) | battery_bucket
    body[1] = 0x78
    body[4] = now.hour % 12 or 12
    body[5] = now.minute
    body[6] = now.second
    body[7] = 1 if now.hour >= 12 else 0
    body[9] = network_type
    body[10] = now.day
    body[11] = now.month
    body[12] = now.year % 100
    return build_frame(ID_CLOCK_STATUS, bytes(body))


def mobile_status_packet(
    cellular_signal_level: int = 3,
    phone_battery_percent: int = 80,
    network_type: int = 13,
) -> bytes:
    """Build the APK's 0x4A clock/status frame using supplied phone values."""
    return clock_status_packet(
        signal_level=cellular_signal_level,
        phone_battery_percent=phone_battery_percent,
        network_type=network_type,
    )


def custom_text_packet(text: str) -> bytes:
    """Build a first-pass 0x4C custom display text write (20 bytes).

    The APK's generic custom-text writer uses UTF-8 text in a fixed 20-byte
    frame. The capture shows a one-byte control prefix before some messages;
    this implementation uses the observed simple marker 0x01. Keep the text
    to printable ASCII because non-ASCII rendering has not been verified.
    """
    cleaned = re.sub(r"[^\x20-\x7e]", " ", text)
    if not cleaned.strip():
        raise ValueError("text must contain at least one printable character")
    encoded = cleaned.encode("ascii", "replace")[:15]
    body = bytes([0x00, 0x01]) + encoded
    return build_frame(ID_CUSTOM_TEXT, body)


def document_frame_count(data_length: int) -> int:
    if data_length <= 0:
        raise ValueError("document image is empty")
    frames = (data_length + DOC_CHUNK_SIZE - 1) // DOC_CHUNK_SIZE
    if frames > DOC_MAX_FRAMES:
        raise ValueError(
            f"image requires {frames} chunks; this first implementation is capped at "
            f"{DOC_MAX_FRAMES} chunks (768,000 bytes) based on the confirmed capture"
        )
    return frames


def document_init_packet(
    *, picture_number: int, total_frames: int, frame_number: int, status: int, name: str = ""
) -> bytes:
    """Build the variable-length 0x53 document init/completion frame.

    Field order is based on the APK utility and the captured 6,000-frame
    transfer: picture number, total frame count, current frame, status, slot,
    UTF-8 label (up to 10 bytes), zero padding, and 0xFF trailer.
    """
    if not 1 <= picture_number <= 255:
        raise ValueError("picture_number must be between 1 and 255")
    if not 1 <= total_frames <= DOC_MAX_FRAMES:
        raise ValueError(f"total_frames must be between 1 and {DOC_MAX_FRAMES}")
    if not 1 <= frame_number <= total_frames:
        raise ValueError("frame_number must be between 1 and total_frames")
    if status not in (1, 3):
        raise ValueError("document status must be 1 (start) or 3 (complete)")
    name_bytes = re.sub(r"[^\x20-\x7e]", " ", name).strip().encode("ascii", "replace")[:10]
    slot = {4: 5, 5: 10}.get(picture_number, 0)
    body = (
        bytes([picture_number])
        + total_frames.to_bytes(2, "big")
        + frame_number.to_bytes(2, "big")
        + bytes([status, slot])
        + name_bytes
    )
    body = body.ljust(20, b"\x00")
    return bytes([0x5A, ID_DOC_INIT]) + bytes(byte ^ XOR_KEY for byte in body) + bytes([TRAILER])


def document_chunk_packet(sequence: int, chunk: bytes) -> bytes:
    """Build one captured-format 0x55 chunk with 128 data bytes and checksum."""
    if not 1 <= sequence <= DOC_MAX_FRAMES:
        raise ValueError(f"sequence must be between 1 and {DOC_MAX_FRAMES}")
    if len(chunk) > DOC_CHUNK_SIZE:
        raise ValueError(f"chunk cannot exceed {DOC_CHUNK_SIZE} bytes")
    sequence_bytes = sequence.to_bytes(2, "big")
    data_bytes = chunk.ljust(DOC_CHUNK_SIZE, b"\x00")
    checksum_input = bytes([0x5A, ID_DOC_CHUNK]) + sequence_bytes + data_bytes
    checksum = (0xFF - (sum(checksum_input) & 0xFF)) & 0xFF
    body = sequence_bytes + data_bytes + bytes([checksum])
    return bytes([0x5A, ID_DOC_CHUNK]) + bytes(byte ^ XOR_KEY for byte in body) + bytes([TRAILER])


def decode_document_ack(frame: bytes) -> tuple[int, int] | None:
    """Return (latest_frame_number, status) from a decoded 0x54 ACK."""
    if len(frame) < 9 or frame[0] != 0x5A or frame[1] != ID_DOC_PROGRESS:
        return None
    return int.from_bytes(frame[5:7], "big"), frame[7]
