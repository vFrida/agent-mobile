import CoreImage
import Foundation

let args = CommandLine.arguments
guard args.count > 1 else {
    FileHandle.standardError.write("usage: qr.swift <text> [L|M|Q|H]\n".data(using: .utf8)!)
    exit(2)
}
let text = args[1]

guard let data = text.data(using: .utf8),
      let filter = CIFilter(name: "CIQRCodeGenerator") else { exit(2) }

filter.setValue(data, forKey: "inputMessage")
filter.setValue(args.count > 2 ? args[2] : "M", forKey: "inputCorrectionLevel")
guard let output = filter.outputImage else { exit(2) }

let width = Int(output.extent.width)
let height = Int(output.extent.height)
var pixels = [UInt8](repeating: 255, count: width * height * 4)
let context = CIContext(options: nil)
context.render(
    output,
    toBitmap: &pixels,
    rowBytes: width * 4,
    bounds: output.extent,
    format: .RGBA8,
    colorSpace: CGColorSpaceCreateDeviceRGB()
)

func isDark(_ x: Int, _ y: Int) -> Bool {
    guard x >= 0, x < width, y >= 0, y < height else { return false }
    return pixels[(y * width + x) * 4] < 128
}

let quiet = 2
let blankLine = String(repeating: " ", count: width + quiet * 2)

print("\u{1B}[30;47m")
for _ in 0..<quiet { print(blankLine) }

for y in stride(from: 0, to: height, by: 2) {
    var line = String(repeating: " ", count: quiet)
    for x in 0..<width {
        let top = isDark(x, y)
        let bottom = isDark(x, y + 1)
        switch (top, bottom) {
        case (true, true): line.append("\u{2588}")
        case (true, false): line.append("\u{2580}")
        case (false, true): line.append("\u{2584}")
        case (false, false): line.append(" ")
        }
    }
    line += String(repeating: " ", count: quiet)
    print(line)
}

for _ in 0..<quiet { print(blankLine) }
print("\u{1B}[0m", terminator: "")
