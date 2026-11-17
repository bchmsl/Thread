import Foundation

/// Something scheduled that can be called off.
public protocol ScheduledWork: AnyObject {
    func cancel()
}

/// Runs work on the main queue after a delay. Tests swap in a fake that fires by hand.
public protocol MainScheduler: AnyObject {
    @discardableResult
    func schedule(after delay: TimeInterval, _ work: @escaping @MainActor () -> Void) -> ScheduledWork
}

public final class DispatchMainScheduler: MainScheduler {
    final class Item: ScheduledWork {
        let item: DispatchWorkItem
        init(_ item: DispatchWorkItem) { self.item = item }
        func cancel() { item.cancel() }
    }

    public init() {}

    @discardableResult
    public func schedule(after delay: TimeInterval, _ work: @escaping @MainActor () -> Void) -> ScheduledWork {
        let item = DispatchWorkItem { MainActor.assumeIsolated { work() } }
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
        return Item(item)
    }
}

/// Trailing debounce: the action runs once, `delay` after the last signal. Calendar changes (`EKEventStoreChanged`)
/// wait 30 s, so a burst of synced changes costs one pass (calendar-plan.md section 4, "Triggers").
@MainActor
public final class Debouncer {
    public nonisolated static let calendarChangeDelay: TimeInterval = 30

    let delay: TimeInterval
    let scheduler: MainScheduler
    let action: @MainActor () -> Void
    private var pending: ScheduledWork?

    public init(delay: TimeInterval = Debouncer.calendarChangeDelay, scheduler: MainScheduler, action: @escaping @MainActor () -> Void) {
        self.delay = delay
        self.scheduler = scheduler
        self.action = action
    }

    public var isPending: Bool { pending != nil }

    public func signal() {
        pending?.cancel()
        pending = scheduler.schedule(after: delay) { [weak self] in
            guard let self else { return }
            self.pending = nil
            self.action()
        }
    }

    public func cancel() {
        pending?.cancel()
        pending = nil
    }
}

/// 429, 5xx and other failed passes wait 1, 5, then 15 minutes (calendar-plan.md section 4, "Errors").
public struct RetryBackoff: Equatable, Sendable {
    public static let delays: [TimeInterval] = [60, 300, 900]

    public private(set) var failures = 0
    public private(set) var until: Date?

    public init() {}

    public mutating func fail(now: Date) -> Date {
        let delay = Self.delays[min(failures, Self.delays.count - 1)]
        failures += 1
        let next = now.addingTimeInterval(delay)
        until = next
        return next
    }

    public mutating func reset() {
        failures = 0
        until = nil
    }

    public func blocks(at now: Date) -> Bool {
        guard let until else { return false }
        return now < until
    }
}
