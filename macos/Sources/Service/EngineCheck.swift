import Foundation
import Shared

/// `--engine-check`: the shared engine end to end without root — the helper (unsandboxed, this user), the probes,
/// Repo and the statistics in a scratch folder. Prints what the engine reports and exits 0 when the local server
/// answered and the unreachable one didn't.
enum EngineCheck {
    static func run(core: String, dir: String, limited: String? = nil) -> Never {
        HelperProcess.devCore = core
        LimitedProcess.devBinary = limited
        let assets = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../android/src/main/assets").standardized.path
        let tunnel = TunnelController()
        EngineBridge.setUp(tunnel: tunnel, dataDir: dir, listsDir: dir + "/lists", assets: assets)
        // A local SOCKS server going out directly, run by the helper itself: a "server" that really answers.
        // A kernel-picked free port — a pid-derived one could collide with a second check run at once. The gap
        // between our close and the helper's bind is a dev tool's residual race, not worth a lock.
        let port = freePort() ?? 19_000 + Int(getpid() % 1000)
        let config = """
            {"log":{"loglevel":"warning"},"inbounds":[{"listen":"127.0.0.1","port":\(port),"protocol":"socks","settings":{"udp":false}}],\
            "outbounds":[{"protocol":"freedom","tag":"direct"}]}
            """
        let started = EngineBridge.helperRequest(#"{"id":999001,"op":"proxy-start","config":\#(quoted(config))}"#)
        print("local server: \(started)")
        let list = ServerList(groups: [StoredGroup(id: StoredGroup.defaultId, name: nil)], servers: [
            StoredServer(id: "local", name: "local", link: "socks://127.0.0.1:\(port)#local", proto: "SOCKS", groupId: StoredGroup.defaultId),
            StoredServer(id: "nowhere", name: "nowhere", link: "trojan://examplePass@192.0.2.1:443?security=tls&sni=example.com#nowhere",
                         proto: "Trojan", groupId: StoredGroup.defaultId),
        ])
        print("save: \(EngineBridge.save(list) ?? "ok")")
        EngineBridge.pingAll()
        var last = ""
        for _ in 0..<40 {
            Thread.sleep(forTimeInterval: 0.5)
            last = EngineBridge.status()
            if let s = try? JSONDecoder().decode(EngineStatus.self, from: Data(last.utf8)), s.scanTotal == 0,
               s.pings["local"] != nil, s.pings["nowhere"] != nil {
                print(last)
                let ok = s.pings["local"]?.ms != nil && s.pings["nowhere"]?.ms == nil
                print(ok ? "ENGINE CHECK OK" : "ENGINE CHECK FAILED")
                EngineBridge.stopHelper()
                exit(ok ? 0 : 1)
            }
        }
        print(last)
        print("ENGINE CHECK FAILED: no result in 20 s")
        EngineBridge.stopHelper()
        exit(1)
    }

    /// A loopback port that was free a moment ago: bind to port 0, read back what the kernel chose.
    private static func freePort() -> Int? {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { return nil }
        defer { close(fd) }
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        let bound = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0 else { return nil }
        var name = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        let got = withUnsafeMutablePointer(to: &name) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getsockname(fd, $0, &len)
            }
        }
        guard got == 0 else { return nil }
        return Int(UInt16(bigEndian: name.sin_port))
    }

    private static func quoted(_ s: String) -> String {
        let data = try! JSONSerialization.data(withJSONObject: [s])
        return String(decoding: data, as: UTF8.self).dropFirst().dropLast().description
    }
}
