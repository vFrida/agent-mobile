#!/usr/bin/env python3
"""Render a QR code as ANSI half blocks (cross-platform fallback for qr.swift)."""

import sys

try:
    import qrcode
    from qrcode.constants import ERROR_CORRECT_L, ERROR_CORRECT_M
except ImportError:
    sys.exit(3)

LEVELS = {"L": ERROR_CORRECT_L, "M": ERROR_CORRECT_M, "Q": ERROR_CORRECT_M, "H": ERROR_CORRECT_M}


def main() -> int:
    if len(sys.argv) < 2:
        sys.stderr.write("usage: qr.py <text> [L|M|Q|H]\n")
        return 2
    text = sys.argv[1]
    level = LEVELS.get(sys.argv[2].upper() if len(sys.argv) > 2 else "M", ERROR_CORRECT_M)

    qr = qrcode.QRCode(border=0, error_correction=level)
    qr.add_data(text)
    qr.make(fit=True)
    matrix = qr.get_matrix()

    width = len(matrix)
    quiet = 2
    blank = " " * (width + quiet * 2)

    out = ["\033[30;47m"]
    out.extend([blank] * quiet)
    for y in range(0, len(matrix), 2):
        row = [" "] * quiet
        for x in range(width):
            top = matrix[y][x]
            bottom = matrix[y + 1][x] if y + 1 < len(matrix) else False
            if top and bottom:
                row.append("\u2588")
            elif top:
                row.append("\u2580")
            elif bottom:
                row.append("\u2584")
            else:
                row.append(" ")
        row.extend([" "] * quiet)
        out.append("".join(row))
    out.extend([blank] * quiet)
    out.append("\033[0m")
    print("\n".join(out), end="", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
