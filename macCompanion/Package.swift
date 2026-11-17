// swift-tools-version: 6.0
// Thread Calendar: the menu bar app that uploads one Apple Calendar's meetings to the owner's Firestore
// (adr/ADR-020-calendar-meetings-sync.md). Build, sign and install it with scripts/build-app.sh; never build in iCloud.
// Swift 5 language mode, as in the spike it was ported from (calendar-plan.md section 9).
import PackageDescription

let package = Package(
    name: "ThreadCalendar",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "ThreadCalendar", targets: ["ThreadCalendar"]),
    ],
    targets: [
        .target(
            name: "ThreadCalendarKit",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .executableTarget(
            name: "ThreadCalendar",
            dependencies: ["ThreadCalendarKit"],
            swiftSettings: [.swiftLanguageMode(.v5)],
            linkerSettings: [
                .linkedFramework("EventKit"),
                .linkedFramework("ServiceManagement"),
            ]
        ),
        .testTarget(
            name: "ThreadCalendarKitTests",
            dependencies: ["ThreadCalendarKit"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
