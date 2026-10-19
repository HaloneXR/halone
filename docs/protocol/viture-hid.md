# VITURE Gen1 HID

The initial target is **Luma Pro**, USB `35ca:1121` / `35ca:1141`.
No Halone hardware captures have been collected yet. Gen2 (Beast / Pro 2)
and Carina (Luma Ultra) have different protocols and are outside this driver.

## Evidence

The supplied Web SDK generated the command vectors in `VitureSdkVectors`.
A local Node probe supplied synthetic MCU replies and IMU frames through its
shared-memory transport. The SDK accepted mode `0x41`, brightness `4`, and
the four quaternions retained in that fixture. These are SDK interoperability
vectors, **not recordings from glasses**.

Reproduce the SDK 2.4.0 comparison with:

```sh
mise exec -- node tools/viture-sdk-probe.cjs VITURE_XR_Glasses_SDK_for_Web.zip
```

The probe fails if command bytes or SDK-decoded quaternions differ from the
retained vectors. It prints SDK version, archive digest and input/output data.

Archive SHA-256:

```text
Web:     7182a2f88bb685083312ddae7c344935afa7deae50ddf713a3d43dc873437b59
Android: df2dd9d8b060400d37eae5e6db588bbd2b95665b27810c1e0345f180f72cdb72
```

The Android archive's `android/LICENSE` restricts redistribution of its
proprietary libraries. The implementation uses Halone's HID transport and
does not package vendor code. SDK archives remain local reference inputs.

References:

- [VITURE SDK guide](https://www.viture.com/developer/glasses-sdk/glasses):
  Gen1 family, NWU coordinates, mode constants and Android permissions.
- [Luma Pro specifications](https://www.viture.com/academy/product/viture-luma-pro-xr-glasses):
  1920×1200 per eye, 52° advertised diagonal FoV, 3840×1200 SBS at 60 Hz.
- [Gen1 HID implementation](https://github.com/mgschwan/viture_virtual_display/blob/main/viture_connection.c)
  and [WebHID protocol notes](https://github.com/bfvogel/viture-webxr-extension/blob/main/VITURE_PROTOCOL.md):
  interface 0 for IMU, interface 1 for MCU. Their framing details disagree;
  the SDK-generated vectors determine the framing used here. Interface numbers
  still need confirmation from the Luma Pro's Android descriptor dump.

## Framing

Reports are 64 bytes, zero padded. Multi-byte envelope fields are little-endian.

| Offset | Size | Meaning |
|---|---|---|
| 0 | 2 | `ff fe` command, `ff fd` reply, `ff fc` IMU |
| 2 | 2 | CRC-16/XMODEM: polynomial `0x1021`, initial value zero |
| 4 | 2 | Length after this field: 12-byte header remainder plus payload |
| 6 | 8 | Device timing / reserved |
| 14 | 2 | Message ID |
| 16 | 2 | Reserved; the SDK sends zero |
| 18 | N | Payload |

CRC covers offset 4 through the declared end (`6 + length`), excluding padding.
There is no trailing `03` marker. A reply payload starts with status (`0` is
success); returned data follows it. Do not mistake status for the value.

| Message | Payload |
|---|---|
| `0005` | Read brightness |
| `0006` | Write brightness: uint16 little-endian |
| `0007` | Read display mode |
| `0008` | Write display mode: byte |
| `0018` | Pose frequency: `2` = 120 Hz |
| `0015` | Pose stream: `1` on, `0` off |
| `0308` | Pose report |

The SDK decoded a `0308` payload of 36 bytes: 12 bytes of big-endian Euler
floats, 8 bytes of device timing, then big-endian `qw,qx,qy,qz` at report
offsets 38, 42, 46, 50. The driver uses the quaternion, validates its norm and
CRC, and rejects unrelated reports. NWU → Halone is `(-Y, +Z, -X)`.

Tracking timestamps use host arrival time until device-to-host clock alignment
is measured. No angular velocity or scanout timing is claimed. Prediction is
therefore unavailable for this stream. FoV, IPD, image distance and mounting
angle are starting estimates; live motion and off-axis rendering must validate
them before they can be described as measured calibration.
