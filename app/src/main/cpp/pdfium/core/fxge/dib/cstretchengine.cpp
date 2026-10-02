// Copyright 2017 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxge/dib/cstretchengine.h"

#include <math.h>

#include <algorithm>
#include <array>
#include <type_traits>
#include <utility>

#include "core/fxcrt/check.h"
#include "core/fxcrt/check_op.h"
#include "core/fxcrt/fx_2d_size.h"
#include "core/fxcrt/fx_ceil_div.h"
#include "core/fxcrt/fx_safe_types.h"
#include "core/fxcrt/fx_system.h"
#include "core/fxcrt/pauseindicator_iface.h"
#include "core/fxcrt/span_util.h"
#include "core/fxcrt/zip.h"
#include "core/fxge/calculate_pitch.h"
#include "core/fxge/dib/cfx_dibbase.h"
#include "core/fxge/dib/cfx_dibitmap.h"
#include "core/fxge/dib/fx_dib.h"
#include "core/fxge/dib/scanlinecomposer_iface.h"

static_assert(
    std::is_trivially_destructible<CStretchEngine::PixelWeight>::value,
    "PixelWeight storage may be re-used without invoking its destructor");

namespace {

size_t TotalBytesForWeightCount(size_t weight_count) {
  // Always room for one weight even for empty ranges due to declaration
  // of weights_[1] in the header. Don't shrink below this since
  // CalculateWeights() relies on this later.
  const size_t extra_weights = weight_count > 0 ? weight_count - 1 : 0;
  FX_SAFE_SIZE_T total_bytes = extra_weights;
  total_bytes *= sizeof(CStretchEngine::PixelWeight::weights_[0]);
  total_bytes += sizeof(CStretchEngine::PixelWeight);
  return total_bytes.ValueOrDie();
}

}  // namespace

// static
bool CStretchEngine::UseInterpolateBilinear(
    const FXDIB_ResampleOptions& options,
    int dest_width,
    int dest_height,
    int src_width,
    int src_height) {
  return !options.bInterpolateBilinear && !options.bNoSmoothing &&
         abs(dest_width) != 0 &&
         abs(dest_height) / 8 <
             static_cast<long long>(src_width) * src_height / abs(dest_width);
}

CStretchEngine::WeightTable::WeightTable() = default;

CStretchEngine::WeightTable::~WeightTable() = default;

bool CStretchEngine::WeightTable::CalculateWeights(
    int dest_len,
    int dest_min,
    int dest_max,
    int src_len,
    int src_min,
    int src_max,
    const FXDIB_ResampleOptions& options) {
  // 512MB should be large enough for this while preventing OOM.
  static constexpr size_t kMaxTableBytesAllowed = 512 * 1024 * 1024;

  // Help the compiler realize that these can't change during a loop iteration:
  const bool bilinear = options.bInterpolateBilinear;

  dest_min_ = 0;
  item_size_bytes_ = 0;
  weight_tables_size_bytes_ = 0;
  weight_tables_.clear();
  if (dest_len == 0) {
    return true;
  }

  if (dest_min > dest_max) {
    return false;
  }

  dest_min_ = dest_min;

  const double scale = static_cast<double>(src_len) / dest_len;
  const double base = dest_len < 0 ? src_len : 0;
  const size_t weight_count = static_cast<size_t>(ceil(fabs(scale))) + 1;
  item_size_bytes_ = TotalBytesForWeightCount(weight_count);

  const size_t dest_range = static_cast<size_t>(dest_max - dest_min);
  const size_t kMaxTableItemsAllowed = kMaxTableBytesAllowed / item_size_bytes_;
  if (dest_range > kMaxTableItemsAllowed) {
    return false;
  }

  weight_tables_size_bytes_ = dest_range * item_size_bytes_;
  weight_tables_.resize(weight_tables_size_bytes_);
  if (options.bNoSmoothing || fabs(scale) < 1.0f) {
    for (int dest_pixel = dest_min; dest_pixel < dest_max; ++dest_pixel) {
      PixelWeight& pixel_weights = *GetPixelWeight(dest_pixel);
      double src_pos = dest_pixel * scale + scale / 2 + base;
      if (bilinear) {
        int src_start = static_cast<int>(floor(src_pos - 0.5));
        int src_end = static_cast<int>(floor(src_pos + 0.5));
        src_start = std::max(src_start, src_min);
        src_end = std::min(src_end, src_max - 1);
        pixel_weights.SetStartEnd(src_start, src_end, weight_count);
        if (pixel_weights.src_start_ >= pixel_weights.src_end_) {
          // Always room for one weight per size calculation.
          pixel_weights.weights_[0] = kFixedPointOne;
        } else {
          const uint32_t second_weight =
              FixedFromDouble(src_pos - pixel_weights.src_start_ - 0.5f);
          pixel_weights.SetWeightForPosition(pixel_weights.src_start_,
                                             kFixedPointOne - second_weight);
          pixel_weights.SetWeightForPosition(pixel_weights.src_start_ + 1,
                                             second_weight);
        }
      } else {
        int pixel_pos = static_cast<int>(floor(src_pos));
        int src_start = std::max(pixel_pos, src_min);
        int src_end = std::min(pixel_pos, src_max - 1);
        pixel_weights.SetStartEnd(src_start, src_end, weight_count);
        pixel_weights.weights_[0] = kFixedPointOne;
      }
    }
    return true;
  }

  for (int dest_pixel = dest_min; dest_pixel < dest_max; ++dest_pixel) {
    PixelWeight& pixel_weights = *GetPixelWeight(dest_pixel);
    double src_start = dest_pixel * scale + base;
    double src_end = src_start + scale;
    int start_i = floor(std::min(src_start, src_end));
    int end_i = floor(std::max(src_start, src_end));
    start_i = std::max(start_i, src_min);
    end_i = std::min(end_i, src_max - 1);
    if (start_i > end_i) {
      // `start_i` is already at least `src_min`, so the lower bound only
      // matters when the source range is empty: `src_max` is then 0 and
      // clamping from above alone would store a negative position.
      start_i = std::clamp(start_i, 0, std::max(src_max - 1, 0));
      pixel_weights.SetStartEnd(start_i, start_i, weight_count);
      continue;
    }
    pixel_weights.SetStartEnd(start_i, end_i, weight_count);
    uint32_t remaining = kFixedPointOne;
    double rounding_error = 0.0;
    for (int j = start_i; j < end_i; ++j) {
      double dest_start = (j - base) / scale;
      double dest_end = (j + 1 - base) / scale;
      if (dest_start > dest_end) {
        std::swap(dest_start, dest_end);
      }
      double area_start = std::max(dest_start, static_cast<double>(dest_pixel));
      double area_end = std::min(dest_end, static_cast<double>(dest_pixel + 1));
      double weight = std::max(0.0, area_end - area_start);
      uint32_t fixed_weight = FixedFromDouble(weight + rounding_error);
      pixel_weights.SetWeightForPosition(j, fixed_weight);
      remaining -= fixed_weight;
      rounding_error =
          weight - static_cast<double>(fixed_weight) / kFixedPointOne;
    }
    // Note: underflow is defined behaviour for unsigned types and will
    // result in an out-of-range value.
    if (remaining && remaining <= kFixedPointOne) {
      pixel_weights.SetWeightForPosition(end_i, remaining);
    } else {
      pixel_weights.RemoveLastWeightAndAdjust(remaining);
    }
  }
  return true;
}

const CStretchEngine::PixelWeight* CStretchEngine::WeightTable::GetPixelWeight(
    int pixel) const {
  DCHECK_GE(pixel, dest_min_);
  return reinterpret_cast<const PixelWeight*>(
      &weight_tables_[(pixel - dest_min_) * item_size_bytes_]);
}

CStretchEngine::PixelWeight* CStretchEngine::WeightTable::GetPixelWeight(
    int pixel) {
  return const_cast<PixelWeight*>(std::as_const(*this).GetPixelWeight(pixel));
}

CStretchEngine::CStretchEngine(ScanlineComposerIface* pDestBitmap,
                               FXDIB_Format dest_format,
                               int dest_width,
                               int dest_height,
                               const FX_RECT& clip_rect,
                               const RetainPtr<const CFX_DIBBase>& pSrcBitmap,
                               const FXDIB_ResampleOptions& options)
    : dest_format_(dest_format),
      dest_bpp_(GetBppFromFormat(dest_format)),
      src_bpp_(pSrcBitmap->GetBPP()),
      has_alpha_(pSrcBitmap->IsAlphaFormat()),
      source_(pSrcBitmap),
      src_palette_(pSrcBitmap->GetPaletteSpan()),
      src_width_(pSrcBitmap->GetWidth()),
      src_height_(pSrcBitmap->GetHeight()),
      dest_bitmap_(pDestBitmap),
      dest_width_(dest_width),
      dest_height_(dest_height),
      dest_clip_(clip_rect) {
  if (has_alpha_) {
    // TODO(crbug.com/42271020): Consider adding support for
    // `FXDIB_Format::kBgraPremul`
    DCHECK_EQ(dest_format_, FXDIB_Format::kBgra);
    DCHECK_EQ(dest_bpp_, GetBppFromFormat(FXDIB_Format::kBgra));
    DCHECK_EQ(source_->GetFormat(), FXDIB_Format::kBgra);
    DCHECK_EQ(src_bpp_, GetBppFromFormat(FXDIB_Format::kBgra));
  }

  std::optional<uint32_t> maybe_size =
      fxge::CalculatePitch32(dest_bpp_, clip_rect.Width());
  if (!maybe_size.has_value()) {
    return;
  }

  if (!IsValidDestinationDimension(dest_width_) ||
      !IsValidDestinationDimension(dest_height_)) {
    return;
  }

  dest_scanline_.resize(maybe_size.value());
  if (dest_format == FXDIB_Format::kBgrx) {
    std::ranges::fill(dest_scanline_, 255);
  }
  inter_pitch_ = fxge::CalculatePitch32OrDie(dest_bpp_, dest_clip_.Width());
  extra_mask_pitch_ = fxge::CalculatePitch32OrDie(8, dest_clip_.Width());
  if (options.bNoSmoothing) {
    resample_options_.bNoSmoothing = true;
  } else {
    if (UseInterpolateBilinear(options, dest_width_, dest_height_, src_width_,
                               src_height_)) {
      resample_options_.bInterpolateBilinear = true;
    } else {
      resample_options_ = options;
    }
  }
  double scale_x = static_cast<float>(src_width_) / dest_width_;
  double scale_y = static_cast<float>(src_height_) / dest_height_;
  double base_x = dest_width_ > 0 ? 0.0f : dest_width_;
  double base_y = dest_height_ > 0 ? 0.0f : dest_height_;
  double src_left = scale_x * (clip_rect.left + base_x);
  double src_right = scale_x * (clip_rect.right + base_x);
  double src_top = scale_y * (clip_rect.top + base_y);
  double src_bottom = scale_y * (clip_rect.bottom + base_y);
  if (src_left > src_right) {
    std::swap(src_left, src_right);
  }
  if (src_top > src_bottom) {
    std::swap(src_top, src_bottom);
  }
  src_clip_.left = static_cast<int>(floor(src_left));
  src_clip_.right = static_cast<int>(ceil(src_right));
  src_clip_.top = static_cast<int>(floor(src_top));
  src_clip_.bottom = static_cast<int>(ceil(src_bottom));
  FX_RECT src_rect(0, 0, src_width_, src_height_);
  src_clip_.Intersect(src_rect);

  switch (src_bpp_) {
    case 1:
      trans_method_ = dest_bpp_ == 8 ? TransformMethod::k1BppTo8Bpp
                                     : TransformMethod::k1BppToManyBpp;
      break;
    case 8:
      trans_method_ = dest_bpp_ == 8 ? TransformMethod::k8BppTo8Bpp
                                     : TransformMethod::k8BppToManyBpp;
      break;
    default:
      trans_method_ = has_alpha_ ? TransformMethod::kManyBpptoManyBppWithAlpha
                                 : TransformMethod::kManyBpptoManyBpp;
      break;
  }
}

CStretchEngine::~CStretchEngine() = default;

bool CStretchEngine::Continue(PauseIndicatorIface* pPause) {
  while (state_ == State::kHorizontal) {
    if (ContinueStretchHorz(pPause)) {
      return true;
    }

    state_ = State::kVertical;
    StretchVert();
  }
  return false;
}

bool CStretchEngine::StartStretchHorz() {
  if (dest_width_ == 0 || inter_pitch_ == 0 || dest_scanline_.empty()) {
    return false;
  }

  FX_SAFE_SIZE_T safe_size = src_clip_.Height();
  safe_size *= inter_pitch_;
  const size_t size = safe_size.ValueOrDefault(0);
  if (size == 0) {
    return false;
  }
  inter_buf_ = FixedSizeDataVector<uint8_t>::TryZeroed(size);
  if (inter_buf_.empty()) {
    return false;
  }
  if (!weight_table_.CalculateWeights(
          dest_width_, dest_clip_.left, dest_clip_.right, src_width_,
          src_clip_.left, src_clip_.right, resample_options_)) {
    return false;
  }
  if (src_width_ > 0 && (trans_method_ == TransformMethod::k1BppTo8Bpp ||
                         trans_method_ == TransformMethod::k1BppToManyBpp)) {
    // Eight pixels per source byte, so round up to whole source bytes.
    FX_SAFE_SIZE_T expanded_size = src_width_;
    expanded_size = fxcrt::CeilDiv(expanded_size, 8);
    expanded_size *= 8;
    expanded_row_ =
        FixedSizeDataVector<uint8_t>::TryUninit(expanded_size.ValueOrDie());
    if (expanded_row_.empty()) {
      return false;
    }
  }
  cur_row_ = src_clip_.top;
  state_ = State::kHorizontal;
  return true;
}

bool CStretchEngine::ContinueStretchHorz(PauseIndicatorIface* pPause) {
  if (!dest_width_) {
    return false;
  }
  if (source_->SkipToScanline(cur_row_, pPause)) {
    return true;
  }

  const int Bpp = dest_bpp_ / 8;
  static const int kStrechPauseRows = 10;
  int rows_to_go = kStrechPauseRows;
  for (; cur_row_ < src_clip_.bottom; ++cur_row_) {
    if (rows_to_go == 0) {
      if (pPause && pPause->NeedToPauseNow()) {
        return true;
      }

      rows_to_go = kStrechPauseRows;
    }

    pdfium::span<const uint8_t> src_row_span = source_->GetScanline(cur_row_);
    pdfium::span<uint8_t> dest_span = inter_buf_.subspan(
        (cur_row_ - src_clip_.top) * inter_pitch_, inter_pitch_);
    size_t dest_span_index = 0;
    // TODO(npm): reduce duplicated code here
    switch (trans_method_) {
      case TransformMethod::k1BppTo8Bpp:
        src_row_span = Expand1BppRow(src_row_span);
        [[fallthrough]];
      case TransformMethod::k8BppTo8Bpp: {
        for (int col = dest_clip_.left; col < dest_clip_.right; ++col) {
          const PixelWeight* pWeights = weight_table_.GetPixelWeight(col);
          pdfium::span<const uint32_t> weights = pWeights->GetWeights();
          // One validated view of exactly the source bytes this column taps.
          auto src_window =
              src_row_span.subspan(pWeights->GetSrcStart(), weights.size());
          uint32_t dest_a = 0;
          // See the matching unrolls in the kManyBpptoManyBpp case below:
          // one and two taps are the common trip counts and the loop is all
          // overhead there. Tap order is unchanged.
          switch (weights.size()) {
            case 1:
              dest_a = weights[0] * src_window[0];
              break;
            case 2:
              dest_a = weights[0] * src_window[0] + weights[1] * src_window[1];
              break;
            default:
              for (auto [pixel_weight, src_byte] :
                   fxcrt::Zip(weights, src_window)) {
                dest_a += pixel_weight * src_byte;
              }
              break;
          }
          dest_span[dest_span_index++] = PixelFromFixed(dest_a);
        }
        break;
      }
      case TransformMethod::k1BppToManyBpp:
        src_row_span = Expand1BppRow(src_row_span);
        [[fallthrough]];
      case TransformMethod::k8BppToManyBpp: {
        // 3 bytes per destination pixel, written contiguously.
        auto dest_pixels =
            fxcrt::reinterpret_span<FX_BGR_STRUCT<uint8_t>>(dest_span);
        size_t dest_index = 0;
        for (int col = dest_clip_.left; col < dest_clip_.right; ++col) {
          const PixelWeight* pWeights = weight_table_.GetPixelWeight(col);
          pdfium::span<const uint32_t> weights = pWeights->GetWeights();
          auto src_window =
              src_row_span.subspan(pWeights->GetSrcStart(), weights.size());
          uint32_t dest_r = 0;
          uint32_t dest_g = 0;
          uint32_t dest_b = 0;
          for (auto [pixel_weight, src_byte] :
               fxcrt::Zip(weights, src_window)) {
            FX_ARGB argb = src_palette_[src_byte];
            if (dest_format_ == FXDIB_Format::kBgr) {
              dest_r += pixel_weight * static_cast<uint8_t>(argb >> 16);
              dest_g += pixel_weight * static_cast<uint8_t>(argb >> 8);
              dest_b += pixel_weight * static_cast<uint8_t>(argb);
            } else {
              dest_b += pixel_weight * static_cast<uint8_t>(argb >> 24);
              dest_g += pixel_weight * static_cast<uint8_t>(argb >> 16);
              dest_r += pixel_weight * static_cast<uint8_t>(argb >> 8);
            }
          }
          FX_BGR_STRUCT<uint8_t>& dest = dest_pixels[dest_index++];
          dest.blue = PixelFromFixed(dest_b);
          dest.green = PixelFromFixed(dest_g);
          dest.red = PixelFromFixed(dest_r);
        }
        break;
      }
      case TransformMethod::kManyBpptoManyBpp: {
        // `Bpp` is the source pixel stride as well as the destination's:
        // this method is only selected for >8bpp sources, and
        // CFX_ImageStretcher::GetStretchedFormat() maps such sources to the
        // same format on the destination side, so both are kBgr (3 bytes) or
        // kBgrx (4 bytes, whose 4th byte this ignores). The generic lambda
        // below is instantiated once per branch, so each instantiation gets a
        // compile-time source pixel size.
        auto stretch_row_from = [this, &dest_span](auto src_pixels) {
          // The destination pixels have the same layout as the source
          // pixels: 3 bytes fully written, or 4 bytes of which the 4th is
          // left untouched.
          using DestPixel =
              std::remove_const_t<typename decltype(src_pixels)::element_type>;
          auto dest_pixels = fxcrt::reinterpret_span<DestPixel>(dest_span);
          size_t dest_index = 0;
          for (int col = dest_clip_.left; col < dest_clip_.right; ++col) {
            const PixelWeight* pWeights = weight_table_.GetPixelWeight(col);
            pdfium::span<const uint32_t> weights = pWeights->GetWeights();
            auto window =
                src_pixels.subspan(pWeights->GetSrcStart(), weights.size());
            uint32_t dest_r = 0;
            uint32_t dest_g = 0;
            uint32_t dest_b = 0;
            // One and two taps are the overwhelmingly common cases -- one is
            // no scaling in this axis, two is bilinear -- and at that trip
            // count the loop is all overhead. Unrolled explicitly; the terms
            // are summed in tap order, so the result is identical.
            switch (weights.size()) {
              case 1: {
                const auto& src0 = window[0];
                dest_b = weights[0] * src0.blue;
                dest_g = weights[0] * src0.green;
                dest_r = weights[0] * src0.red;
                break;
              }
              case 2: {
                const auto& src0 = window[0];
                const auto& src1 = window[1];
                dest_b = weights[0] * src0.blue + weights[1] * src1.blue;
                dest_g = weights[0] * src0.green + weights[1] * src1.green;
                dest_r = weights[0] * src0.red + weights[1] * src1.red;
                break;
              }
              default:
                for (auto [pixel_weight, src] : fxcrt::Zip(weights, window)) {
                  dest_b += pixel_weight * src.blue;
                  dest_g += pixel_weight * src.green;
                  dest_r += pixel_weight * src.red;
                }
                break;
            }
            DestPixel& dest = dest_pixels[dest_index++];
            dest.blue = PixelFromFixed(dest_b);
            dest.green = PixelFromFixed(dest_g);
            dest.red = PixelFromFixed(dest_r);
          }
        };
        if (Bpp == 3) {
          stretch_row_from(
              fxcrt::reinterpret_span<const FX_BGR_STRUCT<uint8_t>>(
                  src_row_span));
        } else {
          CHECK_EQ(Bpp, 4);
          stretch_row_from(
              fxcrt::reinterpret_span<const FX_BGRA_STRUCT<uint8_t>>(
                  src_row_span));
        }
        break;
      }
      case TransformMethod::kManyBpptoManyBppWithAlpha: {
        CHECK(has_alpha_);
        // `has_alpha_` implies both source and destination are kBgra
        // (asserted in the constructor), so the source pixels are 4-byte
        // BGRA structs.
        CHECK_EQ(Bpp, 4);
        auto src_pixels =
            fxcrt::reinterpret_span<const FX_BGRA_STRUCT<uint8_t>>(
                src_row_span);
        auto dest_pixels =
            fxcrt::reinterpret_span<FX_BGRA_STRUCT<uint8_t>>(dest_span);
        size_t dest_index = 0;
        for (int col = dest_clip_.left; col < dest_clip_.right; ++col) {
          const PixelWeight* pWeights = weight_table_.GetPixelWeight(col);
          pdfium::span<const uint32_t> weights = pWeights->GetWeights();
          auto window =
              src_pixels.subspan(pWeights->GetSrcStart(), weights.size());
          uint32_t dest_a = 0;
          uint32_t dest_r = 0;
          uint32_t dest_g = 0;
          uint32_t dest_b = 0;
          for (auto [weight, src] : fxcrt::Zip(weights, window)) {
            uint32_t pixel_weight = weight * src.alpha / 255;
            dest_b += pixel_weight * src.blue;
            dest_g += pixel_weight * src.green;
            dest_r += pixel_weight * src.red;
            dest_a += pixel_weight;
          }
          FX_BGRA_STRUCT<uint8_t>& dest = dest_pixels[dest_index++];
          dest.blue = PixelFromFixed(dest_b);
          dest.green = PixelFromFixed(dest_g);
          dest.red = PixelFromFixed(dest_r);
          dest.alpha = PixelFromFixed(255 * dest_a);
        }
        break;
      }
    }
    rows_to_go--;
  }
  return false;
}

void CStretchEngine::StretchVert() {
  if (dest_height_ == 0) {
    return;
  }

  WeightTable table;
  if (!table.CalculateWeights(dest_height_, dest_clip_.top, dest_clip_.bottom,
                              src_height_, src_clip_.top, src_clip_.bottom,
                              resample_options_)) {
    return;
  }

  const int dest_cols = dest_clip_.right - dest_clip_.left;
  if (dest_cols <= 0) {
    return;
  }

  const int DestBpp = dest_bpp_ / 8;
  const size_t row_bytes = Fx2DSizeOrDie(dest_cols, DestBpp);

  // Taps are the outer loop, so each one reads a contiguous row of the
  // intermediate buffer into a row-wide accumulator. Per byte the taps are
  // still summed in tap order, so the output is unchanged.
  //
  // Allocated once outside the row loop rather than constructed inside it:
  // `row_bytes` uint32s is tens of kilobytes for a wide destination, and one
  // allocate/free pair per destination row is a large fraction of a scanned
  // page's render time.
  //
  // Uninitialised, because the first tap of each row assigns rather than
  // accumulates and so writes every element before anything reads it. The
  // one case with no first tap -- an empty weight range -- zeroes the
  // accumulator explicitly below.
  auto accum = FixedSizeDataVector<uint32_t>::Uninit(row_bytes);
  pdfium::span<uint32_t> accum_span = accum.span();

  for (int row = dest_clip_.top; row < dest_clip_.bottom; ++row) {
    const PixelWeight* pWeights = table.GetPixelWeight(row);
    pdfium::span<const uint32_t> weights = pWeights->GetWeights();
    if (!weights.empty()) {
      CHECK_GE(pWeights->src_start_, src_clip_.top);
      CHECK_LT(pWeights->src_end_, src_clip_.bottom);
    }

    // No taps for this row, so the tap loop below writes nothing: zero the
    // accumulator so the conversion switch sees defined values.
    if (weights.empty()) {
      std::ranges::fill(accum_span, 0u);
    }
    for (size_t i = 0; i < weights.size(); ++i) {
      // Hoisted deliberately: `weights` and `accum_span` are both spans of
      // uint32_t, so the compiler cannot prove the accumulation below does
      // not alias the weight, and would otherwise reload it every iteration
      // and decline to vectorize.
      const uint32_t pixel_weight = weights[i];
      auto src_row = inter_buf_.subspan(
          (static_cast<size_t>(pWeights->src_start_ - src_clip_.top) + i) *
              inter_pitch_,
          row_bytes);
      // The first tap assigns rather than accumulates. That initialises the
      // whole accumulator, so no separate zeroing pass over the row is
      // needed -- one full-width write instead of a write plus a
      // read-modify-write.
      if (i == 0) {
        for (auto [acc, src_byte] : fxcrt::Zip(accum_span, src_row)) {
          acc = pixel_weight * src_byte;
        }
      } else {
        for (auto [acc, src_byte] : fxcrt::Zip(accum_span, src_row)) {
          acc += pixel_weight * src_byte;
        }
      }
    }

    auto dest_row = pdfium::span(dest_scanline_).first(row_bytes);
    switch (trans_method_) {
      case TransformMethod::k1BppTo8Bpp:
      case TransformMethod::k1BppToManyBpp:
      case TransformMethod::k8BppTo8Bpp: {
        // Only the first channel of each destination pixel carries data on
        // these paths; any remaining bytes are left untouched. Typed pixel
        // views give every width the same loop shape. The generic lambda
        // below is instantiated once per branch, so each instantiation gets a
        // compile-time destination pixel size.
        auto write_first_channel = [](auto dest_pixels, auto acc_pixels) {
          for (auto [dest, acc] : fxcrt::Zip(dest_pixels, acc_pixels)) {
            dest.blue = PixelFromFixed(acc.blue);
          }
        };
        if (DestBpp == 1) {
          for (auto [dest, acc] : fxcrt::Zip(dest_row, accum_span)) {
            dest = PixelFromFixed(acc);
          }
        } else if (DestBpp == 3) {
          write_first_channel(
              fxcrt::reinterpret_span<FX_BGR_STRUCT<uint8_t>>(dest_row),
              fxcrt::reinterpret_span<const FX_BGR_STRUCT<uint32_t>>(
                  accum_span));
        } else {
          CHECK_EQ(DestBpp, 4);
          write_first_channel(
              fxcrt::reinterpret_span<FX_BGRA_STRUCT<uint8_t>>(dest_row),
              fxcrt::reinterpret_span<const FX_BGRA_STRUCT<uint32_t>>(
                  accum_span));
        }
        break;
      }
      case TransformMethod::k8BppToManyBpp:
      case TransformMethod::kManyBpptoManyBpp: {
        // The destination is kBgr (3 bytes per pixel, fully written) or
        // kBgrx (4 bytes per pixel, 4th byte left untouched); see the
        // matching dispatch in ContinueStretchHorz().
        if (DestBpp == 3) {
          for (auto [dest, acc] : fxcrt::Zip(dest_row, accum_span)) {
            dest = PixelFromFixed(acc);
          }
        } else {
          CHECK_EQ(DestBpp, 4);
          auto dest_pixels =
              fxcrt::reinterpret_span<FX_BGRA_STRUCT<uint8_t>>(dest_row);
          auto acc_pixels =
              fxcrt::reinterpret_span<const FX_BGRA_STRUCT<uint32_t>>(
                  accum_span);
          for (auto [dest, acc] : fxcrt::Zip(dest_pixels, acc_pixels)) {
            dest.blue = PixelFromFixed(acc.blue);
            dest.green = PixelFromFixed(acc.green);
            dest.red = PixelFromFixed(acc.red);
          }
        }
        break;
      }
      case TransformMethod::kManyBpptoManyBppWithAlpha: {
        CHECK(has_alpha_);
        CHECK_EQ(DestBpp, 4);
        auto dest_pixels =
            fxcrt::reinterpret_span<FX_BGRA_STRUCT<uint8_t>>(dest_row);
        auto acc_pixels =
            fxcrt::reinterpret_span<const FX_BGRA_STRUCT<uint32_t>>(accum_span);
        for (auto [dest, acc] : fxcrt::Zip(dest_pixels, acc_pixels)) {
          if (acc.alpha) {
            int r = acc.red * 255 / acc.alpha;
            int g = acc.green * 255 / acc.alpha;
            int b = acc.blue * 255 / acc.alpha;
            dest.blue = std::clamp(b, 0, 255);
            dest.green = std::clamp(g, 0, 255);
            dest.red = std::clamp(r, 0, 255);
          }
          dest.alpha = PixelFromFixed(acc.alpha);
        }
        break;
      }
    }
    dest_bitmap_->ComposeScanline(row - dest_clip_.top, dest_scanline_);
  }
}

pdfium::span<const uint8_t> CStretchEngine::Expand1BppRow(
    pdfium::span<const uint8_t> src_row) {
  // Maps a byte of eight 1bpp pixels, most significant bit first, to eight
  // bytes that are 0 or 255.
  static constinit auto kBitsToBytes = [] {
    std::array<std::array<uint8_t, 8>, 256> table{};
    for (size_t value = 0; value < table.size(); ++value) {
      for (size_t bit = 0; bit < 8; ++bit) {
        table[value][bit] = (value >> (7 - bit)) & 1 ? 255 : 0;
      }
    }
    return table;
  }();

  pdfium::span<uint8_t> expanded = expanded_row_.span();
  const size_t src_bytes = expanded.size() / 8;
  for (size_t i = 0; i < src_bytes; ++i) {
    fxcrt::spancpy(expanded.subspan(i * 8, 8u),
                   pdfium::span(kBitsToBytes[src_row[i]]));
  }
  return expanded;
}
