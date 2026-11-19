// swift-tools-version:5.8

import PackageDescription

// The video player for iOS 12 (iPad mini 2 and other A7 devices): libmpv with OpenGL ES, built from
// source for iOS 12 by ios12/build-mpv-ios12.sh. Libmpv.xcframework is produced by the CI build and is
// not committed.
let package = Package(
    name: "MPVKit",
    platforms: [.iOS(.v12)],
    products: [
        .library(
            name: "MPVKit",
            targets: ["_MPVKit"]
        ),
    ],
    targets: [
        .target(
            name: "_MPVKit",
            dependencies: ["Libmpv"],
            path: "Sources/_MPVKit",
            linkerSettings: [
                .linkedFramework("AVFoundation"),
                .linkedFramework("AudioToolbox"),
                .linkedFramework("CoreAudio"),
                .linkedFramework("CoreFoundation"),
                .linkedFramework("CoreGraphics"),
                .linkedFramework("CoreMedia"),
                .linkedFramework("CoreText"),
                .linkedFramework("CoreVideo"),
                .linkedFramework("OpenGLES"),
                .linkedFramework("QuartzCore"),
                .linkedFramework("VideoToolbox"),
                .linkedLibrary("iconv"),
                .linkedLibrary("xml2"),
                .linkedLibrary("z"),
                .linkedLibrary("resolv"),
                .linkedLibrary("c++"),
            ]
        ),
        .binaryTarget(
            name: "Libmpv",
            path: "Libmpv.xcframework"
        ),
    ]
)
