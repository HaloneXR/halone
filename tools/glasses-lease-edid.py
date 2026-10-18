#!/usr/bin/env python3
"""Make XREAL glasses leasable on Linux, without a reboot.

KWin (and other Wayland compositors) only offer a display for DRM leasing when
the kernel marks it non-desktop, which the glasses' own EDID does not ask for.
This overrides the EDID through debugfs with the glasses' 2D EDID plus a CTA
extension carrying:

  * a Microsoft head-mounted-display VSDB (version 2), which makes the kernel
    set the connector's non-desktop property;
  * the full-SBS 3840x1080 @60 mode, which the glasses only list while they
    are in 3D. It is their 1920x1080 @60 timing with every horizontal value
    and the pixel clock doubled, as the Air 2 Pro reports it (the image size
    included, which it fills with pixels rather than millimetres).

The compositor only re-reads non-desktop when the display goes away and comes
back, so the connector is unplugged and replugged in software afterwards
(amdgpu's trigger_hotplug; elsewhere, replug the glasses by hand).

While the override is on, the glasses are not a desktop monitor: run
`halone-desktop --glasses --lease`. It lasts until `reset` or a reboot.

Usage (as root: debugfs and the override need it)
  glasses-lease-edid.py status
  glasses-lease-edid.py apply  [--connector DP-2] [--dry-run FILE]
  glasses-lease-edid.py reset  [--connector DP-2]
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

DRM = Path("/sys/class/drm")
DEBUG_DRI = Path("/sys/kernel/debug/dri")
XREAL_MANUFACTURER = "MRG"
MICROSOFT_OUI = bytes([0x5C, 0x12, 0xCA])


def manufacturer(edid: bytes) -> str:
    word = edid[8] << 8 | edid[9]
    return "".join(chr((word >> shift & 31) + 64) for shift in (10, 5, 0))


def display_name(edid: bytes) -> str:
    for offset in (54, 72, 90, 108):
        block = edid[offset:offset + 18]
        if block[0] == 0 and block[1] == 0 and block[3] == 0xFC:
            return block[5:].split(b"\n")[0].decode(errors="replace").strip()
    return "?"


def is_overridden(edid: bytes) -> bool:
    return len(edid) >= 256 and MICROSOFT_OUI in edid[128:256]


def dtd_size(block: bytes) -> tuple[int, int, float]:
    clock = (block[0] | block[1] << 8) * 10_000
    h_active = block[2] | (block[4] & 0xF0) << 4
    h_blank = block[3] | (block[4] & 0x0F) << 8
    v_active = block[5] | (block[7] & 0xF0) << 4
    v_blank = block[6] | (block[7] & 0x0F) << 8
    return h_active, v_active, clock / ((h_active + h_blank) * (v_active + v_blank))


def doubled_horizontally(block: bytes) -> bytes:
    """The same mode at twice the width: clock, active, blanking, sync and image width doubled."""
    clock = (block[0] | block[1] << 8) * 2
    h_active = (block[2] | (block[4] & 0xF0) << 4) * 2
    h_blank = (block[3] | (block[4] & 0x0F) << 8) * 2
    h_offset = (block[8] | (block[11] & 0xC0) << 2) * 2
    h_width = (block[9] | (block[11] & 0x30) << 4) * 2
    h_image = (block[12] | (block[14] & 0xF0) << 4) * 2
    out = bytearray(block)
    out[0], out[1] = clock & 0xFF, clock >> 8
    out[2], out[3] = h_active & 0xFF, h_blank & 0xFF
    out[4] = (h_active >> 8) << 4 | (h_blank >> 8)
    out[8], out[9] = h_offset & 0xFF, h_width & 0xFF
    out[11] = (h_offset >> 8) << 6 | (h_width >> 8) << 4 | (block[11] & 0x0F)
    out[12] = h_image & 0xFF
    out[14] = (h_image >> 8) << 4 | (block[14] & 0x0F)
    return bytes(out)


def checksum(block: bytearray) -> None:
    block[127] = -sum(block[:127]) & 0xFF


def leasable_edid(current: bytes) -> bytes:
    base = bytearray(current[:128])
    modes = [base[o:o + 18] for o in (54, 72, 90, 108) if base[o] or base[o + 1]]
    sixty = next((m for m in modes if dtd_size(m)[:2] == (1920, 1080) and abs(dtd_size(m)[2] - 60) < 0.5), None)
    if sixty is None:
        sizes = ", ".join("%dx%d @%.0f" % dtd_size(m) for m in modes)
        raise SystemExit(f"no 1920x1080 @60 mode in the EDID ({sizes}); switch the glasses to 2D and try again")

    base[126] = 1
    checksum(base)

    vsdb = bytes([3 << 5 | 21]) + MICROSOFT_OUI + bytes([0x02, 0x02, 0x00]) + bytes(15)
    extension = bytearray(128)
    extension[0], extension[1] = 0x02, 0x03
    extension[2] = 4 + len(vsdb)
    extension[4:4 + len(vsdb)] = vsdb
    extension[4 + len(vsdb):4 + len(vsdb) + 18] = doubled_horizontally(bytes(sixty))
    checksum(extension)
    return bytes(base) + bytes(extension)


def connectors() -> list[Path]:
    return sorted(p for p in DRM.glob("card*-*") if (p / "edid").exists())


def find_connector(name: str | None) -> Path:
    if name:
        matches = [p for p in connectors() if p.name.split("-", 1)[1] == name]
        if not matches:
            raise SystemExit(f"no connector named {name}")
        return matches[0]
    glasses = [p for p in connectors() if (p / "status").read_text().strip() == "connected"
               and manufacturer((p / "edid").read_bytes() or bytes(128)) == XREAL_MANUFACTURER]
    if len(glasses) != 1:
        raise SystemExit(f"found {len(glasses)} connected XREAL displays; name one with --connector")
    return glasses[0]


def debug_dir(connector: Path) -> Path:
    card, name = connector.name.split("-", 1)
    return DEBUG_DRI / card.removeprefix("card") / name


def replug(connector: Path) -> None:
    trigger = debug_dir(connector) / "trigger_hotplug"
    if not trigger.exists():
        print("no trigger_hotplug for this driver: unplug and replug the glasses")
        return
    trigger.write_text("0\n")
    time.sleep(2)
    for _ in range(5):
        trigger.write_text("1\n")
        time.sleep(2)
        if (connector / "status").read_text().strip() == "connected":
            return
    print("the glasses did not come back: unplug and replug them")


def status(_: argparse.Namespace) -> None:
    for connector in connectors():
        state = (connector / "status").read_text().strip()
        edid = (connector / "edid").read_bytes()
        if state != "connected" or not edid:
            continue
        mark = "overridden, non-desktop" if is_overridden(edid) else "stock"
        print(f"{connector.name.split('-', 1)[1]}: {manufacturer(edid)} {display_name(edid)}, EDID {mark}")


def apply(args: argparse.Namespace) -> None:
    connector = find_connector(args.connector)
    edid = leasable_edid((connector / "edid").read_bytes())
    if args.dry_run:
        Path(args.dry_run).write_bytes(edid)
        print(f"wrote {len(edid)} bytes to {args.dry_run}")
        return
    (debug_dir(connector) / "edid_override").write_bytes(edid)
    replug(connector)
    print(f"{connector.name.split('-', 1)[1]}: override applied; the glasses are now offered for leasing")


def reset(args: argparse.Namespace) -> None:
    connector = find_connector(args.connector)
    (debug_dir(connector) / "edid_override").write_text("reset")
    replug(connector)
    print(f"{connector.name.split('-', 1)[1]}: override removed; the glasses are a desktop monitor again")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("status").set_defaults(run=status)
    for name, run in (("apply", apply), ("reset", reset)):
        command = commands.add_parser(name)
        command.add_argument("--connector", help="e.g. DP-2; found by the EDID's XREAL maker code if left out")
        if name == "apply":
            command.add_argument("--dry-run", metavar="FILE", help="write the EDID to FILE instead of applying it")
        command.set_defaults(run=run)
    args = parser.parse_args()
    try:
        args.run(args)
    except PermissionError as error:
        sys.exit(f"{error.filename}: permission denied; run as root")


if __name__ == "__main__":
    main()
