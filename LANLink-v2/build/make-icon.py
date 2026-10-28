"""生成 LANLink 图标（纯标准库，不依赖 Pillow）。
输出 256x256 的 PNG 打包进 ICO。
"""
import struct
import zlib
import os

S = 256
SS = 3  # 3x3 超采样抗锯齿


def rounded_rect_sdf(x, y, cx, cy, hw, hh, r):
    dx = abs(x - cx) - (hw - r)
    dy = abs(y - cy) - (hh - r)
    ax, ay = max(dx, 0.0), max(dy, 0.0)
    outside = (ax * ax + ay * ay) ** 0.5
    inside = min(max(dx, dy), 0.0)
    return outside + inside - r


def sample(x, y):
    """返回 (r,g,b,a) 0..1"""
    # 圆角方底 + 对角渐变
    d = rounded_rect_sdf(x, y, S / 2, S / 2, S / 2 - 2, S / 2 - 2, 58)
    bg_a = max(0.0, min(1.0, 0.5 - d))
    if bg_a <= 0:
        return (0.0, 0.0, 0.0, 0.0)
    t = (x + y) / (2 * S)
    r0, g0, b0 = 0x63 / 255, 0x66 / 255, 0xF1 / 255
    r1, g1, b1 = 0x8B / 255, 0x5C / 255, 0xF6 / 255
    cr = r0 + (r1 - r0) * t
    cg = g0 + (g1 - g0) * t
    cb = b0 + (b1 - b0) * t

    # 两个交错的圆角矩形环，组成"链环"
    stroke = 9.0
    d1 = abs(rounded_rect_sdf(x, y, 104, 128, 44, 30, 26)) - stroke
    d2 = abs(rounded_rect_sdf(x, y, 152, 128, 44, 30, 26)) - stroke
    ring = min(d1, d2)
    ink = max(0.0, min(1.0, 0.5 - ring))

    if ink > 0:
        cr = cr + (1.0 - cr) * ink
        cg = cg + (1.0 - cg) * ink
        cb = cb + (1.0 - cb) * ink
    return (cr, cg, cb, bg_a)


def render():
    rows = []
    for y in range(S):
        row = bytearray([0])  # filter type 0
        for x in range(S):
            ar = ag = ab = aa = 0.0
            for sy in range(SS):
                for sx in range(SS):
                    r, g, b, a = sample(x + (sx + 0.5) / SS, y + (sy + 0.5) / SS)
                    ar += r * a
                    ag += g * a
                    ab += b * a
                    aa += a
            n = SS * SS
            if aa > 0:
                row += bytes([
                    int(max(0, min(255, ar / aa * 255))),
                    int(max(0, min(255, ag / aa * 255))),
                    int(max(0, min(255, ab / aa * 255))),
                    int(max(0, min(255, aa / n * 255))),
                ])
            else:
                row += b'\x00\x00\x00\x00'
        rows.append(bytes(row))
    return b''.join(rows)


def png(raw):
    def chunk(tag, data):
        c = struct.pack('>I', len(data)) + tag + data
        return c + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack('>IIBBBBB', S, S, 8, 6, 0, 0, 0)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr)
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def ico(png_bytes):
    # ICONDIR + 单条 ICONDIRENTRY（宽高 0 表示 256）
    header = struct.pack('<HHH', 0, 1, 1)
    entry = struct.pack('<BBBBHHII', 0, 0, 0, 0, 1, 32, len(png_bytes), 6 + 16)
    return header + entry + png_bytes


if __name__ == '__main__':
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'icon.ico')
    data = ico(png(render()))
    with open(out, 'wb') as f:
        f.write(data)
    print('wrote', out, len(data), 'bytes')
