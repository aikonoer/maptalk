import Foundation

/// Short, chat style timestamps: "now", "4m", "3h", "2d".
func relativeTime(_ date: Date?, now: Date = Date()) -> String {
    guard let date else { return "sending\u{2026}" }
    let seconds = max(0, now.timeIntervalSince(date))
    let minutes = Int(seconds / 60)
    switch minutes {
    case ..<1: return "now"
    case ..<60: return "\(minutes)m"
    default: break
    }
    let hours = minutes / 60
    if hours < 24 { return "\(hours)h" }
    let days = hours / 24
    return days < 7 ? "\(days)d" : "\(days / 7)w"
}
