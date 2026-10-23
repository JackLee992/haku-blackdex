// Copyright 2026 Haku contributors. SPDX-License-Identifier: Apache-2.0
#pragma once
#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace haku {
constexpr size_t kMaxDexBytes = 64u * 1024u * 1024u;
using ReadMemory = std::function<bool(uintptr_t, void*, size_t)>;
struct Snapshot {
    std::vector<uint8_t> bytes;
    std::string sha256;
};
uint32_t ReadU32(const uint8_t* p);
std::string Sha256(const std::vector<uint8_t>& bytes);
bool ValidateDex(const std::vector<uint8_t>& bytes, std::string* error);
bool ReadDex(uintptr_t begin, size_t allocation_size, const ReadMemory& reader,
             Snapshot* result, std::string* error);
// Writes immutable original bytes to <sha256>.dex. Existing entries are verified.
bool WriteArtifact(const std::string& directory, const Snapshot& snapshot, std::string* error);
}
