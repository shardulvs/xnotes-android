// Copyright 2015 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxcodec/jbig2/jbig2_grd_proc.h"

#include <array>
#include <functional>
#include <memory>
#include <utility>

#include "core/fxcodec/fax/faxmodule.h"
#include "core/fxcodec/jbig2/jbig2_arith_decoder.h"
#include "core/fxcodec/jbig2/jbig2_bit_stream.h"
#include "core/fxcodec/jbig2/jbig2_image.h"
#include "core/fxcrt/check_op.h"
#include "core/fxcrt/pauseindicator_iface.h"
#include "core/fxcrt/zip.h"

namespace {

// TODO(npm): Name this constants better or merge some together.
constexpr std::array<const uint16_t, 3> kOptConstant1 = {
    {0x9b25, 0x0795, 0x00e5}};
constexpr std::array<const uint16_t, 3> kOptConstant2 = {
    {0x0006, 0x0004, 0x0001}};
constexpr std::array<const uint16_t, 3> kOptConstant3 = {
    {0xf800, 0x1e00, 0x0380}};
constexpr std::array<const uint16_t, 3> kOptConstant4 = {
    {0x0000, 0x0001, 0x0003}};
constexpr std::array<const uint16_t, 3> kOptConstant5 = {
    {0x07f0, 0x01f8, 0x007c}};
constexpr std::array<const uint16_t, 3> kOptConstant6 = {
    {0x7bf7, 0x0efb, 0x01bd}};
constexpr std::array<const uint16_t, 3> kOptConstant7 = {
    {0x0800, 0x0200, 0x0080}};
constexpr std::array<const uint16_t, 3> kOptConstant8 = {
    {0x0010, 0x0008, 0x0004}};
constexpr std::array<const uint16_t, 3> kOptConstant9 = {
    {0x000c, 0x0009, 0x0007}};
constexpr std::array<const uint16_t, 3> kOptConstant10 = {
    {0x0007, 0x000f, 0x0007}};
constexpr std::array<const uint16_t, 3> kOptConstant11 = {
    {0x001f, 0x001f, 0x000f}};
constexpr std::array<const uint16_t, 3> kOptConstant12 = {
    {0x000f, 0x0007, 0x0003}};

struct LineLayout {
  uint32_t full_bytes;
  // Range of values is [1, 8].
  uint32_t last_byte_bits;
};

LineLayout GetLineLayout(uint32_t width) {
  CHECK_GT(width, 0u);
  // Note that this deliberately represents 8 bits as:
  // full_bytes = 0 and last_byte_bits = 8.
  return {.full_bytes = (width - 1) / 8,
          .last_byte_bits = ((width - 1) & 7) + 1};
}

}  // namespace

CJBig2_GRDProc::ProgressiveArithDecodeState::ProgressiveArithDecodeState() =
    default;

CJBig2_GRDProc::ProgressiveArithDecodeState::~ProgressiveArithDecodeState() =
    default;

CJBig2_GRDProc::CJBig2_GRDProc() = default;

CJBig2_GRDProc::~CJBig2_GRDProc() = default;

bool CJBig2_GRDProc::UseTemplate0Opt3() const {
  return (GBAT[0] == 3) && (GBAT[1] == -1) && (GBAT[2] == -3) &&
         (GBAT[3] == -1) && (GBAT[4] == 2) && (GBAT[5] == -2) &&
         (GBAT[6] == -2) && (GBAT[7] == -2) && !USESKIP;
}

bool CJBig2_GRDProc::UseTemplate1Opt3() const {
  return (GBAT[0] == 3) && (GBAT[1] == -1) && !USESKIP;
}

bool CJBig2_GRDProc::UseTemplate23Opt3() const {
  return (GBAT[0] == 2) && (GBAT[1] == -1) && !USESKIP;
}

std::unique_ptr<CJBig2_Image> CJBig2_GRDProc::DecodeArith(
    CJBig2_ArithDecoder* pArithDecoder,
    pdfium::span<JBig2ArithCtx> gbContexts) {
  if (!CJBig2_Image::IsValidImageSize(GBW, GBH)) {
    return std::make_unique<CJBig2_Image>(GBW, GBH);
  }

  switch (GBTEMPLATE) {
    case 0:
      return UseTemplate0Opt3()
                 ? DecodeArithOpt3<0>(pArithDecoder, gbContexts)
                 : DecodeArithTemplateUnopt<0>(pArithDecoder, gbContexts);
    case 1:
      return UseTemplate1Opt3()
                 ? DecodeArithOpt3<1>(pArithDecoder, gbContexts)
                 : DecodeArithTemplateUnopt<1>(pArithDecoder, gbContexts);
    case 2:
      return UseTemplate23Opt3()
                 ? DecodeArithOpt3<2>(pArithDecoder, gbContexts)
                 : DecodeArithTemplateUnopt<2>(pArithDecoder, gbContexts);
    default:
      return UseTemplate23Opt3()
                 ? DecodeArithTemplate3Opt3(pArithDecoder, gbContexts)
                 : DecodeArithTemplate3Unopt(pArithDecoder, gbContexts);
  }
}

template <int OPT>
std::unique_ptr<CJBig2_Image> CJBig2_GRDProc::DecodeArithOpt3(
    CJBig2_ArithDecoder* pArithDecoder,
    pdfium::span<JBig2ArithCtx> gbContexts) {
  auto GBREG = std::make_unique<CJBig2_Image>(GBW, GBH);
  if (!GBREG->has_data()) {
    return nullptr;
  }

  int LTP = 0;
  const LineLayout layout = GetLineLayout(GBW);
  // TODO(npm): Why is the height only trimmed when OPT is 0?
  const uint32_t height = OPT == 0 ? GBH & 0x7fffffff : GBH;
  pdfium::span<uint8_t> row_write;
  pdfium::span<const uint8_t> row_prev1;
  pdfium::span<const uint8_t> row_prev2;
  for (uint32_t h = 0; h < height; ++h) {
    row_prev2 = row_prev1;
    row_prev1 = row_write;
    row_write = GBREG->GetLine(h);
    if (TPGDON) {
      if (pArithDecoder->IsComplete()) {
        return nullptr;
      }

      LTP = LTP ^ pArithDecoder->Decode(&gbContexts[kOptConstant1[OPT]]);
      if (LTP) {
        GBREG->CopyLine(row_write, row_prev1);
        continue;
      }
    }

    if (h < 2) {
      const bool is_second_line = h == 1;
      uint32_t val_prev = 0;
      if (is_second_line) {
        val_prev = row_prev1[0];
      }
      uint32_t CONTEXT =
          ((val_prev >> kOptConstant4[OPT]) & kOptConstant5[OPT]);
      for (uint32_t cc = 0; cc < layout.full_bytes; ++cc) {
        if (is_second_line) {
          val_prev = (val_prev << 8) | row_prev1[cc + 1];
        }
        uint8_t cVal = 0;
        for (int32_t k = 7; k >= 0; --k) {
          if (pArithDecoder->IsComplete()) {
            return nullptr;
          }

          int bVal = pArithDecoder->Decode(&gbContexts[CONTEXT]);
          cVal |= bVal << k;
          CONTEXT =
              (((CONTEXT & kOptConstant6[OPT]) << 1) | bVal |
               ((val_prev >> (k + kOptConstant4[OPT])) & kOptConstant8[OPT]));
        }
        row_write[cc] = cVal;
      }
      val_prev <<= 8;
      uint8_t cVal1 = 0;
      for (uint32_t k = 0; k < layout.last_byte_bits; ++k) {
        if (pArithDecoder->IsComplete()) {
          return nullptr;
        }

        int bVal = pArithDecoder->Decode(&gbContexts[CONTEXT]);
        cVal1 |= bVal << (7 - k);
        CONTEXT = (((CONTEXT & kOptConstant6[OPT]) << 1) | bVal |
                   (((val_prev >> (7 + kOptConstant4[OPT] - k))) &
                    kOptConstant8[OPT]));
      }
      row_write[layout.full_bytes] = cVal1;
      continue;
    }

    uint32_t val_prev2 = row_prev2[0] << kOptConstant2[OPT];
    uint32_t val_prev1 = row_prev1[0];
    uint32_t CONTEXT = (val_prev2 & kOptConstant3[OPT]) |
                       ((val_prev1 >> kOptConstant4[OPT]) & kOptConstant5[OPT]);
    auto row_write_zip = row_write.first(layout.full_bytes);
    auto row_prev1_zip = row_prev1.subspan<1u>();
    auto row_prev2_zip = row_prev2.subspan<1u>();
    for (auto [elem_write, elem_prev1, elem_prev2] :
         fxcrt::Zip(row_write_zip, row_prev1_zip, row_prev2_zip)) {
      val_prev2 = (val_prev2 << 8) | (elem_prev2 << kOptConstant2[OPT]);
      val_prev1 = (val_prev1 << 8) | elem_prev1;
      uint8_t cVal = 0;
      for (int32_t k = 7; k >= 0; --k) {
        if (pArithDecoder->IsComplete()) {
          return nullptr;
        }

        int bVal = pArithDecoder->Decode(&gbContexts[CONTEXT]);
        cVal |= bVal << k;
        CONTEXT =
            (((CONTEXT & kOptConstant6[OPT]) << 1) | bVal |
             ((val_prev2 >> k) & kOptConstant7[OPT]) |
             ((val_prev1 >> (k + kOptConstant4[OPT])) & kOptConstant8[OPT]));
      }
      elem_write = cVal;
    }
    val_prev2 <<= 8;
    val_prev1 <<= 8;
    uint8_t cVal1 = 0;
    for (uint32_t k = 0; k < layout.last_byte_bits; ++k) {
      if (pArithDecoder->IsComplete()) {
        return nullptr;
      }

      int bVal = pArithDecoder->Decode(&gbContexts[CONTEXT]);
      cVal1 |= bVal << (7 - k);
      CONTEXT =
          (((CONTEXT & kOptConstant6[OPT]) << 1) | bVal |
           ((val_prev2 >> (7 - k)) & kOptConstant7[OPT]) |
           ((val_prev1 >> (7 + kOptConstant4[OPT] - k)) & kOptConstant8[OPT]));
    }
    row_write[layout.full_bytes] = cVal1;
  }
  return GBREG;
}

template <int UNOPT>
std::unique_ptr<CJBig2_Image> CJBig2_GRDProc::DecodeArithTemplateUnopt(
    CJBig2_ArithDecoder* pArithDecoder,
    pdfium::span<JBig2ArithCtx> gbContexts) {
  auto GBREG = std::make_unique<CJBig2_Image>(GBW, GBH);
  if (!GBREG->has_data()) {
    return nullptr;
  }

  GBREG->Fill(false);
  int LTP = 0;
  const uint8_t MOD2 = UNOPT % 2;
  const uint8_t DIV2 = UNOPT / 2;
  const uint8_t SHIFT = 4 - UNOPT;
  pdfium::span<uint8_t> row_write;
  pdfium::span<const uint8_t> row_prev1;
  pdfium::span<const uint8_t> row_prev2;
  for (uint32_t h = 0; h < GBH; h++) {
    row_prev2 = row_prev1;
    row_prev1 = row_write;
    row_write = GBREG->GetLine(h);
    if (TPGDON) {
      if (pArithDecoder->IsComplete()) {
        return nullptr;
      }

      LTP = LTP ^ pArithDecoder->Decode(&gbContexts[kOptConstant1[UNOPT]]);
      if (LTP) {
        GBREG->CopyLine(row_write, row_prev1);
        continue;
      }
    }

    pdfium::span<const uint8_t> row_skip;
    if (USESKIP) {
      row_skip = SKIP->GetLine(h);
    }
    pdfium::span<const uint8_t> row_gbat0 = GBREG->GetLine(h + GBAT[1]);
    pdfium::span<const uint8_t> row_gbat1;
    pdfium::span<const uint8_t> row_gbat2;
    pdfium::span<const uint8_t> row_gbat3;
    if (UNOPT == 0) {
      row_gbat1 = GBREG->GetLine(h + GBAT[3]);
      row_gbat2 = GBREG->GetLine(h + GBAT[5]);
      row_gbat3 = GBREG->GetLine(h + GBAT[7]);
    }

    uint32_t val_prev2 = GBREG->GetPixel(1 + MOD2, row_prev2);
    val_prev2 |= GBREG->GetPixel(MOD2, row_prev2) << 1;
    if (UNOPT == 1) {
      val_prev2 |= GBREG->GetPixel(0, row_prev2) << 2;
    }
    uint32_t val_prev1 = GBREG->GetPixel(2 - DIV2, row_prev1);
    val_prev1 |= GBREG->GetPixel(1 - DIV2, row_prev1) << 1;
    if (UNOPT < 2) {
      val_prev1 |= GBREG->GetPixel(0, row_prev1) << 2;
    }
    uint32_t val_current = 0;
    for (uint32_t w = 0; w < GBW; w++) {
      int bVal = 0;
      if (!USESKIP || !SKIP->GetPixel(w, row_skip)) {
        if (pArithDecoder->IsComplete()) {
          return nullptr;
        }

        uint32_t CONTEXT = val_current;
        CONTEXT |= GBREG->GetPixel(w + GBAT[0], row_gbat0) << SHIFT;
        CONTEXT |= val_prev1 << (SHIFT + 1);
        CONTEXT |= val_prev2 << kOptConstant9[UNOPT];
        if (UNOPT == 0) {
          CONTEXT |= GBREG->GetPixel(w + GBAT[2], row_gbat1) << 10;
          CONTEXT |= GBREG->GetPixel(w + GBAT[4], row_gbat2) << 11;
          CONTEXT |= GBREG->GetPixel(w + GBAT[6], row_gbat3) << 15;
        }
        bVal = pArithDecoder->Decode(&gbContexts[CONTEXT]);
        if (bVal) {
          GBREG->SetPixel(w, row_write, bVal);
        }
      }
      val_prev2 =
          ((val_prev2 << 1) | GBREG->GetPixel(w + 2 + MOD2, row_prev2)) &
          kOptConstant10[UNOPT];
      val_prev1 =
          ((val_prev1 << 1) | GBREG->GetPixel(w + 3 - DIV2, row_prev1)) &
          kOptConstant11[UNOPT];
      val_current = ((val_current << 1) | bVal) & kOptConstant12[UNOPT];
    }
  }
  return GBREG;
}

std::unique_ptr<CJBig2_Image> CJBig2_GRDProc::DecodeArithTemplate3Opt3(
    CJBig2_ArithDecoder* pArithDecoder,
    pdfium::span<JBig2ArithCtx> gbContexts) {
  auto GBREG = std::make_unique<CJBig2_Image>(GBW, GBH);
  if (!GBREG->has_data()) {
    return nullptr;
  }

  int LTP = 0;
  const LineLayout layout = GetLineLayout(GBW);
  pdfium::span<uint8_t> row_write;
  pdfium::span<const uint8_t> row_prev;
  for (uint32_t h = 0; h < GBH; h++) {
    row_prev = row_write;
    row_write = GBREG->GetLine(h);
    if (TPGDON) {
      if (pArithDecoder->IsComplete()) {
        return nullptr;
      }

      LTP = LTP ^ pArithDecoder->Decode(&gbContexts[0x0195]);
      if (LTP) {
        GBREG->CopyLine(row_write, row_prev);
        continue;
      }
    }

    if (h == 0) {
      uint32_t CONTEXT = 0;
      for (uint32_t cc = 0; cc < layout.full_bytes; cc++) {
        uint8_t cVal = 0;
        for (int32_t k = 7; k >= 0; k--) {
          if (pArithDecoder->IsComplete()) {
            return nullptr;
          }

          int bVal = pArithDecoder->Decode(&gbContexts[CONTEXT]);
          cVal |= bVal << k;
          CONTEXT = ((CONTEXT & 0x01f7) << 1) | bVal;
        }
        row_write[cc] = cVal;
      }
      uint8_t cVal1 = 0;
      for (uint32_t k = 0; k < layout.last_byte_bits; k++) {
        if (pArithDecoder->IsComplete()) {
          return nullptr;
        }

        int bVal = pArithDecoder->Decode(&gbContexts[CONTEXT]);
        cVal1 |= bVal << (7 - k);
        CONTEXT = ((CONTEXT & 0x01f7) << 1) | bVal;
      }
      row_write[layout.full_bytes] = cVal1;
      continue;
    }

    uint32_t val_prev = row_prev[0];
    uint32_t CONTEXT = (val_prev >> 1) & 0x03f0;
    for (uint32_t cc = 0; cc < layout.full_bytes; cc++) {
      val_prev = (val_prev << 8) | row_prev[cc + 1];
      uint8_t cVal = 0;
      for (int32_t k = 7; k >= 0; k--) {
        if (pArithDecoder->IsComplete()) {
          return nullptr;
        }

        int bVal = pArithDecoder->Decode(&gbContexts[CONTEXT]);
        cVal |= bVal << k;
        CONTEXT =
            ((CONTEXT & 0x01f7) << 1) | bVal | ((val_prev >> (k + 1)) & 0x0010);
      }
      row_write[cc] = cVal;
    }
    val_prev <<= 8;
    uint8_t cVal1 = 0;
    for (uint32_t k = 0; k < layout.last_byte_bits; k++) {
      if (pArithDecoder->IsComplete()) {
        return nullptr;
      }

      int bVal = pArithDecoder->Decode(&gbContexts[CONTEXT]);
      cVal1 |= bVal << (7 - k);
      CONTEXT =
          ((CONTEXT & 0x01f7) << 1) | bVal | ((val_prev >> (8 - k)) & 0x0010);
    }
    row_write[layout.full_bytes] = cVal1;
  }
  return GBREG;
}

std::unique_ptr<CJBig2_Image> CJBig2_GRDProc::DecodeArithTemplate3Unopt(
    CJBig2_ArithDecoder* pArithDecoder,
    pdfium::span<JBig2ArithCtx> gbContexts) {
  auto GBREG = std::make_unique<CJBig2_Image>(GBW, GBH);
  if (!GBREG->has_data()) {
    return nullptr;
  }

  GBREG->Fill(false);
  int LTP = 0;
  pdfium::span<uint8_t> row_write;
  pdfium::span<const uint8_t> row_prev;
  for (uint32_t h = 0; h < GBH; h++) {
    row_prev = row_write;
    row_write = GBREG->GetLine(h);
    if (TPGDON) {
      if (pArithDecoder->IsComplete()) {
        return nullptr;
      }

      LTP = LTP ^ pArithDecoder->Decode(&gbContexts[0x0195]);
      if (LTP) {
        GBREG->CopyLine(row_write, row_prev);
        continue;
      }
    }

    pdfium::span<const uint8_t> row_skip;
    if (USESKIP) {
      row_skip = SKIP->GetLine(h);
    }
    pdfium::span<const uint8_t> row_gbat = GBREG->GetLine(h + GBAT[1]);

    uint32_t val_prev = GBREG->GetPixel(1, row_prev);
    val_prev |= GBREG->GetPixel(0, row_prev) << 1;
    uint32_t val_current = 0;
    for (uint32_t w = 0; w < GBW; w++) {
      int bVal = 0;
      if (!USESKIP || !SKIP->GetPixel(w, row_skip)) {
        uint32_t CONTEXT = val_current;
        CONTEXT |= GBREG->GetPixel(w + GBAT[0], row_gbat) << 4;
        CONTEXT |= val_prev << 5;
        if (pArithDecoder->IsComplete()) {
          return nullptr;
        }

        bVal = pArithDecoder->Decode(&gbContexts[CONTEXT]);
      }
      if (bVal) {
        GBREG->SetPixel(w, row_write, bVal);
      }
      val_prev = ((val_prev << 1) | GBREG->GetPixel(w + 2, row_prev)) & 0x1f;
      val_current = ((val_current << 1) | bVal) & 0x0f;
    }
  }
  return GBREG;
}

FXCODEC_STATUS CJBig2_GRDProc::StartDecodeArith(
    ProgressiveArithDecodeState* pState) {
  if (!CJBig2_Image::IsValidImageSize(GBW, GBH)) {
    progressive_status_ = FXCODEC_STATUS::kDecodeFinished;
    return FXCODEC_STATUS::kDecodeFinished;
  }
  progressive_status_ = FXCODEC_STATUS::kDecodeReady;
  std::unique_ptr<CJBig2_Image>* pImage = pState->pImage;
  if (!*pImage) {
    *pImage = std::make_unique<CJBig2_Image>(GBW, GBH);
  }
  if (!(*pImage)->has_data()) {
    *pImage = nullptr;
    progressive_status_ = FXCODEC_STATUS::kError;
    return FXCODEC_STATUS::kError;
  }
  pImage->get()->Fill(false);
  decode_type_ = 1;
  ltp_ = 0;
  line_prev2_ = {};
  line_prev1_ = {};
  line_ = {};
  loop_index_ = 0;
  return ProgressiveDecodeArith(pState);
}

FXCODEC_STATUS CJBig2_GRDProc::ProgressiveDecodeArith(
    ProgressiveArithDecodeState* pState) {
  int iline = loop_index_;

  using DecodeFunction = std::function<FXCODEC_STATUS(
      CJBig2_GRDProc&, ProgressiveArithDecodeState*)>;
  DecodeFunction func;
  switch (GBTEMPLATE) {
    case 0:
      func = UseTemplate0Opt3()
                 ? &CJBig2_GRDProc::ProgressiveDecodeArithTemplate0Opt3
                 : &CJBig2_GRDProc::ProgressiveDecodeArithTemplate0Unopt;
      break;
    case 1:
      func = UseTemplate1Opt3()
                 ? &CJBig2_GRDProc::ProgressiveDecodeArithTemplate1Opt3
                 : &CJBig2_GRDProc::ProgressiveDecodeArithTemplate1Unopt;
      break;
    case 2:
      func = UseTemplate23Opt3()
                 ? &CJBig2_GRDProc::ProgressiveDecodeArithTemplate2Opt3
                 : &CJBig2_GRDProc::ProgressiveDecodeArithTemplate2Unopt;
      break;
    default:
      func = UseTemplate23Opt3()
                 ? &CJBig2_GRDProc::ProgressiveDecodeArithTemplate3Opt3
                 : &CJBig2_GRDProc::ProgressiveDecodeArithTemplate3Unopt;
      break;
  }
  CJBig2_Image* pImage = pState->pImage->get();
  progressive_status_ = func(*this, pState);
  replace_rect_.left = 0;
  replace_rect_.right = pImage->width();
  replace_rect_.top = iline;
  replace_rect_.bottom = loop_index_;
  if (progressive_status_ == FXCODEC_STATUS::kDecodeFinished) {
    loop_index_ = 0;
  }

  return progressive_status_;
}

FXCODEC_STATUS CJBig2_GRDProc::StartDecodeMMR(
    std::unique_ptr<CJBig2_Image>* pImage,
    CJBig2_BitStream* pStream) {
  auto image = std::make_unique<CJBig2_Image>(GBW, GBH);
  auto image_span = image->span();
  if (image_span.empty()) {
    *pImage = nullptr;
    progressive_status_ = FXCODEC_STATUS::kError;
    return progressive_status_;
  }
  uint32_t bitpos = pStream->getBitPos();
  bitpos = FaxModule::FaxG4Decode(pStream->getBufSpan(), bitpos, GBW, GBH,
                                  image->stride(), image_span);
  pStream->setBitPos(bitpos);
  for (uint8_t& elem : image->span()) {
    elem = ~elem;
  }

  progressive_status_ = FXCODEC_STATUS::kDecodeFinished;
  replace_rect_.left = 0;
  replace_rect_.right = image->width();
  replace_rect_.top = 0;
  replace_rect_.bottom = image->height();
  *pImage = std::move(image);
  return progressive_status_;
}

FXCODEC_STATUS CJBig2_GRDProc::ContinueDecode(
    ProgressiveArithDecodeState* pState) {
  if (progressive_status_ != FXCODEC_STATUS::kDecodeToBeContinued) {
    return progressive_status_;
  }

  if (decode_type_ != 1) {
    progressive_status_ = FXCODEC_STATUS::kError;
    return progressive_status_;
  }
  return ProgressiveDecodeArith(pState);
}

void CJBig2_GRDProc::FinishDecode() {
  line_prev2_ = {};
  line_prev1_ = {};
  line_ = {};
}

FXCODEC_STATUS CJBig2_GRDProc::ProgressiveDecodeArithTemplate0Opt3(
    ProgressiveArithDecodeState* pState) {
  CJBig2_Image* pImage = pState->pImage->get();
  if (line_.empty()) {
    line_ = pImage->span();
  }

  static constexpr TemplateOpt3Params kParams = {.tp_ctx = 0x9b25,
                                                 .context_mask = 0x7bf7,
                                                 .val1_shift = 0,
                                                 .val1_context_mask = 0x07f0,
                                                 .val1_bit_mask = 0x0010,
                                                 .val2_shift = 6,
                                                 .val2_context_mask = 0xf800,
                                                 .val2_bit_mask = 0x0800};
  const LineLayout layout = GetLineLayout(GBW);
  const uint32_t height = GBH & 0x7fffffff;
  for (; loop_index_ < height; loop_index_++) {
    if (!ProgressiveDecodeArithTemplateOpt3Helper(
            pState, kParams, layout.full_bytes, layout.last_byte_bits)) {
      return FXCODEC_STATUS::kError;
    }

    AdvanceLine(pImage);
    if (pState->pPause && pState->pPause->NeedToPauseNow()) {
      loop_index_++;
      progressive_status_ = FXCODEC_STATUS::kDecodeToBeContinued;
      return FXCODEC_STATUS::kDecodeToBeContinued;
    }
  }
  progressive_status_ = FXCODEC_STATUS::kDecodeFinished;
  return FXCODEC_STATUS::kDecodeFinished;
}

FXCODEC_STATUS CJBig2_GRDProc::ProgressiveDecodeArithTemplate0Unopt(
    ProgressiveArithDecodeState* pState) {
  CJBig2_Image* pImage = pState->pImage->get();
  pdfium::span<JBig2ArithCtx> gbContexts = pState->gbContexts;
  CJBig2_ArithDecoder* pArithDecoder = pState->pArithDecoder;
  pdfium::span<uint8_t> row_write = pImage->GetLine(loop_index_ - 1);
  pdfium::span<const uint8_t> row_prev1 = pImage->GetLine(loop_index_ - 2);
  pdfium::span<const uint8_t> row_prev2;
  for (; loop_index_ < GBH; loop_index_++) {
    row_prev2 = row_prev1;
    row_prev1 = row_write;
    row_write = pImage->GetLine(loop_index_);
    if (TPGDON) {
      if (pArithDecoder->IsComplete()) {
        return FXCODEC_STATUS::kError;
      }

      ltp_ = ltp_ ^ pArithDecoder->Decode(&gbContexts[0x9b25]);
    }
    if (ltp_) {
      pImage->CopyLine(row_write, row_prev1);
    } else {
      pdfium::span<const uint8_t> row_skip;
      if (USESKIP) {
        row_skip = SKIP->GetLine(loop_index_);
      }
      pdfium::span<const uint8_t> row_gbat0 =
          pImage->GetLine(loop_index_ + GBAT[1]);
      pdfium::span<const uint8_t> row_gbat1 =
          pImage->GetLine(loop_index_ + GBAT[3]);
      pdfium::span<const uint8_t> row_gbat2 =
          pImage->GetLine(loop_index_ + GBAT[5]);
      pdfium::span<const uint8_t> row_gbat3 =
          pImage->GetLine(loop_index_ + GBAT[7]);

      uint32_t val_prev2 = pImage->GetPixel(1, row_prev2);
      val_prev2 |= pImage->GetPixel(0, row_prev2) << 1;
      uint32_t val_prev1 = pImage->GetPixel(2, row_prev1);
      val_prev1 |= pImage->GetPixel(1, row_prev1) << 1;
      val_prev1 |= pImage->GetPixel(0, row_prev1) << 2;
      uint32_t val_current = 0;
      for (uint32_t w = 0; w < GBW; w++) {
        int bVal = 0;
        if (!USESKIP || !SKIP->GetPixel(w, row_skip)) {
          uint32_t CONTEXT = val_current;
          CONTEXT |= pImage->GetPixel(w + GBAT[0], row_gbat0) << 4;
          CONTEXT |= val_prev1 << 5;
          CONTEXT |= pImage->GetPixel(w + GBAT[2], row_gbat1) << 10;
          CONTEXT |= pImage->GetPixel(w + GBAT[4], row_gbat2) << 11;
          CONTEXT |= val_prev2 << 12;
          CONTEXT |= pImage->GetPixel(w + GBAT[6], row_gbat3) << 15;
          if (pArithDecoder->IsComplete()) {
            return FXCODEC_STATUS::kError;
          }

          bVal = pArithDecoder->Decode(&gbContexts[CONTEXT]);
        }
        if (bVal) {
          pImage->SetPixel(w, row_write, bVal);
        }
        val_prev2 =
            ((val_prev2 << 1) | pImage->GetPixel(w + 2, row_prev2)) & 0x07;
        val_prev1 =
            ((val_prev1 << 1) | pImage->GetPixel(w + 3, row_prev1)) & 0x1f;
        val_current = ((val_current << 1) | bVal) & 0x0f;
      }
    }
    if (pState->pPause && pState->pPause->NeedToPauseNow()) {
      loop_index_++;
      progressive_status_ = FXCODEC_STATUS::kDecodeToBeContinued;
      return FXCODEC_STATUS::kDecodeToBeContinued;
    }
  }
  progressive_status_ = FXCODEC_STATUS::kDecodeFinished;
  return FXCODEC_STATUS::kDecodeFinished;
}

FXCODEC_STATUS CJBig2_GRDProc::ProgressiveDecodeArithTemplate1Opt3(
    ProgressiveArithDecodeState* pState) {
  CJBig2_Image* pImage = pState->pImage->get();
  if (line_.empty()) {
    line_ = pImage->span();
  }

  static constexpr TemplateOpt3Params kParams = {.tp_ctx = 0x0795,
                                                 .context_mask = 0x0efb,
                                                 .val1_shift = 1,
                                                 .val1_context_mask = 0x01f8,
                                                 .val1_bit_mask = 0x0008,
                                                 .val2_shift = 4,
                                                 .val2_context_mask = 0x1e00,
                                                 .val2_bit_mask = 0x0200};
  const LineLayout layout = GetLineLayout(GBW);
  for (; loop_index_ < GBH; loop_index_++) {
    if (!ProgressiveDecodeArithTemplateOpt3Helper(
            pState, kParams, layout.full_bytes, layout.last_byte_bits)) {
      return FXCODEC_STATUS::kError;
    }

    AdvanceLine(pImage);
    if (pState->pPause && pState->pPause->NeedToPauseNow()) {
      loop_index_++;
      progressive_status_ = FXCODEC_STATUS::kDecodeToBeContinued;
      return FXCODEC_STATUS::kDecodeToBeContinued;
    }
  }
  progressive_status_ = FXCODEC_STATUS::kDecodeFinished;
  return FXCODEC_STATUS::kDecodeFinished;
}

FXCODEC_STATUS CJBig2_GRDProc::ProgressiveDecodeArithTemplate1Unopt(
    ProgressiveArithDecodeState* pState) {
  CJBig2_Image* pImage = pState->pImage->get();
  pdfium::span<JBig2ArithCtx> gbContexts = pState->gbContexts;
  CJBig2_ArithDecoder* pArithDecoder = pState->pArithDecoder;
  pdfium::span<uint8_t> row_write = pImage->GetLine(loop_index_ - 1);
  pdfium::span<const uint8_t> row_prev1 = pImage->GetLine(loop_index_ - 2);
  pdfium::span<const uint8_t> row_prev2;
  for (; loop_index_ < GBH; loop_index_++) {
    row_prev2 = row_prev1;
    row_prev1 = row_write;
    row_write = pImage->GetLine(loop_index_);
    if (TPGDON) {
      if (pArithDecoder->IsComplete()) {
        return FXCODEC_STATUS::kError;
      }

      ltp_ = ltp_ ^ pArithDecoder->Decode(&gbContexts[0x0795]);
    }
    if (ltp_) {
      pImage->CopyLine(row_write, row_prev1);
    } else {
      pdfium::span<const uint8_t> row_skip;
      if (USESKIP) {
        row_skip = SKIP->GetLine(loop_index_);
      }
      pdfium::span<const uint8_t> row_gbat =
          pImage->GetLine(loop_index_ + GBAT[1]);

      uint32_t val_prev2 = pImage->GetPixel(2, row_prev2);
      val_prev2 |= pImage->GetPixel(1, row_prev2) << 1;
      val_prev2 |= pImage->GetPixel(0, row_prev2) << 2;
      uint32_t val_prev1 = pImage->GetPixel(2, row_prev1);
      val_prev1 |= pImage->GetPixel(1, row_prev1) << 1;
      val_prev1 |= pImage->GetPixel(0, row_prev1) << 2;
      uint32_t val_current = 0;
      for (uint32_t w = 0; w < GBW; w++) {
        int bVal = 0;
        if (!USESKIP || !SKIP->GetPixel(w, row_skip)) {
          uint32_t CONTEXT = val_current;
          CONTEXT |= pImage->GetPixel(w + GBAT[0], row_gbat) << 3;
          CONTEXT |= val_prev1 << 4;
          CONTEXT |= val_prev2 << 9;
          if (pArithDecoder->IsComplete()) {
            return FXCODEC_STATUS::kError;
          }

          bVal = pArithDecoder->Decode(&gbContexts[CONTEXT]);
        }
        if (bVal) {
          pImage->SetPixel(w, row_write, bVal);
        }
        val_prev2 =
            ((val_prev2 << 1) | pImage->GetPixel(w + 3, row_prev2)) & 0x0f;
        val_prev1 =
            ((val_prev1 << 1) | pImage->GetPixel(w + 3, row_prev1)) & 0x1f;
        val_current = ((val_current << 1) | bVal) & 0x07;
      }
    }
    if (pState->pPause && pState->pPause->NeedToPauseNow()) {
      loop_index_++;
      progressive_status_ = FXCODEC_STATUS::kDecodeToBeContinued;
      return FXCODEC_STATUS::kDecodeToBeContinued;
    }
  }
  progressive_status_ = FXCODEC_STATUS::kDecodeFinished;
  return FXCODEC_STATUS::kDecodeFinished;
}

FXCODEC_STATUS CJBig2_GRDProc::ProgressiveDecodeArithTemplate2Opt3(
    ProgressiveArithDecodeState* pState) {
  CJBig2_Image* pImage = pState->pImage->get();
  if (line_.empty()) {
    line_ = pImage->span();
  }

  static constexpr TemplateOpt3Params kParams = {.tp_ctx = 0x00e5,
                                                 .context_mask = 0x01bd,
                                                 .val1_shift = 3,
                                                 .val1_context_mask = 0x007c,
                                                 .val1_bit_mask = 0x0004,
                                                 .val2_shift = 1,
                                                 .val2_context_mask = 0x0380,
                                                 .val2_bit_mask = 0x0080};
  const LineLayout layout = GetLineLayout(GBW);
  for (; loop_index_ < GBH; loop_index_++) {
    if (!ProgressiveDecodeArithTemplateOpt3Helper(
            pState, kParams, layout.full_bytes, layout.last_byte_bits)) {
      return FXCODEC_STATUS::kError;
    }

    AdvanceLine(pImage);
    if (pState->pPause && loop_index_ % 50 == 0 &&
        pState->pPause->NeedToPauseNow()) {
      loop_index_++;
      progressive_status_ = FXCODEC_STATUS::kDecodeToBeContinued;
      return FXCODEC_STATUS::kDecodeToBeContinued;
    }
  }
  progressive_status_ = FXCODEC_STATUS::kDecodeFinished;
  return FXCODEC_STATUS::kDecodeFinished;
}

bool CJBig2_GRDProc::ProgressiveDecodeArithTemplateOpt3Helper(
    ProgressiveArithDecodeState* state,
    const TemplateOpt3Params& params,
    uint32_t nLineBytes,
    uint32_t nBitsLeft) {
  if (TPGDON) {
    if (state->pArithDecoder->IsComplete()) {
      return false;
    }

    ltp_ =
        ltp_ ^ state->pArithDecoder->Decode(&state->gbContexts[params.tp_ctx]);
  }
  if (ltp_) {
    CopyPrevLine(state->pImage->get());
    return true;
  }

  if (loop_index_ <= 1) {
    const bool is_second_line = loop_index_ == 1;
    uint32_t val_prev = is_second_line ? line_prev1_[0] : 0;
    uint32_t CONTEXT =
        (val_prev >> params.val1_shift) & params.val1_context_mask;
    for (uint32_t cc = 0; cc < nLineBytes; cc++) {
      if (is_second_line) {
        val_prev = (val_prev << 8) | line_prev1_[cc + 1];
      }
      uint8_t cVal = 0;
      for (int32_t k = 7; k >= 0; k--) {
        if (state->pArithDecoder->IsComplete()) {
          return false;
        }

        int bVal = state->pArithDecoder->Decode(&state->gbContexts[CONTEXT]);
        cVal |= bVal << k;
        CONTEXT =
            ((CONTEXT & params.context_mask) << 1) | bVal |
            ((val_prev >> (k + params.val1_shift)) & params.val1_bit_mask);
      }
      line_[cc] = cVal;
    }
    val_prev <<= 8;
    uint8_t cVal1 = 0;
    for (uint32_t k = 0; k < nBitsLeft; k++) {
      if (state->pArithDecoder->IsComplete()) {
        return false;
      }

      int bVal = state->pArithDecoder->Decode(&state->gbContexts[CONTEXT]);
      cVal1 |= bVal << (7 - k);
      CONTEXT =
          ((CONTEXT & params.context_mask) << 1) | bVal |
          ((val_prev >> (7 - k + params.val1_shift)) & params.val1_bit_mask);
    }
    line_[nLineBytes] = cVal1;
    return true;
  }

  uint32_t val_prev2 = line_prev2_[0] << params.val2_shift;
  uint32_t val_prev1 = line_prev1_[0];
  uint32_t CONTEXT =
      (val_prev2 & params.val2_context_mask) |
      ((val_prev1 >> params.val1_shift) & params.val1_context_mask);
  for (uint32_t cc = 0; cc < nLineBytes; cc++) {
    val_prev2 = (val_prev2 << 8) | (line_prev2_[cc + 1] << params.val2_shift);
    val_prev1 = (val_prev1 << 8) | line_prev1_[cc + 1];
    uint8_t cVal = 0;
    for (int32_t k = 7; k >= 0; k--) {
      if (state->pArithDecoder->IsComplete()) {
        return false;
      }

      int bVal = state->pArithDecoder->Decode(&state->gbContexts[CONTEXT]);
      cVal |= bVal << k;
      CONTEXT = ((CONTEXT & params.context_mask) << 1) | bVal |
                ((val_prev2 >> k) & params.val2_bit_mask) |
                ((val_prev1 >> (k + params.val1_shift)) & params.val1_bit_mask);
    }
    line_[cc] = cVal;
  }
  val_prev2 <<= 8;
  val_prev1 <<= 8;
  uint8_t cVal1 = 0;
  for (uint32_t k = 0; k < nBitsLeft; k++) {
    if (state->pArithDecoder->IsComplete()) {
      return false;
    }

    int bVal = state->pArithDecoder->Decode(&state->gbContexts[CONTEXT]);
    cVal1 |= bVal << (7 - k);
    CONTEXT =
        ((CONTEXT & params.context_mask) << 1) | bVal |
        ((val_prev2 >> (7 - k)) & params.val2_bit_mask) |
        ((val_prev1 >> (7 - k + params.val1_shift)) & params.val1_bit_mask);
  }
  line_[nLineBytes] = cVal1;
  return true;
}

FXCODEC_STATUS CJBig2_GRDProc::ProgressiveDecodeArithTemplate2Unopt(
    ProgressiveArithDecodeState* pState) {
  CJBig2_Image* pImage = pState->pImage->get();
  pdfium::span<JBig2ArithCtx> gbContexts = pState->gbContexts;
  CJBig2_ArithDecoder* pArithDecoder = pState->pArithDecoder;
  pdfium::span<uint8_t> row_write = pImage->GetLine(loop_index_ - 1);
  pdfium::span<const uint8_t> row_prev1 = pImage->GetLine(loop_index_ - 2);
  pdfium::span<const uint8_t> row_prev2;
  for (; loop_index_ < GBH; loop_index_++) {
    row_prev2 = row_prev1;
    row_prev1 = row_write;
    row_write = pImage->GetLine(loop_index_);
    if (TPGDON) {
      if (pArithDecoder->IsComplete()) {
        return FXCODEC_STATUS::kError;
      }

      ltp_ = ltp_ ^ pArithDecoder->Decode(&gbContexts[0x00e5]);
    }
    if (ltp_) {
      pImage->CopyLine(row_write, row_prev1);
    } else {
      pdfium::span<const uint8_t> row_skip;
      if (USESKIP) {
        row_skip = SKIP->GetLine(loop_index_);
      }
      pdfium::span<const uint8_t> row_gbat =
          pImage->GetLine(loop_index_ + GBAT[1]);

      uint32_t val_prev2 = pImage->GetPixel(1, row_prev2);
      val_prev2 |= pImage->GetPixel(0, row_prev2) << 1;
      uint32_t val_prev1 = pImage->GetPixel(1, row_prev1);
      val_prev1 |= pImage->GetPixel(0, row_prev1) << 1;
      uint32_t val_current = 0;
      for (uint32_t w = 0; w < GBW; w++) {
        int bVal = 0;
        if (!USESKIP || !SKIP->GetPixel(w, row_skip)) {
          uint32_t CONTEXT = val_current;
          CONTEXT |= pImage->GetPixel(w + GBAT[0], row_gbat) << 2;
          CONTEXT |= val_prev1 << 3;
          CONTEXT |= val_prev2 << 7;
          if (pArithDecoder->IsComplete()) {
            return FXCODEC_STATUS::kError;
          }

          bVal = pArithDecoder->Decode(&gbContexts[CONTEXT]);
        }
        if (bVal) {
          pImage->SetPixel(w, row_write, bVal);
        }
        val_prev2 =
            ((val_prev2 << 1) | pImage->GetPixel(w + 2, row_prev2)) & 0x07;
        val_prev1 =
            ((val_prev1 << 1) | pImage->GetPixel(w + 2, row_prev1)) & 0x0f;
        val_current = ((val_current << 1) | bVal) & 0x03;
      }
    }
    if (pState->pPause && pState->pPause->NeedToPauseNow()) {
      loop_index_++;
      progressive_status_ = FXCODEC_STATUS::kDecodeToBeContinued;
      return FXCODEC_STATUS::kDecodeToBeContinued;
    }
  }
  progressive_status_ = FXCODEC_STATUS::kDecodeFinished;
  return FXCODEC_STATUS::kDecodeFinished;
}

FXCODEC_STATUS CJBig2_GRDProc::ProgressiveDecodeArithTemplate3Opt3(
    ProgressiveArithDecodeState* pState) {
  CJBig2_Image* pImage = pState->pImage->get();
  pdfium::span<JBig2ArithCtx> gbContexts = pState->gbContexts;
  CJBig2_ArithDecoder* pArithDecoder = pState->pArithDecoder;
  if (line_.empty()) {
    line_ = pImage->span();
  }

  const LineLayout layout = GetLineLayout(GBW);
  for (; loop_index_ < GBH; loop_index_++) {
    if (TPGDON) {
      if (pArithDecoder->IsComplete()) {
        return FXCODEC_STATUS::kError;
      }

      ltp_ = ltp_ ^ pArithDecoder->Decode(&gbContexts[0x0195]);
    }
    if (ltp_) {
      CopyPrevLine(pImage);
    } else {
      if (loop_index_ == 0) {
        uint32_t CONTEXT = 0;
        for (uint32_t cc = 0; cc < layout.full_bytes; cc++) {
          uint8_t cVal = 0;
          for (int32_t k = 7; k >= 0; k--) {
            if (pArithDecoder->IsComplete()) {
              return FXCODEC_STATUS::kError;
            }

            int bVal = pArithDecoder->Decode(&gbContexts[CONTEXT]);
            cVal |= bVal << k;
            CONTEXT = ((CONTEXT & 0x01f7) << 1) | bVal;
          }
          line_[cc] = cVal;
        }
        uint8_t cVal1 = 0;
        for (uint32_t k = 0; k < layout.last_byte_bits; k++) {
          if (pArithDecoder->IsComplete()) {
            return FXCODEC_STATUS::kError;
          }

          int bVal = pArithDecoder->Decode(&gbContexts[CONTEXT]);
          cVal1 |= bVal << (7 - k);
          CONTEXT = ((CONTEXT & 0x01f7) << 1) | bVal;
        }
        line_[layout.full_bytes] = cVal1;
      } else {
        uint32_t val_prev = line_prev1_[0];
        uint32_t CONTEXT = (val_prev >> 1) & 0x03f0;
        for (uint32_t cc = 0; cc < layout.full_bytes; cc++) {
          val_prev = (val_prev << 8) | line_prev1_[cc + 1];
          uint8_t cVal = 0;
          for (int32_t k = 7; k >= 0; k--) {
            if (pArithDecoder->IsComplete()) {
              return FXCODEC_STATUS::kError;
            }

            int bVal = pArithDecoder->Decode(&gbContexts[CONTEXT]);
            cVal |= bVal << k;
            CONTEXT = ((CONTEXT & 0x01f7) << 1) | bVal |
                      ((val_prev >> (k + 1)) & 0x0010);
          }
          line_[cc] = cVal;
        }
        val_prev <<= 8;
        uint8_t cVal1 = 0;
        for (uint32_t k = 0; k < layout.last_byte_bits; k++) {
          if (pArithDecoder->IsComplete()) {
            return FXCODEC_STATUS::kError;
          }

          int bVal = pArithDecoder->Decode(&gbContexts[CONTEXT]);
          cVal1 |= bVal << (7 - k);
          CONTEXT = ((CONTEXT & 0x01f7) << 1) | bVal |
                    ((val_prev >> (8 - k)) & 0x0010);
        }
        line_[layout.full_bytes] = cVal1;
      }
    }
    AdvanceLine(pImage);
    if (pState->pPause && pState->pPause->NeedToPauseNow()) {
      loop_index_++;
      progressive_status_ = FXCODEC_STATUS::kDecodeToBeContinued;
      return FXCODEC_STATUS::kDecodeToBeContinued;
    }
  }
  progressive_status_ = FXCODEC_STATUS::kDecodeFinished;
  return FXCODEC_STATUS::kDecodeFinished;
}

FXCODEC_STATUS CJBig2_GRDProc::ProgressiveDecodeArithTemplate3Unopt(
    ProgressiveArithDecodeState* pState) {
  CJBig2_Image* pImage = pState->pImage->get();
  pdfium::span<JBig2ArithCtx> gbContexts = pState->gbContexts;
  CJBig2_ArithDecoder* pArithDecoder = pState->pArithDecoder;
  pdfium::span<uint8_t> row_write = pImage->GetLine(loop_index_ - 1);
  pdfium::span<const uint8_t> row_prev;
  for (; loop_index_ < GBH; loop_index_++) {
    row_prev = row_write;
    row_write = pImage->GetLine(loop_index_);
    if (TPGDON) {
      if (pArithDecoder->IsComplete()) {
        return FXCODEC_STATUS::kError;
      }

      ltp_ = ltp_ ^ pArithDecoder->Decode(&gbContexts[0x0195]);
    }
    if (ltp_) {
      pImage->CopyLine(row_write, row_prev);
    } else {
      pdfium::span<const uint8_t> row_skip;
      if (USESKIP) {
        row_skip = SKIP->GetLine(loop_index_);
      }
      pdfium::span<const uint8_t> row_gbat =
          pImage->GetLine(loop_index_ + GBAT[1]);

      uint32_t val_prev = pImage->GetPixel(1, row_prev);
      val_prev |= pImage->GetPixel(0, row_prev) << 1;
      uint32_t val_current = 0;
      for (uint32_t w = 0; w < GBW; w++) {
        int bVal = 0;
        if (!USESKIP || !SKIP->GetPixel(w, row_skip)) {
          uint32_t CONTEXT = val_current;
          CONTEXT |= pImage->GetPixel(w + GBAT[0], row_gbat) << 4;
          CONTEXT |= val_prev << 5;
          if (pArithDecoder->IsComplete()) {
            return FXCODEC_STATUS::kError;
          }

          bVal = pArithDecoder->Decode(&gbContexts[CONTEXT]);
        }
        if (bVal) {
          pImage->SetPixel(w, row_write, bVal);
        }
        val_prev = ((val_prev << 1) | pImage->GetPixel(w + 2, row_prev)) & 0x1f;
        val_current = ((val_current << 1) | bVal) & 0x0f;
      }
    }
    if (pState->pPause && pState->pPause->NeedToPauseNow()) {
      loop_index_++;
      progressive_status_ = FXCODEC_STATUS::kDecodeToBeContinued;
      return FXCODEC_STATUS::kDecodeToBeContinued;
    }
  }
  progressive_status_ = FXCODEC_STATUS::kDecodeFinished;
  return FXCODEC_STATUS::kDecodeFinished;
}

void CJBig2_GRDProc::AdvanceLine(const CJBig2_Image* image) {
  line_prev2_ = std::move(line_prev1_);
  auto next_line = line_.subspan(static_cast<size_t>(image->stride()));
  line_prev1_ = std::move(line_);
  line_ = std::move(next_line);
}

void CJBig2_GRDProc::CopyPrevLine(CJBig2_Image* image) {
  if (!line_prev1_.empty()) {
    image->CopyLine(line_,
                    line_prev1_.first(static_cast<size_t>(image->stride())));
  }
}
