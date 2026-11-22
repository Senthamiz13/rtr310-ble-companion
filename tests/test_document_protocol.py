"""Offline checks for the custom-text and Digi Docs BLE protocol.

Run: .venv/bin/python tests/test_document_protocol.py
"""

import asyncio
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

from bike_protocol import (
    DOC_CHUNK_SIZE,
    build_frame,
    custom_text_packet,
    decode_document_ack,
    document_chunk_packet,
    document_frame_count,
    document_init_packet,
)
from bike_web import BikeBridge, home, upload_document


def decoded(packet: bytes) -> bytes:
    output = bytearray(packet)
    for index in range(2, len(output) - 1):
        output[index] ^= 0xEA
    return bytes(output)


class TestDocumentPackets(unittest.TestCase):
    def test_custom_text_uses_captured_frame_shape(self):
        packet = custom_text_packet("Hi Rider")
        self.assertEqual(len(packet), 20)
        self.assertEqual(packet[:2], bytes([0x5B, 0x4C]))
        clear = decoded(packet)
        self.assertEqual(clear[2:4], b"\x00\x01")
        self.assertEqual(clear[4:12], b"Hi Rider")
        self.assertEqual(clear[-1], 0xFF)

    def test_document_init_includes_capture_fields(self):
        packet = document_init_packet(
            picture_number=1,
            total_frames=6000,
            frame_number=1,
            status=1,
            name="license",
        )
        clear = decoded(packet)
        self.assertEqual(packet[:2], bytes([0x5A, 0x53]))
        self.assertEqual(len(packet), 23)
        self.assertEqual(clear[2], 1)
        self.assertEqual(int.from_bytes(clear[3:5], "big"), 6000)
        self.assertEqual(int.from_bytes(clear[5:7], "big"), 1)
        self.assertEqual(clear[7], 1)
        self.assertEqual(clear[8], 0)
        self.assertEqual(clear[9:16], b"license")

    def test_chunk_has_128_byte_data_and_sequence(self):
        packet = document_chunk_packet(1, b"x")
        clear = decoded(packet)
        self.assertEqual(len(packet), 134)
        self.assertEqual(packet[:2], bytes([0x5A, 0x55]))
        self.assertEqual(int.from_bytes(clear[2:4], "big"), 1)
        self.assertEqual(clear[4:5], b"x")
        self.assertEqual(len(clear[4:132]), DOC_CHUNK_SIZE)
        expected_checksum = (0xFF - (sum(b"\x5a\x55\x00\x01" + clear[4:132]) & 0xFF)) & 0xFF
        self.assertEqual(clear[132], expected_checksum)
        self.assertEqual(clear[133], 0xFF)

    def test_ack_decoder_and_confirmed_limit(self):
        body = bytearray(17)
        body[3:5] = (20).to_bytes(2, "big")
        body[5] = 1
        self.assertEqual(decode_document_ack(decoded(build_frame(0x54, body, header=0x5A))), (20, 1))
        self.assertEqual(document_frame_count(768000), 6000)
        with self.assertRaises(ValueError):
            document_frame_count(768001)


class FakeBleakClient:
    is_connected = True

    def __init__(self, bridge: BikeBridge, session_id: str):
        self.bridge = bridge
        self.session_id = session_id
        self.writes: list[bytes] = []

    async def write_gatt_char(self, _uuid: str, packet: bytes, response: bool = True):
        self.writes.append(packet)
        clear = decoded(packet)
        if packet[1] == 0x55:
            sequence = int.from_bytes(clear[2:4], "big")
            if sequence % 20 == 0:
                self._ack(sequence, 1)
        elif packet[1] == 0x53 and clear[7] == 3:
            self._ack(int.from_bytes(clear[5:7], "big"), 3)

    def _ack(self, sequence: int, status: int):
        body = bytearray(17)
        body[3:5] = sequence.to_bytes(2, "big")
        body[5] = status
        self.bridge._notification_received(
            build_frame(0x54, bytes(body), header=0x5A), self.session_id, None
        )


class TestTransferFlow(unittest.IsolatedAsyncioTestCase):
    async def test_image_transfer_completes_with_progress_acks(self):
        bridge = BikeBridge()
        bridge.session_id = "test-session"
        client = FakeBleakClient(bridge, bridge.session_id)
        bridge.client = client
        result = await bridge.send_document_image(
            b"\x89PNG\r\n\x1a\n" + b"x" * 3000,
            name="sample",
            picture_number=1,
        )
        self.assertEqual(result["document_transfer_result"]["status"], "completed")
        self.assertEqual(result["document_transfer_result"]["sent_frames"], 24)
        self.assertEqual(result["document_transfer_result"]["acknowledged_frames"], 24)
        self.assertEqual(sum(packet[1] == 0x55 for packet in client.writes), 24)
        self.assertEqual(sum(packet[1] == 0x53 for packet in client.writes), 2)


class FakeRequest:
    def __init__(self, payload: bytes):
        self.payload = payload

    async def stream(self):
        yield self.payload


class TestDashboardControls(unittest.IsolatedAsyncioTestCase):
    async def test_page_has_custom_text_and_document_controls(self):
        page = await home()
        self.assertIn("customText", page)
        self.assertIn("uploadDocument", page)
        self.assertIn("documentProgress", page)

    async def test_png_upload_requires_a_live_ble_connection(self):
        with self.assertRaises(Exception) as caught:
            await upload_document(FakeRequest(b"\x89PNG\r\n\x1a\n" + b"x" * 32), "demo", 1)
        self.assertEqual(getattr(caught.exception, "status_code", None), 409)

    async def test_non_png_upload_is_rejected_without_ble(self):
        with self.assertRaises(Exception) as caught:
            await upload_document(FakeRequest(b"not an image"), "demo", 1)
        self.assertEqual(getattr(caught.exception, "status_code", None), 415)


if __name__ == "__main__":
    unittest.main()
