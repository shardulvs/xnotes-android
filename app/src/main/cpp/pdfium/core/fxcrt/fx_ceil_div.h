// Copyright 2026 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CORE_FXCRT_FX_CEIL_DIV_H_
#define CORE_FXCRT_FX_CEIL_DIV_H_

#include <type_traits>

#include "core/fxcrt/check_op.h"
#include "core/fxcrt/fx_safe_types.h"

namespace fxcrt {

// Returns `dividend / divisor` rounded towards positive infinity.
// `divisor` must be positive.
// Never overflows, unlike `(dividend + divisor - 1) / divisor`.
template <typename T>
  requires(std::is_integral_v<T> && !std::is_same_v<T, bool>)
constexpr T CeilDiv(T dividend, std::type_identity_t<T> divisor) {
  CHECK_GT(divisor, T{0});
  // Integer division truncates towards zero, so it only rounds away from
  // positive infinity when there is a positive remainder.
  const T quotient = dividend / divisor;
  return dividend % divisor > T{0} ? static_cast<T>(quotient + 1) : quotient;
}

// Same, but for checked types.
template <typename T>
  requires(std::is_integral_v<T> && !std::is_same_v<T, bool>)
constexpr pdfium::CheckedNumeric<T> CeilDiv(pdfium::CheckedNumeric<T> dividend,
                                            std::type_identity_t<T> divisor) {
  if (!dividend.IsValid()) {
    return dividend;
  }
  // ValueOrDie() returns a StrictNumeric<T>, which converts to both `T` and
  // CheckedNumeric<T>. Without the cast, this would self-recurse.
  return CeilDiv(static_cast<T>(dividend.ValueOrDie()), divisor);
}

}  // namespace fxcrt

#endif  // CORE_FXCRT_FX_CEIL_DIV_H_
