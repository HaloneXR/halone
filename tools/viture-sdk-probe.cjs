// Runs the local vendor SDK as an interoperability oracle. No vendor files are
// retained or bundled into Halone; all replies and pose inputs are synthetic.
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

async function probe() {
    const archive = path.resolve(process.argv[2] ?? 'VITURE_XR_Glasses_SDK_for_Web.zip');
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'halone-viture-sdk-'));
    try {
        execFileSync('unzip', ['-q', archive, 'glasses.js', 'glasses.wasm', '-d', directory]);
        const factory = require(path.join(directory, 'glasses.js'));
        const sdk = await factory({ print: () => {}, printErr: message => process.stderr.write(`${message}\n`) });
        const call = (name, args = []) => sdk.ccall(name, 'number', args.map(() => 'number'), args);
        const imu = sdk._malloc(4104);
        const mcu = sdk._malloc(1032);
        sdk.HEAPU8.fill(0, imu, imu + 4104);
        sdk.HEAPU8.fill(0, mcu, mcu + 1032);
        call('xr_webhid_set_imu_buffer', [imu, 64]);
        call('xr_webhid_set_mcu_buffer', [mcu]);
        const handle = call('xr_device_provider_create', [0x1121]);
        assert.ok(handle);
        assert.equal(call('xr_device_provider_initialize', [handle, 0, 0]), 0);

        const channel = new Int32Array(sdk.HEAPU8.buffer, mcu, 258);
        const commands = [];
        const timer = setInterval(() => {
            if (Atomics.load(channel, 0) !== 1) return;
            const packet = sdk.HEAPU8.slice(mcu + 4, mcu + 68);
            commands.push(Buffer.from(packet).toString('hex'));
            Atomics.store(channel, 0, 0);
            const reply = new Uint8Array(64);
            reply[0] = 0xff; reply[1] = 0xfd;
            reply[4] = 14; reply[14] = packet[14]; reply[15] = packet[15];
            reply[19] = packet[14] === 7 ? 0x41 : 4;
            const crc = crc16(reply, 20);
            reply[2] = crc & 255; reply[3] = crc >> 8;
            sdk.HEAPU8.set(reply, mcu + 520);
            Atomics.store(channel, 129, 1);
            Atomics.notify(channel, 129);
        }, 1);
        try {
            for (const [name, args, expected] of [
                ['xr_device_provider_get_display_mode', [], 0x41],
                ['xr_device_provider_set_display_mode', [0x42], 0],
                ['xr_device_provider_get_brightness_level', [], 4],
                ['xr_device_provider_set_brightness_level', [4], 0],
                ['xr_device_provider_open_imu', [1, 2], 0],
                ['xr_device_provider_close_imu', [1], 0],
            ]) assert.equal(await call(name, [handle, ...args]), expected, name);

            const expectedCommands = [
                'fffee3470c00000000000000000007000000',
                'fffea0780d0000000000000000000800000042',
                'fffe8baa0c00000000000000000005000000',
                'fffe8baa0c00000000000000000005000000',
                'fffe1b900e000000000000000000060000000400',
                'fffe3e340d0000000000000000001800000002',
                'fffe27250d0000000000000000001500000001',
                'fffe06350d0000000000000000001500000000',
            ].map(hex => hex.padEnd(128, '0'));
            assert.deepEqual(commands, expectedCommands);
            assert.equal(await call('xr_device_provider_start', [handle]), 0);
            call('xr_webhid_register_imu_pose_callback', [handle]);
            assert.equal(await call('xr_device_provider_open_imu', [handle, 1, 2]), 0);

            const ring = new Int32Array(sdk.HEAPU8.buffer, imu, 2);
            const posePointer = call('xr_webhid_get_pose_ptr');
            const half = Math.sqrt(0.5);
            const poses = [];
            for (const quaternion of [[1, 0, 0, 0], [half, half, 0, 0], [half, 0, half, 0], [half, 0, 0, half]]) {
                const packet = new Uint8Array(64);
                packet[0] = 255; packet[1] = 252; packet[4] = 48; packet[14] = 8; packet[15] = 3;
                const view = new DataView(packet.buffer);
                [30, 20, 10].forEach((value, index) => view.setFloat32(18 + index * 4, value, false));
                quaternion.forEach((value, index) => view.setFloat32(38 + index * 4, value, false));
                const crc = crc16(packet, 54);
                packet[2] = crc & 255; packet[3] = crc >> 8;
                const head = Atomics.load(ring, 0);
                sdk.HEAPU8.set(packet, imu + 8 + head * 64);
                Atomics.store(ring, 0, (head + 1) % 64);
                Atomics.notify(ring, 0);
                await new Promise(resolve => setTimeout(resolve, 100));
                const decoded = Array.from(new Float32Array(sdk.HEAPU8.buffer, posePointer, 7));
                [...[30, 20, 10], ...quaternion].forEach((expected, index) => assert.ok(Math.abs(decoded[index] - expected) < 0.00001));
                poses.push({ packet: Buffer.from(packet).toString('hex'), decoded });
            }
            console.log(JSON.stringify({
                sdkVersion: sdk.ccall('GetVersionString', 'string', [], []),
                archiveSha256: crypto.createHash('sha256').update(fs.readFileSync(archive)).digest('hex'),
                devicePid: '0x1121', transport: 'synthetic', commands: expectedCommands, poses,
            }, null, 2));
        } finally {
            clearInterval(timer);
        }
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
}

function crc16(packet, end) {
    let crc = 0;
    for (let at = 4; at < end; at++) {
        crc ^= packet[at] << 8;
        for (let bit = 0; bit < 8; bit++) crc = ((crc << 1) ^ (crc & 0x8000 ? 0x1021 : 0)) & 65535;
    }
    return crc;
}

// Emscripten's worker pool keeps Node alive after the probe has completed.
probe().then(() => process.exit(0), error => { console.error(error); process.exit(1); });
