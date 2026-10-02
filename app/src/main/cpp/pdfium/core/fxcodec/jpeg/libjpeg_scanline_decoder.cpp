// Copyright 2026 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxcodec/jpeg/libjpeg_scanline_decoder.h"

#include <algorithm>
#include <memory>
#include <optional>
#include <utility>

#include "core/fxcodec/jpeg/jpeg_common.h"
#include "core/fxcrt/check.h"
#include "core/fxcrt/check_op.h"
#include "core/fxcrt/compiler_specific.h"
#include "core/fxcrt/data_vector.h"
#include "core/fxcrt/fx_ceil_div.h"
#include "core/fxcrt/fx_safe_types.h"
#include "core/fxcrt/raw_span.h"

namespace fxcodec {

namespace {

constexpr size_t kKnownBadHeaderWithInvalidHeightByteOffsetStarts[] = {94, 163};

pdfium::span<const uint8_t> JpegScanSOI(pdfium::span<const uint8_t> src_span) {
  if (src_span.size() < 2) {
    return src_span;
  }

  for (size_t offset = 0; offset + 1 < src_span.size(); ++offset) {
    if (src_span[offset] == 0xff && src_span[offset + 1] == 0xd8) {
      return src_span.subspan(offset);
    }
  }
  return src_span;
}

// Returns the output dimension libjpeg produces for `dim` when decoding at
// 1/`scale_denom` scale. Matches jpeg_core_output_dimensions() for the
// power-of-two scalings (scale_denom of 1, 2, 4, 8), which reduce to
// ceil(dim / scale_denom).
uint32_t ScaledJpegSize(uint32_t dim, uint32_t scale_denom) {
  return fxcrt::CeilDiv(dim, scale_denom);
}

}  // namespace

// static
std::unique_ptr<ScanlineDecoder> LibjpegScanlineDecoder::Create(
    pdfium::span<const uint8_t> src_span,
    uint32_t width,
    uint32_t height,
    int nComps,
    bool ColorTransform,
    uint32_t scale_denom,
    bool prefer_bgr_output) {
  auto decoder = std::make_unique<LibjpegScanlineDecoder>();
  if (!decoder->CreateImpl(src_span, width, height, nComps, ColorTransform,
                           scale_denom, prefer_bgr_output)) {
    return nullptr;
  }
  return decoder;
}

LibjpegScanlineDecoder::LibjpegScanlineDecoder() = default;

LibjpegScanlineDecoder::~LibjpegScanlineDecoder() {
  if (decompress_created_) {
    jpeg_common_destroy_decompress(&common_);
  }

  // Span in superclass can't outlive our buffer.
  last_scanline_ = pdfium::span<uint8_t>();
}

bool LibjpegScanlineDecoder::InitDecode(bool bAcceptKnownBadHeader) {
  common_.cinfo.err = &common_.error_mgr;
  common_.cinfo.client_data = &common_;
  if (!jpeg_common_create_decompress(&common_)) {
    return false;
  }
  decompress_created_ = true;
  common_.cinfo.image_width = orig_width_;
  common_.cinfo.image_height = orig_height_;
  InitDecompressSrc();
  if (jpeg_common_read_header(&common_, TRUE) != JPEG_HEADER_OK) {
    std::optional<size_t> known_bad_header_offset;
    if (bAcceptKnownBadHeader) {
      for (size_t offset : kKnownBadHeaderWithInvalidHeightByteOffsetStarts) {
        if (HasKnownBadHeaderWithInvalidHeight(offset)) {
          known_bad_header_offset = offset;
          break;
        }
      }
    }
    jpeg_common_destroy_decompress(&common_);
    decompress_created_ = false;
    if (!known_bad_header_offset.has_value()) {
      return false;
    }
    PatchUpKnownBadHeaderWithInvalidHeight(known_bad_header_offset.value());
    if (!jpeg_common_create_decompress(&common_)) {
      return false;
    }
    decompress_created_ = true;
    common_.cinfo.image_width = orig_width_;
    common_.cinfo.image_height = orig_height_;
    InitDecompressSrc();
    if (jpeg_common_read_header(&common_, TRUE) != JPEG_HEADER_OK) {
      jpeg_common_destroy_decompress(&common_);
      decompress_created_ = false;
      return false;
    }
  }
  if (common_.cinfo.saw_Adobe_marker) {
    jpeg_transform_ = true;
  }

  if (common_.cinfo.num_components == 3 && !jpeg_transform_) {
    common_.cinfo.out_color_space = common_.cinfo.jpeg_color_space;
  }

#ifdef JCS_EXTENSIONS
  // When the caller asked for BGR scanlines and the output would otherwise
  // be plain 3-component RGB, have libjpeg-turbo emit the swapped order
  // directly (JCS_EXT_BGR): the same decode pipeline, storing the three
  // converted samples at exchanged offsets, so the per-sample values are
  // identical to a JCS_RGB decode. Deliberately not done when the output
  // is anything else (grayscale, CMYK, or the raw non-JCS_RGB pass-through
  // selected above), where no swap exists to fold in.
  scanlines_are_bgr_ = prefer_bgr_output_ &&
                       common_.cinfo.num_components == 3 &&
                       common_.cinfo.out_color_space == JCS_RGB;
  if (scanlines_are_bgr_) {
    common_.cinfo.out_color_space = JCS_EXT_BGR;
  }
#endif  // JCS_EXTENSIONS

  orig_width_ = common_.cinfo.image_width;
  orig_height_ = common_.cinfo.image_height;

  // Reduced-size (scale_denom) decoding collapses each 8x8 DCT block toward its
  // low-frequency content, ultimately its average at 1/8. If a dimension is not
  // a multiple of the MCU size, the encoder had to pad the partial edge blocks;
  // that padding is not required to replicate the edge (some encoders fill it
  // with black), and reduced decoding cannot crop it back out, so it would
  // contaminate the visible edge pixels (e.g. a too-dark right/bottom fringe).
  // Only decode at reduced size when there are no padded edge blocks in any
  // component, which makes the result exact regardless of the encoder. The MCU
  // size in pixels is max_samp_factor * DCTSIZE; derive it from the per-
  // component sampling factors, which are valid immediately after read_header.
  // See https://crbug.com/890745 and
  // https://github.com/libjpeg-turbo/libjpeg-turbo/issues/297.
  int max_h_samp = 1;
  int max_v_samp = 1;
  // SAFETY: libjpeg guarantees `comp_info` points to `num_components` entries
  // once the header has been read.
  auto comp_info = UNSAFE_BUFFERS(
      pdfium::span(common_.cinfo.comp_info,
                   static_cast<size_t>(common_.cinfo.num_components)));
  for (const jpeg_component_info& comp : comp_info) {
    max_h_samp = std::max(max_h_samp, comp.h_samp_factor);
    max_v_samp = std::max(max_v_samp, comp.v_samp_factor);
  }
  if (orig_width_ % (max_h_samp * DCTSIZE) != 0 ||
      orig_height_ % (max_v_samp * DCTSIZE) != 0) {
    // Reset to 1 (no reduction). This is equivalent to using
    // common_.cinfo.scale_denom, which libjpeg leaves at its default of 1 until
    // jpeg_start_decompress(); that has not run yet here.
    scale_denom_ = 1;
  }

  output_width_ = ScaledJpegSize(orig_width_, scale_denom_);
  output_height_ = ScaledJpegSize(orig_height_, scale_denom_);
  return true;
}

bool LibjpegScanlineDecoder::CreateImpl(pdfium::span<const uint8_t> src_span,
                                        uint32_t width,
                                        uint32_t height,
                                        int nComps,
                                        bool ColorTransform,
                                        uint32_t scale_denom,
                                        bool prefer_bgr_output) {
  prefer_bgr_output_ = prefer_bgr_output;
  // Only power-of-two scalings up to 1/8 are supported: ScaledJpegSize() (which
  // sizes the scanline buffer via CalcPitch()) only matches libjpeg's actual
  // output dimensions for these. A different value would desynchronize the two
  // and lead to a scanline-buffer overflow.
  DCHECK(scale_denom == 1 || scale_denom == 2 || scale_denom == 4 ||
         scale_denom == 8);
  scale_denom_ = scale_denom;
  src_span_ = JpegScanSOI(src_span);
  if (src_span_.size() < 2) {
    return false;
  }

  PatchUpTrailer();

  common_.error_mgr.error_exit = jpeg_common_error_fatal;
  common_.error_mgr.emit_message = jpeg_common_error_do_nothing_int;
  common_.error_mgr.output_message = jpeg_common_error_do_nothing;
  common_.error_mgr.format_message = jpeg_common_error_do_nothing_char;
  common_.error_mgr.reset_error_mgr = jpeg_common_error_do_nothing;
  common_.source_mgr.init_source = jpeg_common_src_do_nothing;
  common_.source_mgr.term_source = jpeg_common_src_do_nothing;
  common_.source_mgr.skip_input_data = jpeg_common_src_skip_data_or_trap;
  common_.source_mgr.fill_input_buffer = jpeg_common_src_fill_buffer;
  common_.source_mgr.resync_to_restart = jpeg_common_src_resync;
  jpeg_transform_ = ColorTransform;
  output_width_ = orig_width_ = width;
  output_height_ = orig_height_ = height;
  if (!InitDecode(/*bAcceptKnownBadHeader=*/true)) {
    return false;
  }

  if (common_.cinfo.num_components < nComps) {
    return false;
  }

  if (common_.cinfo.image_width < width) {
    return false;
  }

  CalcPitch();
  scanline_buf_ = DataVector<uint8_t>(pitch_);
  comps_ = common_.cinfo.num_components;
  bpc_ = 8;
  started_ = false;
  return true;
}

bool LibjpegScanlineDecoder::Rewind() {
  if (started_) {
    jpeg_common_destroy_decompress(&common_);
    if (!InitDecode(/*bAcceptKnownBadHeader=*/false)) {
      return false;
    }
  }
  // `CreateImpl()` validated `scale_denom_` and, for non-MCU-aligned images,
  // reset it to 1 in `InitDecode()` (called from `CreateImpl()`, and again just
  // above when re-decoding), so it is safe to use as-is here.
  common_.cinfo.scale_denom = scale_denom_;
  output_width_ = ScaledJpegSize(orig_width_, scale_denom_);
  output_height_ = ScaledJpegSize(orig_height_, scale_denom_);
  if (!jpeg_common_start_decompress(&common_)) {
    jpeg_common_destroy_decompress(&common_);
    return false;
  }
  // `CalcPitch()` sized the scanline buffer for `output_width_` columns,
  // so libjpeg must not produce more than that per scanline.
  CHECK_LE(static_cast<int>(common_.cinfo.output_width), output_width_);
  started_ = true;
  return true;
}

pdfium::span<uint8_t> LibjpegScanlineDecoder::GetNextLine() {
  uint8_t* row_array[] = {scanline_buf_.data()};
  int nlines = jpeg_common_read_scanlines(&common_, row_array, 1u);
  if (nlines <= 0) {
    return pdfium::span<uint8_t>();
  }
  return scanline_buf_;
}

uint32_t LibjpegScanlineDecoder::GetSrcOffset() {
  return static_cast<uint32_t>(src_span_.size() -
                               common_.source_mgr.bytes_in_buffer);
}

void LibjpegScanlineDecoder::CalcPitch() {
  // `output_width_` must be finalized (post scale/alignment decision in
  // `InitDecode()`) before this runs; the sole caller is `CreateImpl()`, after
  // `InitDecode()`.
  DCHECK_GT(output_width_, 0);
  pitch_ = static_cast<uint32_t>(output_width_) * common_.cinfo.num_components;
  pitch_ = fxcrt::CeilDiv(pitch_, 4) * 4;
}

void LibjpegScanlineDecoder::InitDecompressSrc() {
  common_.cinfo.src = &common_.source_mgr;
  common_.source_mgr.bytes_in_buffer = src_span_.size();
  common_.source_mgr.next_input_byte = src_span_.data();
}

bool LibjpegScanlineDecoder::HasKnownBadHeaderWithInvalidHeight(
    size_t dimension_offset) const {
  // Perform lots of possibly redundant checks to make sure this has no false
  // positives.
  bool bDimensionChecks =
      common_.cinfo.err->msg_code == JERR_IMAGE_TOO_BIG &&
      common_.cinfo.image_width < JPEG_MAX_DIMENSION &&
      common_.cinfo.image_height == 0xffff && orig_width_ > 0 &&
      orig_width_ <= JPEG_MAX_DIMENSION && orig_height_ > 0 &&
      orig_height_ <= JPEG_MAX_DIMENSION;
  if (!bDimensionChecks) {
    return false;
  }

  if (src_span_.size() <= dimension_offset + 3u) {
    return false;
  }

  if (!IsSofSegment(dimension_offset - kSofMarkerByteOffset)) {
    return false;
  }

  const auto pHeaderDimensions = src_span_.subspan(dimension_offset);
  uint8_t nExpectedWidthByte1 = (orig_width_ >> 8) & 0xff;
  uint8_t nExpectedWidthByte2 = orig_width_ & 0xff;
  // Height high byte, height low byte, width high byte, width low byte.
  return pHeaderDimensions[0] == 0xff && pHeaderDimensions[1] == 0xff &&
         pHeaderDimensions[2] == nExpectedWidthByte1 &&
         pHeaderDimensions[3] == nExpectedWidthByte2;
}

bool LibjpegScanlineDecoder::IsSofSegment(size_t marker_offset) const {
  const auto pHeaderMarker = src_span_.subspan(marker_offset);
  return pHeaderMarker[0] == 0xff && pHeaderMarker[1] >= 0xc0 &&
         pHeaderMarker[1] <= 0xcf;
}

void LibjpegScanlineDecoder::PatchUpKnownBadHeaderWithInvalidHeight(
    size_t dimension_offset) {
  DCHECK(src_span_.size() > dimension_offset + 1u);
  auto pData = GetWritableSrcData().subspan(dimension_offset);
  pData[0] = (orig_height_ >> 8) & 0xff;
  pData[1] = orig_height_ & 0xff;
}

void LibjpegScanlineDecoder::PatchUpTrailer() {
  auto pData = GetWritableSrcData();
  pData[src_span_.size() - 2] = 0xff;
  pData[src_span_.size() - 1] = 0xd9;
}

pdfium::span<uint8_t> LibjpegScanlineDecoder::GetWritableSrcData() {
  // SAFETY: const_cast<> doesn't change size.
  return UNSAFE_BUFFERS(
      pdfium::span(const_cast<uint8_t*>(src_span_.data()), src_span_.size()));
}

// static
std::optional<JpegModule::ImageInfo> LibjpegScanlineDecoder::LoadInfo(
    pdfium::span<const uint8_t> src_span) {
  src_span = JpegScanSOI(src_span);

  JpegCommon jpeg_common = {};
  jpeg_common.error_mgr.error_exit = jpeg_common_error_fatal;
  jpeg_common.error_mgr.emit_message = jpeg_common_error_do_nothing_int;
  jpeg_common.error_mgr.output_message = jpeg_common_error_do_nothing;
  jpeg_common.error_mgr.format_message = jpeg_common_error_do_nothing_char;
  jpeg_common.error_mgr.reset_error_mgr = jpeg_common_error_do_nothing;
  jpeg_common.error_mgr.trace_level = 0;
  jpeg_common.cinfo.err = &jpeg_common.error_mgr;
  jpeg_common.cinfo.client_data = &jpeg_common;
  if (!jpeg_common_create_decompress(&jpeg_common)) {
    return std::nullopt;
  }

  jpeg_common.source_mgr.init_source = jpeg_common_src_do_nothing;
  jpeg_common.source_mgr.term_source = jpeg_common_src_do_nothing;
  jpeg_common.source_mgr.skip_input_data = jpeg_common_src_skip_data_or_trap;
  jpeg_common.source_mgr.fill_input_buffer = jpeg_common_src_fill_buffer;
  jpeg_common.source_mgr.resync_to_restart = jpeg_common_src_resync;
  jpeg_common.source_mgr.bytes_in_buffer = src_span.size();
  jpeg_common.source_mgr.next_input_byte = src_span.data();
  jpeg_common.cinfo.src = &jpeg_common.source_mgr;
  if (jpeg_common_read_header(&jpeg_common, TRUE) != JPEG_HEADER_OK) {
    jpeg_common_destroy_decompress(&jpeg_common);
    return std::nullopt;
  }

  JpegModule::ImageInfo info;
  info.width = jpeg_common.cinfo.image_width;
  info.height = jpeg_common.cinfo.image_height;
  info.num_components = jpeg_common.cinfo.num_components;
  info.color_transform = jpeg_common.cinfo.jpeg_color_space == JCS_YCbCr ||
                         jpeg_common.cinfo.jpeg_color_space == JCS_YCCK;
  info.bits_per_components = jpeg_common.cinfo.data_precision;
  jpeg_common_destroy_decompress(&jpeg_common);
  return info;
}

}  // namespace fxcodec
