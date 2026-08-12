// swift-tools-version:5.7

import PackageDescription

let package = Package(
    name: "OneSignalKMP",
    platforms: [
        .iOS(.v11),
        .macCatalyst(.v14),
    ],
    products: [
        .library(
            name: "OneSignalKMP",
            targets: ["OneSignalKMP"]
        ),
    ],
    targets: [
        .binaryTarget(
            name: "OneSignalKMP",
            path: "kmp/build/XCFrameworks/release/OneSignalKMP.xcframework"
        ),
    ]
)
