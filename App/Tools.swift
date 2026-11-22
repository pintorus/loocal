import CoreLocation
import Foundation
import MapKit
import NaturalLanguage
import Translation

/// Tools that answer for the model where a small model is weak: exact maths and dates,
/// places around you, the text of a web page, and real translation. Each one is spotted in
/// your words, runs on the iPhone (Maps and web pages fetch what they need), shows a box in
/// the reply, and hands the model a fact to build its answer on.
@MainActor
enum ToolBelt {
    /// Runs every tool the message calls for. `show` adds or updates a box in the reply.
    /// `force` holds tools placed in the prompt as islands (Maps), which run even when the
    /// words alone wouldn't call for them.
    static func run(for message: String, force: Set<String> = [], show: (ToolUse) -> Void) async -> String {
        var notes: [String] = []
        if let calc = Calculator.answer(message) {
            let sides = calc.shown.components(separatedBy: " = ")
            let widget = sides.count == 2 ? ToolWidget.calculation(expression: sides[0], result: sides[1]) : .calculation(expression: "", result: calc.shown)
            show(ToolUse(kind: "calc", icon: "function", running: "Calculating", done: "Calculated", detail: calc.shown, status: .done, widget: widget))
            notes.append("Exact result from the calculator (use it as is, don't redo the maths): \(calc.fact)")
        }
        if let request = Translator.detect(message) {
            var box = ToolUse(kind: "translate", icon: "character.bubble", running: "Translating", done: "Translated on this iPhone", detail: request.targetName)
            show(box)
            switch await Translator.translate(request) {
            case .success(let text):
                box.status = .done
                box.detail = "\(request.targetName) · \(text)"
                box.widget = .translation(language: request.targetName, text: text)
                notes.append("On-device translation (Apple Translation) into \(request.targetName): “\(text)”. Give exactly this translation as the answer, then nothing else unless useful.")
            case .failure(let error):
                box.status = .failed
                box.detail = error.localizedDescription
                notes.append("On-device translation wasn't available (\(error.localizedDescription)). Translate it yourself into \(request.targetName), carefully.")
            }
            show(box)
        }
        if let place = Places.detect(message) ?? (force.contains(Connector.maps.id) ? Places.forced(message) : nil) {
            var box = ToolUse(kind: "places", icon: place.isRoute ? "car" : "mappin.and.ellipse", running: place.isRoute ? "Working out the trip" : "Looking around you", done: place.isRoute ? "Checked Apple Maps" : "Searched Apple Maps", detail: place.query)
            show(box)
            switch await Places.run(place) {
            case .success(let found):
                box.status = .done
                box.links = found.links
                if !found.summary.isEmpty { box.detail = found.summary }
                if !found.pins.isEmpty { box.widget = .map(title: place.query, pins: found.pins, here: found.here) }
                notes.append(found.fact)
            case .failure(let error):
                box.status = .failed
                box.detail = error.localizedDescription
                notes.append("Apple Maps couldn't answer: \(error.localizedDescription). Say so briefly.")
            }
            show(box)
        }
        for url in PageReader.links(in: message).prefix(1) {
            var box = ToolUse(kind: "page", icon: "doc.text", running: "Reading the page", done: "Read the page", detail: url.host() ?? url.absoluteString)
            show(box)
            if let page = await PageReader.read(url) {
                box.status = .done
                box.detail = page.title.isEmpty ? (url.host() ?? "") : page.title
                box.links = [ToolUse.Link(title: page.title.isEmpty ? url.absoluteString : page.title, url: url)]
                notes.append("Text of the page the user linked (\(url.host() ?? "")), \(page.title):\n\(page.text)")
            } else {
                box.status = .failed
                box.detail = url.host() ?? ""
                notes.append("The linked page couldn't be read. Say so.")
            }
            show(box)
        }
        return notes.joined(separator: "\n\n")
    }
}

// MARK: - Calculator and dates

enum Calculator {
    struct Answer { var shown: String; var fact: String }

    static func answer(_ message: String) -> Answer? {
        let text = message.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = text.lowercased()
        if let date = dateAnswer(text, lower) { return date }
        // "15% of 80", "20 % de 45"
        if let m = lower.firstMatch(of: /(\d+(?:[.,]\d+)?)\s*%\s*(?:of|de|du|des)\s*(\d+(?:[.,]\d+)?)/),
           let a = number(String(m.1)), let b = number(String(m.2)) {
            let value = a / 100 * b
            return Answer(shown: "\(m.1)% of \(m.2) = \(format(value))", fact: "\(m.1)% of \(m.2) = \(format(value))")
        }
        // "square root of 2", "racine carrée de 81"
        if let m = lower.firstMatch(of: /(?:square root of|sqrt|racine carr[ée]e de)\s*\(?(\d+(?:[.,]\d+)?)\)?/), let a = number(String(m.1)) {
            return Answer(shown: "√\(m.1) = \(format(a.squareRoot()))", fact: "the square root of \(m.1) is \(format(a.squareRoot()))")
        }
        // Plain arithmetic somewhere in the message: "what's 12*7 + 3?"
        let normalized = text
            .replacingOccurrences(of: "×", with: "*").replacingOccurrences(of: "÷", with: "/")
            .replacingOccurrences(of: #"(?<=\d)\s*[xX]\s*(?=\d)"#, with: "*", options: .regularExpression)
            .replacingOccurrences(of: #"(?<=\d),(?=\d{1,2}\b)"#, with: ".", options: .regularExpression)
            .replacingOccurrences(of: #"(?<=\d)[\s,](?=\d{3}\b)"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: "plus", with: "+").replacingOccurrences(of: "minus", with: "-")
            .replacingOccurrences(of: "times", with: "*").replacingOccurrences(of: "divided by", with: "/")
        guard let range = normalized.range(of: #"[\d(][\d\s.+\-*/^()]*[\d)]"#, options: .regularExpression) else { return nil }
        let expression = normalized[range].trimmingCharacters(in: .whitespaces)
        guard expression.range(of: #"\d\s*[+\-*/^]\s*[\d(]"#, options: .regularExpression) != nil,
              let value = Expression(String(expression)).evaluate(), value.isFinite else { return nil }
        return Answer(shown: "\(expression) = \(format(value))", fact: "\(expression) = \(format(value))")
    }

    // MARK: Dates

    private static func dateAnswer(_ text: String, _ lower: String) -> Answer? {
        let today = Calendar.current.startOfDay(for: .now)
        let untilWords = ["how many days until", "how many days till", "how many days to", "how many days before", "days until", "days till", "how long until", "how long till", "combien de jours avant", "combien de jours jusqu", "dans combien de jours", "combien de jours reste"]
        if untilWords.contains(where: lower.contains), let target = dates(in: text).first ?? holiday(lower) {
            let days = Calendar.current.dateComponents([.day], from: today, to: Calendar.current.startOfDay(for: target)).day ?? 0
            let day = target.formatted(.dateTime.weekday(.wide).day().month(.wide).year())
            let shown = days >= 0 ? "\(days) day\(days == 1 ? "" : "s") until \(day)" : "\(-days) days since \(day)"
            return Answer(shown: shown, fact: "Today is \(Date.now.formatted(.dateTime.weekday(.wide).day().month(.wide).year())). " + shown + ".")
        }
        if lower.contains("days between") || lower.contains("jours entre") {
            let found = dates(in: text)
            if found.count >= 2 {
                let days = abs(Calendar.current.dateComponents([.day], from: Calendar.current.startOfDay(for: found[0]), to: Calendar.current.startOfDay(for: found[1])).day ?? 0)
                return Answer(shown: "\(days) days between them", fact: "There are \(days) days between \(found[0].formatted(date: .long, time: .omitted)) and \(found[1].formatted(date: .long, time: .omitted)).")
            }
        }
        // "what's the date in 45 days", "dans 3 semaines on sera quel jour"
        if let m = lower.firstMatch(of: /(?:in|dans)\s+(\d{1,4})\s+(days?|weeks?|months?|jours?|semaines?|mois)/),
           lower.contains("date") || lower.contains("what day") || lower.contains("quel jour") || lower.contains("which day"),
           let amount = Int(m.1) {
            let unit = String(m.2)
            let component: Calendar.Component = unit.hasPrefix("week") || unit.hasPrefix("semaine") ? .weekOfYear : unit.hasPrefix("month") || unit == "mois" ? .month : .day
            if let date = Calendar.current.date(byAdding: component, value: amount, to: today) {
                let day = date.formatted(.dateTime.weekday(.wide).day().month(.wide).year())
                return Answer(shown: "In \(amount) \(unit): \(day)", fact: "In \(amount) \(unit) from today it will be \(day).")
            }
        }
        // "what day is 3 March 2027", "quel jour tombe le 14 juillet"
        if lower.contains("what day") || lower.contains("which day") || lower.contains("quel jour"), let date = dates(in: text).first ?? holiday(lower) {
            let day = date.formatted(.dateTime.weekday(.wide).day().month(.wide).year())
            return Answer(shown: day, fact: "That date is \(day).")
        }
        return nil
    }

    private static func dates(in text: String) -> [Date] {
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.date.rawValue) else { return [] }
        // "1 Jan and 20 Mar" can come back as one range: a start plus a duration.
        return detector.matches(in: text, range: NSRange(text.startIndex..., in: text)).flatMap { match -> [Date] in
            guard let date = match.date else { return [] }
            return match.duration > 0 ? [date, date.addingTimeInterval(match.duration)] : [date]
        }
    }

    /// Named days the date detector doesn't know.
    private static func holiday(_ lower: String) -> Date? {
        let named: [(String, Int, Int)] = [("christmas", 12, 25), ("noël", 12, 25), ("noel", 12, 25), ("new year", 1, 1), ("nouvel an", 1, 1), ("halloween", 10, 31), ("valentine", 2, 14), ("saint-valentin", 2, 14), ("14 juillet", 7, 14), ("bastille", 7, 14)]
        guard let match = named.first(where: { lower.contains($0.0) }) else { return nil }
        let calendar = Calendar.current
        let year = calendar.component(.year, from: .now)
        for y in [year, year + 1] {
            if let date = calendar.date(from: DateComponents(year: y, month: match.1, day: match.2)), date >= calendar.startOfDay(for: .now) { return date }
        }
        return nil
    }

    private static func number(_ text: String) -> Double? { Double(text.replacingOccurrences(of: ",", with: ".")) }

    static func format(_ value: Double) -> String {
        if value == value.rounded() && abs(value) < 1e15 { return String(Int(value)) }
        return value.formatted(.number.precision(.significantDigits(1...10)))
    }

    /// A tiny, safe arithmetic parser: + - * / ^, brackets, unary minus.
    struct Expression {
        private let chars: [Character]
        private var i = 0
        init(_ text: String) { chars = Array(text.filter { !$0.isWhitespace }) }

        func evaluate() -> Double? {
            var parser = self
            guard let value = parser.sum(), parser.i == parser.chars.count else { return nil }
            return value
        }

        private mutating func sum() -> Double? {
            guard var value = product() else { return nil }
            while i < chars.count, chars[i] == "+" || chars[i] == "-" {
                let op = chars[i]; i += 1
                guard let rhs = product() else { return nil }
                value = op == "+" ? value + rhs : value - rhs
            }
            return value
        }

        private mutating func product() -> Double? {
            guard var value = power() else { return nil }
            while i < chars.count, chars[i] == "*" || chars[i] == "/" {
                let op = chars[i]; i += 1
                guard let rhs = power() else { return nil }
                if op == "/" && rhs == 0 { return nil }
                value = op == "*" ? value * rhs : value / rhs
            }
            return value
        }

        private mutating func power() -> Double? {
            guard let base = unary() else { return nil }
            if i < chars.count, chars[i] == "^" {
                i += 1
                guard let exponent = power() else { return nil }
                return pow(base, exponent)
            }
            return base
        }

        private mutating func unary() -> Double? {
            if i < chars.count, chars[i] == "-" { i += 1; return unary().map { -$0 } }
            if i < chars.count, chars[i] == "(" {
                i += 1
                guard let value = sum(), i < chars.count, chars[i] == ")" else { return nil }
                i += 1
                return value
            }
            var digits = ""
            while i < chars.count, chars[i].isNumber || chars[i] == "." { digits.append(chars[i]); i += 1 }
            return Double(digits)
        }
    }
}

// MARK: - Places

enum Places {
    struct Request { var query: String; var isRoute: Bool }
    struct Found {
        var summary: String; var fact: String; var links: [ToolUse.Link]
        var pins: [ToolWidget.Pin] = []
        var here: ToolWidget.Pin? = nil
    }

    private static let nearby = ["near me", "nearby", "near here", "around me", "around here", "close by", "closest", "nearest", "near", "around", "local", "à proximité", "près de moi", "près d'ici", "autour de moi", "à côté", "le plus proche", "la plus proche", "les plus proches", "dans le coin", "dans le quartier", "proche", "près"]

    /// Kinds of places people look for. With one of these and a "find / where / recommend",
    /// it's a place search even without "near me".
    private static let kinds = ["restaurant", "café", "cafe", "coffee", "bar", "pub", "pharmacy", "chemist", "drugstore", "supermarket", "grocery", "bakery", "hotel", "gym", "park", "atm", "bank", "gas station", "petrol", "parking", "hospital", "doctor", "dentist", "museum", "cinema", "movie theater", "shop", "store", "mall", "sushi", "pizza", "burger", "brunch", "breakfast", "lunch", "dinner", "food", "ramen", "tacos", "vegan", "vegetarian", "library", "post office", "laundry", "laundromat", "metro", "subway", "train station", "bus stop", "hairdresser", "barber", "florist", "vet", "boulangerie", "pharmacie", "supermarché", "épicerie", "hôtel", "salle de sport", "parc", "banque", "distributeur", "station-service", "hôpital", "médecin", "dentiste", "musée", "cinéma", "magasin", "boutique", "bibliothèque", "poste", "laverie", "gare", "coiffeur", "fleuriste", "vétérinaire", "brasserie", "bistrot", "crêperie", "glacier", "librairie"]

    private static let asks = ["find", "where", "any ", "recommend", "suggest", "best", "good", "show", "look for", "search", "places", "somewhere", "trouve", "cherche", "où", "conseille", "recommande", "meilleur", "bon ", "bonne ", "y a-t-il", "il y a"]

    static func detect(_ message: String) -> Request? {
        let lower = message.lowercased()
        // Trips: "how long to get to the station", "how far is Lyon", "directions to the Louvre"
        if let m = lower.firstMatch(of: /(?:how long (?:does it take |will it take |would it take )?to (?:get|go|drive|walk|bike|cycle) to|how far (?:is|away is)|directions to|route to|itinéraire (?:vers|pour aller à|jusqu'à)|combien de temps pour aller (?:à|au|aux|chez)|à quelle distance (?:est|se trouve))\s+(.+?)[?.!]*$/) {
            let destination = String(m.1).trimmingCharacters(in: .whitespaces)
            return destination.isEmpty ? nil : Request(query: destination, isRoute: true)
        }
        let words = Set(lower.components(separatedBy: CharacterSet.letters.union(CharacterSet(charactersIn: "-'’")).inverted))
        let hasNear = nearby.contains { phrase in phrase.contains(" ") ? lower.contains(phrase) : words.contains(phrase) }
        let kind = kinds.first { kind in kind.contains(" ") ? lower.contains(kind) : words.contains(kind) || words.contains(kind + "s") || words.contains(kind + "es") }
        let asking = asks.contains { lower.contains($0) }
        guard (hasNear && (kind != nil || asking)) || (kind != nil && asking) else { return nil }
        return Request(query: query(from: lower, kind: kind), isRoute: false)
    }

    /// A Maps island in the prompt: search for whatever the words ask, even without "near me".
    static func forced(_ message: String) -> Request {
        let lower = message.lowercased()
        let kind = kinds.first { lower.contains($0) }
        return Request(query: query(from: lower, kind: kind), isRoute: false)
    }

    /// What to search Maps for: the message minus the asking and the "near me".
    private static func query(from lower: String, kind: String?) -> String {
        var query = " " + lower + " "
        for phrase in nearby.filter({ $0.contains(" ") }) { query = query.replacingOccurrences(of: phrase, with: " ") }
        query = query.replacingOccurrences(of: #"[?!.,;:]"#, with: " ", options: .regularExpression)
        let filler: Set<String> = ["can", "could", "you", "please", "find", "show", "search", "for", "look", "looking", "where", "where's", "is", "are", "there", "any", "some", "a", "an", "the", "me", "us", "my", "i", "want", "need", "to", "eat", "go", "recommend", "suggest", "best", "good", "nice", "near", "nearby", "around", "here", "closest", "nearest", "local", "places", "place", "somewhere", "in", "area", "trouve", "trouver", "cherche", "chercher", "moi", "nous", "un", "une", "des", "le", "la", "les", "de", "du", "où", "est", "sont", "il", "y", "a-t-il", "a", "bon", "bonne", "bons", "bonnes", "meilleur", "meilleurs", "meilleure", "proche", "près", "coin", "quartier", "conseille", "recommande", "manger", "aller", "pour", "qui", "ici", "what", "what's", "which"]
        let kept = query.split(separator: " ").map(String.init).filter { !filler.contains($0) }
        let result = kept.joined(separator: " ").trimmingCharacters(in: .whitespaces)
        return result.isEmpty ? (kind ?? "restaurants") : result
    }

    static func run(_ request: Request) async -> Result<Found, Error> {
        do {
            let here = try await LocationFetcher.current()
            if request.isRoute { return .success(try await trip(to: request.query, from: here)) }
            let search = MKLocalSearch.Request()
            search.naturalLanguageQuery = request.query
            search.region = MKCoordinateRegion(center: here.coordinate, latitudinalMeters: 4000, longitudinalMeters: 4000)
            search.resultTypes = .pointOfInterest
            let items = Array(try await MKLocalSearch(request: search).start().mapItems.prefix(5))
            guard !items.isEmpty else { return .success(Found(summary: "Nothing found nearby", fact: "Apple Maps found no \(request.query) near the user.", links: [])) }
            let rows = items.map { item -> (String, ToolUse.Link?) in
                let distance = item.location.distance(from: here)
                let name = item.name ?? "Place"
                let address = item.address?.shortAddress ?? ""
                return ("\(name), \(meters(distance)) away\(address.isEmpty ? "" : ", " + address)", link(name, item.location.coordinate, "\(name) · \(meters(distance))"))
            }
            let fact = "Places matching “\(request.query)” near the user, nearest first (from Apple Maps):\n" + rows.enumerated().map { "\($0.offset + 1). \($0.element.0)" }.joined(separator: "\n") + "\nAnswer from these; don't invent others."
            let pins = items.map { item in
                ToolWidget.Pin(name: item.name ?? "Place", detail: meters(item.location.distance(from: here)), latitude: item.location.coordinate.latitude, longitude: item.location.coordinate.longitude,
                               url: link(item.name ?? "Place", item.location.coordinate, "")?.url)
            }
            return .success(Found(summary: "\(items.count) places · nearest \(meters(items[0].location.distance(from: here)))", fact: fact, links: rows.compactMap(\.1), pins: pins, here: pin(here)))
        } catch {
            return .failure(error)
        }
    }

    private static func trip(to destination: String, from here: CLLocation) async throws -> Found {
        let search = MKLocalSearch.Request()
        search.naturalLanguageQuery = destination
        search.region = MKCoordinateRegion(center: here.coordinate, latitudinalMeters: 50_000, longitudinalMeters: 50_000)
        guard let place = try await MKLocalSearch(request: search).start().mapItems.first else {
            throw PlaceError.notFound(destination)
        }
        var lines: [String] = []
        for (type, label) in [(MKDirectionsTransportType.walking, "walking"), (.automobile, "by car"), (.transit, "by public transport")] {
            let request = MKDirections.Request()
            request.source = MKMapItem.forCurrentLocation()
            request.destination = place
            request.transportType = type
            if let eta = try? await MKDirections(request: request).calculateETA() {
                lines.append("\(label): \(minutes(eta.expectedTravelTime)) (\(meters(eta.distance)))")
            }
        }
        let name = place.name ?? destination
        let straight = meters(place.location.distance(from: here))
        let fact = "Trip from the user's location to \(name) (Apple Maps): " + (lines.isEmpty ? "about \(straight) as the crow flies; no route times available." : lines.joined(separator: "; ") + ".")
        let destination = ToolWidget.Pin(name: name, detail: lines.first ?? straight, latitude: place.location.coordinate.latitude, longitude: place.location.coordinate.longitude, url: link(name, place.location.coordinate, "")?.url)
        return Found(summary: lines.first.map { "\(name) · \($0)" } ?? "\(name) · \(straight)", fact: fact, links: [link(name, place.location.coordinate, "\(name) in Maps")].compactMap { $0 },
                     pins: [destination], here: pin(here))
    }

    private static func pin(_ here: CLLocation) -> ToolWidget.Pin {
        ToolWidget.Pin(name: "You", detail: "", latitude: here.coordinate.latitude, longitude: here.coordinate.longitude)
    }

    private static func link(_ name: String, _ coordinate: CLLocationCoordinate2D, _ title: String) -> ToolUse.Link? {
        var components = URLComponents(string: "https://maps.apple.com/")!
        components.queryItems = [.init(name: "q", value: name), .init(name: "ll", value: "\(coordinate.latitude),\(coordinate.longitude)")]
        return components.url.map { ToolUse.Link(title: title, url: $0) }
    }

    static func meters(_ value: CLLocationDistance) -> String {
        Measurement(value: value, unit: UnitLength.meters).formatted(.measurement(width: .abbreviated, usage: .road))
    }

    static func minutes(_ seconds: TimeInterval) -> String {
        let m = Int((seconds / 60).rounded())
        return m < 60 ? "\(m) min" : "\(m / 60) h \(m % 60) min"
    }

    enum PlaceError: LocalizedError {
        case notFound(String)
        var errorDescription: String? { if case .notFound(let place) = self { return "Couldn’t find “\(place)” on the map." }; return nil }
    }
}

/// One fix of where you are, asking for permission the first time.
@MainActor
final class LocationFetcher: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var waiting: CheckedContinuation<CLLocation, Error>?
    private static var shared: LocationFetcher?

    enum LocationError: LocalizedError {
        case denied, unavailable
        var errorDescription: String? {
            switch self {
            case .denied: "Loocal isn’t allowed to use your location. You can allow it in Settings."
            case .unavailable: "Your location isn’t available right now."
            }
        }
    }

    static func current() async throws -> CLLocation {
        let fetcher = LocationFetcher()
        shared = fetcher
        defer { shared = nil }
        return try await fetcher.fetch()
    }

    private func fetch() async throws -> CLLocation {
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        if let recent = manager.location, recent.timestamp.timeIntervalSinceNow > -300 { return recent }
        return try await withCheckedThrowingContinuation { continuation in
            waiting = continuation
            switch manager.authorizationStatus {
            case .notDetermined: manager.requestWhenInUseAuthorization()
            case .denied, .restricted: finish(.failure(LocationError.denied))
            default: manager.requestLocation()
            }
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(10))
                self?.finish(.failure(LocationError.unavailable))
            }
        }
    }

    private func finish(_ result: Result<CLLocation, Error>) {
        guard let waiting else { return }
        self.waiting = nil
        waiting.resume(with: result)
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            switch status {
            case .authorizedWhenInUse, .authorizedAlways: self.manager.requestLocation()
            case .denied, .restricted: self.finish(.failure(LocationError.denied))
            default: break
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        Task { @MainActor in self.finish(.success(location)) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in self.finish(.failure(LocationError.unavailable)) }
    }
}

// MARK: - Reading a web page

enum PageReader {
    struct Page { var title: String; var text: String }

    static func links(in text: String) -> [URL] {
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else { return [] }
        return detector.matches(in: text, range: NSRange(text.startIndex..., in: text)).compactMap(\.url).filter { $0.scheme == "http" || $0.scheme == "https" }
    }

    /// The readable text of a page: its article or main part if it has one, without
    /// scripts, menus and footers, cut to what fits the phone's small context.
    static func read(_ url: URL, limit: Int = 1600) async -> Page? {
        var request = URLRequest(url: url, timeoutInterval: 8)
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1", forHTTPHeaderField: "User-Agent")
        request.httpShouldHandleCookies = false
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (response as? HTTPURLResponse).map({ (200..<400).contains($0.statusCode) }) ?? true,
              let html = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) else { return nil }
        return extract(html, limit: limit)
    }

    static func extract(_ html: String, limit: Int) -> Page? {
        let title = first(#"<title[^>]*>([\s\S]*?)</title>"#, in: html).map(plain) ?? ""
        var body = html
        for tag in ["script", "style", "noscript", "svg", "nav", "header", "footer", "aside", "form", "iframe", "template", "textarea", "table", "figure", "sup"] {
            body = body.replacingOccurrences(of: "<\(tag)[\\s\\S]*?</\(tag)>", with: " ", options: [.regularExpression, .caseInsensitive])
        }
        if let main = first(#"<article[\s\S]*?</article>"#, in: body, whole: true) ?? first(#"<main[\s\S]*?</main>"#, in: body, whole: true) { body = main }
        // Real prose lives in paragraphs: use them when there are enough.
        if let regex = try? NSRegularExpression(pattern: #"<p[^>]*>([\s\S]*?)</p>"#, options: .caseInsensitive) {
            let paragraphs = regex.matches(in: body, range: NSRange(body.startIndex..., in: body)).compactMap { Range($0.range(at: 1), in: body).map { plain(String(body[$0])) } }
                .filter { $0.count > 60 && !$0.contains("{{") }
            if paragraphs.joined().count > 300 {
                return Page(title: title, text: String(paragraphs.joined(separator: "\n").prefix(limit)))
            }
        }
        // Paragraph breaks survive as new lines.
        body = body.replacingOccurrences(of: #"</(p|div|h[1-6]|li|br)>|<br\s*/?>"#, with: "\n", options: [.regularExpression, .caseInsensitive])
        let lines = plain(body).components(separatedBy: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { $0.count > 40 }
        let text = lines.joined(separator: "\n")
        guard text.count > 80 else { return nil }
        return Page(title: title, text: String(text.prefix(limit)))
    }

    private static func first(_ pattern: String, in text: String, whole: Bool = false) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]),
              let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              let range = Range(match.range(at: whole ? 0 : 1), in: text) else { return nil }
        return String(text[range])
    }

    private static func plain(_ html: String) -> String {
        // Inline tags (links, bold) vanish without a trace; block tags were already new lines.
        var text = html.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        for (entity, value) in ["&amp;": "&", "&quot;": "\"", "&#x27;": "’", "&#39;": "’", "&rsquo;": "’", "&lsquo;": "‘", "&ldquo;": "“", "&rdquo;": "”", "&lt;": "<", "&gt;": ">", "&nbsp;": " ", "&mdash;": "—", "&ndash;": "–", "&hellip;": "…"] {
            text = text.replacingOccurrences(of: entity, with: value)
        }
        return text.replacingOccurrences(of: #"[ \t]+"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"\n\s*\n+"#, with: "\n", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

// MARK: - Translation

enum Translator {
    struct Request { var text: String; var target: Locale.Language; var targetName: String }

    static func detect(_ message: String) -> Request? {
        let text = message.trimmingCharacters(in: .whitespacesAndNewlines)
        let patterns: [Regex<(Substring, Substring, Substring)>] = [
            /(?i)^(?:please |can you |could you )?translate\s+["“']?(.+?)["”']?\s+(?:to|into|in)\s+([A-Za-zÀ-ÿ\-]+)\s*[.?!]?$/,
            /(?i)^how (?:do|would) you say\s+["“']?(.+?)["”']?\s+in\s+([A-Za-zÀ-ÿ\-]+)\s*[?.!]?$/,
            /(?i)^(?:peux-tu |tu peux )?tradui[st]\s+["“']?(.+?)["”']?\s+en\s+([A-Za-zÀ-ÿ\-]+)\s*[.?!]?$/,
            /(?i)^comment (?:dit-on|on dit|dire)\s+["“']?(.+?)["”']?\s+en\s+([A-Za-zÀ-ÿ\-]+)\s*[?.!]?$/,
        ]
        for pattern in patterns {
            if let m = text.firstMatch(of: pattern), let language = language(named: String(m.2)) {
                return Request(text: String(m.1), target: language.0, targetName: language.1)
            }
        }
        // "translate to Spanish: good morning" / "traduis en anglais : bonjour"
        if let m = text.firstMatch(of: /(?i)^(?:translate (?:to|into)|tradui[st] en)\s+([A-Za-zÀ-ÿ\-]+)\s*:\s*(.+)$/), let language = language(named: String(m.1)) {
            return Request(text: String(m.2), target: language.0, targetName: language.1)
        }
        return nil
    }

    private static let codes = ["en", "fr", "es", "de", "it", "pt", "nl", "ja", "ko", "zh", "ru", "ar", "hi", "tr", "pl", "uk", "sv", "da", "nb", "fi", "el", "he", "th", "vi", "id", "cs", "ro", "hu"]

    /// "Spanish", "espagnol", "español" → es.
    private static func language(named name: String) -> (Locale.Language, String)? {
        let wanted = name.lowercased().folding(options: .diacriticInsensitive, locale: nil)
        let namers = [Locale(identifier: "en"), Locale(identifier: "fr"), Locale.current]
        for code in codes {
            for namer in namers {
                if let localized = namer.localizedString(forLanguageCode: code)?.lowercased().folding(options: .diacriticInsensitive, locale: nil), localized == wanted {
                    let display = Locale.current.localizedString(forLanguageCode: code)?.capitalized ?? name
                    return (Locale.Language(identifier: code == "zh" ? "zh-Hans" : code), display)
                }
            }
            if let own = Locale(identifier: code).localizedString(forLanguageCode: code)?.lowercased().folding(options: .diacriticInsensitive, locale: nil), own == wanted {
                return (Locale.Language(identifier: code), Locale.current.localizedString(forLanguageCode: code)?.capitalized ?? name)
            }
        }
        return nil
    }

    enum TranslateError: LocalizedError {
        case notDownloaded(String), unsupported, unknownSource
        var errorDescription: String? {
            switch self {
            case .notDownloaded(let name): "\(name) isn’t downloaded for on-device translation (Settings › Apps › Translate › Downloaded Languages)"
            case .unsupported: "This language pair isn’t supported"
            case .unknownSource: "Couldn’t tell which language the text is in"
            }
        }
    }

    static func translate(_ request: Request) async -> Result<String, Error> {
        let recognizer = NLLanguageRecognizer()
        recognizer.processString(request.text)
        guard let code = recognizer.dominantLanguage?.rawValue else { return .failure(TranslateError.unknownSource) }
        let source = Locale.Language(identifier: code)
        switch await LanguageAvailability().status(from: source, to: request.target) {
        case .installed:
            do {
                let session = TranslationSession(installedSource: source, target: request.target)
                return .success(try await session.translate(request.text).targetText)
            } catch {
                return .failure(error)
            }
        case .supported: return .failure(TranslateError.notDownloaded(request.targetName))
        default: return .failure(TranslateError.unsupported)
        }
    }
}
