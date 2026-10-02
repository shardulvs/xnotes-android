// Copyright 2026 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "core/fxcodec/image_predictors.h"

#include <stdlib.h>

#include <algorithm>
#include <optional>
#include <utility>

#include "core/fxcrt/fx_2d_size.h"
#include "core/fxcrt/fx_ceil_div.h"
#include "core/fxcrt/fx_extension.h"
#include "core/fxcrt/notreached.h"
#include "core/fxcrt/numerics/safe_conversions.h"
#include "core/fxcrt/span_util.h"
#include "core/fxcrt/stl_util.h"
#include "core/fxcrt/zip.h"
#include "core/fxge/calculate_pitch.h"

namespace fxcodec {

namespace {

uint8_t PaethPredictor(uint8_t a, uint8_t b, uint8_t c) {
  int p = static_cast<int>(a) + b - c;
  int pa = abs(p - a);
  int pb = abs(p - b);
  int pc = abs(p - c);
  if (pa <= pb && pa <= pc) {
    return a;
  }
  return pb <= pc ? b : c;
}

std::optional<DataVector<uint8_t>> PngPredictor(
    int colors,
    int bits_per_component,
    int columns,
    pdfium::span<const uint8_t> src_span) {
  const uint32_t row_size =
      fxge::CalculatePitch8(bits_per_component, colors, columns).value_or(0);
  if (row_size == 0) {
    return std::nullopt;
  }

  const uint32_t src_row_size = row_size + 1;
  if (src_row_size == 0) {
    // Avoid divide by 0.
    return std::nullopt;
  }
  const size_t row_count = (src_span.size() + row_size) / src_row_size;
  if (row_count == 0) {
    return std::nullopt;
  }

  const uint32_t last_row_size = src_span.size() % src_row_size;
  size_t dest_size = Fx2DSizeOrDie(row_size, row_count);
  if (last_row_size) {
    dest_size -= src_row_size - last_row_size;
  }
  DataVector<uint8_t> dest_buf(dest_size);
  pdfium::span<const uint8_t> remaining_src_span = src_span;
  pdfium::span<uint8_t> remaining_dest_span = pdfium::span(dest_buf);
  pdfium::span<uint8_t> prev_dest_span;
  const uint32_t bytes_per_pixel =
      fxcrt::CeilDiv(colors * bits_per_component, 8);
  for (size_t row = 0; row < row_count; row++) {
    const size_t remaining_row_size =
        std::min<size_t>(row_size, remaining_src_span.size() - 1);
    PngPredictLine(remaining_dest_span, remaining_src_span, prev_dest_span,
                   remaining_row_size, bytes_per_pixel);
    remaining_src_span = remaining_src_span.subspan(remaining_row_size + 1);
    prev_dest_span = remaining_dest_span;
    remaining_dest_span = remaining_dest_span.subspan(remaining_row_size);
  }
  return dest_buf;
}

bool TiffPredictor(int colors,
                   int bits_per_component,
                   int columns,
                   pdfium::span<uint8_t> data_span) {
  const uint32_t row_size =
      fxge::CalculatePitch8(bits_per_component, colors, columns).value_or(0);
  if (row_size == 0) {
    return false;
  }

  while (!data_span.empty()) {
    auto row_span =
        data_span.first(std::min<size_t>(row_size, data_span.size()));
    TiffPredictLine(row_span, bits_per_component, colors, columns);
    data_span = data_span.subspan(row_span.size());
  }
  return true;
}

}  //  namespace

PredictorType GetPredictor(int predictor) {
  if (predictor >= 10) {
    return PredictorType::kPng;
  }
  if (predictor == 2) {
    return PredictorType::kTiff;
  }
  return PredictorType::kNone;
}

const DataAndBytesConsumed ApplyPredictor(DataVector<uint8_t> decoded_buf,
                                          int predictor,
                                          int colors,
                                          int bits_per_component,
                                          int columns,
                                          uint32_t bytes_consumed) {
  PredictorType predictor_type = GetPredictor(predictor);
  switch (predictor_type) {
    case PredictorType::kNone: {
      return {std::move(decoded_buf), bytes_consumed};
    }
    case PredictorType::kPng: {
      std::optional<DataVector<uint8_t>> result =
          PngPredictor(colors, bits_per_component, columns, decoded_buf);
      if (!result.has_value()) {
        return {std::move(decoded_buf), FX_INVALID_OFFSET};
      }
      return {std::move(result.value()), bytes_consumed};
    }
    case PredictorType::kTiff: {
      bool ret =
          TiffPredictor(colors, bits_per_component, columns, decoded_buf);
      return {std::move(decoded_buf), ret ? bytes_consumed : FX_INVALID_OFFSET};
    }
  }
  NOTREACHED();
}

// Fixed-size pixel views for the Sub filter's per-channel fast paths. The
// channel names are deliberately positional: PNG predictors operate on raw
// bytes and never care about color order.
struct Pixel3 {
  uint8_t c0;
  uint8_t c1;
  uint8_t c2;
};

struct Pixel4 {
  uint8_t c0;
  uint8_t c1;
  uint8_t c2;
  uint8_t c3;
};

void PngPredictLine(pdfium::span<uint8_t> dest_span,
                    pdfium::span<const uint8_t> src_span,
                    pdfium::span<const uint8_t> last_span,
                    size_t row_size,
                    uint32_t bytes_per_pixel) {
  const uint8_t tag = src_span.front();
  // `row_size` is a count, not an end index, so this is exactly `row_size`
  // bytes after the tag byte. PngPredictor() (below) pre-clamps the
  // `row_size` it passes for a stream's last, possibly truncated, row, and
  // this CHECKs rather than clamps, so `row_size` and `src_span.size()`
  // are equal on every path that reaches the code below. If this is ever
  // relaxed from a CHECK to a clamp, `row_size` stops being a valid loop
  // bound and every use below needs the post-clamp span size instead.
  src_span = src_span.subspan(1u, row_size);
  // Size the row views once up front, then hoist the two special cases out
  // of the loops: the first `bytes_per_pixel` bytes of a row, where the left
  // and upper-left neighbors are zero, and the first row of the image, where
  // up and upper-left are zero.
  dest_span = dest_span.first(row_size);
  if (!last_span.empty()) {
    last_span = last_span.first(row_size);
  }
  const size_t bpp = bytes_per_pixel;
  const size_t lead = std::min(bpp, row_size);
  switch (tag) {
    case 1: {
      // Sub: left neighbor only, so the first row needs no special casing.
      // The lead bytes have a zero left neighbor.
      fxcrt::Copy(src_span.first(lead), dest_span);
      // A Sub-filtered row's channels form independent additive chains; the
      // generic loop below re-reads dest_span[i - bpp], a value it stored `bpp`
      // iterations earlier, which serializes the loop on store-to-load
      // forwarding. For the common 3- and 4-byte pixel sizes, walking the
      // row as pixel structs and carrying the running channel values in a
      // local removes that dependency (and, via fxcrt::Zip(), all
      // per-element bounds checks).
      if (bpp == 3 && row_size >= 3) {
        auto src_px = fxcrt::reinterpret_span<const Pixel3>(src_span);
        auto dest_px = fxcrt::reinterpret_span<Pixel3>(dest_span);
        Pixel3 carry = dest_px.front();
        for (auto [s, d] :
             fxcrt::Zip(src_px.subspan(1u), dest_px.subspan(1u))) {
          carry.c0 += s.c0;
          carry.c1 += s.c1;
          carry.c2 += s.c2;
          d = carry;
        }
        for (size_t i = row_size - row_size % 3; i < row_size; ++i) {
          dest_span[i] = src_span[i] + dest_span[i - 3];
        }
        break;
      }
      if (bpp == 4 && row_size >= 4) {
        auto src_px = fxcrt::reinterpret_span<const Pixel4>(src_span);
        auto dest_px = fxcrt::reinterpret_span<Pixel4>(dest_span);
        Pixel4 carry = dest_px.front();
        for (auto [s, d] :
             fxcrt::Zip(src_px.subspan(1u), dest_px.subspan(1u))) {
          carry.c0 += s.c0;
          carry.c1 += s.c1;
          carry.c2 += s.c2;
          carry.c3 += s.c3;
          d = carry;
        }
        for (size_t i = row_size - row_size % 4; i < row_size; ++i) {
          dest_span[i] = src_span[i] + dest_span[i - 4];
        }
        break;
      }
      for (size_t i = lead; i < row_size; ++i) {
        dest_span[i] = src_span[i] + dest_span[i - bpp];
      }
      break;
    }
    case 2: {
      // Up: previous-row neighbor only, zero on the first row.
      if (last_span.empty()) {
        fxcrt::Copy(src_span, dest_span);
        break;
      }
      for (auto [s, u, d] : fxcrt::Zip(src_span, last_span, dest_span)) {
        d = s + u;
      }
      break;
    }
    case 3: {
      // Average: (left + up) / 2, with the zero cases hoisted.
      if (last_span.empty()) {
        fxcrt::Copy(src_span.first(lead), dest_span);
        for (size_t i = lead; i < row_size; ++i) {
          dest_span[i] = src_span[i] + dest_span[i - bpp] / 2;
        }
        break;
      }
      for (auto [s, u, d] :
           fxcrt::Zip(src_span.first(lead), last_span, dest_span)) {
        d = s + u / 2;
      }
      for (size_t i = lead; i < row_size; ++i) {
        dest_span[i] = src_span[i] + (last_span[i] + dest_span[i - bpp]) / 2;
      }
      break;
    }
    case 4: {
      // Paeth. With up == upper_left == 0 (first row) the predictor always
      // selects the left neighbor, so the first row reduces to Sub; with
      // left == upper_left == 0 (lead bytes) it always selects up.
      if (last_span.empty()) {
        fxcrt::Copy(src_span.first(lead), dest_span);
        for (size_t i = lead; i < row_size; ++i) {
          dest_span[i] = src_span[i] + dest_span[i - bpp];
        }
        break;
      }
      for (auto [s, u, d] :
           fxcrt::Zip(src_span.first(lead), last_span, dest_span)) {
        d = s + u;
      }
      for (size_t i = lead; i < row_size; ++i) {
        dest_span[i] =
            src_span[i] + PaethPredictor(dest_span[i - bpp], last_span[i],
                                         last_span[i - bpp]);
      }
      break;
    }
    default: {
      fxcrt::Copy(src_span, dest_span);
      break;
    }
  }
}

void TiffPredictLine(pdfium::span<uint8_t> dest_span,
                     int bits_per_component,
                     int colors,
                     int columns) {
  if (bits_per_component == 1) {
    int row_bits = std::min(bits_per_component * colors * columns,
                            pdfium::checked_cast<int>(dest_span.size() * 8));
    int index_pre = 0;
    int col_pre = 0;
    for (int i = 1; i < row_bits; i++) {
      int col = i % 8;
      int index = i / 8;
      if (((dest_span[index] >> (7 - col)) & 1) ^
          ((dest_span[index_pre] >> (7 - col_pre)) & 1)) {
        dest_span[index] |= 1 << (7 - col);
      } else {
        dest_span[index] &= ~(1 << (7 - col));
      }
      index_pre = index;
      col_pre = col;
    }
    return;
  }
  int bytes_per_pixel = bits_per_component * colors / 8;
  if (bits_per_component == 16) {
    for (size_t i = bytes_per_pixel; i + 1 < dest_span.size(); i += 2) {
      uint16_t pixel = (dest_span[i - bytes_per_pixel] << 8) |
                       dest_span[i - bytes_per_pixel + 1];
      pixel += (dest_span[i] << 8) | dest_span[i + 1];
      dest_span[i] = pixel >> 8;
      dest_span[i + 1] = (uint8_t)pixel;
    }
  } else {
    for (size_t i = bytes_per_pixel; i < dest_span.size(); i++) {
      dest_span[i] += dest_span[i - bytes_per_pixel];
    }
  }
}

}  // namespace fxcodec
