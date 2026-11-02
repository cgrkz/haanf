"""Two more simulated devices. 'nohr' offers no heart rate service; 'pair' demands encryption."""
import asyncio
import sys
import time

from bumble import att
from bumble.core import AdvertisingData, UUID
from bumble.device import Device
from bumble.gatt import Characteristic, CharacteristicValue, Service
from bumble.hci import Address
from bumble.pairing import PairingConfig, PairingDelegate
from bumble.profiles.battery_service import BatteryService
from bumble.profiles.heart_rate_service import HeartRateService
from bumble.transport import open_transport

mode = sys.argv[1]


def log(message):
    print(f"[{time.strftime('%H:%M:%S')}] {message}", flush=True)


async def main():
    name = "Haanf Sim NoHR" if mode == "nohr" else "Haanf Sim Paired"
    address = "F0:F1:F2:F3:F4:F6" if mode == "nohr" else "F0:F1:F2:F3:F4:F7"
    async with await open_transport(f"android-netsim:name=haanf-{mode}") as hci:
        device = Device.with_hci(name, Address(address), hci.source, hci.sink)
        if mode == "nohr":
            device.add_services([
                BatteryService(lambda _c: 80),
                Service(UUID("FEE0"), [Characteristic(UUID("FF01"), Characteristic.Properties.READ,
                                                      Characteristic.READABLE, bytes(1))]),
            ])
            advertised = bytes(UUID.from_16_bits(0xFEE0))
            hr = None
        else:
            hr = HeartRateService(
                read_heart_rate_measurement=lambda _c: HeartRateService.HeartRateMeasurement(heart_rate=133),
            )
            device.add_services([hr])
            cccd = [a for a in device.gatt_server.attributes
                    if getattr(a, "type", None) == UUID.from_16_bits(0x2902)]
            for attribute in cccd:
                attribute.permissions = att.Attribute.READABLE | att.Attribute.WRITE_REQUIRES_ENCRYPTION
            log(f"protected {len(cccd)} CCCD attribute(s)")
            device.pairing_config_factory = lambda _c: PairingConfig(sc=True, mitm=False, bonding=True,
                                                                    delegate=PairingDelegate())
            advertised = bytes(UUID.from_16_bits(0x180D))
        device.advertising_data = bytes(AdvertisingData([
            (AdvertisingData.COMPLETE_LOCAL_NAME, name.encode()),
            (AdvertisingData.FLAGS, bytes([0x06])),
            (AdvertisingData.INCOMPLETE_LIST_OF_16_BIT_SERVICE_CLASS_UUIDS, advertised),
        ]))
        device.on("connection", lambda c: log(f"connected {c.peer_address}"))
        device.on("disconnection", lambda r: log(f"disconnected reason={r}"))
        await device.power_on()
        await device.start_advertising(auto_restart=True)
        log(f"advertising as {name}")
        while True:
            await asyncio.sleep(1)
            if hr is not None:
                await device.notify_subscribers(hr.heart_rate_measurement_characteristic)


asyncio.run(main())
