// swift-tools-version:5.9
//
// SwiftPM manifest for the standalone watchOS companion agent.
//
// Its purpose is to give CI a real toolchain target for the watchOS SDK so
// WatchBridgeAgent.swift is genuinely type-checked (not just syntax-scanned).
// The file keeps its original path and is referenced explicitly, so the
// specification's file tree is unchanged.
//
// Build / type-check on macOS:
//   swift build --triple arm64-apple-watchos10.0
//   swiftc -typecheck -sdk "$(xcrun --sdk watchos --show-sdk-path)" \
//          -target arm64-apple-watchos10.0 watchos-companion/WatchBridgeAgent.swift

import PackageDescription

let package = Package(
    name: "WatchBridgeAgent",
    platforms: [
        .watchOS(.v10)
    ],
    targets: [
        .executableTarget(
            name: "WatchBridgeAgent",
            path: "watchos-companion",
            sources: ["WatchBridgeAgent.swift"]
        )
    ]
)
