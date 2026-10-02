// Copyright 2026 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CORE_FXCRT_TO_UNDERLYING_H_
#define CORE_FXCRT_TO_UNDERLYING_H_

#include <type_traits>

namespace fxcrt {
// Implementation of C++23's std::to_underlying.
// Reference: https://en.cppreference.com/w/cpp/utility/to_underlying
// TODO(crbug.com/486228086): Remove this once PDFium migration to C++ 23 is
// complete.
template <typename EnumT>
  requires(std::is_enum_v<EnumT>)
constexpr std::underlying_type_t<EnumT> to_underlying(EnumT e) noexcept {
  return static_cast<std::underlying_type_t<EnumT>>(e);
}
}  // namespace fxcrt

#endif  // CORE_FXCRT_TO_UNDERLYING_H_
