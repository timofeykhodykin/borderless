import Foundation
import Shared

/// The socket protocol between the app and the service ([Wire], the [Request]/[Reply]/[ServerList] models) and the
/// stored servers file ([ServerList.load]). `ServiceClient` itself is in the app target and can't be imported — its
/// framing half is checked here: several messages in one read, an unfinished tail, a pushed line read before the
/// reply, and the length cap the service applies to stored data.
func wireChecks() {
    check("wire lines: several at once, an empty one, the tail kept") {
        var buffer = Data("{\"a\":1}\n\n{\"b\":".utf8)
        let lines = Wire.lines(&buffer)
        expect(lines.count == 2, "two complete lines, got \(lines.count)")
        expect(lines[0] == Data("{\"a\":1}".utf8), "the first line")
        expect(lines[1].isEmpty, "an empty line is a line of its own")
        expect(buffer == Data("{\"b\":".utf8), "the unfinished tail stays")
        var nothing = Data()
        expect(Wire.lines(&nothing).isEmpty, "an empty buffer gives nothing")
        var partial = Data("{\"x\"".utf8)
        expect(Wire.lines(&partial).isEmpty && partial == Data("{\"x\"".utf8), "no newline, nothing consumed")
    }

    check("a pushed line doesn't eat the reply behind it") {
        // What ServiceClient relies on since the fix: a line the service pushed before answering is skipped and the
        // reply line still decodes — the framing itself keeps them separate.
        var buffer = Data()
        buffer.append(try Wire.encode(Reply(version: "0.1.0", tunnel: .connected, serverId: "s1")))
        buffer.append(try Wire.encode(Reply(version: "0.1.0", tunnel: .off, error: "busy")))
        let lines = Wire.lines(&buffer)
        let decoded = try lines.map { try Wire.decode(Reply.self, line: $0) }
        expect(decoded.count == 2 && decoded[0].serverId == "s1" && decoded[1].error == "busy", "both arrive in order")
        expect(buffer.isEmpty, "nothing left")
    }

    check("a request travels a socket pair") {
        var fds: [Int32] = [-1, -1]
        expect(socketpair(AF_UNIX, SOCK_STREAM, 0, &fds) == 0, "socketpair")
        defer { for fd in fds where fd >= 0 { close(fd) } }
        let list = ServerList(groups: [StoredGroup(id: "g1", name: "G")],
                              servers: [StoredServer(id: "s1", name: "One", link: "vless://x", proto: "vless", groupId: "g1")],
                              subscriptions: [StoredSubscription(id: "p1", name: "P", url: "https://example.org/s", groupId: "g1")])
        let req = Request(.saveServers, serverId: "s1", servers: list, json: "{\"a\":1}", on: true,
                          countries: ["NL"], fromMs: 1, toMs: 2, target: "sub:p1")
        expect(Wire.send(try Wire.encode(req), to: fds[0]), "the request is sent")
        var buffer = Data()
        var chunk = [UInt8](repeating: 0, count: 4096)
        let n = read(fds[1], &chunk, chunk.count)
        expect(n > 0, "bytes arrive")
        if n > 0 { buffer.append(contentsOf: chunk[0..<n]) }
        let lines = Wire.lines(&buffer)
        expect(lines.count == 1, "one line arrives")
        let back = try Wire.decode(Request.self, line: lines[0])
        expect(back == req, "every field round-trips")
    }

    check("the longest socket path still fits sockaddr_un") {
        // 103 bytes of path + the NUL fill the 104-byte sun_path exactly; one more traps by design (a cut path is a
        // different address — the precondition can't be caught in a check, so only the fitting edge is tested).
        let long = String(repeating: "a", count: 103)
        var addr = unixAddress(long)
        let path = withUnsafeBytes(of: &addr.sun_path) { String(cString: $0.bindMemory(to: CChar.self).baseAddress!) }
        expect(path == long, "the path survives whole")
    }

    check("an older service's engine answer still decodes") {
        // Fields added later (routing, eco, deviceId, the lists' state, unreported) are optional: a service from an
        // older build doesn't send them and its answer must still read.
        let old = try JSONDecoder().decode(EngineStatus.self, from: Data("""
        {"phase":"OFF","unanswered":false,"noServers":true,"nextRetryAt":0,"scanDone":0,"scanTotal":0,\
        "scanStartedAt":0,"probing":[],"pending":[],"pings":{},"strategy":"auto"}
        """.utf8))
        expect(old.routing == nil && old.eco == nil && old.deviceId == nil && old.unreported == nil
               && old.listsUpdated == nil && old.ecoEstimate == nil, "the absent fields stay nil")
        let full = try JSONDecoder().decode(EngineStatus.self, from: Data("""
        {"phase":"CONNECTED","unanswered":false,"noServers":false,"nextRetryAt":0,"scanDone":3,"scanTotal":5,\
        "scanStartedAt":0,"probing":[],"pending":[],"pings":{"s1":{"ms":12,"at":7}},"strategy":"auto",\
        "routing":true,"eco":false,"ecoEstimate":5}
        """.utf8))
        expect(full.pings["s1"]?.ms == 12 && full.pings["s1"]?.at == 7, "a ping reads")
        expect(full.routing == true && full.eco == false && full.ecoEstimate == 5, "the newer fields read")
        expect((try? JSONDecoder().decode(EngineStatus.self, from: Data(#"{"unanswered":false}"#.utf8))) == nil,
               "a missing required field fails")
    }

    check("a stored server reads Android's omitted fields as defaults") {
        let bare = try JSONDecoder().decode(StoredServer.self, from: Data(#"{"id":"s1","link":"vless://x"}"#.utf8))
        expect(bare.groupId == StoredGroup.defaultId && bare.name == "" && bare.proto == "" && !bare.disabled
               && bare.limitedStreams == 0 && bare.limitedDevice == "" && !bare.limitedIgnoreLimit, "the defaults")
        let named = try JSONDecoder().decode(StoredServer.self,
                                             from: Data(#"{"id":"s1","link":"x","protocol":"vless"}"#.utf8))
        expect(named.proto == "vless", "Android's field name 'protocol'")
    }

    check("the service refuses a bad servers list with the reason") {
        func list(_ servers: [StoredServer]) -> ServerList { ServerList(servers: servers) }
        func server(_ id: String, _ link: String = "x") -> StoredServer {
            StoredServer(id: id, name: "", link: link, proto: "", groupId: "own")
        }
        expect(list([server("a"), server("a")]).problem == "two servers with one id", "duplicate ids")
        expect(list([server("")]).problem == "a server is damaged", "an empty id")
        expect(list([server("a", String(repeating: "x", count: ServerList.maxLinkBytes + 1))]).problem == "a server is damaged",
               "a link past the size cap")
        expect(list((0...ServerList.maxServers).map { server("s\($0)") }).problem == "too many servers", "past the cap")
        expect(ServerList().problem == nil, "an empty list is fine")
    }

    check("a damaged servers file is kept aside, a good one loads") {
        let dir = NSTemporaryDirectory() + "borderless-checks-\(UUID().uuidString)"
        try FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(atPath: dir) }
        let missing = ServerList.load(at: dir + "/none.json")
        expect(missing.list == ServerList() && missing.keptAside == nil, "no file, an empty list")
        let good = ServerList(servers: [StoredServer(id: "s1", name: "One", link: "vless://x", proto: "vless", groupId: "own")])
        try JSONEncoder().encode(good).write(to: URL(fileURLWithPath: dir + "/servers.json"))
        let loaded = ServerList.load(at: dir + "/servers.json")
        expect(loaded.list == good && loaded.keptAside == nil, "the stored list reads back")
        try Data("not json".utf8).write(to: URL(fileURLWithPath: dir + "/bad.json"))
        let bad = ServerList.load(at: dir + "/bad.json")
        expect(bad.list == ServerList(), "a damaged file reads as empty")
        let aside = bad.keptAside ?? ""
        expect(aside.hasPrefix(dir + "/bad.json.broken-"), "the kept copy's name: \(aside)")
        expect(FileManager.default.contents(atPath: aside) == Data("not json".utf8), "the copy holds the bytes")
    }

    check("a servers file at the size limit loads, one past it is kept aside") {
        let dir = NSTemporaryDirectory() + "borderless-checks-\(UUID().uuidString)"
        try FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(atPath: dir) }
        // A valid list padded with whitespace (JSON ignores it) to just under and just at Wire.maxLine.
        let json = #"{"groups":[],"servers":[],"subscriptions":[]}"#
        let pad = String(repeating: " ", count: Wire.maxLine - 1 - json.utf8.count)
        try Data((json + pad).utf8).write(to: URL(fileURLWithPath: dir + "/edge.json"))
        let edge = ServerList.load(at: dir + "/edge.json")
        expect(edge.list == ServerList() && edge.keptAside == nil, "maxLine - 1 loads")
        try Data((json + pad + " ").utf8).write(to: URL(fileURLWithPath: dir + "/over.json"))
        let over = ServerList.load(at: dir + "/over.json")
        expect(over.list == ServerList() && over.keptAside != nil, "maxLine is kept aside")
    }
}
