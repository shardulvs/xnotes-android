// Copyright 2014 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxcodec/flate/flatemodule.h"

#include <stddef.h>

#include <algorithm>
#include <limits>
#include <memory>
#include <optional>
#include <utility>
#include <vector>

#include "core/fxcodec/data_and_bytes_consumed.h"
#include "core/fxcodec/image_predictors.h"
#include "core/fxcodec/scanlinedecoder.h"
#include "core/fxcrt/check.h"
#include "core/fxcrt/data_vector.h"
#include "core/fxcrt/fixed_size_data_vector.h"
#include "core/fxcrt/fx_ceil_div.h"
#include "core/fxcrt/fx_extension.h"
#include "core/fxcrt/fx_safe_types.h"
#include "core/fxcrt/notreached.h"
#include "core/fxcrt/numerics/safe_conversions.h"
#include "core/fxcrt/raw_span.h"
#include "core/fxcrt/span.h"
#include "core/fxcrt/span_util.h"
#include "core/fxcrt/stl_util.h"
#include "core/fxge/calculate_pitch.h"

#if defined(USE_SYSTEM_ZLIB)
#include <zlib.h>
#else
#include "third_party/zlib/zlib.h"
#endif

extern "C" {

static void* my_alloc_func(void* opaque,
                           unsigned int items,
                           unsigned int size) {
  return FX_Alloc2D(uint8_t, items, size);
}

static void my_free_func(void* opaque, void* address) {
  FX_Free(address);
}

}  // extern "C"

namespace fxcodec {

namespace {

static constexpr uint32_t kMaxTotalOutSize = 1024 * 1024 * 1024;  // 1 GiB

uint32_t FlateGetPossiblyTruncatedTotalOut(z_stream* context) {
  return std::min(pdfium::saturated_cast<uint32_t>(context->total_out),
                  kMaxTotalOutSize);
}

uint32_t FlateGetPossiblyTruncatedTotalIn(z_stream* context) {
  return pdfium::saturated_cast<uint32_t>(context->total_in);
}

size_t FlateCompress(pdfium::span<const uint8_t> src_span,
                     pdfium::span<uint8_t> dest_span) {
  const auto src_size = pdfium::checked_cast<unsigned long>(src_span.size());
  auto dest_size = pdfium::checked_cast<unsigned long>(dest_span.size());
  if (compress(dest_span.data(), &dest_size, src_span.data(), src_size) !=
      Z_OK) {
    return 0;
  }
  return pdfium::checked_cast<size_t>(dest_size);
}

z_stream* FlateInit() {
  z_stream* p = FX_Alloc(z_stream, 1);
  p->zalloc = my_alloc_func;
  p->zfree = my_free_func;
  inflateInit(p);
  return p;
}

void FlateInput(z_stream* context, pdfium::span<const uint8_t> src_buf) {
  context->next_in = const_cast<unsigned char*>(src_buf.data());
  context->avail_in = static_cast<uint32_t>(src_buf.size());
}

bool FlateOutput(z_stream* context, pdfium::span<uint8_t> dest_span) {
  context->next_out = dest_span.data();
  context->avail_out = pdfium::checked_cast<uint32_t>(dest_span.size());
  uint32_t pre_pos = FlateGetPossiblyTruncatedTotalOut(context);
  bool ret = inflate(static_cast<z_stream*>(context), Z_SYNC_FLUSH) == Z_OK;

  uint32_t post_pos = FlateGetPossiblyTruncatedTotalOut(context);
  CHECK_GE(post_pos, pre_pos);
  std::ranges::fill(dest_span.subspan(post_pos - pre_pos), 0);

  return ret;
}

uint32_t FlateGetAvailOut(z_stream* context) {
  return context->avail_out;
}

void FlateEnd(z_stream* context) {
  inflateEnd(context);
  FX_Free(context);
}

// For use with std::unique_ptr<z_stream>.
struct FlateDeleter {
  inline void operator()(z_stream* context) { FlateEnd(context); }
};

class CLZWDecoder {
 public:
  CLZWDecoder(pdfium::span<const uint8_t> src_span, bool early_change);

  bool Decode();
  uint32_t GetSrcSize() const { return fxcrt::CeilDiv(src_bit_pos_, 8); }
  DataVector<uint8_t> TakeDestBuf() {
    dest_buf_.resize(dest_byte_pos_);
    return std::move(dest_buf_);
  }

 private:
  void AddCode(uint32_t prefix_code, uint8_t append_char);
  void DecodeString(uint32_t code);
  bool ExpandDestBuf(size_t additional_size);

  pdfium::raw_span<const uint8_t> const src_span_;
  DataVector<uint8_t> dest_buf_;
  uint32_t src_bit_pos_ = 0;
  uint32_t dest_byte_pos_ = 0;  // Size used.
  uint32_t stack_len_ = 0;
  FixedSizeDataVector<uint8_t> decode_stack_;
  const uint8_t early_change_;
  uint8_t code_len_ = 9;
  uint32_t current_code_ = 0;
  FixedSizeDataVector<uint32_t> codes_;
};

CLZWDecoder::CLZWDecoder(pdfium::span<const uint8_t> src_span,
                         bool early_change)
    : src_span_(src_span),
      decode_stack_(FixedSizeDataVector<uint8_t>::Zeroed(4000)),
      early_change_(early_change ? 1 : 0),
      codes_(FixedSizeDataVector<uint32_t>::Zeroed(5021)) {}

void CLZWDecoder::AddCode(uint32_t prefix_code, uint8_t append_char) {
  if (current_code_ + early_change_ == 4094) {
    return;
  }

  pdfium::span<uint32_t> codes_span = codes_.span();
  codes_span[current_code_++] = (prefix_code << 16) | append_char;
  if (current_code_ + early_change_ == 512 - 258) {
    code_len_ = 10;
  } else if (current_code_ + early_change_ == 1024 - 258) {
    code_len_ = 11;
  } else if (current_code_ + early_change_ == 2048 - 258) {
    code_len_ = 12;
  }
}

void CLZWDecoder::DecodeString(uint32_t code) {
  pdfium::span<uint8_t> decode_span = decode_stack_.span();
  pdfium::span<const uint32_t> codes_span = codes_.span();
  while (true) {
    int index = code - 258;
    if (index < 0 || static_cast<uint32_t>(index) >= current_code_) {
      break;
    }

    uint32_t data = codes_span[index];
    if (stack_len_ >= decode_span.size()) {
      return;
    }

    decode_span[stack_len_++] = static_cast<uint8_t>(data);
    code = data >> 16;
  }
  if (stack_len_ >= decode_span.size()) {
    return;
  }

  decode_span[stack_len_++] = static_cast<uint8_t>(code);
}

bool CLZWDecoder::ExpandDestBuf(size_t additional_size) {
  FX_SAFE_SIZE_T new_size = std::max(dest_buf_.size() / 2, additional_size);
  new_size += dest_buf_.size();
  if (!new_size.IsValid()) {
    dest_buf_.clear();
    return false;
  }

  dest_buf_.resize(new_size.ValueOrDie());
  return true;
}

bool CLZWDecoder::Decode() {
  pdfium::span<uint8_t> decode_span = decode_stack_.span();
  uint32_t old_code = 0xFFFFFFFF;
  uint8_t last_char = 0;

  // In one PDF test set, 40% of Decode() calls did not need to realloc with
  // this size.
  dest_buf_.resize(512);
  while (true) {
    if (src_bit_pos_ + code_len_ > src_span_.size() * 8) {
      break;
    }

    int byte_pos = src_bit_pos_ / 8;
    int bit_pos = src_bit_pos_ % 8;
    uint8_t bit_left = code_len_;
    uint32_t code = 0;
    if (bit_pos) {
      bit_left -= 8 - bit_pos;
      code = (src_span_[byte_pos++] & ((1 << (8 - bit_pos)) - 1)) << bit_left;
    }
    if (bit_left < 8) {
      code |= src_span_[byte_pos] >> (8 - bit_left);
    } else {
      bit_left -= 8;
      code |= src_span_[byte_pos++] << bit_left;
      if (bit_left) {
        code |= src_span_[byte_pos] >> (8 - bit_left);
      }
    }
    src_bit_pos_ += code_len_;

    if (code < 256) {
      if (dest_byte_pos_ >= dest_buf_.size()) {
        if (!ExpandDestBuf(dest_byte_pos_ - dest_buf_.size() + 1)) {
          return false;
        }
      }

      dest_buf_[dest_byte_pos_] = static_cast<uint8_t>(code);
      dest_byte_pos_++;
      last_char = (uint8_t)code;
      if (old_code != 0xFFFFFFFF) {
        AddCode(old_code, last_char);
      }
      old_code = code;
      continue;
    }
    if (code == 256) {
      code_len_ = 9;
      current_code_ = 0;
      old_code = 0xFFFFFFFF;
      continue;
    }
    if (code == 257) {
      break;
    }

    // Case where |code| is 258 or greater.
    if (old_code == 0xFFFFFFFF) {
      return false;
    }

    DCHECK(old_code < 256 || old_code >= 258);
    stack_len_ = 0;
    if (code - 258 >= current_code_) {
      if (stack_len_ < decode_stack_.size()) {
        decode_span[stack_len_++] = last_char;
      }
      DecodeString(old_code);
    } else {
      DecodeString(code);
    }

    FX_SAFE_UINT32 safe_required_size = dest_byte_pos_;
    safe_required_size += stack_len_;
    if (!safe_required_size.IsValid()) {
      return false;
    }

    uint32_t required_size = safe_required_size.ValueOrDie();
    if (required_size > dest_buf_.size()) {
      if (!ExpandDestBuf(required_size - dest_buf_.size())) {
        return false;
      }
    }

    for (uint32_t i = 0; i < stack_len_; i++) {
      dest_buf_[dest_byte_pos_ + i] = decode_span[stack_len_ - i - 1];
    }
    dest_byte_pos_ += stack_len_;
    last_char = decode_span[stack_len_ - 1];
    if (old_code >= 258 && old_code - 258 >= current_code_) {
      break;
    }

    AddCode(old_code, last_char);
    old_code = code;
  }
  return dest_byte_pos_ != 0;
}

uint32_t EstimateFlateUncompressBufferSize(uint32_t orig_size,
                                           size_t src_size) {
  static constexpr uint32_t kMaxInitialAllocSize = 10000000;
  uint32_t guess_size =
      orig_size ? orig_size : pdfium::checked_cast<uint32_t>(src_size * 2);
  return std::min(guess_size, kMaxInitialAllocSize);
}

DataAndBytesConsumed FlateUncompress(pdfium::span<const uint8_t> src_buf,
                                     uint32_t orig_size) {
  std::unique_ptr<z_stream, FlateDeleter> context(FlateInit());
  if (!context) {
    return {DataVector<uint8_t>(), 0u};
  }

  FlateInput(context.get(), src_buf);

  const uint32_t buf_size =
      EstimateFlateUncompressBufferSize(orig_size, src_buf.size());
  uint32_t last_buf_size = buf_size;
  DataVector<uint8_t> guess_buf(buf_size);
  std::vector<DataVector<uint8_t>> result_tmp_bufs;
  {
    DataVector<uint8_t> cur_buf = std::move(guess_buf);
    while (true) {
      bool ret = FlateOutput(context.get(), cur_buf);
      uint32_t avail_buf_size = FlateGetAvailOut(context.get());
      if (!ret || avail_buf_size != 0) {
        last_buf_size = buf_size - avail_buf_size;
        result_tmp_bufs.push_back(std::move(cur_buf));
        break;
      }
      result_tmp_bufs.push_back(std::move(cur_buf));
      cur_buf = DataVector<uint8_t>(buf_size);
    }
  }

  // The TotalOut size returned from the library may not be big enough to
  // handle the content the library returns. We can only handle items
  // up to 4GB in size.
  const uint32_t dest_size = FlateGetPossiblyTruncatedTotalOut(context.get());
  const uint32_t bytes_consumed =
      FlateGetPossiblyTruncatedTotalIn(context.get());
  if (result_tmp_bufs.size() == 1) {
    CHECK_LE(dest_size, buf_size);
    result_tmp_bufs.front().resize(dest_size);
    return {std::move(result_tmp_bufs.front()), bytes_consumed};
  }

  DataVector<uint8_t> result_buf(dest_size);
  auto result_span = pdfium::span(result_buf);
  for (size_t i = 0; i < result_tmp_bufs.size(); i++) {
    DataVector<uint8_t> tmp_buf = std::move(result_tmp_bufs[i]);
    const uint32_t tmp_buf_size =
        i + 1 < result_tmp_bufs.size() ? buf_size : last_buf_size;
    size_t cp_size = std::min<size_t>(tmp_buf_size, result_span.size());
    result_span =
        fxcrt::spancpy(result_span, pdfium::span(tmp_buf).first(cp_size));
  }
  return {std::move(result_buf), bytes_consumed};
}

class FlateScanlineDecoder : public ScanlineDecoder {
 public:
  FlateScanlineDecoder(pdfium::span<const uint8_t> src_span,
                       int width,
                       int height,
                       int nComps,
                       int bpc);
  ~FlateScanlineDecoder() override;

  // ScanlineDecoder:
  [[nodiscard]] bool Rewind() override;
  pdfium::span<uint8_t> GetNextLine() override;
  uint32_t GetSrcOffset() override;

 protected:
  std::unique_ptr<z_stream, FlateDeleter> flate_;
  const pdfium::raw_span<const uint8_t> src_buf_;
  FixedSizeDataVector<uint8_t> scanline_;
};

FlateScanlineDecoder::FlateScanlineDecoder(pdfium::span<const uint8_t> src_span,
                                           int width,
                                           int height,
                                           int nComps,
                                           int bpc)
    : ScanlineDecoder(width,
                      height,
                      width,
                      height,
                      nComps,
                      bpc,
                      fxge::CalculatePitch8OrDie(bpc, nComps, width)),
      src_buf_(src_span),
      scanline_(FixedSizeDataVector<uint8_t>::Zeroed(pitch_)) {}

FlateScanlineDecoder::~FlateScanlineDecoder() {
  // Span in superclass can't outlive our buffer.
  last_scanline_ = pdfium::span<uint8_t>();
}

bool FlateScanlineDecoder::Rewind() {
  flate_.reset(FlateInit());
  if (!flate_) {
    return false;
  }

  FlateInput(flate_.get(), src_buf_);
  return true;
}

pdfium::span<uint8_t> FlateScanlineDecoder::GetNextLine() {
  FlateOutput(flate_.get(), scanline_);
  return scanline_;
}

uint32_t FlateScanlineDecoder::GetSrcOffset() {
  return FlateGetPossiblyTruncatedTotalIn(flate_.get());
}

class FlatePredictorScanlineDecoder final : public FlateScanlineDecoder {
 public:
  FlatePredictorScanlineDecoder(pdfium::span<const uint8_t> src_span,
                                int width,
                                int height,
                                int comps,
                                int bpc,
                                PredictorType predictor,
                                int Colors,
                                int BitsPerComponent,
                                int Columns);
  ~FlatePredictorScanlineDecoder() override;

  // ScanlineDecoder:
  bool Rewind() override;
  pdfium::span<uint8_t> GetNextLine() override;

 private:
  void GetNextLineWithPredictedPitch();
  void GetNextLineWithoutPredictedPitch();
  size_t CopyAndAdvanceLine(size_t bytes_to_go);

  const PredictorType predictor_;
  int colors_ = 0;
  int bits_per_component_ = 0;
  int columns_ = 0;
  uint32_t predict_pitch_ = 0;
  size_t left_over_ = 0;
  FixedSizeDataVector<uint8_t> last_line_;
  FixedSizeDataVector<uint8_t> predict_buffer_;
  FixedSizeDataVector<uint8_t> predict_raw_;
};

FlatePredictorScanlineDecoder::FlatePredictorScanlineDecoder(
    pdfium::span<const uint8_t> src_span,
    int width,
    int height,
    int comps,
    int bpc,
    PredictorType predictor,
    int Colors,
    int BitsPerComponent,
    int Columns)
    : FlateScanlineDecoder(src_span, width, height, comps, bpc),
      predictor_(predictor) {
  DCHECK(predictor_ != PredictorType::kNone);
  if (BitsPerComponent * Colors * Columns == 0) {
    BitsPerComponent = bpc_;
    Colors = comps_;
    Columns = orig_width_;
  }
  colors_ = Colors;
  bits_per_component_ = BitsPerComponent;
  columns_ = Columns;
  predict_pitch_ =
      fxge::CalculatePitch8OrDie(bits_per_component_, colors_, columns_);
  last_line_ = FixedSizeDataVector<uint8_t>::Zeroed(predict_pitch_);
  predict_buffer_ = FixedSizeDataVector<uint8_t>::Zeroed(predict_pitch_);
  predict_raw_ = FixedSizeDataVector<uint8_t>::Zeroed(predict_pitch_ + 1);
}

FlatePredictorScanlineDecoder::~FlatePredictorScanlineDecoder() {
  // Span in superclass can't outlive our buffer.
  last_scanline_ = pdfium::span<uint8_t>();
}

bool FlatePredictorScanlineDecoder::Rewind() {
  if (!FlateScanlineDecoder::Rewind()) {
    return false;
  }

  left_over_ = 0;
  return true;
}

pdfium::span<uint8_t> FlatePredictorScanlineDecoder::GetNextLine() {
  if (pitch_ == predict_pitch_) {
    GetNextLineWithPredictedPitch();
  } else {
    GetNextLineWithoutPredictedPitch();
  }
  return scanline_;
}

void FlatePredictorScanlineDecoder::GetNextLineWithPredictedPitch() {
  switch (predictor_) {
    case PredictorType::kPng: {
      const uint32_t row_size =
          fxge::CalculatePitch8OrDie(bits_per_component_, colors_, columns_);
      const uint32_t bytes_per_pixel =
          fxcrt::CeilDiv(bits_per_component_ * colors_, 8);
      FlateOutput(flate_.get(), predict_raw_);
      PngPredictLine(scanline_, predict_raw_, last_line_, row_size,
                     bytes_per_pixel);
      fxcrt::Copy(scanline_.first(predict_pitch_), last_line_.span());
      break;
    }
    case PredictorType::kTiff: {
      FlateOutput(flate_.get(), scanline_);
      TiffPredictLine(scanline_.first(predict_pitch_), bpc_, comps_,
                      output_width_);
      break;
    }
    case PredictorType::kNone: {
      NOTREACHED();
    }
  }
}

void FlatePredictorScanlineDecoder::GetNextLineWithoutPredictedPitch() {
  size_t bytes_to_go = pitch_;
  size_t read_leftover = left_over_ > bytes_to_go ? bytes_to_go : left_over_;
  if (read_leftover) {
    fxcrt::Copy(
        predict_buffer_.subspan(predict_pitch_ - left_over_, read_leftover),
        scanline_.span());
    left_over_ -= read_leftover;
    bytes_to_go -= read_leftover;
  }
  const uint32_t row_size =
      fxge::CalculatePitch8OrDie(bits_per_component_, colors_, columns_);
  const uint32_t bytes_per_pixel =
      fxcrt::CeilDiv(bits_per_component_ * colors_, 8);
  switch (predictor_) {
    case PredictorType::kPng: {
      while (bytes_to_go) {
        FlateOutput(flate_.get(), predict_raw_);
        PngPredictLine(predict_buffer_, predict_raw_, last_line_, row_size,
                       bytes_per_pixel);
        fxcrt::Copy(predict_buffer_.span(), last_line_.span());
        bytes_to_go = CopyAndAdvanceLine(bytes_to_go);
      }
      break;
    }
    case PredictorType::kTiff: {
      while (bytes_to_go) {
        FlateOutput(flate_.get(), predict_buffer_);
        TiffPredictLine(predict_buffer_, bits_per_component_, colors_,
                        columns_);
        bytes_to_go = CopyAndAdvanceLine(bytes_to_go);
      }
      break;
    }
    case PredictorType::kNone: {
      NOTREACHED();
    }
  }
}

size_t FlatePredictorScanlineDecoder::CopyAndAdvanceLine(size_t bytes_to_go) {
  size_t read_bytes = std::min<size_t>(predict_pitch_, bytes_to_go);
  fxcrt::Copy(predict_buffer_.first(read_bytes),
              scanline_.subspan(pitch_ - bytes_to_go));
  left_over_ += predict_pitch_ - read_bytes;
  return bytes_to_go - read_bytes;
}

}  // namespace

// static
std::unique_ptr<ScanlineDecoder> FlateModule::CreateDecoder(
    pdfium::span<const uint8_t> src_span,
    int width,
    int height,
    int nComps,
    int bpc,
    int predictor,
    int Colors,
    int BitsPerComponent,
    int Columns) {
  PredictorType predictor_type = GetPredictor(predictor);
  if (predictor_type == PredictorType::kNone) {
    return std::make_unique<FlateScanlineDecoder>(src_span, width, height,
                                                  nComps, bpc);
  }
  return std::make_unique<FlatePredictorScanlineDecoder>(
      src_span, width, height, nComps, bpc, predictor_type, Colors,
      BitsPerComponent, Columns);
}

// static
DataAndBytesConsumed FlateModule::FlateOrLZWDecode(
    bool bLZW,
    pdfium::span<const uint8_t> src_span,
    bool bEarlyChange,
    int predictor,
    int Colors,
    int BitsPerComponent,
    int Columns,
    uint32_t estimated_size) {
  DataVector<uint8_t> dest_buf;
  uint32_t bytes_consumed = FX_INVALID_OFFSET;

  if (bLZW) {
    auto decoder = std::make_unique<CLZWDecoder>(src_span, bEarlyChange);
    if (!decoder->Decode()) {
      return {std::move(dest_buf), bytes_consumed};
    }

    dest_buf = decoder->TakeDestBuf();
    bytes_consumed = decoder->GetSrcSize();
  } else {
    DataAndBytesConsumed result = FlateUncompress(src_span, estimated_size);
    dest_buf = std::move(result.data);
    bytes_consumed = result.bytes_consumed;
  }

  return ApplyPredictor(std::move(dest_buf), predictor, Colors,
                        BitsPerComponent, Columns, bytes_consumed);
}

// static
DataVector<uint8_t> FlateModule::Encode(pdfium::span<const uint8_t> src_span) {
  FX_SAFE_SIZE_T safe_dest_size = src_span.size();
  safe_dest_size += src_span.size() / 1000;
  safe_dest_size += 12;
  DataVector<uint8_t> dest_buf(safe_dest_size.ValueOrDie());
  size_t compressed_size = FlateCompress(src_span, dest_buf);
  dest_buf.resize(compressed_size);
  return dest_buf;
}

}  // namespace fxcodec
