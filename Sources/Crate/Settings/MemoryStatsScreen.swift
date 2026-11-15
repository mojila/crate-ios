// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import SwiftUI
import UIKit

/// Live RAM figures: the device's total, used and available, and what iOS lets
/// crate hold besides. Reachable from Settings > Memory.
struct MemoryStatsScreen: View {
    @State private var device = DeviceMemory()
    @State private var report = MemoryReport()

    var body: some View {
        Form {
            deviceSection
            crateSection
        }
        .navigationTitle("Memory Stats")
        .navigationBarTitleDisplayMode(.inline)
        .task { await refresh() }
    }

    // MARK: - Device

    private var deviceSection: some View {
        Section {
            LabeledContent("Total") {
                Text(Format.memory(device.total))
                    .monospacedDigit()
            }
            LabeledContent("Used") {
                Text(device.isReported ? Format.memory(device.used) : "Not reported")
                    .monospacedDigit()
                    .contentTransition(.numericText())
            }
            LabeledContent("Available") {
                Text(device.isReported ? Format.memory(device.available) : "Not reported")
                    .monospacedDigit()
                    .contentTransition(.numericText())
            }
            if device.isReported {
                ProgressView(value: device.usedFraction) {
                    Text(device.usedFraction.formatted(.percent.precision(.fractionLength(0))) + " used")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .tint(usedColor)
            }
        } header: {
            Text("\(UIDevice.current.localizedModel) Memory")
        } footer: {
            Text(deviceFooter)
        }
    }

    private var usedColor: Color {
        switch device.usedFraction {
        case ..<0.75: .green
        case ..<0.9: .orange
        default: .red
        }
    }

    private var deviceFooter: String {
        guard device.isReported else {
            return "iOS didn't report the device's memory figures here; on an iPhone or iPad it does. Total is still the RAM the device has."
        }
        return "Used is what the whole system holds: crate, the rest of iOS and, while it runs, the VM. Available is free memory plus what iOS can reclaim, so Used and Available add up to Total."
    }

    // MARK: - crate

    private var crateSection: some View {
        Section {
            LabeledContent("crate's memory") {
                Text(Format.memory(report.footprint))
                    .monospacedDigit()
                    .contentTransition(.numericText())
            }
            LabeledContent("Limit") {
                Text(report.limit.map { Format.memory($0) } ?? "Not reported")
                    .monospacedDigit()
            }
            LabeledContent("Available to crate") {
                Text(report.available.map { Format.memory($0) } ?? "Not reported")
                    .monospacedDigit()
                    .contentTransition(.numericText())
            }
            LabeledContent("Increased limit", value: report.hasIncreasedLimit ? "Yes" : "No")
        } header: {
            Text("crate")
        } footer: {
            Text("A VM's memory comes out of what iOS lets crate hold, up to the limit. The increased-memory-limit entitlement raises that limit.")
        }
    }

    // MARK: - Refresh

    /// Re-reads both reports while the screen is up.
    private func refresh() async {
        while !Task.isCancelled {
            withAnimation {
                device = DeviceMemory()
                report = MemoryReport()
            }
            try? await Task.sleep(for: .seconds(2))
        }
    }
}
