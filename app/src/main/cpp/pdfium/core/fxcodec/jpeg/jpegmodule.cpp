// Copyright 2026 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxcodec/jpeg/jpegmodule.h"

#include <memory>
#include <optional>

#include "build/build_config.h"
#include "core/fxcodec/scanlinedecoder.h"
#include "core/fxcrt/span.h"

#if defined(PDF_ENABLE_RUST_JPEG)
#include "core/fxcodec/jpeg/rust_jpeg_scanline_decoder.h"
#else
#include "core/fxcodec/jpeg/libjpeg_scanline_decoder.h"
#endif

#if BUILDFLAG(IS_WIN)
#include "core/fxcodec/jpeg/jpeg_common.h"
#include "core/fxcrt/compiler_specific.h"
#include "core/fxcrt/fx_memory.h"
#include "core/fxcrt/fx_memory_wrappers.h"
#include "core/fxcrt/fx_safe_types.h"
#include "core/fxcrt/numerics/safe_conversions.h"
#include "core/fxcrt/retain_ptr.h"
#include "core/fxge/dib/cfx_dibbase.h"
#include "core/fxge/dib/fx_dib.h"
#endif  // BUILDFLAG(IS_WIN)

namespace fxcodec {

#if BUILDFLAG(IS_WIN)
namespace {

struct ScopedJpegCompressCommon {
  ~ScopedJpegCompressCommon() {
    if (created) {
      jpeg_common_destroy_compress(&common);
    }
  }
  JpegCompressCommon common = {};
  bool created = false;
};

}  // namespace
#endif  // BUILDFLAG(IS_WIN)

// static
std::unique_ptr<ScanlineDecoder> JpegModule::CreateDecoder(
    pdfium::span<const uint8_t> src_span,
    uint32_t width,
    uint32_t height,
    int nComps,
    bool ColorTransform,
    uint32_t scale_denom,
    bool prefer_bgr_output) {
#if defined(PDF_ENABLE_RUST_JPEG)
  // RustJpegScanlineDecoder does not implement `prefer_bgr_output`; it never
  // reports ScanlinesAreBgr(), so callers translate as usual.
  return RustJpegScanlineDecoder::Create(src_span, width, height, nComps,
                                         ColorTransform, scale_denom);
#else
  return LibjpegScanlineDecoder::Create(src_span, width, height, nComps,
                                        ColorTransform, scale_denom,
                                        prefer_bgr_output);
#endif
}

// static
std::optional<JpegModule::ImageInfo> JpegModule::LoadInfo(
    pdfium::span<const uint8_t> src_span) {
#if defined(PDF_ENABLE_RUST_JPEG)
  return RustJpegScanlineDecoder::LoadInfo(src_span);
#else
  return LibjpegScanlineDecoder::LoadInfo(src_span);
#endif
}

#if BUILDFLAG(IS_WIN)
bool JpegModule::JpegEncode(const RetainPtr<const CFX_DIBBase>& pSource,
                            uint8_t** dest_buf,
                            size_t* dest_size) {
  const int bytes_per_pixel = pSource->GetBPP() / 8;
  uint32_t nComponents = bytes_per_pixel >= 3 ? 3 : 1;
  uint32_t pitch = pSource->GetPitch();
  uint32_t width = pdfium::checked_cast<uint32_t>(pSource->GetWidth());
  uint32_t height = pdfium::checked_cast<uint32_t>(pSource->GetHeight());
  FX_SAFE_UINT32 safe_buf_len = width;
  safe_buf_len *= height;
  safe_buf_len *= nComponents;
  safe_buf_len += 1024;
  if (!safe_buf_len.IsValid()) {
    return false;
  }

  static constexpr uint32_t kMinTryBufLen = 1024;
  uint32_t dest_buf_length = safe_buf_len.ValueOrDie();
  std::unique_ptr<uint8_t, FxFreeDeleter> local_dest;
  for (; dest_buf_length >= kMinTryBufLen; dest_buf_length >>= 1) {
    local_dest.reset(FX_TryAlloc(uint8_t, dest_buf_length));
    if (local_dest) {
      break;
    }
  }
  if (!local_dest) {
    return false;
  }

  ScopedJpegCompressCommon compress_common;
  compress_common.created =
      jpeg_common_create_compress(&compress_common.common);
  if (!compress_common.created) {
    return false;
  }

  compress_common.common.dest_mgr.next_output_byte = local_dest.get();
  compress_common.common.dest_mgr.free_in_buffer = dest_buf_length;
  compress_common.common.cinfo.image_width = width;
  compress_common.common.cinfo.image_height = height;
  compress_common.common.cinfo.input_components = nComponents;
  if (nComponents == 1) {
    compress_common.common.cinfo.in_color_space = JCS_GRAYSCALE;
  } else if (nComponents == 3) {
    compress_common.common.cinfo.in_color_space = JCS_RGB;
  } else {
    compress_common.common.cinfo.in_color_space = JCS_CMYK;
  }

  std::unique_ptr<uint8_t, FxFreeDeleter> line_buf;
  if (nComponents > 1) {
    line_buf.reset(FX_Alloc2D(uint8_t, width, nComponents));
  }

  if (!jpeg_common_set_defaults(&compress_common.common) ||
      !jpeg_common_start_compress(&compress_common.common, TRUE)) {
    return false;
  }

  JSAMPROW row_pointer;
  JDIMENSION row;
  while (compress_common.common.cinfo.next_scanline <
         compress_common.common.cinfo.image_height) {
    pdfium::span<const uint8_t> src_scan =
        pSource->GetScanline(compress_common.common.cinfo.next_scanline);
    if (nComponents > 1) {
      uint8_t* dest_scan = line_buf.get();
      if (nComponents == 3) {
        UNSAFE_TODO({
          for (uint32_t i = 0; i < width; i++) {
            ReverseCopy3Bytes(dest_scan, src_scan.data());
            dest_scan += 3;
            src_scan = src_scan.subspan(static_cast<size_t>(bytes_per_pixel));
          }
        });
      } else {
        UNSAFE_TODO({
          for (uint32_t i = 0; i < pitch; i++) {
            *dest_scan++ = ~src_scan.front();
            src_scan = src_scan.subspan<1u>();
          }
        });
      }
      row_pointer = line_buf.get();
    } else {
      row_pointer = const_cast<uint8_t*>(src_scan.data());
    }
    row = compress_common.common.cinfo.next_scanline;
    if (jpeg_common_write_scanlines(&compress_common.common, &row_pointer, 1) <
        0) {
      return false;
    }
    UNSAFE_TODO({
      if (compress_common.common.cinfo.next_scanline == row) {
        static constexpr size_t kJpegBlockSize = 1048576;
        local_dest.reset(FX_Realloc(uint8_t, local_dest.release(),
                                    dest_buf_length + kJpegBlockSize));
        compress_common.common.dest_mgr.next_output_byte =
            local_dest.get() + dest_buf_length -
            compress_common.common.dest_mgr.free_in_buffer;
        dest_buf_length += kJpegBlockSize;
        compress_common.common.dest_mgr.free_in_buffer += kJpegBlockSize;
      }
    });
  }
  if (!jpeg_common_finish_compress(&compress_common.common)) {
    return false;
  }

  *dest_size =
      dest_buf_length -
      static_cast<size_t>(compress_common.common.dest_mgr.free_in_buffer);
  *dest_buf = local_dest.release();
  return true;
}
#endif  // BUILDFLAG(IS_WIN)

}  // namespace fxcodec
