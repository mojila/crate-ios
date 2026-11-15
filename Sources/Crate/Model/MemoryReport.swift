// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import Foundation
import os

/// What iOS lets Crate have, read fresh each time one is made.
struct MemoryReport {
    /// STUB: whether the build carries the increased-memory-limit entitlement.
    /// iOS has no public call that reads a process's own entitlements, and this
    /// project always asks for it (project.yml), so the answer is fixed for now.
    let hasIncreasedLimit = true

    /// RAM in the device.
    let physical: UInt64

    /// What Crate could allocate right now before jetsam kills it. Nil in the
    /// Simulator, which has no jetsam limit and reports 0.
    let available: UInt64?

    /// The most Crate can ever hold: what it holds now plus what is left.
    var limit: UInt64? {
        available.map { $0 + footprint }
    }

    /// Memory charged to Crate right now.
    let footprint: UInt64

    init() {
        physical = ProcessInfo.processInfo.physicalMemory
        let free = UInt64(os_proc_available_memory())
        available = free == 0 ? nil : free
        footprint = Self.currentFootprint()
    }

    /// The figure jetsam compares against the limit (phys_footprint).
    private static func currentFootprint() -> UInt64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(
            MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return result == KERN_SUCCESS ? info.phys_footprint : 0
    }
}

/// The whole device's RAM, not just crate's share: total, used and available,
/// read from the kernel. Used is everything the system can't hand out right
/// away (active, wired and compressed); available is free pages plus inactive
/// pages the system can reclaim, so the two add up to the total.
struct DeviceMemory {
    /// Whether the kernel gave figures at all. The Simulator sometimes says no.
    let isReported: Bool
    let total: UInt64
    let used: UInt64
    let available: UInt64

    /// How much of the device's RAM is in use, 0...1, for a progress bar.
    var usedFraction: Double {
        guard total > 0 else { return 0 }
        return min(1, Double(used) / Double(total))
    }

    init() {
        total = ProcessInfo.processInfo.physicalMemory

        var stats = vm_statistics64_data_t()
        var count = mach_msg_type_number_t(
            MemoryLayout<vm_statistics64_data_t>.size / MemoryLayout<integer_t>.size)
        let host = mach_host_self()
        let result = withUnsafeMutablePointer(to: &stats) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                host_statistics64(host, HOST_VM_INFO64, $0, &count)
            }
        }
        mach_port_deallocate(mach_task_self_, host)
        guard result == KERN_SUCCESS else {
            // No kernel figures (the Simulator can be like this): report the
            // total and leave the rest unknown rather than inventing numbers.
            isReported = false
            used = 0
            available = 0
            return
        }

        isReported = true
        let page = UInt64(vm_kernel_page_size)
        // Speculative pages are already counted in free_count.
        let free = UInt64(stats.free_count) * page
        let reclaimable = UInt64(stats.inactive_count) * page
        available = min(total, free + reclaimable)
        used = total - available
    }
}
