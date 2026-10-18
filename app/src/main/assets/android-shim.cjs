// Copied into the guest rootfs at /opt/andchamber/android-shim.cjs and loaded via NODE_OPTIONS.
// On Android, SELinux makes uv_interface_addresses (behind os.networkInterfaces) fail with EACCES,
// which crashes OpenChamber at startup. Fall back to a loopback-only interface list instead.
'use strict';

const os = require('os');

const original = os.networkInterfaces;
if (typeof original === 'function') {
  os.networkInterfaces = function networkInterfaces(...args) {
    try {
      return original.apply(this, args);
    } catch (_error) {
      return {
        lo: [
          {
            address: '127.0.0.1',
            netmask: '255.0.0.0',
            family: 'IPv4',
            mac: '00:00:00:00:00:00',
            internal: true,
            cidr: '127.0.0.1/8',
          },
        ],
      };
    }
  };
}
