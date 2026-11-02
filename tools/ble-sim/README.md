# Simulated Bluetooth peripherals

These scripts use [Bumble](https://github.com/google/bumble) to act as Bluetooth devices on the Android emulator's virtual radio. They test Haanf's real Bluetooth code against a real GATT peer, including failures that are hard to stage with a watch.

This is a software check. It says nothing about how any real watch behaves, so never count it as hardware evidence.

## Set up

Start an emulator first, because it also starts the virtual radio. Then, outside the repo:

```bash
python3 -m venv /tmp/bumble && /tmp/bumble/bin/pip install bumble grpcio protobuf
```

## Run

| Command | Simulates |
| --- | --- |
| `hr_peripheral.py <stream_s> <silent_s>` | A heart rate strap. Streams, drops the link, streams, goes silent while connected, streams. |
| `other_peripheral.py nohr` | A device with no 0x180D service, the path for a non-standard watch. |
| `other_peripheral.py pair` | A strap that demands encryption before notifications, the path for a watch that needs pairing. |

```bash
/tmp/bumble/bin/python -I tools/ble-sim/hr_peripheral.py 20 45
```

Android's pairing window lasts 30 seconds, so finish the pairing dialog promptly.

Restarting `other_peripheral.py pair` loses its bond keys, so the phone side must forget the device before pairing again.
