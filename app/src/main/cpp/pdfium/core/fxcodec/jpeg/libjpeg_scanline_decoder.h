// Copyright 2026 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CORE_FXCODEC_JPEG_LIBJPEG_SCANLINE_DECODER_H_
#define CORE_FXCODEC_JPEG_LIBJPEG_SCANLINE_DECODER_H_

#include <stddef.h>
#include <stdint.h>

#include <memory>
#include <optional>

#include "core/fxcodec/jpeg/jpeg_common.h"
#include "core/fxcodec/jpeg/jpegmodule.h"
#include "core/fxcodec/scanlinedecoder.h"
#include "core/fxcrt/data_vector.h"
#include "core/fxcrt/raw_span.h"
#include "core/fxcrt/span.h"

namespace fxcodec {

class LibjpegScanlineDecoder final : public ScanlineDecoder {
 public:
  static std::unique_ptr<ScanlineDecoder> Create(
      pdfium::span<const uint8_t> src_span,
      uint32_t width,
      uint32_t height,
      int nComps,
      bool ColorTransform,
      uint32_t scale_denom,
      bool prefer_bgr_output);

  static std::optional<JpegModule::ImageInfo> LoadInfo(
      pdfium::span<const uint8_t> src_span);

  LibjpegScanlineDecoder();
  ~LibjpegScanlineDecoder() override;

  // ScanlineDecoder:
  [[nodiscard]] bool Rewind() override;
  pdfium::span<uint8_t> GetNextLine() override;
  uint32_t GetSrcOffset() override;

 private:
  bool CreateImpl(pdfium::span<const uint8_t> src_span,
                  uint32_t width,
                  uint32_t height,
                  int nComps,
                  bool ColorTransform,
                  uint32_t scale_denom,
                  bool prefer_bgr_output);
  bool InitDecode(bool bAcceptKnownBadHeader);
  void CalcPitch();
  void InitDecompressSrc();
  bool HasKnownBadHeaderWithInvalidHeight(size_t dimension_offset) const;
  bool IsSofSegment(size_t marker_offset) const;
  void PatchUpKnownBadHeaderWithInvalidHeight(size_t dimension_offset);
  // Patch up the JPEG trailer, even if it is correct.
  void PatchUpTrailer();
  pdfium::span<uint8_t> GetWritableSrcData();

  // For a given invalid height byte offset in
  // |kKnownBadHeaderWithInvalidHeightByteOffsetStarts|, the SOFn marker should
  // be this many bytes before that.
  static constexpr size_t kSofMarkerByteOffset = 5;

  JpegCommon common_ = {};
  pdfium::raw_span<const uint8_t> src_span_;
  DataVector<uint8_t> scanline_buf_;
  bool decompress_created_ = false;
  bool started_ = false;
  bool jpeg_transform_ = false;
  bool prefer_bgr_output_ = false;
  uint32_t scale_denom_ = 1;
};

}  // namespace fxcodec

#endif  // CORE_FXCODEC_JPEG_LIBJPEG_SCANLINE_DECODER_H_
