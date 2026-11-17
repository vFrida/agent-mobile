import SwiftUI

enum Palette {
    static let background = Color(red: 0.973, green: 0.973, blue: 0.973)
    static let surface = Color.white
    static let surfaceWeak = Color(red: 0.953, green: 0.953, blue: 0.953)
    static let textStrong = Color(red: 0.090, green: 0.090, blue: 0.090)
    static let textBase = Color(red: 0.435, green: 0.435, blue: 0.435)
    static let textWeak = Color(red: 0.561, green: 0.561, blue: 0.561)
    static let border = Color(red: 0.898, green: 0.898, blue: 0.898)
    static let success = Color(red: 0.086, green: 0.639, blue: 0.290)
    static let critical = Color(red: 0.863, green: 0.149, blue: 0.149)
    static let chatBackground = Color(red: 0.929, green: 0.929, blue: 0.929)
    static let userBubble = Color(red: 0.584, green: 0.925, blue: 0.412)
    static let assistantBubble = Color.white
    static let inputBar = Color(red: 0.969, green: 0.969, blue: 0.969)
    static let systemText = Color(red: 0.608, green: 0.608, blue: 0.608)
    static let sendGreen = Color(red: 0.027, green: 0.757, blue: 0.376)
    static let accentBlue = Color(red: 0.298, green: 0.620, blue: 0.918)
    static let accentRed = Color(red: 0.980, green: 0.318, blue: 0.318)
    static let accentOrange = Color(red: 1.0, green: 0.624, blue: 0.039)
    static let softBlue = Color(red: 0.929, green: 0.961, blue: 1.0)
    static let accentPurple = Color(red: 0.545, green: 0.361, blue: 0.965)
    static let topBarTint = Color(red: 0.945, green: 0.980, blue: 0.957)
    static let assistantBubbleTint = Color(red: 0.984, green: 0.992, blue: 1.0)
    static let chatGradientTop = Color(red: 0.953, green: 0.965, blue: 0.980)
    static let chatGradientBottom = Color(red: 0.922, green: 0.937, blue: 0.957)
    static let sessionPalette: [Color] = [
        accentBlue,
        sendGreen,
        accentOrange,
        Color(red: 0.545, green: 0.361, blue: 0.965),
        Color(red: 0.925, green: 0.282, blue: 0.600),
        Color(red: 0.078, green: 0.722, blue: 0.651)
    ]
}
