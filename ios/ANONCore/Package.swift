// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "ANONCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "ANONCore", targets: ["ANONCore"])],
    targets: [
        .target(name: "ANONCore"),
        .testTarget(name: "ANONCoreTests", dependencies: ["ANONCore"])
    ]
)
