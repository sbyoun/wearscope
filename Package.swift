// swift-tools-version: 5.9
// WearScope — observability SDK for smart-glasses (DAT) apps.
// Core has zero dependencies. WearScopeDAT is the one-line auto-instrumentation
// adapter for the Meta Wearables Device Access Toolkit (iOS only).
import PackageDescription

let package = Package(
  name: "WearScope",
  platforms: [.iOS(.v17), .macOS(.v14)],
  products: [
    .library(name: "WearScope", targets: ["WearScope"]),
    .library(name: "WearScopeDAT", targets: ["WearScopeDAT"]),
  ],
  dependencies: [
    .package(url: "https://github.com/facebook/meta-wearables-dat-ios", exact: "0.8.0")
  ],
  targets: [
    .target(name: "WearScope"),
    .target(
      name: "WearScopeDAT",
      dependencies: [
        "WearScope",
        .product(name: "MWDATCore", package: "meta-wearables-dat-ios",
                 condition: .when(platforms: [.iOS])),
        .product(name: "MWDATCamera", package: "meta-wearables-dat-ios",
                 condition: .when(platforms: [.iOS])),
      ]),
  ]
)
