const dgram = require('dgram');
const net = require('net');
const os = require('os');
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');
const crypto = require('crypto');

const PORT = 1716;
const DEVICE_ID = 'win11_' + crypto.randomBytes(4).toString('hex');
const DEVICE_NAME = os.hostname() || 'Windows 11';

console.log('========================================================');
console.log('  KDE Connect Image & Text Clipboard Sync (Windows 11)');
console.log(`  Device Name: ${DEVICE_NAME} (${DEVICE_ID})`);
console.log('========================================================\n');

// Active TCP connections to paired/connected devices
const activeSockets = new Map();
let lastClipboardHash = '';

// Helper to calculate hash to avoid feedback loops
function getHash(data) {
  return crypto.createHash('sha256').update(data).digest('hex');
}

// 1. Setup TCP Server for incoming Android connections
const tcpServer = net.createServer((socket) => {
  const remoteAddress = socket.remoteAddress;
  console.log(`[TCP] Connected device from ${remoteAddress}`);

  let buffer = '';
  socket.on('data', (chunk) => {
    buffer += chunk.toString('utf8');
    let boundary = buffer.indexOf('\n');
    while (boundary !== -1) {
      const line = buffer.slice(0, boundary).trim();
      buffer = buffer.slice(boundary + 1);
      if (line) {
        handleIncomingPacket(line, socket);
      }
      boundary = buffer.indexOf('\n');
    }
  });

  socket.on('close', () => {
    console.log(`[TCP] Connection closed with ${remoteAddress}`);
    for (const [key, s] of activeSockets.entries()) {
      if (s === socket) activeSockets.delete(key);
    }
  });

  socket.on('error', (err) => {
    console.error(`[TCP Error] ${err.message}`);
  });
});

tcpServer.listen(PORT, '0.0.0.0', () => {
  console.log(`[Server] Listening for Android connections on TCP port ${PORT}`);
});

// 2. Setup UDP Broadcast for KDE Connect Discovery
const udp = dgram.createSocket({ type: 'udp4', reuseAddr: true });

udp.on('listening', () => {
  udp.setBroadcast(true);
  console.log(`[UDP] Discovery listening on port ${PORT}`);
  sendIdentityBroadcast();
});

udp.on('message', (msg, rinfo) => {
  try {
    const packet = JSON.parse(msg.toString('utf8'));
    if (packet.type === 'kdeconnect.identity' && packet.body?.deviceId !== DEVICE_ID) {
      const remoteId = packet.body.deviceId;
      const remoteName = packet.body.deviceName || 'Android Device';
      const remotePort = packet.body.tcpPort || PORT;

      if (!activeSockets.has(remoteId)) {
        console.log(`[Discovery] Found Android device: ${remoteName} at ${rinfo.address}:${remotePort}`);
        connectToDevice(rinfo.address, remotePort, remoteId);
      }
    }
  } catch (e) {
    // Non-json or ping packet
  }
});

udp.bind(PORT);

function sendIdentityBroadcast() {
  const identityPacket = {
    id: Date.now(),
    type: 'kdeconnect.identity',
    body: {
      deviceId: DEVICE_ID,
      deviceName: DEVICE_NAME,
      protocolVersion: 7,
      deviceType: 'desktop',
      incomingCapabilities: ['kdeconnect.clipboard', 'kdeconnect.clipboard.image'],
      outgoingCapabilities: ['kdeconnect.clipboard', 'kdeconnect.clipboard.image'],
      tcpPort: PORT
    }
  };

  const payload = Buffer.from(JSON.stringify(identityPacket) + '\n');
  const broadcastAddresses = getBroadcastAddresses();
  broadcastAddresses.forEach((addr) => {
    udp.send(payload, 0, payload.length, PORT, addr, (err) => {
      if (err && err.code !== 'ENETUNREACH') {
        // Ignore unreachable subnets
      }
    });
  });
}

// Periodically broadcast discovery
setInterval(sendIdentityBroadcast, 4000);

function getBroadcastAddresses() {
  const list = ['255.255.255.255'];
  const ifaces = os.networkInterfaces();
  for (const name in ifaces) {
    for (const iface of ifaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        // Simple class C broadcast approximation
        const parts = iface.address.split('.');
        parts[3] = '255';
        list.push(parts.join('.'));
      }
    }
  }
  return [...new Set(list)];
}

function connectToDevice(ip, port, deviceId) {
  if (activeSockets.has(deviceId)) return;

  const client = net.createConnection({ host: ip, port: port }, () => {
    console.log(`[TCP] Connected to ${deviceId} (${ip}:${port})`);
    activeSockets.set(deviceId, client);

    // Send initial handshake
    const greeting = {
      id: Date.now(),
      type: 'kdeconnect.identity',
      body: {
        deviceId: DEVICE_ID,
        deviceName: DEVICE_NAME,
        protocolVersion: 7,
        deviceType: 'desktop'
      }
    };
    client.write(JSON.stringify(greeting) + '\n');
  });

  client.on('error', (err) => {
    activeSockets.delete(deviceId);
  });

  client.on('close', () => {
    activeSockets.delete(deviceId);
  });
}

// 3. Handle Packets from Android
function handleIncomingPacket(raw, socket) {
  try {
    const packet = JSON.parse(raw);
    if (packet.type === 'kdeconnect.identity') {
      const devId = packet.body.deviceId;
      activeSockets.set(devId, socket);
      console.log(`[Handshake] Paired with ${packet.body.deviceName}`);
    } else if (packet.type === 'kdeconnect.clipboard') {
      const content = packet.body.content;
      if (content && getHash(content) !== lastClipboardHash) {
        lastClipboardHash = getHash(content);
        console.log(`[Received Text] "${content.slice(0, 50)}${content.length > 50 ? '...' : ''}"`);
        writeToWindowsClipboard('text', content);
      }
    } else if (packet.type === 'kdeconnect.clipboard.image') {
      const b64 = packet.body.data;
      if (b64 && getHash(b64) !== lastClipboardHash) {
        lastClipboardHash = getHash(b64);
        console.log(`[Received Image] Size: ${(b64.length * 0.75 / 1024).toFixed(1)} KB`);
        writeToWindowsClipboard('image', b64);
      }
    }
  } catch (err) {
    console.error('[Packet Error]', err.message);
  }
}

// 4. Windows Clipboard Watcher Process
function startClipboardWatcher() {
  const psPath = path.join(__dirname, 'clipboard_watcher.ps1');
  const watcher = spawn('powershell.exe', [
    '-STA',
    '-NoProfile',
    '-ExecutionPolicy', 'Bypass',
    '-File', psPath
  ]);

  let buf = '';
  watcher.stdout.on('data', (data) => {
    buf += data.toString();
    let lines = buf.split('\n');
    buf = lines.pop();

    for (const line of lines) {
      const clean = line.trim();
      if (clean === 'CLIPBOARD_WATCHER_READY') {
        console.log('[Watcher] Windows 11 Clipboard Watcher is running (monitoring text & screenshot images)...');
      } else if (clean.startsWith('CLIPBOARD_EVENT:')) {
        const jsonStr = clean.slice('CLIPBOARD_EVENT:'.length);
        try {
          const event = JSON.parse(jsonStr);
          onLocalClipboardChanged(event);
        } catch (e) {
          console.error('[Watcher JSON Error]', e.message);
        }
      }
    }
  });

  watcher.stderr.on('data', (err) => {
    // console.error('[Watcher Stderr]', err.toString());
  });

  watcher.on('exit', (code) => {
    console.log(`[Watcher] Exited with code ${code}, restarting in 2s...`);
    setTimeout(startClipboardWatcher, 2000);
  });
}

function onLocalClipboardChanged(event) {
  if (event.type === 'text') {
    const hash = getHash(event.content);
    if (hash === lastClipboardHash) return;
    lastClipboardHash = hash;

    console.log(`\n[Copy Detected: Text] "${event.content.slice(0, 50)}${event.content.length > 50 ? '...' : ''}"`);
    const packet = {
      id: Date.now(),
      type: 'kdeconnect.clipboard',
      body: { content: event.content }
    };
    broadcastPacket(packet);
  } else if (event.type === 'image') {
    const hash = getHash(event.data);
    if (hash === lastClipboardHash) return;
    lastClipboardHash = hash;

    const sizeKb = (event.data.length * 0.75 / 1024).toFixed(1);
    console.log(`\n[Copy Detected: Image] Screenshot/Image (${sizeKb} KB) -> Sending to Android...`);

    const packet = {
      id: Date.now(),
      type: 'kdeconnect.clipboard.image',
      body: {
        mimeType: event.mime || 'image/png',
        data: event.data
      }
    };
    broadcastPacket(packet);
  }
}

function broadcastPacket(packet) {
  const data = JSON.stringify(packet) + '\n';
  let sentCount = 0;
  for (const [id, socket] of activeSockets.entries()) {
    try {
      socket.write(data);
      sentCount++;
    } catch (e) {
      activeSockets.delete(id);
    }
  }
  if (sentCount > 0) {
    console.log(` -> Dispatched to ${sentCount} connected device(s)`);
  } else {
    console.log(` -> (No Android device currently connected to sync)`);
  }
}

function writeToWindowsClipboard(type, contentOrB64) {
  const writerPs = path.join(__dirname, 'clipboard_writer.ps1');
  let args = ['-STA', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', writerPs, '-Type', type];

  let tempFile = null;
  if (type === 'image') {
    tempFile = path.join(os.tmpdir(), `clip_img_${Date.now()}.tmp`);
    fs.writeFileSync(tempFile, contentOrB64);
    args.push('-Base64File', tempFile);
  } else {
    args.push('-Content', contentOrB64);
  }

  const p = spawn('powershell.exe', args);
  p.on('close', () => {
    if (tempFile && fs.existsSync(tempFile)) {
      try { fs.unlinkSync(tempFile); } catch (e) {}
    }
  });
}

startClipboardWatcher();
