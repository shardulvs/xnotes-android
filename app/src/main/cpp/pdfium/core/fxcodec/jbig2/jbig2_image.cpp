// Copyright 2014 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxcodec/jbig2/jbig2_image.h"

#include <limits.h>
#include <stddef.h>

#include <algorithm>
#include <memory>
#include <utility>

#include "core/fxcrt/byteorder.h"
#include "core/fxcrt/check.h"
#include "core/fxcrt/check_op.h"
#include "core/fxcrt/fx_2d_size.h"
#include "core/fxcrt/fx_ceil_div.h"
#include "core/fxcrt/fx_coordinates.h"
#include "core/fxcrt/fx_safe_types.h"
#include "core/fxcrt/notreached.h"
#include "core/fxcrt/span_util.h"
#include "core/fxcrt/zip.h"

using fxcrt::FromBE32;

namespace {

const int kMaxImagePixels = INT_MAX - 31;
const int kMaxImageBytes = kMaxImagePixels / 8;

uint32_t BitIndexToByte(uint32_t index) {
  return index / 8;
}

uint32_t BitIndexToAlignedUint32(uint32_t index) {
  return index / 32;
}

size_t GetMiddleElementCount(int xd0, int xd1) {
  return pdfium::checked_cast<size_t>((xd1 / 32) - fxcrt::CeilDiv(xd0, 32));
}

uint32_t DoCompose(JBig2ComposeOp op, uint32_t val1, uint32_t val2) {
  switch (op) {
    case JBIG2_COMPOSE_OR:
      return val1 | val2;
    case JBIG2_COMPOSE_AND:
      return val1 & val2;
    case JBIG2_COMPOSE_XOR:
      return val1 ^ val2;
    case JBIG2_COMPOSE_XNOR:
      return ~(val1 ^ val2);
    case JBIG2_COMPOSE_REPLACE:
      return val1;
  }
  NOTREACHED();
}

uint32_t DoComposeWithMask(JBig2ComposeOp op,
                           uint32_t val1,
                           uint32_t val2,
                           uint32_t mask) {
  return (val2 & ~mask) | (DoCompose(op, val1, val2) & mask);
}

}  // namespace

CJBig2_Image::CJBig2_Image(int32_t w, int32_t h) {
  if (w <= 0 || h <= 0 || w > kMaxImagePixels) {
    return;
  }

  int32_t stride_pixels = FxAlignToBoundary<32>(w);
  if (h > kMaxImagePixels / stride_pixels) {
    return;
  }

  width_ = w;
  height_ = h;
  stride_ = stride_pixels / 8;
  CHECK_GE(stride_, 0);
  data_ = MaybeOwnedDataVector<uint8_t>(
      DataVector<uint8_t>(Fx2DSizeOrDie(stride_, height_)));
}

CJBig2_Image::CJBig2_Image(int32_t w,
                           int32_t h,
                           int32_t stride,
                           pdfium::span<uint8_t> pBuf) {
  if (w < 0 || h < 0) {
    return;
  }

  // Stride must be word-aligned.
  if (stride < 0 || stride > kMaxImageBytes || stride % 4 != 0) {
    return;
  }

  int32_t stride_pixels = 8 * stride;
  if (stride_pixels < w || h > kMaxImagePixels / stride_pixels) {
    return;
  }

  if (stride > 0 && h > 0 && pBuf.empty()) {
    return;
  }

  width_ = w;
  height_ = h;
  stride_ = stride;
  CHECK_GE(stride_, 0);
  data_ = MaybeOwnedDataVector<uint8_t>(
      pBuf.first(Fx2DSizeOrDie(stride_, height_)));
}

CJBig2_Image::CJBig2_Image(const CJBig2_Image& other)
    : data_(other.data_),
      width_(other.width_),
      height_(other.height_),
      stride_(other.stride_) {}

CJBig2_Image::~CJBig2_Image() = default;

// static
bool CJBig2_Image::IsValidImageSize(int32_t w, int32_t h) {
  return w > 0 && w <= kJBig2MaxImageSize && h > 0 && h <= kJBig2MaxImageSize;
}

pdfium::span<const uint8_t> CJBig2_Image::span() const {
  return data_.span();
}

pdfium::span<uint8_t> CJBig2_Image::span() {
  return data_.span();
}

int CJBig2_Image::GetPixel(int32_t x, pdfium::span<const uint8_t> line) const {
  if (line.empty() || x < 0 || x >= width_) {
    return 0;
  }

  uint32_t m = BitIndexToByte(x);
  int32_t n = x & 7;
  return (line[m] >> (7 - n)) & 1;
}

void CJBig2_Image::SetPixel(int32_t x, pdfium::span<uint8_t> line, int v) {
  if (line.empty() || x < 0 || x >= width_) {
    return;
  }

  uint32_t m = BitIndexToByte(x);
  int32_t n = 1 << (7 - (x & 7));
  if (v) {
    line[m] |= n;
  } else {
    line[m] &= ~n;
  }
}

pdfium::span<const uint8_t> CJBig2_Image::GetLine(int32_t y) const {
  std::optional<size_t> offset = GetLineOffset(y);
  if (!offset.has_value()) {
    return {};
  }
  return span().subspan(offset.value(), static_cast<size_t>(stride_));
}

pdfium::span<uint8_t> CJBig2_Image::GetLine(int32_t y) {
  std::optional<size_t> offset = GetLineOffset(y);
  if (!offset.has_value()) {
    return {};
  }
  return span().subspan(offset.value(), static_cast<size_t>(stride_));
}

void CJBig2_Image::CopyLine(pdfium::span<uint8_t> dest,
                            pdfium::span<const uint8_t> src) {
  if (dest.empty()) {
    return;
  }

  if (src.empty()) {
    std::ranges::fill(dest, 0);
    return;
  }
  fxcrt::spancpy(dest, src);
}

void CJBig2_Image::Fill(bool v) {
  std::ranges::fill(span(), v ? 0xff : 0);
}

bool CJBig2_Image::ComposeTo(CJBig2_Image* pDst,
                             int64_t x,
                             int64_t y,
                             JBig2ComposeOp op) {
  return has_data() &&
         ComposeToInternal(pDst, x, y, op, FX_RECT(0, 0, width_, height_));
}

bool CJBig2_Image::ComposeToWithRect(CJBig2_Image* pDst,
                                     int64_t x,
                                     int64_t y,
                                     const FX_RECT& rtSrc,
                                     JBig2ComposeOp op) {
  return has_data() && ComposeToInternal(pDst, x, y, op, rtSrc);
}

bool CJBig2_Image::ComposeFrom(int64_t x,
                               int64_t y,
                               CJBig2_Image* pSrc,
                               JBig2ComposeOp op) {
  return has_data() && pSrc->ComposeTo(this, x, y, op);
}

bool CJBig2_Image::ComposeFromWithRect(int64_t x,
                                       int64_t y,
                                       CJBig2_Image* pSrc,
                                       const FX_RECT& rtSrc,
                                       JBig2ComposeOp op) {
  return has_data() && pSrc->ComposeToWithRect(this, x, y, rtSrc, op);
}

std::unique_ptr<CJBig2_Image> CJBig2_Image::SubImage(int32_t x,
                                                     int32_t y,
                                                     int32_t w,
                                                     int32_t h) const {
  auto image = std::make_unique<CJBig2_Image>(w, h);
  if (!image->has_data() || !has_data()) {
    return image;
  }

  if (x < 0 || x >= width_ || y < 0 || y >= height_) {
    return image;
  }

  // Fast case when byte-aligned, normal slow case otherwise.
  if ((x & 7) == 0) {
    SubImageFast(x, y, w, h, image.get());
  } else {
    SubImageSlow(x, y, w, h, image.get());
  }

  return image;
}

std::optional<size_t> CJBig2_Image::GetLineOffset(int32_t y) const {
  if (!has_data() || y < 0 || y >= height_) {
    return std::nullopt;
  }

  // Since `y` is in [0, height), this is safe without `FX_SAFE_SIZE_T`.
  return static_cast<size_t>(stride_) * static_cast<size_t>(y);
}

pdfium::span<const uint32_t> CJBig2_Image::GetLines32(int32_t y,
                                                      int32_t count) const {
  std::optional<size_t> offset = GetLineOffset(y);
  if (!offset.has_value()) {
    return {};
  }
  // `GetLineOffset()` verified `y` is in bounds.
  if (count <= 0 || count > height_ - y) {
    return {};
  }
  return fxcrt::reinterpret_span<const uint32_t>(
      span().subspan(offset.value(), static_cast<size_t>(stride_) * count));
}

pdfium::span<uint32_t> CJBig2_Image::GetLines32(int32_t y, int32_t count) {
  auto lines = std::as_const(*this).GetLines32(y, count);
  // SAFETY: const_cast<>() doesn't change size.
  return UNSAFE_BUFFERS(
      pdfium::span(const_cast<uint32_t*>(lines.data()), lines.size()));
}

void CJBig2_Image::SubImageFast(uint32_t x,
                                uint32_t y,
                                int32_t w,
                                int32_t h,
                                CJBig2_Image* image) const {
  uint32_t m = BitIndexToByte(x);
  // SubImage() made sure `x` is [0, width_).
  CHECK_GT(static_cast<size_t>(stride_), m);
  size_t bytes_to_copy = std::min<size_t>(image->stride_, stride_ - m);
  // SubImage() made sure both images have data.
  CHECK_GT(bytes_to_copy, 0);
  int32_t lines_to_copy = std::min<int32_t>(image->height_, height_ - y);
  for (int32_t i = 0; i < lines_to_copy; ++i) {
    pdfium::span<const uint8_t> src = GetLine(y + i).subspan(m, bytes_to_copy);
    fxcrt::spancpy(image->GetLine(i), src);
  }
}

void CJBig2_Image::SubImageSlow(uint32_t x,
                                uint32_t y,
                                int32_t w,
                                int32_t h,
                                CJBig2_Image* image) const {
  uint32_t m = BitIndexToAlignedUint32(x);
  // SubImage() made sure `x` is [0, width_).
  CHECK_GT(stride32(), m);
  int32_t n = x & 31;
  const uint32_t src_elems_count = stride32() - m;
  const uint32_t elems_to_copy = std::min(image->stride32(), src_elems_count);
  // SubImage() made sure both images have data. The strides are always a
  // multiple of 4.
  CHECK_GT(elems_to_copy, 0);
  int32_t lines_to_copy = std::min<int32_t>(image->height_, height_ - y);
  pdfium::span<const uint32_t> src_lines = GetLines32(y, lines_to_copy);
  pdfium::span<uint32_t> dest_lines = image->GetLines32(0, lines_to_copy);
  CHECK(!dest_lines.empty());
  while (!dest_lines.empty()) {
    pdfium::span<const uint32_t> src = src_lines.subspan(m, src_elems_count);
    pdfium::span<uint32_t> dest = dest_lines.first(elems_to_copy);
    uint32_t saved_src_elem = FromBE32(src.take_first_elem());
    auto [dest_zip, dest_remaining] =
        dest.split_at(std::min(dest.size(), src.size()));
    for (auto [dest_elem, src_elem] : fxcrt::Zip(dest_zip, src)) {
      uint32_t next_src_elem = FromBE32(src_elem);
      dest_elem = FromBE32((saved_src_elem << n) | (next_src_elem >> (32 - n)));
      saved_src_elem = next_src_elem;
    }
    if (!dest_remaining.empty()) {
      dest_remaining[0] = FromBE32(saved_src_elem << n);
    }
    src_lines = src_lines.subspan(stride32());
    dest_lines = dest_lines.subspan(image->stride32());
  }
}

void CJBig2_Image::Expand(int32_t h, bool v) {
  if (!has_data() || h <= height_ || h > kMaxImageBytes / stride_) {
    return;
  }

  // Save for use later. See note below.
  const size_t current_size = span().size();

  // Won't die unless `kMaxImageBytes` were to be increased someday.
  const size_t desired_size = Fx2DSizeOrDie(h, stride_);

  // NOTE: Updating `data_` instantly changes subsequent span() calls.
  if (data_.IsOwned()) {
    data_.ResizeOwned(desired_size);
  } else {
    pdfium::span<const uint8_t> external_buffer = span();
    data_ = MaybeOwnedDataVector<uint8_t>(DataVector<uint8_t>(desired_size));
    fxcrt::spancpy(span().first(external_buffer.size()), external_buffer);
  }
  std::ranges::fill(span().subspan(current_size), v ? 0xff : 0);
  height_ = h;
}

bool CJBig2_Image::ComposeToInternal(CJBig2_Image* pDst,
                                     int64_t x_in,
                                     int64_t y_in,
                                     JBig2ComposeOp op,
                                     const FX_RECT& rtSrc) {
  DCHECK(has_data());

  // TODO(weili): Check whether the range check is correct. Should x>=1048576?
  if (x_in < -1048576 || x_in > 1048576 || y_in < -1048576 || y_in > 1048576) {
    return false;
  }
  const int32_t x = static_cast<int32_t>(x_in);
  const int32_t y = static_cast<int32_t>(y_in);

  const int32_t sw = rtSrc.Width();
  const int32_t sh = rtSrc.Height();

  const int32_t xs0 = x < 0 ? -x : 0;
  int32_t xs1;
  FX_SAFE_INT32 iChecked = pDst->width_;
  iChecked -= x;
  if (iChecked.IsValid() && sw > iChecked.ValueOrDie()) {
    xs1 = iChecked.ValueOrDie();
  } else {
    xs1 = sw;
  }

  const int32_t ys0 = y < 0 ? -y : 0;
  int32_t ys1;
  iChecked = pDst->height_;
  iChecked -= y;
  if (iChecked.IsValid() && sh > iChecked.ValueOrDie()) {
    ys1 = iChecked.ValueOrDie();
  } else {
    ys1 = sh;
  }

  if (ys0 >= ys1 || xs0 >= xs1) {
    return false;
  }

  const int32_t xd0 = std::max(x, 0);
  const int32_t yd0 = std::max(y, 0);
  const int32_t w = xs1 - xs0;
  const int32_t h = ys1 - ys0;
  const int32_t xd1 = xd0 + w;
  const uint32_t d1 = xd0 & 31;
  const uint32_t d2 = xd1 & 31;
  const uint32_t s1 = xs0 & 31;
  const uint32_t maskL = 0xffffffff >> d1;
  const uint32_t maskR = 0xffffffff << ((32 - (xd1 & 31)) % 32);
  const uint32_t maskM = maskL & maskR;

  const int src_start_line = rtSrc.top + ys0;
  const int dest_start_line = yd0;
  const uint32_t src_offset =
      BitIndexToAlignedUint32(pdfium::checked_cast<uint32_t>(xs0 + rtSrc.left));
  CHECK_GT(stride32(), src_offset);
  const uint32_t src_size = stride32() - src_offset;
  const uint32_t dest_offset = BitIndexToAlignedUint32(xd0);
  CHECK_GT(pDst->stride32(), dest_offset);
  const uint32_t dest_size = pDst->stride32() - dest_offset;
  const uint32_t line_size =
      pdfium::checked_cast<uint32_t>(stride32() - BitIndexToAlignedUint32(xs0));

  pdfium::span<const uint32_t> src_lines = GetLines32(src_start_line, h);
  pdfium::span<uint32_t> dest_lines = pDst->GetLines32(dest_start_line, h);
  CHECK(!dest_lines.empty());

  enum class ComposeToOp {
    kDestAlignedSrcAlignedSrcGreaterThanDest,
    kDestAlignedSrcAlignedSrcLessThanEqualDest,
    kDestAlignedSrcNotAligned,
    kDestNotAlignedSrcGreaterThanDest,
    kDestNotAlignedSrcEqualToDest,
    kDestNotAlignedSrcLessThanDest
  };

  ComposeToOp compose_to_op;
  if ((xd0 & ~31) == ((xd1 - 1) & ~31)) {
    if ((xs0 & ~31) == ((xs1 - 1) & ~31)) {
      compose_to_op =
          s1 > d1 ? ComposeToOp::kDestAlignedSrcAlignedSrcGreaterThanDest
                  : ComposeToOp::kDestAlignedSrcAlignedSrcLessThanEqualDest;
    } else {
      compose_to_op = ComposeToOp::kDestAlignedSrcNotAligned;
    }
  } else {
    if (s1 > d1) {
      compose_to_op = ComposeToOp::kDestNotAlignedSrcGreaterThanDest;
    } else if (s1 == d1) {
      compose_to_op = ComposeToOp::kDestNotAlignedSrcEqualToDest;
    } else {
      compose_to_op = ComposeToOp::kDestNotAlignedSrcLessThanDest;
    }
  }

  switch (compose_to_op) {
    case ComposeToOp::kDestAlignedSrcAlignedSrcGreaterThanDest: {
      const uint32_t shift = s1 - d1;
      while (!dest_lines.empty()) {
        pdfium::span<const uint32_t> src = src_lines.first(stride32());
        pdfium::span<uint32_t> dest = dest_lines.first(pDst->stride32());
        uint32_t src_val = FromBE32(src.subspan(src_offset).front()) << shift;
        uint32_t& dest_elem = dest.subspan(dest_offset).front();
        dest_elem = FromBE32(
            DoComposeWithMask(op, src_val, FromBE32(dest_elem), maskM));
        src_lines = src_lines.subspan(stride32());
        dest_lines = dest_lines.subspan(pDst->stride32());
      }
      return true;
    }
    case ComposeToOp::kDestAlignedSrcAlignedSrcLessThanEqualDest: {
      const uint32_t shift = d1 - s1;
      while (!dest_lines.empty()) {
        pdfium::span<const uint32_t> src = src_lines.first(stride32());
        pdfium::span<uint32_t> dest = dest_lines.first(pDst->stride32());
        uint32_t src_val = FromBE32(src.subspan(src_offset).front()) >> shift;
        uint32_t& dest_elem = dest.subspan(dest_offset).front();
        dest_elem = FromBE32(
            DoComposeWithMask(op, src_val, FromBE32(dest_elem), maskM));
        src_lines = src_lines.subspan(stride32());
        dest_lines = dest_lines.subspan(pDst->stride32());
      }
      return true;
    }
    case ComposeToOp::kDestAlignedSrcNotAligned: {
      const uint32_t shift1 = s1 - d1;
      const uint32_t shift2 = 32 - shift1;
      while (!dest_lines.empty()) {
        pdfium::span<const uint32_t> src =
            src_lines.subspan(src_offset, src_size);
        pdfium::span<uint32_t> dest = dest_lines.first(pDst->stride32());
        uint32_t src_val = (FromBE32(src.take_first_elem()) << shift1) |
                           (FromBE32(src.front()) >> shift2);
        uint32_t& dest_elem = dest.subspan(dest_offset).front();
        dest_elem = FromBE32(
            DoComposeWithMask(op, src_val, FromBE32(dest_elem), maskM));
        src_lines = src_lines.subspan(stride32());
        dest_lines = dest_lines.subspan(pDst->stride32());
      }
      return true;
    }
    case ComposeToOp::kDestNotAlignedSrcGreaterThanDest: {
      const uint32_t shift1 = s1 - d1;
      const uint32_t shift2 = 32 - shift1;
      const size_t middle_elem_count = GetMiddleElementCount(xd0, xd1);
      while (!dest_lines.empty()) {
        pdfium::span<const uint32_t> src =
            src_lines.subspan(src_offset, src_size);
        pdfium::span<uint32_t> dest =
            dest_lines.subspan(dest_offset, dest_size);
        if (d2 != 0) {
          src = src.first(line_size);
        }

        uint32_t saved_src_elem = FromBE32(src.take_first_elem());
        if (d1 != 0) {
          uint32_t next_src_elem = FromBE32(src.take_first_elem());
          uint32_t src_val =
              (saved_src_elem << shift1) | (next_src_elem >> shift2);
          saved_src_elem = next_src_elem;
          uint32_t& dest_elem = dest.take_first<1u>().front();
          dest_elem = FromBE32(
              DoComposeWithMask(op, src_val, FromBE32(dest_elem), maskL));
        }
        auto [zipped_dest, dest_remaining] = dest.split_at(middle_elem_count);
        for (auto [dest_elem, src_elem] : fxcrt::Zip(zipped_dest, src)) {
          uint32_t next_src_elem = FromBE32(src_elem);
          uint32_t src_val =
              (saved_src_elem << shift1) | (next_src_elem >> shift2);
          saved_src_elem = next_src_elem;
          dest_elem = FromBE32(DoCompose(op, src_val, FromBE32(dest_elem)));
        }
        if (d2 != 0) {
          src = src.subspan(middle_elem_count);
          uint32_t src_val = saved_src_elem << shift1;
          if (!src.empty()) {
            src_val |= FromBE32(src.front()) >> shift2;
          }
          uint32_t& dest_elem = dest_remaining.front();
          dest_elem = FromBE32(
              DoComposeWithMask(op, src_val, FromBE32(dest_elem), maskR));
        }
        src_lines = src_lines.subspan(stride32());
        dest_lines = dest_lines.subspan(pDst->stride32());
      }
      return true;
    }
    case ComposeToOp::kDestNotAlignedSrcEqualToDest: {
      const size_t middle_elem_count = GetMiddleElementCount(xd0, xd1);
      while (!dest_lines.empty()) {
        pdfium::span<const uint32_t> src =
            src_lines.subspan(src_offset, src_size);
        pdfium::span<uint32_t> dest =
            dest_lines.subspan(dest_offset, dest_size);
        if (d1 != 0) {
          uint32_t src_val = FromBE32(src.take_first_elem());
          uint32_t& dest_elem = dest.take_first<1u>().front();
          dest_elem = FromBE32(
              DoComposeWithMask(op, src_val, FromBE32(dest_elem), maskL));
        }
        auto [zipped_dest, dest_remaining] = dest.split_at(middle_elem_count);
        for (auto [dest_elem, src_elem] : fxcrt::Zip(zipped_dest, src)) {
          uint32_t src_val = FromBE32(src_elem);
          dest_elem = FromBE32(DoCompose(op, src_val, FromBE32(dest_elem)));
        }
        if (d2 != 0) {
          src = src.subspan(middle_elem_count);
          uint32_t src_val = FromBE32(src.front());
          uint32_t& dest_elem = dest_remaining.front();
          dest_elem = FromBE32(
              DoComposeWithMask(op, src_val, FromBE32(dest_elem), maskR));
        }
        src_lines = src_lines.subspan(stride32());
        dest_lines = dest_lines.subspan(pDst->stride32());
      }
      return true;
    }
    case ComposeToOp::kDestNotAlignedSrcLessThanDest: {
      const uint32_t shift1 = d1 - s1;
      const uint32_t shift2 = 32 - shift1;
      const size_t middle_elem_count = GetMiddleElementCount(xd0, xd1);
      while (!dest_lines.empty()) {
        pdfium::span<const uint32_t> src =
            src_lines.subspan(src_offset, src_size);
        pdfium::span<uint32_t> dest =
            dest_lines.subspan(dest_offset, dest_size);
        if (d2 != 0) {
          src = src.first(line_size);
        }

        uint32_t saved_src_elem = FromBE32(src.take_first_elem());
        if (d1 != 0) {
          uint32_t src_val = saved_src_elem >> shift1;
          uint32_t& dest_elem = dest.take_first<1u>().front();
          dest_elem = FromBE32(
              DoComposeWithMask(op, src_val, FromBE32(dest_elem), maskL));
        }
        auto [zipped_dest, dest_remaining] = dest.split_at(middle_elem_count);
        for (auto [dest_elem, src_elem] : fxcrt::Zip(zipped_dest, src)) {
          uint32_t next_src_elem = FromBE32(src_elem);
          uint32_t src_val =
              (saved_src_elem << shift2) | (next_src_elem >> shift1);
          saved_src_elem = next_src_elem;
          dest_elem = FromBE32(DoCompose(op, src_val, FromBE32(dest_elem)));
        }
        if (d2 != 0) {
          src = src.subspan(middle_elem_count);
          uint32_t src_val = saved_src_elem << shift2;
          if (!src.empty()) {
            src_val |= FromBE32(src.front()) >> shift1;
          }
          uint32_t& dest_elem = dest_remaining.front();
          dest_elem = FromBE32(
              DoComposeWithMask(op, src_val, FromBE32(dest_elem), maskR));
        }
        src_lines = src_lines.subspan(stride32());
        dest_lines = dest_lines.subspan(pDst->stride32());
      }
      return true;
    }
  }
  NOTREACHED();
}
