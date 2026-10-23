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
