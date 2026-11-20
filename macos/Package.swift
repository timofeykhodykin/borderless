// swift-tools-version:6.0
// Border(less) for macOS. Builds with the Command Line Tools alone (`swift build`); `build.sh` turns the products into
// `out/Border(less).app` with the service inside it. See local/macos/PLAN.md §1.0 for the design.
import PackageDescription

/// The Kotlin code shared with Android (`shared/`, Kotlin Multiplatform): a static framework built by Gradle
/// (`./gradlew :shared:linkReleaseFrameworkMacos<Arch>`, which macos/build.sh runs first), imported as a module.
/// The manifest compiles for the host arch — the framework dir follows it (macosArm64 / macosX64).
#if arch(arm64)
let kotlinArch = "macosArm64"
#else
let kotlinArch = "macosX64"
#endif
let kotlinFrameworks = "../shared/build/bin/\(kotlinArch)/releaseFramework"
let kotlinShared = (
    swift: [SwiftSetting.unsafeFlags(["-F", kotlinFrameworks])],
    linker: [LinkerSetting.unsafeFlags(["-F", kotlinFrameworks, "-framework", "BorderlessShared"]),
             .linkedLibrary("c++"), .linkedFramework("Foundation")]
)

let package = Package(
    name: "BorderlessMac",
    platforms: [.macOS(.v14)],
    targets: [
        // What the app and the service agree on: paths, the launchd label, the messages on the socket.
        .target(name: "Shared"),
        // The window and the menu bar item, running as the user.
        .executableTarget(name: "App", dependencies: ["Shared"],
                          swiftSettings: kotlinShared.swift, linkerSettings: kotlinShared.linker),
        // The system service (root): owns the tunnel and the engine, talks to the app over a local socket.
        .executableTarget(name: "Service", dependencies: ["Shared"],
                          swiftSettings: kotlinShared.swift, linkerSettings: kotlinShared.linker),
        // Unit checks: `swift run Checks`. The Command Line Tools ship neither XCTest nor Swift Testing, so this is a
        // plain program that exits non-zero on a failure.
        .executableTarget(name: "Checks", dependencies: ["Shared"], path: "Tests/Checks",
                          swiftSettings: kotlinShared.swift, linkerSettings: kotlinShared.linker),
    ]
)
