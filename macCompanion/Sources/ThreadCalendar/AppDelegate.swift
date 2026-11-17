import AppKit
import EventKit
import Network
import ServiceManagement
import ThreadCalendarKit

/// The menu bar app (calendar-plan.md section 4, "Menu"). Calendar data stays on this Mac except the meeting fields
/// the rules allow; the diagnostics hold counts, states and error codes only.
@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private let config = AppConfig.load()
    private let calendar = CalendarService()
    private let appVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
    private let buildStamp = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "dev"

    private var log: DiagnosticsLog!
    private var http: HTTPClient!
    private var account: AccountSession!
    private var engine: SyncEngine!
    private var debouncer: Debouncer!
    private let scheduler = DispatchMainScheduler()
    private var retry: ScheduledWork?

    private var statusItem: NSStatusItem!
    private var background: NSBackgroundActivityScheduler?
    private let pathMonitor = NWPathMonitor()
    private var online = true
    private var signingIn = false
    private var stoppingSync = false

    // MARK: Launch

    func applicationDidFinishLaunching(_ notification: Notification) {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        let menu = NSMenu()
        menu.delegate = self
        statusItem.menu = menu
        guard let config else {
            // build-app.sh wrote a Config.plist this app cannot read: say so and do nothing else.
            setIcon(problem: true)
            return
        }
        let mode = config.mode
        log = DiagnosticsLog(directory: DiagnosticsLog.folder(mode: mode))
        http = HTTPClient(networkGuard: config.networkGuard)
        let service = mode == .cloud ? "com.bachana.ThreadCalendar" : "com.bachana.ThreadCalendar.emulator"
        account = AccountSession(auth: FirebaseAuthREST(http: http, endpoints: config.authEndpoints), vault: KeychainAccountVault(service: service))
        do {
            engine = SyncEngine(
                account: account,
                firestore: FirestoreClient(endpoint: try config.firestoreEndpoint(), http: http),
                calendar: calendar,
                index: IndexStore(directory: log.directory),
                state: DefaultsEngineState(),
                log: log,
                appVersion: appVersion,
                isOnline: { [weak self] in self?.online ?? true }
            )
        } catch {
            setIcon(problem: true)
            return
        }
        engine.onChange = { [weak self] status in self?.statusChanged(status) }
        debouncer = Debouncer(scheduler: scheduler) { [weak self] in self?.trigger(.calendarChanged) }
        setIcon(problem: engine.status.isProblem)

        log.event("launch", [
            "mode": .code(mode.rawValue),
            "build": .code(buildStamp),
            "access": .code(calendar.access.rawValue),
            "calendarPicked": .bool(calendar.picked != nil),
            "signedIn": .bool(account.isSignedIn),
            "loginItem": .code(loginItemCode),
        ])
        log.set([
            "app.mode": .code(mode.rawValue),
            "app.build": .code(buildStamp),
            "app.access": .code(calendar.access.rawValue),
            "app.signedIn": .bool(account.isSignedIn),
            "app.loginItem": .code(loginItemCode),
        ])
        startTriggers()
        trigger(.launch)
    }

    private func trigger(_ trigger: PassTrigger) {
        guard let engine else { return }
        Task { await engine.run(trigger) }
    }

    private func statusChanged(_ status: SyncStatus) {
        setIcon(problem: status.isProblem)
        log.event("status", ["status": .code(status.code)])
        retry?.cancel()
        retry = nil
        if case .retrying(let at, _) = status {
            retry = scheduler.schedule(after: max(1, at.timeIntervalSinceNow + 1)) { [weak self] in self?.trigger(.retry) }
        }
    }

    private func setIcon(problem: Bool) {
        let name = problem ? "calendar.badge.exclamationmark" : "calendar"
        statusItem.button?.image = NSImage(systemSymbolName: name, accessibilityDescription: "Thread Calendar")
    }

    // MARK: Triggers

    private func startTriggers() {
        NotificationCenter.default.addObserver(self, selector: #selector(storeChanged), name: .EKEventStoreChanged, object: calendar.store)
        NSWorkspace.shared.notificationCenter.addObserver(self, selector: #selector(didWake), name: NSWorkspace.didWakeNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(dayChanged), name: .NSCalendarDayChanged, object: nil)

        let suffix = config?.mode == .cloud ? "" : ".emulator"
        let background = NSBackgroundActivityScheduler(identifier: "com.bachana.ThreadCalendar.pass" + suffix)
        background.repeats = true
        background.interval = 15 * 60
        background.schedule { [weak self] completion in
            Task { @MainActor in
                if let engine = self?.engine { await engine.run(.schedule) }
                completion(.finished)
            }
        }
        self.background = background

        pathMonitor.pathUpdateHandler = { [weak self] path in
            let isOnline = path.status == .satisfied
            Task { @MainActor in
                guard let self else { return }
                let wasOnline = self.online
                self.online = isOnline
                if isOnline != wasOnline { self.log.event("network", ["online": .bool(isOnline)]) }
                if isOnline && !wasOnline { self.trigger(.networkBack) }
            }
        }
        pathMonitor.start(queue: .global(qos: .utility))
    }

    /// EventKit may post this on any thread. Every calendar on the Mac posts it, so it waits 30 s.
    @objc nonisolated func storeChanged() {
        Task { @MainActor in self.debouncer?.signal() }
    }

    @objc nonisolated func didWake() {
        Task { @MainActor in self.trigger(.wake) }
    }

    @objc nonisolated func dayChanged() {
        Task { @MainActor in self.trigger(.dayChanged) }
    }

    // MARK: Menu

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        guard let config, let engine, let account else {
            menu.addItem(disabled("Thread Calendar cannot read its settings. Rebuild it with scripts/build-app.sh."))
            menu.addItem(.separator())
            menu.addItem(withTitle: "Quit Thread Calendar", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
            return
        }
        if config.mode == .emulator { menu.addItem(disabled("Local emulator mode")) }
        menu.addItem(disabled(StatusText.line(engine.status)))
        menu.addItem(.separator())

        switch calendar.access {
        case .notDetermined:
            menu.addItem(action("Allow calendar access…", #selector(requestAccess)))
        case .denied:
            menu.addItem(action("Calendar access is off. Open System Settings…", #selector(openPrivacySettings)))
        case .granted:
            menu.addItem(calendarMenu())
        }

        if account.isSignedIn {
            let signedIn = NSMenuItem(title: "Signed in as \(account.email ?? "your Google account")", action: nil, keyEquivalent: "")
            let details = NSMenu()
            // On this Mac only, to compare with Firebase console › Authentication › Users. Never logged or uploaded.
            details.addItem(disabled("User UID: \(account.uid ?? "unknown")"))
            signedIn.submenu = details
            menu.addItem(signedIn)
        } else if signingIn {
            menu.addItem(disabled("Signing in… finish in the browser"))
        } else {
            menu.addItem(action("Sign in to Thread…", #selector(signIn)))
        }
        if engine.status == .otherUploader {
            menu.addItem(action("Another Mac is uploading meetings. Take over?", #selector(takeOver)))
        }
        let syncNow = action("Sync now", #selector(syncNow))
        syncNow.isEnabled = account.isSignedIn && !stoppingSync
        menu.addItem(syncNow)
        menu.addItem(.separator())

        let login = action("Start at login", #selector(toggleLoginItem))
        login.state = SMAppService.mainApp.status == .enabled ? .on : .off
        menu.addItem(login)
        let stop = action(stoppingSync ? "Removing meetings…" : "Stop syncing and remove meetings…", #selector(stopSyncing))
        stop.isEnabled = account.isSignedIn && !stoppingSync
        menu.addItem(stop)
        if config.threadURL != nil { menu.addItem(action("Open Thread", #selector(openThread))) }
        menu.addItem(.separator())
        menu.addItem(withTitle: "Quit Thread Calendar", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
    }

    private func calendarMenu() -> NSMenuItem {
        let picked = calendar.picked
        let item = NSMenuItem(title: "Calendar: \(picked?.title ?? "none picked")", action: nil, keyEquivalent: "")
        let submenu = NSMenu()
        var lastSource: String?
        for entry in calendar.calendars() {
            if entry.source.title != lastSource {
                if !submenu.items.isEmpty { submenu.addItem(.separator()) }
                submenu.addItem(disabled(entry.source.title))
                lastSource = entry.source.title
            }
            let row = NSMenuItem(title: entry.title, action: #selector(pickCalendar(_:)), keyEquivalent: "")
            row.target = self
            row.indentationLevel = 1
            row.representedObject = entry.calendarIdentifier
            row.state = entry.calendarIdentifier == picked?.calendarIdentifier ? .on : .off
            submenu.addItem(row)
        }
        if submenu.items.isEmpty { submenu.addItem(disabled("No calendars on this Mac")) }
        item.submenu = submenu
        return item
    }

    private func disabled(_ title: String) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.isEnabled = false
        return item
    }

    private func action(_ title: String, _ selector: Selector) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: selector, keyEquivalent: "")
        item.target = self
        return item
    }

    // MARK: Calendar

    @objc func requestAccess() {
        Task { @MainActor in
            let granted = await calendar.requestAccess()
            log.event("accessRequested", ["granted": .bool(granted), "access": .code(calendar.access.rawValue)])
            log.set(["app.access": .code(calendar.access.rawValue)])
            if granted { trigger(.syncNow) }
        }
    }

    @objc func openPrivacySettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Calendars") {
            NSWorkspace.shared.open(url)
        }
    }

    @objc func pickCalendar(_ sender: NSMenuItem) {
        guard
            let id = sender.representedObject as? String,
            let picked = calendar.calendars().first(where: { $0.calendarIdentifier == id })
        else { return }
        calendar.pick(picked)
        log.event("calendarPicked")
        trigger(.calendarPicked)
    }

    // MARK: Account

    @objc func signIn() {
        guard let config, !signingIn else { return }
        signingIn = true
        Task { @MainActor in
            defer { signingIn = false }
            do {
                let googleIdToken = try await config.googleIdToken(http: http) { NSWorkspace.shared.open($0) }
                try await account.signIn(googleIdToken: googleIdToken)
                engine.signedIn()
                log.event("signIn", ["result": .code("ok")])
                log.set(["app.signedIn": .bool(true), "app.signInResult": .code("ok")])
                await engine.run(.signIn)
            } catch {
                let code = (error as? CalendarSyncError)?.code ?? "unexpected"
                log.event("signIn", ["result": .code(code)])
                log.set(["app.signInResult": .code(code)])
                alert("Sign-in failed (\(code))", SignInHint.text(for: code))
            }
        }
    }

    @objc func takeOver() {
        let answer = ask(
            "Another Mac is uploading meetings. Take over?",
            "Only one Mac should upload, because the same meeting can get different ids on two Macs. "
                + "Take over only if the other Mac has stopped syncing.",
            confirm: "Take over"
        )
        guard answer else { return }
        engine.approveTakeover()
        log.event("takeover")
        trigger(.takeover)
    }

    @objc func syncNow() {
        trigger(.syncNow)
    }

    @objc func stopSyncing() {
        let answer = ask(
            "Stop syncing and remove meetings?",
            "Thread Calendar deletes the meetings it uploaded to Thread, then signs out on this Mac. "
                + "Your calendar does not change, and Topic links stay.",
            confirm: "Remove meetings"
        )
        guard answer else { return }
        stoppingSync = true
        Task { @MainActor in
            defer { stoppingSync = false }
            do {
                try await engine.stopSyncing()
                log.set(["app.signedIn": .bool(false)])
            } catch {
                let code = (error as? CalendarSyncError)?.code ?? "unexpected"
                log.event("stopSyncingFailed", ["code": .code(code)])
                alert("Meetings were not removed (\(code))", "You are still signed in. Try again when online.")
            }
        }
    }

    @objc func openThread() {
        if let url = config?.threadURL { NSWorkspace.shared.open(url) }
    }

    // MARK: Login item

    var loginItemCode: String {
        switch SMAppService.mainApp.status {
        case .enabled: return "enabled"
        case .notRegistered: return "notRegistered"
        case .requiresApproval: return "requiresApproval"
        case .notFound: return "notFound"
        @unknown default: return "unknown"
        }
    }

    @objc func toggleLoginItem() {
        var failed = false
        do {
            if SMAppService.mainApp.status == .enabled {
                try SMAppService.mainApp.unregister()
            } else {
                try SMAppService.mainApp.register()
            }
        } catch {
            failed = true
        }
        log.event("loginItem", ["status": .code(loginItemCode), "failed": .bool(failed)])
        log.set(["app.loginItem": .code(loginItemCode)])
        if SMAppService.mainApp.status == .requiresApproval {
            alert("Allow Thread Calendar to start at login", "Turn it on in System Settings › General › Login Items.")
            SMAppService.openSystemSettingsLoginItems()
        }
    }

    // MARK: Alerts

    private func alert(_ title: String, _ text: String) {
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = text
        NSApp.activate(ignoringOtherApps: true)
        alert.runModal()
    }

    private func ask(_ title: String, _ text: String, confirm: String) -> Bool {
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = text
        alert.addButton(withTitle: confirm)
        alert.addButton(withTitle: "Cancel")
        NSApp.activate(ignoringOtherApps: true)
        return alert.runModal() == .alertFirstButtonReturn
    }
}
