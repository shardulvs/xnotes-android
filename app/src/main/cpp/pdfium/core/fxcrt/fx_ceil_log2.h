// Copyright 2026 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CORE_FXCRT_CEIL_LOG2_H_
#define CORE_FXCRT_CEIL_LOG2_H_

#include <stdint.h>

namespace fxcrt {

constexpr uint8_t CeilLog2(uint32_t value) {
  uint8_t temp = 0;
  while (static_cast<uint32_t>(1u << temp) < value) {
    ++temp;
  }
  return temp;
}

}  // namespace fxcrt

#endif  // CORE_FXCRT_CEIL_LOG2_H_
