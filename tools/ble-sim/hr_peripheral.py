"""Simulated heart rate strap for the Haanf emulator. Scripted: stream, drop, go silent, stream."""
import asyncio
import sys
import time

from bumble.core import AdvertisingData, UUID
from bumble.device import Device
from bumble.hci import Address
from bumble.profiles.heart_rate_service import HeartRateService
from bumble.transport import open_transport

NAME = "Haanf Sim HR"
Measurement = HeartRateService.HeartRateMeasurement


def log(message):
    print(f"[{time.strftime('%H:%M:%S')}] {message}", flush=True)


async def main():
    stream_s, silent_s = int(sys.argv[1]), int(sys.argv[2])
    bpm = [120]

    def measurement(_connection):
        return Measurement(heart_rate=bpm[0], sensor_contact_detected=True, rr_intervals=[0.75, 0.76])

    async with await open_transport("android-netsim:name=haanf-sim") as hci:
        device = Device.with_hci(NAME, Address("F0:F1:F2:F3:F4:F5"), hci.source, hci.sink)
        hr = HeartRateService(
            read_heart_rate_measurement=measurement,
            body_sensor_location=HeartRateService.BodySensorLocation.WRIST,
        )
        device.add_services([hr])
        device.advertising_data = bytes(AdvertisingData([
            (AdvertisingData.COMPLETE_LOCAL_NAME, NAME.encode()),
            (AdvertisingData.FLAGS, bytes([0x06])),
            (AdvertisingData.INCOMPLETE_LIST_OF_16_BIT_SERVICE_CLASS_UUIDS, bytes(UUID.from_16_bits(0x180D))),
        ]))
        connections = []
        device.on("connection", lambda c: (connections.append(c), log(f"connected {c.peer_address}")))
        device.on("disconnection", lambda r: log(f"disconnected reason={r}"))
        await device.power_on()
        await device.start_advertising(auto_restart=True)
        log(f"advertising as {NAME}")

        async def stream(seconds, label):
            log(f"phase: {label}")
            sent = 0
            for _ in range(seconds):
                bpm[0] = 120 + (bpm[0] - 119) % 40
                await device.notify_subscribers(hr.heart_rate_measurement_characteristic)
                sent += 1
                await asyncio.sleep(1)
            log(f"  sent {sent} notifications")

        while not connections:
            await asyncio.sleep(0.2)
        await asyncio.sleep(3)
        await stream(stream_s, "normal streaming")

        log("phase: hard link drop")
        count = len(connections)
        await connections[-1].disconnect()
        while len(connections) == count:
            await asyncio.sleep(0.2)
        log("  central reconnected")
        await asyncio.sleep(3)
        await stream(stream_s, "streaming after reconnect")

        log(f"phase: link up but silent for {silent_s}s")
        count = len(connections)
        await asyncio.sleep(silent_s)
        log(f"  reconnections during silence: {len(connections) - count}")
        await asyncio.sleep(3)
        await stream(stream_s, "streaming after silence")
        log("SCRIPT_DONE")
        await asyncio.sleep(3600)


asyncio.run(main())
