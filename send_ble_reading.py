#!/usr/bin/env python3
"""
GlucoProxBLE Test Broadcaster for Linux
Broadcasts simulated blood glucose readings over Bluetooth Low Energy (BLE)
using the BlueZ D-Bus advertising interface.
"""

import sys
import os
import time
import argparse
import threading

try:
    import dbus
    import dbus.service
    import dbus.mainloop.glib
    from gi.repository import GLib
except ImportError as e:
    print(f"Error: Missing required Python D-Bus/GLib libraries: {e}")
    print("If running inside Flatpak (e.g. VS Codium terminal), run with:")
    print("  flatpak-spawn --host python3 send_ble_reading.py")
    sys.exit(1)

try:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
except ImportError:
    print("Error: Missing Python 'cryptography' library.")
    print("Install via: pip install cryptography (or distro package python3-cryptography)")
    sys.exit(1)

BLUEZ_SERVICE_NAME = 'org.bluez'
ADVERTISING_MANAGER_IFACE = 'org.bluez.LEAdvertisingManager1'
ADVERTISEMENT_IFACE = 'org.bluez.LEAdvertisement1'
GENERIC_SERVICE_UUID = '00001801-0000-1000-8000-00805f9b34fb'


def compress_bg(mgdl: float) -> int:
    val = round(mgdl)
    if val <= 0:
        return 0
    if val < 30:
        return 1
    if val < 181:
        return val - 27
    if val < 291:
        return 153 + round((val - 180) / 2.0)
    if val < 429:
        return 208 + round((val - 290) / 3.0)
    return 2


def decompress_bg(compressed: int) -> int:
    if compressed == 0:
        return 0
    if compressed == 1:
        return 25
    if compressed == 2:
        return 420
    if compressed < 152:
        return compressed + 27
    elif compressed < 207:
        return (compressed - 153) * 2 + 180
    else:
        return (compressed - 208) * 3 + 290


def build_ble_packet(device_id: int, bg: int, delta: int, age_min: int,
                     encrypt: bool = True, aes_key: bytes = b'GlucoProxSecret1',
                     night_mode: bool = False) -> bytes:
    raw_age = age_min & 0x3F
    if night_mode:
        raw_age |= 0x40

    payload = bytearray(16)
    payload[0] = compress_bg(bg)

    prev_bg = bg - delta
    if prev_bg > 0:
        payload[1] = compress_bg(prev_bg)

    if encrypt and aes_key and len(aes_key) == 16:
        raw_age |= 0x80
        cipher = Cipher(algorithms.AES(aes_key), modes.ECB())
        encryptor = cipher.encryptor()
        payload = encryptor.update(bytes(payload)) + encryptor.finalize()

    packet = bytearray(19)
    packet[0] = device_id & 0xFF
    packet[1] = (device_id >> 8) & 0xFF
    packet[2] = raw_age & 0xFF
    packet[3:19] = payload
    return bytes(packet)


class GlucoProxAdvertisement(dbus.service.Object):
    PATH = '/org/bluez/glucoprox/adv0'

    def __init__(self, bus, packet_bytes):
        self.bus = bus
        self.packet_bytes = packet_bytes
        dbus.service.Object.__init__(self, bus, self.PATH)

    def get_properties(self):
        return {
            ADVERTISEMENT_IFACE: {
                'Type': 'peripheral',
                'ServiceUUIDs': dbus.Array([GENERIC_SERVICE_UUID], signature='s'),
                'ServiceData': dbus.Dictionary({
                    GENERIC_SERVICE_UUID: dbus.Array([dbus.Byte(b) for b in self.packet_bytes], signature='y')
                }, signature='sv'),
                'Discoverable': dbus.Boolean(True),
            }
        }

    def get_path(self):
        return dbus.ObjectPath(self.PATH)

    @dbus.service.method(ADVERTISEMENT_IFACE, in_signature='', out_signature='')
    def Release(self):
        print("\n[BLE] Advertisement released by BlueZ")

    @dbus.service.method('org.freedesktop.DBus.Properties', in_signature='s', out_signature='a{sv}')
    def GetAll(self, interface):
        if interface != ADVERTISEMENT_IFACE:
            raise Exception('Invalid interface')
        return self.get_properties()[ADVERTISEMENT_IFACE]

    def update_packet(self, new_packet_bytes):
        self.packet_bytes = new_packet_bytes
        props = self.get_properties()[ADVERTISEMENT_IFACE]
        self.PropertiesChanged(ADVERTISEMENT_IFACE, {'ServiceData': props['ServiceData']}, [])

    @dbus.service.signal('org.freedesktop.DBus.Properties', signature='sa{sv}as')
    def PropertiesChanged(self, interface, changed, invalidated):
        pass


def main():
    parser = argparse.ArgumentParser(
        description="Broadcast simulated xDrip blood glucose readings over BLE to TTGO T-Display"
    )
    parser.add_argument("--bg", type=int, default=120, help="Blood glucose in mg/dl (default: 120)")
    parser.add_argument("--delta", type=int, default=3, help="Change / Delta in mg/dl (default: +3)")
    parser.add_argument("--age", type=int, default=0, help="Reading age in minutes (default: 0)")
    parser.add_argument("--device-id", type=lambda x: int(x, 0), default=0x0003,
                        help="Device ID (hex or decimal, default: 0x0003)")
    parser.add_argument("--key", type=str, default="GlucoProxSecret1",
                        help="16-character AES-128 secret key (default: GlucoProxSecret1)")
    parser.add_argument("--night", action="store_true", help="Enable Night Mode flag in BLE packet")
    parser.add_argument("--no-encrypt", action="store_true", help="Disable AES-128 encryption")
    parser.add_argument("--auto-advance", action="store_true",
                        help="Automatically advance age by 1 minute every 60 seconds")
    parser.add_argument("-i", "--interactive", action="store_true",
                        help="Interactive mode: change values in real-time from CLI")

    args = parser.parse_args()

    state = {
        'bg': args.bg,
        'delta': args.delta,
        'age': args.age,
        'device_id': args.device_id,
        'encrypt': not args.no_encrypt,
        'night_mode': args.night,
        'key': args.key.encode('utf-8')[:16].ljust(16, b'\0')
    }

    print("=====================================================")
    print("  GlucoProxBLE Laptop BLE Debug Broadcaster")
    print("=====================================================")
    print(f" Target Device ID : 0x{state['device_id']:04X}")
    print(f" Glucose (BG)     : {state['bg']} mg/dl")
    print(f" Delta (+/-)      : {state['delta']:+d} mg/dl")
    print(f" Reading Age      : {state['age']} min")
    print(f" Night Mode       : {'ON (Dim / Red)' if state['night_mode'] else 'OFF (Normal / White)'}")
    print(f" Encryption       : {'AES-128-ECB' if state['encrypt'] else 'None'}")
    print(f" Service UUID     : {GENERIC_SERVICE_UUID}")
    print("=====================================================")

    initial_packet = build_ble_packet(
        state['device_id'], state['bg'], state['delta'], state['age'],
        encrypt=state['encrypt'], aes_key=state['key'],
        night_mode=state['night_mode']
    )
    print(f" Initial Packet (19 bytes): {initial_packet.hex()}")

    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()

    # Find first available Bluetooth adapter with Advertising Manager
    adapter_path = '/org/bluez/hci0'
    try:
        adapter = bus.get_object(BLUEZ_SERVICE_NAME, adapter_path)
        adv_mgr = dbus.Interface(adapter, ADVERTISING_MANAGER_IFACE)
    except dbus.DBusException as e:
        print(f"\nError: Could not access BlueZ advertising manager on {adapter_path}: {e}")
        print("Make sure Bluetooth is powered on: 'bluetoothctl power on'")
        sys.exit(1)

    ad = GlucoProxAdvertisement(bus, initial_packet)
    loop = GLib.MainLoop()

    def on_register_ok():
        print("\n>>> BLE Advertisement ACTIVE! Broadcasting to ESP32...")
        if args.interactive:
            print("\nInteractive Commands:")
            print("  bg <val>      - Set glucose (e.g. 'bg 140')")
            print("  delta <val>   - Set delta (e.g. 'delta -5')")
            print("  age <val>     - Set age in min (e.g. 'age 10' for yellow test)")
            print("  night [on|off]- Toggle or set Night Mode (Red text & dim)")
            print("  send <b,d,a>  - Set all: bg delta [age] (e.g. 'send 85 -2 0')")
            print("  stale         - Test stale warning (sets age to 10 min)")
            print("  low           - Test low warning (bg 65, delta -4)")
            print("  high          - Test high warning (bg 210, delta +6)")
            print("  q / quit      - Exit\n")

    def on_register_err(err):
        print(f"\nError registering advertisement: {err}")
        loop.quit()

    adv_mgr.RegisterAdvertisement(
        ad.get_path(), {},
        reply_handler=on_register_ok,
        error_handler=on_register_err
    )

    def push_update():
        pkt = build_ble_packet(
            state['device_id'], state['bg'], state['delta'], state['age'],
            encrypt=state['encrypt'], aes_key=state['key'],
            night_mode=state['night_mode']
        )
        ad.update_packet(pkt)
        status_color = "[YELLOW WARNING]" if state['age'] >= 7 else "[NORMAL]"
        night_tag = "[NIGHT MODE]" if state['night_mode'] else "[DAY MODE]"
        print(f"[{time.strftime('%H:%M:%S')}] Broadcasting: BG={state['bg']} mg/dl, "
              f"Delta={state['delta']:+d}, Age={state['age']} min {status_color} {night_tag} (packet: {pkt.hex()[:10]}...)")

    # Optional 60s timer for auto-advancing age
    if args.auto_advance:
        def tick_age():
            state['age'] += 1
            push_update()
            return True
        GLib.timeout_add_seconds(60, tick_age)

    # Interactive input thread
    if args.interactive:
        def console_reader():
            while True:
                try:
                    line = input("> ").strip()
                    if not line:
                        continue
                    parts = line.split()
                    cmd = parts[0].lower()

                    if cmd in ('q', 'quit', 'exit'):
                        GLib.idle_add(loop.quit)
                        break
                    elif cmd == 'bg' and len(parts) > 1:
                        state['bg'] = int(parts[1])
                        GLib.idle_add(push_update)
                    elif cmd == 'delta' and len(parts) > 1:
                        state['delta'] = int(parts[1])
                        GLib.idle_add(push_update)
                    elif cmd == 'age' and len(parts) > 1:
                        state['age'] = int(parts[1])
                        GLib.idle_add(push_update)
                    elif cmd == 'night':
                        if len(parts) > 1:
                            state['night_mode'] = parts[1].lower() in ('on', '1', 'true', 'yes')
                        else:
                            state['night_mode'] = not state['night_mode']
                        print(f"-> Night Mode set to: {'ON' if state['night_mode'] else 'OFF'}")
                        GLib.idle_add(push_update)
                    elif cmd == 'stale':
                        state['age'] = 10
                        print("-> Stale mode: Age set to 10 min (Yellow text test)")
                        GLib.idle_add(push_update)
                    elif cmd == 'low':
                        state['bg'] = 65
                        state['delta'] = -4
                        state['age'] = 0
                        print("-> Low mode: BG=65, Delta=-4, Age=0")
                        GLib.idle_add(push_update)
                    elif cmd == 'high':
                        state['bg'] = 210
                        state['delta'] = 6
                        state['age'] = 0
                        print("-> High mode: BG=210, Delta=+6, Age=0")
                        GLib.idle_add(push_update)
                    elif cmd == 'send' and len(parts) >= 2:
                        state['bg'] = int(parts[1])
                        if len(parts) >= 3:
                            state['delta'] = int(parts[2])
                        if len(parts) >= 4:
                            state['age'] = int(parts[3])
                        GLib.idle_add(push_update)
                    else:
                        print(f"Unknown command: '{line}'. Type 'bg 120', 'age 10', 'stale', 'low', 'high', or 'quit'.")
                except (EOFError, KeyboardInterrupt):
                    GLib.idle_add(loop.quit)
                    break
                except Exception as ex:
                    print(f"Input error: {ex}")

        threading.Thread(target=console_reader, daemon=True).start()
    else:
        print("Press Ctrl+C to stop broadcasting.")

    try:
        loop.run()
    except KeyboardInterrupt:
        pass
    finally:
        print("\nStopping BLE advertisement...")
        try:
            adv_mgr.UnregisterAdvertisement(ad.get_path())
            print("BLE advertisement stopped.")
        except Exception as e:
            print(f"Error unregistering: {e}")


if __name__ == '__main__':
    main()
