import Foundation
import Shared

/// The user's servers as the service kept them before Repo (`servers.json`, one file, root only, 0600). Read once
/// by [EngineBridge.migrate], which moves the list into the shared store and deletes the file; new writes go
/// through the engine, so nothing here writes.
enum ServerStore {
    static let path = ServicePaths.dataDir + "/servers.json"

    /// The list, or a fresh empty one; a file that won't decode is first copied aside ([ServerList.load]),
    /// because the migration's save of the empty list would otherwise destroy the user's servers for good.
    static func load() -> ServerList {
        let (list, keptAside) = ServerList.load(at: path)
        if let keptAside { log("servers.json would not decode; a copy was kept as \(keptAside)") }
        return list
    }
}
