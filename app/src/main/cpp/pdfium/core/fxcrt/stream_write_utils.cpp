// Copyright 2019 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "core/fxcrt/stream_write_utils.h"

#include <array>
#include <cmath>
#include <limits>
#include <ostream>

#include "core/fxcrt/span.h"
#include "third_party/dragonbox/src/include/dragonbox/dragonbox.h"

namespace {

constexpr unsigned kMaximumFloatToDecimalLength = 49;

// Convert a float into a decimal string.
//
// The resulting string will be in the form `[-]?([0-9]*\.)?[0-9]+` (it does
// not use scientific notation).
//
// INFINITY and -INFINITY are rounded to FLT_MAX and -FLT_MAX.
//
// NAN values are converted to 0.
//
// This function will always add a terminating '\0' to the output.
unsigned FloatToDecimal(
    float value,
    pdfium::span<char, kMaximumFloatToDecimalLength> output) {
  // The longest result is -FLT_MIN.
  // Serialize it as "-.0000000000000000000000000000000000000117549435"
  // which has 48 characters plus a terminating '\0'.
  static_assert(kMaximumFloatToDecimalLength == 49, "");
  static_assert(kMaximumFloatToDecimalLength ==
                    3 + 9 - std::numeric_limits<float>::min_exponent10,
                "");

  size_t out_idx = 0;

  if (std::isnan(value) || value == 0.0f) {
    // NAN is unsupported in PDF. Always output a valid number.
    // Also catch zero here, as a special case.
    output[out_idx++] = '0';
    output[out_idx] = '\0';
    return static_cast<unsigned>(out_idx);
  }

  if (value == INFINITY) {
    value = std::numeric_limits<float>::max();
  } else if (value == -INFINITY) {
    value = std::numeric_limits<float>::lowest();
  }

  if (value < 0.0f) {
    output[out_idx++] = '-';
    value = -value;
  }

  const auto decimal =
      jkj::dragonbox::to_decimal(value, jkj::dragonbox::policy::sign::ignore,
                                 jkj::dragonbox::policy::trailing_zero::remove);

  std::array<char, 10> digits;
  size_t num_digits = 0;
  uint32_t significand = decimal.significand;
  do {
    digits[num_digits++] = static_cast<char>('0' + (significand % 10));
    significand /= 10;
  } while (significand > 0);

  const int exponent = decimal.exponent;
  if (exponent >= 0) {
    while (num_digits > 0) {
      output[out_idx++] = digits[--num_digits];
    }
    for (int i = 0; i < exponent; ++i) {
      output[out_idx++] = '0';
    }
  } else {
    const int places_before_decimal = static_cast<int>(num_digits) + exponent;
    if (places_before_decimal > 0) {
      for (int i = 0; i < places_before_decimal; ++i) {
        output[out_idx++] = digits[--num_digits];
      }
      output[out_idx++] = '.';
      while (num_digits > 0) {
        output[out_idx++] = digits[--num_digits];
      }
    } else {
      output[out_idx++] = '.';
      for (int i = 0; i < -places_before_decimal; ++i) {
        output[out_idx++] = '0';
      }
      while (num_digits > 0) {
        output[out_idx++] = digits[--num_digits];
        if (out_idx + 1 >= output.size()) {
          break;
        }
      }
    }
  }

  output[out_idx] = '\0';
  return static_cast<unsigned>(out_idx);
}

}  // namespace

namespace fxcrt {

std::ostream& WriteFloat(std::ostream& stream, float value) {
  char buffer[kMaximumFloatToDecimalLength];
  unsigned size = FloatToDecimal(value, buffer);
  stream.write(buffer, size);
  return stream;
}

std::ostream& WriteMatrix(std::ostream& stream, const CFX_Matrix& matrix) {
  WriteFloat(stream, matrix.a) << " ";
  WriteFloat(stream, matrix.b) << " ";
  WriteFloat(stream, matrix.c) << " ";
  WriteFloat(stream, matrix.d) << " ";
  WriteFloat(stream, matrix.e) << " ";
  WriteFloat(stream, matrix.f);
  return stream;
}

std::ostream& WritePoint(std::ostream& stream, const CFX_PointF& point) {
  WriteFloat(stream, point.x) << " ";
  WriteFloat(stream, point.y);
  return stream;
}

std::ostream& WriteRect(std::ostream& stream, const CFX_FloatRect& rect) {
  WriteFloat(stream, rect.left) << " ";
  WriteFloat(stream, rect.bottom) << " ";
  WriteFloat(stream, rect.Width()) << " ";
  WriteFloat(stream, rect.Height());
  return stream;
}

}  // namespace fxcrt
