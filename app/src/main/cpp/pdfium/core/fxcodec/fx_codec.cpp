// Copyright 2014 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxcodec/fx_codec.h"

#include <utility>

#include "core/fxcrt/numerics/safe_conversions.h"
#include "core/fxcrt/span_util.h"
#include "core/fxcrt/zip.h"
#include "core/fxge/dib/fx_dib.h"

#if BUILDFLAG(IS_WIN)
#include "core/fxcodec/basic/basicmodule.h"
#include "core/fxcodec/fax/faxmodule.h"
#include "core/fxcodec/flate/flatemodule.h"
#include "core/fxcodec/jpeg/jpegmodule.h"
#include "core/fxge/cfx_gemodule.h"
#endif

#if BUILDFLAG(IS_WIN)
namespace {

constexpr EncoderIface kEncoderIface = {
    BasicModule::A85Encode, FaxModule::FaxEncode, FlateModule::Encode,
    JpegModule::JpegEncode, BasicModule::RunLengthEncode};

}  // namespace
#endif

namespace fxcodec {

#ifdef PDF_ENABLE_XFA
CFX_DIBAttribute::CFX_DIBAttribute() = default;

CFX_DIBAttribute::~CFX_DIBAttribute() = default;
#endif  // PDF_ENABLE_XFA

void ReverseRGB(pdfium::span<uint8_t> pDestBuf,
                pdfium::span<const uint8_t> pSrcBuf,
                int pixels) {
  const size_t count = pdfium::checked_cast<size_t>(pixels);
  auto dst_span =
      fxcrt::reinterpret_span<FX_RGB_STRUCT<uint8_t>>(pDestBuf).first(count);

  const auto src_span =
      fxcrt::reinterpret_span<const FX_RGB_STRUCT<uint8_t>>(pSrcBuf).first(
          count);

  if (dst_span.data() == src_span.data()) {
    for (auto& pix : dst_span) {
      std::swap(pix.red, pix.blue);
    }
    return;
  }
  for (auto [src_pix, dst_pix] : fxcrt::Zip(src_span, dst_span)) {
    // Compiler can't prove `src_span` and `dst_span` aren't aliased, so use
    // locals to avoid interleaved loads/stores.
    const uint8_t blue = src_pix.blue;
    const uint8_t green = src_pix.green;
    const uint8_t red = src_pix.red;
    dst_pix.red = blue;
    dst_pix.green = green;
    dst_pix.blue = red;
  }
}

void RegisterEncoders() {
#if BUILDFLAG(IS_WIN)
  CFX_GEModule::Get()->SetEncoderIface(&kEncoderIface);
#endif
}

}  // namespace fxcodec
