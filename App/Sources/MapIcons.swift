import MapLibre
import UIKit
import VelaCore

/** The images Vela's map layers draw: category pins, the red picked pin, the puck, route bubbles. */
enum MapIcons {
    struct Kind {
        let name: String
        let symbol: String
        let color: UInt32
        let words: [String]
    }

    static let kinds: [Kind] = [
        Kind(name: "cat-coffee", symbol: "cup.and.saucer.fill", color: 0xE8710A, words: ["coffee", "cafe", "café", "bakery", "tea", "donut"]),
        Kind(name: "cat-food", symbol: "fork.knife", color: 0xE8453C, words: ["restaurant", "food", "pizza", "sushi", "burger", "grill", "bar", "pub", "diner", "taco", "bistro", "steak", "ramen", "noodle", "kitchen", "eatery", "sandwich", "chicken"]),
        Kind(name: "cat-gas", symbol: "fuelpump.fill", color: 0x1A73E8, words: ["gas", "fuel", "petrol", "charging"]),
        Kind(name: "cat-shop", symbol: "cart.fill", color: 0x1A73E8, words: ["grocery", "supermarket", "store", "shop", "market", "mall", "pharmacy", "drugstore"]),
        Kind(name: "cat-hotel", symbol: "bed.double.fill", color: 0xD01884, words: ["hotel", "motel", "inn", "lodging", "hostel", "resort"]),
        Kind(name: "cat-park", symbol: "leaf.fill", color: 0x188038, words: ["park", "garden", "trail", "playground", "beach"]),
        Kind(name: "cat-health", symbol: "cross.case.fill", color: 0xC5221F, words: ["hospital", "clinic", "doctor", "dentist", "medical", "urgent"]),
        Kind(name: "cat-transit", symbol: "tram.fill", color: 0x12B5A5, words: ["station", "bus", "subway", "metro", "train", "airport"]),
        Kind(name: "cat-money", symbol: "banknote.fill", color: 0x5F6368, words: ["atm", "bank"]),
        Kind(name: "cat-parking", symbol: "parkingsign", color: 0x1A73E8, words: ["parking", "garage"]),
    ]

    static func iconName(for p: Place) -> String {
        let c = (p.category ?? "").lowercased()
        return kinds.first { k in k.words.contains { c.contains($0) } }?.name ?? "cat-default"
    }

    static func register(in style: MLNStyle) {
        for k in kinds { style.setImage(dot(k.symbol, UIColor(hex: k.color)), forName: k.name) }
        style.setImage(dot("mappin", UIColor(hex: 0xE8453C)), forName: "cat-default")
        style.setImage(selectedPin(), forName: "pin-selected")
        style.setImage(originDot(), forName: "pin-origin")
        style.setImage(puck(), forName: "puck")
        style.setImage(bubble(fill: UIColor(hex: 0x1F6FEB), stroke: .white), forName: "bubble-sel")
        style.setImage(bubble(fill: .white, stroke: UIColor(hex: 0xBDC1C6)), forName: "bubble-alt")
        style.setImage(speedCamera(), forName: "cam-speed")
        style.setImage(plateCamera(), forName: "cam-plate")
        for k in WazeAlert.Kind.allCases { style.setImage(waze(k), forName: "waze-\(k.rawValue)") }
        style.setImage(turnHead(), forName: "turn-head")
        style.setImage(dot("smallcircle.filled.circle", UIColor(hex: 0x5F6368)), forName: "pin-stop")
        // My places: home, work, the parked car and saved places.
        style.setImage(dot("house.fill", UIColor(hex: 0x1A73E8)), forName: "mine-home")
        style.setImage(dot("briefcase.fill", UIColor(hex: 0x8D6E63)), forName: "mine-work")
        style.setImage(dot("parkingsign", UIColor(hex: 0x1967D2)), forName: "mine-parking")
        style.setImage(dot("bookmark.fill", UIColor(hex: 0x0F9D58)), forName: "mine-saved")
    }

    /** Vela's speed camera badge: amber rounded square, head-on camera with a big lens and flash slit. */
    /** The head of the turn arrow drawn on the road: white with a dark blue edge, pointing up. */
    static func turnHead() -> UIImage {
        let size = CGSize(width: 30, height: 26)
        return UIGraphicsImageRenderer(size: size).image { _ in
            let p = UIBezierPath()
            p.move(to: CGPoint(x: 15, y: 2))
            p.addLine(to: CGPoint(x: 28, y: 22))
            p.addLine(to: CGPoint(x: 2, y: 22))
            p.close()
            p.lineJoinStyle = .round
            UIColor.white.setFill()
            UIColor(hex: 0x0B3D91).setStroke()
            p.lineWidth = 2.5
            p.fill()
            p.stroke()
        }
    }

    static func speedCamera() -> UIImage {
        badge(UIColor(hex: 0xEF6C00)) { amber in
            UIColor.white.setFill()
            UIBezierPath(roundedRect: CGRect(x: 15, y: 12, width: 16, height: 20), cornerRadius: 3).fill()
            amber.setFill()
            UIBezierPath(ovalIn: CGRect(x: 23 - 4.6, y: 20 - 4.6, width: 9.2, height: 9.2)).fill()
            UIColor.white.setFill()
            UIBezierPath(ovalIn: CGRect(x: 21, y: 18, width: 4, height: 4)).fill()
            amber.setFill()
            UIBezierPath(rect: CGRect(x: 18, y: 26.5, width: 10, height: 3)).fill()
        }
    }

    /** Vela's plate reader badge: purple rounded square with a CCTV camera on a mount. */
    static func plateCamera() -> UIImage {
        badge(UIColor(hex: 0x7B1FA2)) { purple in
            UIColor.white.setFill()
            UIBezierPath(roundedRect: CGRect(x: 12, y: 18, width: 18, height: 9), cornerRadius: 2).fill()
            UIBezierPath(ovalIn: CGRect(x: 31 - 4.2, y: 22.5 - 4.2, width: 8.4, height: 8.4)).fill()
            purple.setFill()
            UIBezierPath(ovalIn: CGRect(x: 29, y: 20.5, width: 4, height: 4)).fill()
            let arm = UIBezierPath()
            arm.move(to: CGPoint(x: 17, y: 18)); arm.addLine(to: CGPoint(x: 17, y: 12))
            arm.move(to: CGPoint(x: 12, y: 12)); arm.addLine(to: CGPoint(x: 24, y: 12))
            arm.lineWidth = 2.4
            UIColor.white.setStroke()
            arm.stroke()
        }
    }

    /** Waze report badges, in Waze's own colors: blue police, red crashes, amber hazards… */
    static func waze(_ k: WazeAlert.Kind) -> UIImage {
        let symbol: String
        switch k {
        case .police: symbol = "shield.fill"
        case .accident: symbol = "burst.fill"
        case .hazard: symbol = "exclamationmark.triangle.fill"
        case .weather: symbol = "cloud.fog.fill"
        case .roadworks: symbol = "wrench.and.screwdriver.fill"
        case .closure: symbol = "nosign"
        case .jam: symbol = "car.2.fill"
        }
        return badge(wazeColor(k)) { _ in
            draw(symbol, in: CGRect(x: 12, y: 12, width: 22, height: 22), color: .white)
        }
    }

    static func wazeColor(_ k: WazeAlert.Kind) -> UIColor {
        switch k {
        case .police: return UIColor(hex: 0x1E88E5)
        case .accident, .jam: return UIColor(hex: 0xD93025)
        case .hazard: return UIColor(hex: 0xF29900)
        case .weather: return UIColor(hex: 0x00897B)
        case .roadworks: return UIColor(hex: 0xE8710A)
        case .closure: return UIColor(hex: 0xB71C1C)
        }
    }

    /** A 46-unit badge drawn at 34 points (sharp when the map scales it up): white rim, colored field, then the glyph. */
    private static func badge(_ color: UIColor, glyph: (UIColor) -> Void) -> UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: 34.5, height: 34.5)).image { ctx in
            ctx.cgContext.scaleBy(x: 0.75, y: 0.75)
            UIColor.white.setFill()
            UIBezierPath(roundedRect: CGRect(x: 2, y: 2, width: 42, height: 42), cornerRadius: 11).fill()
            color.setFill()
            UIBezierPath(roundedRect: CGRect(x: 4.5, y: 4.5, width: 37, height: 37), cornerRadius: 9).fill()
            glyph(color)
        }
    }

    private static func dot(_ symbol: String, _ color: UIColor) -> UIImage {
        let size = CGSize(width: 28, height: 28)
        return UIGraphicsImageRenderer(size: size).image { ctx in
            let r = CGRect(origin: .zero, size: size).insetBy(dx: 1.5, dy: 1.5)
            color.setFill()
            UIColor.white.setStroke()
            let path = UIBezierPath(ovalIn: r)
            path.lineWidth = 2
            path.fill()
            path.stroke()
            draw(symbol, in: r.insetBy(dx: 6, dy: 6), color: .white)
            _ = ctx
        }
    }

    private static func draw(_ symbol: String, in r: CGRect, color: UIColor) {
        let cfg = UIImage.SymbolConfiguration(pointSize: r.height, weight: .semibold)
        guard let img = UIImage(systemName: symbol, withConfiguration: cfg)?.withTintColor(color, renderingMode: .alwaysOriginal) else { return }
        let s = img.size
        let scale = min(r.width / s.width, r.height / s.height)
        let w = s.width * scale, h = s.height * scale
        img.draw(in: CGRect(x: r.midX - w / 2, y: r.midY - h / 2, width: w, height: h))
    }

    private static func selectedPin() -> UIImage {
        let size = CGSize(width: 34, height: 46)
        return UIGraphicsImageRenderer(size: size).image { _ in
            let red = UIColor(hex: 0xEA4335)
            let head = CGRect(x: 2, y: 2, width: 30, height: 30)
            let p = UIBezierPath()
            p.move(to: CGPoint(x: 17, y: 44))
            p.addCurve(to: CGPoint(x: 3.5, y: 23), controlPoint1: CGPoint(x: 13, y: 37), controlPoint2: CGPoint(x: 6, y: 29))
            p.addArc(withCenter: CGPoint(x: head.midX, y: head.midY), radius: 15, startAngle: .pi * 0.86, endAngle: .pi * 0.14, clockwise: true)
            p.addCurve(to: CGPoint(x: 17, y: 44), controlPoint1: CGPoint(x: 28, y: 29), controlPoint2: CGPoint(x: 21, y: 37))
            p.close()
            red.setFill()
            UIColor(hex: 0xA50E0E).setStroke()
            p.lineWidth = 1.5
            p.fill()
            p.stroke()
            UIColor(hex: 0x7A0B0B).setFill()
            UIBezierPath(ovalIn: CGRect(x: 12, y: 12, width: 10, height: 10)).fill()
        }
    }

    private static func originDot() -> UIImage {
        let size = CGSize(width: 22, height: 22)
        return UIGraphicsImageRenderer(size: size).image { _ in
            UIColor.white.setFill()
            UIBezierPath(ovalIn: CGRect(origin: .zero, size: size).insetBy(dx: 1, dy: 1)).fill()
            UIColor(hex: 0x1F6FEB).setStroke()
            let ring = UIBezierPath(ovalIn: CGRect(origin: .zero, size: size).insetBy(dx: 4, dy: 4))
            ring.lineWidth = 4
            ring.stroke()
        }
    }

    /** Vela's navigation arrow: white-ringed blue disc with a white chevron pointing up. */
    private static func puck() -> UIImage {
        let size = CGSize(width: 46, height: 46)
        return UIGraphicsImageRenderer(size: size).image { ctx in
            let g = ctx.cgContext
            g.setShadow(offset: CGSize(width: 0, height: 1), blur: 4, color: UIColor.black.withAlphaComponent(0.4).cgColor)
            UIColor.white.setFill()
            UIBezierPath(ovalIn: CGRect(x: 4, y: 4, width: 38, height: 38)).fill()
            g.setShadow(offset: .zero, blur: 0, color: nil)
            UIColor(hex: 0x1F6FEB).setFill()
            UIBezierPath(ovalIn: CGRect(x: 7, y: 7, width: 32, height: 32)).fill()
            let a = UIBezierPath()
            a.move(to: CGPoint(x: 23, y: 12))
            a.addLine(to: CGPoint(x: 32, y: 33))
            a.addLine(to: CGPoint(x: 23, y: 28))
            a.addLine(to: CGPoint(x: 14, y: 33))
            a.close()
            UIColor.white.setFill()
            a.fill()
        }
    }

    private static func bubble(fill: UIColor, stroke: UIColor) -> UIImage {
        let size = CGSize(width: 30, height: 24)
        let img = UIGraphicsImageRenderer(size: size).image { _ in
            let p = UIBezierPath(roundedRect: CGRect(origin: .zero, size: size).insetBy(dx: 1, dy: 1), cornerRadius: 8)
            fill.setFill()
            stroke.setStroke()
            p.lineWidth = 1.5
            p.fill()
            p.stroke()
        }
        return img.resizableImage(withCapInsets: UIEdgeInsets(top: 10, left: 10, bottom: 10, right: 10), resizingMode: .stretch)
    }
}
