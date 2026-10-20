#!/usr/bin/env python3
"""Synthetic loopback RFB backend for the on-device node test; never captures or controls the host desktop."""
import json
import os
import socket
import struct
import sys

address = sys.argv[sys.argv.index('-unixsock') + 1]
log = os.environ['UMBRA_NODE_TEST_INPUT_LOG']
assert sys.argv[sys.argv.index('-rfbport') + 1] == '0', 'TCP listener must remain disabled'
server = socket.socket(socket.AF_UNIX)
server.bind(address)
server.listen(1)
conn, _ = server.accept()
conn.settimeout(30)
def read(n):
    value = b''
    while len(value) < n:
        part = conn.recv(n - len(value))
        if not part:
            raise EOFError
        value += part
    return value
def record(value):
    with open(log, 'a') as out:
        out.write(json.dumps(value) + '\n')
try:
    conn.sendall(b'RFB 003.008\n')
    assert read(12) == b'RFB 003.008\n'
    conn.sendall(b'\x01\x01')
    assert read(1) == b'\x01'
    conn.sendall(b'\0' * 4)
    read(1)
    width, height = 320, 180
    conn.sendall(struct.pack('>HH', width, height) + bytes([32, 24, 0, 1, 0, 255, 0, 255, 0, 255, 16, 8, 0, 0, 0, 0]) + struct.pack('>I', 13) + b'Umbra QA only')
    pixels = b''.join(bytes([64 + y // 2, 64 + x // 3, 60 + (x // 40 % 2) * 90, 0]) for y in range(height) for x in range(width))
    while True:
        kind = read(1)[0]
        if kind == 0:
            read(19)
        elif kind == 2:
            count = struct.unpack('>H', read(3)[1:])[0]
            read(count * 4)
        elif kind == 3:
            read(9)
            conn.sendall(b'\0\0\0\1' + struct.pack('>HHHHi', 0, 0, width, height, 0) + pixels)
        elif kind == 4:
            value = read(7)
            record({'key': struct.unpack('>I', value[3:])[0], 'down': bool(value[0])})
        elif kind == 5:
            value = read(5)
            x, y = struct.unpack('>HH', value[1:])
            record({'buttons': value[0], 'x': x, 'y': y})
        else:
            raise RuntimeError('Unexpected RFB message')
except (EOFError, BrokenPipeError, ConnectionResetError, TimeoutError):
    pass
finally:
    conn.close()
    server.close()
