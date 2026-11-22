import EventKit
import Foundation
import Security
import UIKit

/// A source of context the model can read. Built-ins are fetched on device right before a
/// reply and added to the system prompt; MCP servers expose tools the model can call.
struct Connector: Identifiable, Hashable {
    let id: String
    let name: String
    let icon: String
    let detail: String

    static let calendar = Connector(id: "calendar", name: "Calendar", icon: "calendar", detail: "Your next 7 days")
    static let reminders = Connector(id: "reminders", name: "Reminders", icon: "checklist", detail: "Open reminders")
    static let clipboard = Connector(id: "clipboard", name: "Clipboard", icon: "doc.on.clipboard", detail: "What you last copied")
    static let wikipedia = Connector(id: "wikipedia", name: "Wikipedia", icon: "globe", detail: "Looks your question up online")
    static let web = Connector(id: "web", name: "Web search", icon: "magnifyingglass", detail: "Lets the model search the web")
    static let maps = Connector(id: "maps", name: "Maps", icon: "map", detail: "Searches Apple Maps around you")
    static let builtIn = [web, maps, calendar, reminders, clipboard, wikipedia]

    /// The box shown in a reply when this source is read.
    var use: ToolUse {
        switch id {
        case Connector.calendar.id: ToolUse(kind: id, icon: icon, running: "Reading your calendar", done: "Read your calendar", detail: "Next 7 days")
        case Connector.reminders.id: ToolUse(kind: id, icon: icon, running: "Reading your reminders", done: "Read your reminders", detail: "Open reminders")
        case Connector.clipboard.id: ToolUse(kind: id, icon: icon, running: "Reading the clipboard", done: "Read the clipboard")
        case Connector.wikipedia.id: ToolUse(kind: id, icon: "globe", running: "Looking it up on Wikipedia", done: "Looked it up on Wikipedia")
        default: ToolUse(kind: id, icon: icon, running: "Using \(name)", done: "Used \(name)")
        }
    }

    static func mcp(_ server: MCPServer) -> Connector {
        Connector(id: server.connectorID, name: server.name, icon: "server.rack", detail: "\(server.tools.count) tools · \(server.url.host() ?? "")")
    }
}

// MARK: - Built-in context

@MainActor
enum ConnectorHub {
    static let store = EKEventStore()
    /// Each connector's share of the prompt; the whole context window is only 2,048 tokens.
    private static let budget = 700

    /// Ask for the permission a connector needs when it's switched on.
    static func requestAccess(for id: String) async -> Bool {
        switch id {
        case Connector.calendar.id: return (try? await store.requestFullAccessToEvents()) ?? false
        case Connector.reminders.id: return (try? await store.requestFullAccessToReminders()) ?? false
        default: return true
        }
    }

    /// Whether Loocal may read this source now.
    static func isAllowed(_ id: String) -> Bool {
        switch id {
        case Connector.calendar.id: EKEventStore.authorizationStatus(for: .event) == .fullAccess
        case Connector.reminders.id: EKEventStore.authorizationStatus(for: .reminder) == .fullAccess
        default: true
        }
    }

    /// Sources a request asks for in so many words. Allowed ones come back to be read; for
    /// any that aren't, a note tells the model so it says that rather than guessing.
    /// `ask` shows the permission prompt for a source that was never asked about.
    static func resolve(_ wanted: Set<String>, ask: Bool) async -> (allowed: Set<String>, note: String) {
        var allowed: Set<String> = [], refused: [String] = []
        for id in wanted.sorted() {
            if isAllowed(id) { allowed.insert(id); continue }
            if ask, needsAsking(id), await requestAccess(for: id), isAllowed(id) { allowed.insert(id); continue }
            refused.append(Connector.builtIn.first { $0.id == id }?.name ?? id)
        }
        guard !refused.isEmpty else { return (allowed, "") }
        let names = ListFormatter.localizedString(byJoining: refused)
        return (allowed, "Loocal isn't allowed to read the user's \(names), so you can't see it. Say so plainly, and that they can allow it in the Settings app › Apps › Loocal. Never guess what's in it.")
    }

    private static func needsAsking(_ id: String) -> Bool {
        switch id {
        case Connector.calendar.id: EKEventStore.authorizationStatus(for: .event) == .notDetermined
        case Connector.reminders.id: EKEventStore.authorizationStatus(for: .reminder) == .notDetermined
        default: false
        }
    }

    static func context(for active: Set<String>, prompt: String) async -> String {
        var parts: [String] = []
        if active.contains(Connector.calendar.id), let text = calendar(SourceIntent.calendarWindow(for: prompt)) { parts.append(text) }
        if active.contains(Connector.reminders.id), let text = await reminders() { parts.append(text) }
        if active.contains(Connector.clipboard.id), let text = UIPasteboard.general.string, !text.isEmpty {
            parts.append("Clipboard:\n" + String(text.prefix(budget)))
        }
        if active.contains(Connector.wikipedia.id), let text = await Wikipedia.summary(for: prompt) { parts.append(text) }
        guard !parts.isEmpty else { return "" }
        return "Context from the user's connected sources (use it when relevant):\n\n" + parts.joined(separator: "\n\n")
    }

    private static func calendar(_ window: SourceIntent.Window) -> String? {
        guard isAllowed(Connector.calendar.id) else { return nil }
        let long = window.end.timeIntervalSince(window.start) > 8 * 86_400
        let all = store.events(matching: store.predicateForEvents(withStart: window.start, end: window.end, calendars: nil))
        let events = all.prefix(long ? 40 : 14)
        let span = "\(window.start.formatted(.dateTime.weekday().day().month())) – \(window.end.addingTimeInterval(-1).formatted(.dateTime.weekday().day().month()))"
        guard !events.isEmpty else { return "Calendar (\(window.label), \(span)): nothing scheduled. Say the calendar is clear then." }
        let lines = events.map { event in
            let day = long ? event.startDate.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated)) : event.startDate.formatted(.dateTime.weekday().day().month())
            let when = event.isAllDay ? day + " (all day)" : day + " " + event.startDate.formatted(.dateTime.hour().minute())
            return "- \(when): \(event.title ?? "Untitled")" + (long ? "" : event.location.map { " @ \($0)" } ?? "")
        }
        let more = all.count > events.count ? "\n(and \(all.count - events.count) more)" : ""
        return String(("Calendar (\(window.label), \(span)), the user's real events, all of them:\n" + lines.joined(separator: "\n") + more).prefix(long ? budget * 2 : budget))
    }

    private static func reminders() async -> String? {
        guard EKEventStore.authorizationStatus(for: .reminder) == .fullAccess else { return nil }
        let predicate = store.predicateForIncompleteReminders(withDueDateStarting: nil, ending: nil, calendars: nil)
        let titles: [String] = await withCheckedContinuation { continuation in
            store.fetchReminders(matching: predicate) { reminders in
                let items = (reminders ?? []).prefix(14).map { reminder in
                    let due = reminder.dueDateComponents?.date.map { " (due \($0.formatted(.dateTime.day().month())))" } ?? ""
                    return "- \(reminder.title ?? "Untitled")\(due)"
                }
                continuation.resume(returning: Array(items))
            }
        }
        guard !titles.isEmpty else { return "Reminders: none open." }
        return String(("Open reminders:\n" + titles.joined(separator: "\n")).prefix(budget))
    }
}

/// Which of the user's own sources a request is about, from its words alone, so "check my
/// calendar" or "what have I got next month" reads the calendar without a Calendar island.
enum SourceIntent {
    static let calendarWords = [
        "calendar", "agenda", "schedule", "meeting", "appointment", "event", "going on", "am i free", "am i busy", "i'm free", "im free",
        "what do i have", "what have i got", "what i have", "what's on", "whats on", "my plans", "plans for", "my day", "my week", "my weekend", "my month",
        "calendrier", "rendez-vous", "rdv", "reunion", "evenement", "ce que j'ai", "qu'est-ce que j'ai", "j'ai quoi", "je suis libre", "suis-je libre",
        "suis libre", "mon planning", "planning", "ma journee", "ma semaine", "mon week-end", "mon mois", "mes plans", "prevu",
    ]
    static let reminderWords = [
        "reminders", "my reminder", "to-do", "todo", "to do list", "my tasks", "what's due", "whats due", "need to do",
        "rappels", "mes rappels", "taches", "a faire", "dois faire",
    ]
    static let clipboardWords = ["clipboard", "i copied", "i've copied", "presse-papier", "j'ai copie"]

    static func folded(_ text: String) -> String {
        text.lowercased().folding(options: .diacriticInsensitive, locale: nil).replacingOccurrences(of: "’", with: "'")
    }

    static func wanted(_ text: String) -> Set<String> {
        let text = folded(text)
        var ids: Set<String> = []
        if calendarWords.contains(where: text.contains) { ids.insert(Connector.calendar.id) }
        if reminderWords.contains(where: text.contains) { ids.insert(Connector.reminders.id) }
        if clipboardWords.contains(where: text.contains) { ids.insert(Connector.clipboard.id) }
        return ids
    }

    struct Window: Equatable { let start: Date; let end: Date; let label: String }

    /// The stretch of calendar the words are about; the next 7 days when they don't say.
    static func calendarWindow(for text: String, now: Date = .now, calendar: Calendar = .current) -> Window {
        let text = folded(text)
        let has = { (words: [String]) in words.contains(where: text.contains) }
        let today = calendar.startOfDay(for: now)
        let day = { (offset: Int) in calendar.date(byAdding: .day, value: offset, to: today)! }
        if let match = text.firstMatch(of: /(?:next|coming|prochains?)\s+(\d{1,2})\s+(?:days|jours)|(\d{1,2})\s+(?:prochains|next)\s+(?:jours|days)/),
           let count = Int(match.output.1 ?? match.output.2 ?? "") {
            let days = min(60, max(1, count))
            return Window(start: now, end: day(days + 1), label: "next \(days) days")
        }
        if has(["next month", "mois prochain", "coming month"]) {
            let next = calendar.dateInterval(of: .month, for: calendar.date(byAdding: .month, value: 1, to: now)!)!
            return Window(start: next.start, end: next.end, label: "next month")
        }
        if has(["this month", "ce mois", "my month", "mon mois", "rest of the month"]) {
            return Window(start: now, end: calendar.dateInterval(of: .month, for: now)!.end, label: "the rest of this month")
        }
        if has(["next week", "semaine prochaine"]) {
            let next = calendar.dateInterval(of: .weekOfYear, for: calendar.date(byAdding: .weekOfYear, value: 1, to: now)!)!
            return Window(start: next.start, end: next.end, label: "next week")
        }
        if has(["weekend", "week-end", "week end"]) {
            let weekday = calendar.component(.weekday, from: now) // 1 Sunday … 7 Saturday
            if weekday == 7 || weekday == 1 { return Window(start: now, end: day(weekday == 7 ? 2 : 1), label: "this weekend") }
            return Window(start: day(7 - weekday), end: day(9 - weekday), label: "the weekend")
        }
        if has(["week ahead", "coming week", "7 days", "sept jours"]) { return Window(start: now, end: day(7), label: "next 7 days") }
        if has(["this week", "cette semaine", "my week", "ma semaine", "rest of the week"]) {
            return Window(start: now, end: calendar.dateInterval(of: .weekOfYear, for: now)!.end, label: "the rest of this week")
        }
        if has(["tomorrow", "demain"]) { return Window(start: day(1), end: day(2), label: "tomorrow") }
        if has(["today", "tonight", "aujourd'hui", "ce soir", "my day", "ma journee"]) { return Window(start: now, end: day(1), label: "today") }
        return Window(start: now, end: day(7), label: "next 7 days")
    }
}

/// Wikipedia search + summary in the device language. Sends only the question text.
enum Wikipedia {
    static func summary(for question: String) async -> String? {
        let language = Locale.current.language.languageCode?.identifier ?? "en"
        var search = URLComponents(string: "https://\(language).wikipedia.org/w/api.php")!
        let terms = keywords(question)
        guard !terms.isEmpty else { return nil }
        search.queryItems = [.init(name: "action", value: "query"), .init(name: "list", value: "search"), .init(name: "srsearch", value: terms.joined(separator: " ")), .init(name: "srlimit", value: "5"), .init(name: "format", value: "json")]
        guard let url = search.url,
              let data = try? await fetch(url),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let hits = ((root["query"] as? [String: Any])?["search"] as? [[String: Any]])?.compactMap({ $0["title"] as? String }),
              // Prefer the result whose title shares the most words with the question.
              let title = hits.enumerated().max(by: { overlap($0.element, terms) - Double($0.offset) * 0.01 < overlap($1.element, terms) - Double($1.offset) * 0.01 })?.element,
              let page = title.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed),
              let summaryURL = URL(string: "https://\(language).wikipedia.org/api/rest_v1/page/summary/\(page)"),
              let summaryData = try? await fetch(summaryURL),
              let summary = try? JSONSerialization.jsonObject(with: summaryData) as? [String: Any],
              let extract = summary["extract"] as? String else { return nil }
        return "Wikipedia — \(title):\n" + String(extract.prefix(700))
    }

    private static let stopwords: Set<String> = ["a", "an", "the", "is", "are", "was", "were", "be", "of", "in", "on", "at", "to", "for", "and", "or", "what", "who", "whom", "which", "when", "where", "why", "how", "tall", "big", "old", "much", "many", "does", "do", "did", "can", "could", "tell", "me", "about", "i", "you", "it", "its", "le", "la", "les", "un", "une", "des", "de", "du", "est", "qui", "que", "quoi", "quel", "quelle", "comment", "pourquoi", "où", "et", "en"]

    /// Search terms: proper nouns if the question has any, otherwise its content words.
    static func keywords(_ question: String) -> [String] {
        let words = question.components(separatedBy: CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "'-")).inverted).filter { !$0.isEmpty }
        let proper = words.dropFirst().filter { $0.first?.isUppercase == true }
        if !proper.isEmpty { return Array(proper) }
        return words.filter { !stopwords.contains($0.lowercased()) }
    }

    private static func overlap(_ title: String, _ terms: [String]) -> Double {
        let words = Set(title.lowercased().split(separator: " ").map(String.init))
        return Double(terms.filter { words.contains($0.lowercased()) }.count)
    }

    private static func fetch(_ url: URL) async throws -> Data {
        var request = URLRequest(url: url, timeoutInterval: 6)
        request.setValue("Loocal/1.0 (iOS app)", forHTTPHeaderField: "User-Agent")
        return try await URLSession.shared.data(for: request).0
    }
}

// MARK: - Web search

/// Web search for the on-device model, as a tool it can call. Only the query leaves the
/// phone: it goes to DuckDuckGo, with no account and no cookies. Falls back to Wikipedia.
enum WebSearch {
    struct Hit: Sendable, Hashable {
        var title: String
        var url: URL
        var snippet: String
    }

    /// The tool as the model sees it. Its URL never leaves the app; calls are routed here.
    static let definition = ToolDefinition(url: URL(string: "loocal://web-search")!, token: nil, tool: MCPTool(
        name: "web_search",
        description: "Search the web. Use it for news, recent events, prices, opening hours, places, people, or anything you are not sure about.",
        schema: #"{"type":"object","properties":{"query":{"type":"string","description":"A short search query"}},"required":["query"]}"#))

    static func isLocal(_ definition: ToolDefinition) -> Bool { definition.url.scheme == "loocal" }

    /// Small models often answer from memory instead of calling the tool. When a message
    /// clearly asks about something current, the search runs first, whatever the model does.
    static func wantsSearch(_ text: String) -> Bool {
        let lower = text.lowercased()
        let words = Set(lower.components(separatedBy: CharacterSet.letters.union(.decimalDigits).union(CharacterSet(charactersIn: "'’")).inverted).filter { !$0.isEmpty })
        if !words.isDisjoint(with: triggerWords) { return true }
        return triggerPhrases.contains { lower.contains($0) }
    }

    /// Words that ask for a search outright: these run one even with web search off.
    static func explicitlyAsked(_ text: String) -> Bool {
        let lower = text.lowercased()
        return ["search", "look up", "look it up", "google", "on the web", "online", "cherche", "recherche", "sur internet", "sur le web"].contains { lower.contains($0) }
    }

    private static let triggerWords: Set<String> = [
        "latest", "news", "today", "today's", "tonight", "current", "currently", "recent", "recently", "update", "updates",
        "price", "prices", "cost", "weather", "forecast", "score", "scores", "winner", "won", "release", "released", "launch",
        "launched", "upcoming", "search", "google", "trending", "stock", "stocks", "election", "headlines", "2025", "2026", "2027",
        "dernier", "dernière", "derniers", "dernières", "actu", "actualité", "actualités", "aujourd'hui", "aujourd’hui", "récent",
        "récente", "récemment", "prix", "météo", "sortie", "cherche", "recherche", "tendance",
    ]
    private static let triggerPhrases = ["right now", "look up", "this week", "this year", "this month", "what's new", "what’s new", "en ce moment", "cette semaine", "cette année", "quoi de neuf", "open now", "near me"]

    /// The message as a search query: its first line, without the polite padding.
    static func searchQuery(from text: String) -> String {
        var query = text.split(whereSeparator: \.isNewline).first.map(String.init) ?? text
        for filler in ["can you ", "could you ", "please ", "tell me ", "search the web for ", "search online for ", "search for ", "search ", "look up ", "google ", "cherche sur internet ", "cherche "] {
            if query.lowercased().hasPrefix(filler) { query = String(query.dropFirst(filler.count)) }
        }
        return String(query.trimmingCharacters(in: .whitespacesAndNewlines.union(.punctuationCharacters)).prefix(120))
    }

    static func arguments(for query: String) -> String {
        (try? JSONSerialization.data(withJSONObject: ["query": query])).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }

    /// Added to the system prompt when the tool is on.
    static let guidance = "You can search the web with web_search. Use it for anything that may have changed recently: news, the latest versions, prices, weather, results, opening hours. Do not guess current facts."

    static func query(from argumentsJSON: String) -> String {
        let object = (try? JSONSerialization.jsonObject(with: Data(argumentsJSON.utf8))) as? [String: Any]
        return ((object?["query"] ?? object?["q"]) as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func search(_ query: String) async -> [Hit] {
        guard !query.isEmpty else { return [] }
        let hits = (try? await duckDuckGo(query)) ?? []
        if !hits.isEmpty { return hits }
        // Nothing back (offline page, a challenge page): try Wikipedia instead.
        if let text = await Wikipedia.summary(for: query), let title = text.split(separator: "\n").first {
            let name = title.replacingOccurrences(of: "Wikipedia — ", with: "").replacingOccurrences(of: ":", with: "")
            let language = Locale.current.language.languageCode?.identifier ?? "en"
            let page = name.replacingOccurrences(of: " ", with: "_").addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? ""
            if let url = URL(string: "https://\(language).wikipedia.org/wiki/\(page)") {
                return [Hit(title: name, url: url, snippet: String(text.dropFirst(title.count + 1)))]
            }
        }
        return []
    }

    /// What the model reads back: numbered results, trimmed to fit the small context.
    static func report(_ hits: [Hit], for query: String) -> String {
        guard !hits.isEmpty else { return "No web results for “\(query)”." }
        return "Web results for “\(query)”:\n" + hits.prefix(5).enumerated().map { i, hit in
            "\(i + 1). \(hit.title) (\(hit.url.host() ?? "")): \(hit.snippet.prefix(220))"
        }.joined(separator: "\n") + "\nAnswer from these results and name the sources you used."
    }

    private static func duckDuckGo(_ query: String) async throws -> [Hit] {
        var request = URLRequest(url: URL(string: "https://html.duckduckgo.com/html/")!, timeoutInterval: 8)
        request.httpMethod = "POST"
        request.httpShouldHandleCookies = false
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1", forHTTPHeaderField: "User-Agent")
        let region = Locale.current.region.map { "\($0.identifier.lowercased())-\(Locale.current.language.languageCode?.identifier ?? "en")" } ?? "wt-wt"
        var form = URLComponents()
        form.queryItems = [.init(name: "q", value: query), .init(name: "kl", value: region)]
        request.httpBody = form.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B").data(using: .utf8)
        let (data, _) = try await URLSession.shared.data(for: request)
        return parse(String(decoding: data, as: UTF8.self))
    }

    static func parse(_ html: String) -> [Hit] {
        html.components(separatedBy: "class=\"result results_links").dropFirst().compactMap { block -> Hit? in
            guard !block.prefix(120).contains("result--ad"),
                  let link = first(#"class="result__a"[^>]*href="([^"]*)"[^>]*>([\s\S]*?)</a>"#, in: block), link.count == 2,
                  let url = target(link[0]) else { return nil }
            let snippet = first(#"class="result__snippet"[^>]*>([\s\S]*?)</a>"#, in: block)?.first ?? ""
            return Hit(title: plain(link[1]), url: url, snippet: plain(snippet))
        }
        .prefix(6).map { $0 }
    }

    /// Result links are either direct or DuckDuckGo redirects carrying the target in `uddg`.
    private static func target(_ href: String) -> URL? {
        let href = href.replacingOccurrences(of: "&amp;", with: "&")
        let absolute = href.hasPrefix("//") ? "https:" + href : href
        guard let components = URLComponents(string: absolute) else { return nil }
        if let redirect = components.queryItems?.first(where: { $0.name == "uddg" })?.value { return URL(string: redirect) }
        guard components.host?.contains("duckduckgo.com") == false else { return nil }
        return components.url
    }

    private static func first(_ pattern: String, in text: String) -> [String]? {
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) else { return nil }
        return (1..<match.numberOfRanges).compactMap { Range(match.range(at: $0), in: text).map { String(text[$0]) } }
    }

    private static func plain(_ html: String) -> String {
        var text = html.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        for (entity, value) in ["&amp;": "&", "&quot;": "\"", "&#x27;": "’", "&#39;": "’", "&lt;": "<", "&gt;": ">", "&nbsp;": " "] {
            text = text.replacingOccurrences(of: entity, with: value)
        }
        return text.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

// MARK: - MCP

struct MCPTool: Codable, Hashable, Sendable {
    var name: String
    var description: String
    /// The tool's JSON input schema, stored as text.
    var schema: String
}

struct MCPServer: Codable, Identifiable, Hashable {
    var id = UUID()
    var url: URL
    var name: String
    var tools: [MCPTool]
    var connectorID: String { "mcp:" + id.uuidString }

    static func load() -> [MCPServer] {
        guard let data = UserDefaults.standard.data(forKey: "mcp.servers") else { return [] }
        return (try? JSONDecoder().decode([MCPServer].self, from: data)) ?? []
    }

    static func save(_ servers: [MCPServer]) {
        UserDefaults.standard.set(try? JSONEncoder().encode(servers), forKey: "mcp.servers")
    }
}

/// One MCP tool as the model sees it, with what's needed to call it.
struct ToolDefinition: Sendable {
    let url: URL
    let token: String?
    let tool: MCPTool
}

/// Minimal MCP client over the Streamable HTTP transport: JSON-RPC requests whose replies
/// arrive as JSON or as a short server-sent-event stream.
actor MCPClient {
    private let url: URL
    private let token: String?
    private var sessionID: String?
    private var nextID = 1

    init(url: URL, token: String?) {
        self.url = url
        self.token = token
    }

    /// Handshake, then list tools. Returns the server's name and its tools.
    func discover() async throws -> (name: String, tools: [MCPTool]) {
        let info = try await handshake()
        let listed = try await call("tools/list", params: [:])
        let tools = (listed["tools"] as? [[String: Any]] ?? []).compactMap { tool -> MCPTool? in
            guard let name = tool["name"] as? String else { return nil }
            let schema = (tool["inputSchema"]).flatMap { try? JSONSerialization.data(withJSONObject: $0) }.flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
            return MCPTool(name: name, description: tool["description"] as? String ?? "", schema: schema)
        }
        return (info, tools)
    }

    /// Run one tool and return its text output.
    func run(_ tool: String, argumentsJSON: String) async throws -> String {
        _ = try await handshake()
        let arguments = (try? JSONSerialization.jsonObject(with: Data(argumentsJSON.utf8))) ?? [String: Any]()
        let result = try await call("tools/call", params: ["name": tool, "arguments": arguments])
        let text = (result["content"] as? [[String: Any]] ?? []).compactMap { $0["text"] as? String }.joined(separator: "\n")
        if result["isError"] as? Bool == true { return "Tool error: " + text }
        return text.isEmpty ? "(no output)" : text
    }

    private func handshake() async throws -> String {
        let result = try await call("initialize", params: [
            "protocolVersion": "2025-06-18",
            "capabilities": [String: Any](),
            "clientInfo": ["name": "Loocal", "version": "1.0"],
        ])
        try await notify("notifications/initialized")
        return (result["serverInfo"] as? [String: Any])?["name"] as? String ?? url.host() ?? "MCP server"
    }

    private func notify(_ method: String) async throws {
        _ = try await send(["jsonrpc": "2.0", "method": method])
    }

    private func call(_ method: String, params: [String: Any]) async throws -> [String: Any] {
        let id = nextID
        nextID += 1
        let messages = try await send(["jsonrpc": "2.0", "id": id, "method": method, "params": params])
        guard let reply = messages.first(where: { ($0["id"] as? Int) == id }) ?? messages.first else {
            throw IslandError.message("The MCP server didn’t answer \(method).")
        }
        if let error = reply["error"] as? [String: Any] {
            throw IslandError.message("MCP: \(error["message"] as? String ?? "request failed")")
        }
        return reply["result"] as? [String: Any] ?? [:]
    }

    private func send(_ body: [String: Any]) async throws -> [[String: Any]] {
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.httpMethod = "POST"
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json, text/event-stream", forHTTPHeaderField: "Accept")
        request.setValue("2025-06-18", forHTTPHeaderField: "MCP-Protocol-Version")
        if let sessionID { request.setValue(sessionID, forHTTPHeaderField: "Mcp-Session-Id") }
        if let token, !token.isEmpty { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw IslandError.message("MCP: no response.") }
        if let session = http.value(forHTTPHeaderField: "Mcp-Session-Id") { sessionID = session }
        guard (200..<300).contains(http.statusCode) else {
            throw IslandError.message(http.statusCode == 401 ? "MCP: the server needs a valid token." : "MCP: server returned \(http.statusCode).")
        }
        if data.isEmpty { return [] }
        if http.value(forHTTPHeaderField: "Content-Type")?.contains("text/event-stream") == true {
            return String(decoding: data, as: UTF8.self)
                .split(whereSeparator: \.isNewline) // handles \r\n, a single Character in Swift
                .filter { $0.hasPrefix("data:") }
                .compactMap { line in try? JSONSerialization.jsonObject(with: Data(line.dropFirst(5).utf8)) as? [String: Any] }
        }
        let object = try JSONSerialization.jsonObject(with: data)
        return (object as? [[String: Any]]) ?? [(object as? [String: Any]) ?? [:]]
    }
}

/// MCP bearer tokens live in the Keychain, never in UserDefaults.
enum Keychain {
    private static let service = "local.islandai.mcp"

    static func set(_ value: String?, for account: String) {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
        SecItemDelete(query as CFDictionary)
        guard let value, !value.isEmpty else { return }
        var item = query
        item[kSecValueData as String] = Data(value.utf8)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(item as CFDictionary, nil)
    }

    static func get(_ account: String) -> String? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account, kSecReturnData as String: true]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
}

// MARK: - Tool calls in model output

/// Parses a Hermes-style `<tool_call>{"name": …, "arguments": {…}}</tool_call>` block.
enum ToolCall {
    static let open = "<tool_call>"

    static func parse(_ text: String) -> (name: String, argumentsJSON: String)? {
        guard let start = text.range(of: open) else { return nil }
        var body = text[start.upperBound...]
        if let end = body.range(of: "</tool_call>") { body = body[..<end.lowerBound] }
        guard let data = body.trimmingCharacters(in: .whitespacesAndNewlines).data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let name = object["name"] as? String else { return nil }
        let arguments = object["arguments"].flatMap { try? JSONSerialization.data(withJSONObject: $0) }.flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        return (name, arguments)
    }

    /// The part of the text that should be shown: everything before a tool call starts.
    static func visible(_ text: String) -> String {
        text.components(separatedBy: open).first ?? text
    }
}
