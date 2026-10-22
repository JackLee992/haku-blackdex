// Copyright 2026 Haku contributors. SPDX-License-Identifier: Apache-2.0
#pragma once
#include "SafeDex.h"
#include <limits>

namespace haku {
// AOSP android-13.0.0_r1 DexFile prefix: vptr, begin_, size_. This is a
// candidate contract, not a portable ART ABI. Caller MUST restrict API/ABI and
// compare a retained, owned calibration DEX byte-for-byte before target use.
inline bool ReadArtDexObject(uintptr_t object, const ReadMemory& reader,
                             Snapshot* out, std::string* error) {
    if (!object || object > std::numeric_limits<uintptr_t>::max() - 3 * sizeof(uintptr_t)) {
        if (error) *error = "DexFile object address overflow";
        return false;
    }
    uintptr_t fields[2];
    if (!reader(object + sizeof(uintptr_t), fields, sizeof(fields))) {
        if (error) *error = "DexFile fields unreadable";
        return false;
    }
    return ReadDex(fields[0], fields[1], reader, out, error);
}
}
