// Copyright 2015 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxcodec/jbig2/jbig2_grrd_proc.h"

#include <array>
#include <memory>

#include "core/fxcodec/jbig2/jbig2_arith_decoder.h"
#include "core/fxcodec/jbig2/jbig2_bit_stream.h"
#include "core/fxcodec/jbig2/jbig2_image.h"
#include "core/fxcrt/zip.h"

CJBig2_GRRDProc::CJBig2_GRRDProc() = default;

CJBig2_GRRDProc::~CJBig2_GRRDProc() = default;

std::unique_ptr<CJBig2_Image> CJBig2_GRRDProc::Decode(
    CJBig2_ArithDecoder* pArithDecoder,
    pdfium::span<JBig2ArithCtx> grContexts) {
  if (!CJBig2_Image::IsValidImageSize(GRW, GRH)) {
    return std::make_unique<CJBig2_Image>(GRW, GRH);
  }

  if (!GRTEMPLATE) {
    if ((GRAT[0] == -1) && (GRAT[1] == -1) && (GRAT[2] == -1) &&
        (GRAT[3] == -1) && (GRREFERENCEDX == 0) &&
        (GRW == (uint32_t)GRREFERENCE->width())) {
      return DecodeTemplate0Opt(pArithDecoder, grContexts);
    }
    return DecodeTemplate0Unopt(pArithDecoder, grContexts);
  }

  if ((GRREFERENCEDX == 0) && (GRW == (uint32_t)GRREFERENCE->width())) {
    return DecodeTemplate1Opt(pArithDecoder, grContexts);
  }

  return DecodeTemplate1Unopt(pArithDecoder, grContexts);
}

std::unique_ptr<CJBig2_Image> CJBig2_GRRDProc::DecodeTemplate0Unopt(
    CJBig2_ArithDecoder* pArithDecoder,
    pdfium::span<JBig2ArithCtx> grContexts) {
  auto GRREG = std::make_unique<CJBig2_Image>(GRW, GRH);
  if (!GRREG->has_data()) {
    return nullptr;
  }

  GRREG->Fill(false);
  int LTP = 0;
  for (uint32_t h = 0; h < GRH; h++) {
    if (TPGRON) {
      if (pArithDecoder->IsComplete()) {
        return nullptr;
      }
      LTP = LTP ^ pArithDecoder->Decode(&grContexts[0x0010]);
    }

    pdfium::span<uint8_t> row_write = GRREG->GetLine(h);
    pdfium::span<const uint8_t> row_prev = GRREG->GetLine(h - 1);
    std::array<pdfium::span<const uint8_t>, 3> row_refs_dy = GetRowRefsDy(h);

    std::array<uint32_t, 5> lines;
    lines[0] = GRREG->GetPixel(1, row_prev);
    lines[0] |= GRREG->GetPixel(0, row_prev) << 1;
    lines[1] = 0;
    lines[2] = GRREFERENCE->GetPixel(-GRREFERENCEDX + 1, row_refs_dy[0]);
    lines[2] |= GRREFERENCE->GetPixel(-GRREFERENCEDX, row_refs_dy[0]) << 1;
    lines[3] = GRREFERENCE->GetPixel(-GRREFERENCEDX + 1, row_refs_dy[1]);
    lines[3] |= GRREFERENCE->GetPixel(-GRREFERENCEDX, row_refs_dy[1]) << 1;
    lines[3] |= GRREFERENCE->GetPixel(-GRREFERENCEDX - 1, row_refs_dy[1]) << 2;
    lines[4] = GRREFERENCE->GetPixel(-GRREFERENCEDX + 1, row_refs_dy[2]);
    lines[4] |= GRREFERENCE->GetPixel(-GRREFERENCEDX, row_refs_dy[2]) << 1;
    lines[4] |= GRREFERENCE->GetPixel(-GRREFERENCEDX - 1, row_refs_dy[2]) << 2;

    pdfium::span<const uint8_t> row_ref_grat =
        GRREFERENCE->GetLine(h - GRREFERENCEDY + GRAT[3]);
    pdfium::span<const uint8_t> row_grat = GRREG->GetLine(h + GRAT[1]);
    if (!LTP) {
      for (uint32_t w = 0; w < GRW; w++) {
        uint32_t CONTEXT = DecodeTemplate0UnoptCalculateContext(
            *GRREG, lines, w, row_ref_grat, row_grat);
        if (pArithDecoder->IsComplete()) {
          return nullptr;
        }

        int bVal = pArithDecoder->Decode(&grContexts[CONTEXT]);
        DecodeTemplate0UnoptSetPixel(GRREG.get(), lines, w, bVal, row_refs_dy,
                                     row_prev, row_write);
      }
    } else {
      std::array<pdfium::span<const uint8_t>, 3> row_refs = GetRowRefs(h);
      for (uint32_t w = 0; w < GRW; w++) {
        int bVal = GRREFERENCE->GetPixel(w, row_refs[1]);
        if (!TPGRON || !TypicalPrediction(w, bVal, row_refs)) {
          uint32_t CONTEXT = DecodeTemplate0UnoptCalculateContext(
              *GRREG, lines, w, row_ref_grat, row_grat);
          if (pArithDecoder->IsComplete()) {
            return nullptr;
          }

          bVal = pArithDecoder->Decode(&grContexts[CONTEXT]);
        }
        DecodeTemplate0UnoptSetPixel(GRREG.get(), lines, w, bVal, row_refs_dy,
                                     row_prev, row_write);
      }
    }
  }
  return GRREG;
}

uint32_t CJBig2_GRRDProc::DecodeTemplate0UnoptCalculateContext(
    const CJBig2_Image& GRREG,
    pdfium::span<const uint32_t, 5> lines,
    uint32_t w,
    pdfium::span<const uint8_t> row_ref_grat,
    pdfium::span<const uint8_t> row_grat) const {
  uint32_t CONTEXT = lines[4];
  CONTEXT |= lines[3] << 3;
  CONTEXT |= lines[2] << 6;
  CONTEXT |= GRREFERENCE->GetPixel(w - GRREFERENCEDX + GRAT[2], row_ref_grat)
             << 8;
  CONTEXT |= lines[1] << 9;
  CONTEXT |= lines[0] << 10;
  CONTEXT |= GRREG.GetPixel(w + GRAT[0], row_grat) << 12;
  return CONTEXT;
}

void CJBig2_GRRDProc::DecodeTemplate0UnoptSetPixel(
    CJBig2_Image* GRREG,
    pdfium::span<uint32_t, 5> lines,
    uint32_t w,
    int bVal,
    pdfium::span<pdfium::span<const uint8_t>, 3> row_refs_dy,
    pdfium::span<const uint8_t> row_prev,
    pdfium::span<uint8_t> row_write) {
  GRREG->SetPixel(w, row_write, bVal);
  const int w_dx = w - GRREFERENCEDX + 2;
  lines[0] = ((lines[0] << 1) | GRREG->GetPixel(w + 2, row_prev)) & 0x03;
  lines[1] = ((lines[1] << 1) | bVal) & 0x01;
  lines[2] =
      ((lines[2] << 1) | GRREFERENCE->GetPixel(w_dx, row_refs_dy[0])) & 0x03;
  lines[3] =
      ((lines[3] << 1) | GRREFERENCE->GetPixel(w_dx, row_refs_dy[1])) & 0x07;
  lines[4] =
      ((lines[4] << 1) | GRREFERENCE->GetPixel(w_dx, row_refs_dy[2])) & 0x07;
}

std::unique_ptr<CJBig2_Image> CJBig2_GRRDProc::DecodeTemplate0Opt(
    CJBig2_ArithDecoder* pArithDecoder,
    pdfium::span<JBig2ArithCtx> grContexts) {
  if (!GRREFERENCE->has_data()) {
    return nullptr;
  }

  int32_t iGRW = static_cast<int32_t>(GRW);
  int32_t iGRH = static_cast<int32_t>(GRH);
  auto GRREG = std::make_unique<CJBig2_Image>(iGRW, iGRH);
  if (!GRREG->has_data()) {
    return nullptr;
  }

  int LTP = 0;
  const int32_t GRWR = GRREFERENCE->width();
  const int32_t GRHR = GRREFERENCE->height();
  if (GRREFERENCEDY < -GRHR + 1 || GRREFERENCEDY > GRHR - 1) {
    GRREFERENCEDY = 0;
  }
  for (int32_t h = 0; h < iGRH; h++) {
    if (TPGRON) {
      if (pArithDecoder->IsComplete()) {
        return nullptr;
      }
      LTP = LTP ^ pArithDecoder->Decode(&grContexts[0x0010]);
    }

    const bool is_first_line = h == 0;
    pdfium::span<uint8_t> row_write = GRREG->GetLine(h);
    pdfium::span<const uint8_t> row_prev;
    uint32_t line1 = 0;
    if (!is_first_line) {
      row_prev = GRREG->GetLine(h - 1);
      line1 = row_prev.front() << 4;
    }
    const int32_t reference_h = h - GRREFERENCEDY;
    std::array<pdfium::span<const uint8_t>, 3> row_refs_dy;
    std::array<uint32_t, 3> refs = {};
    if (reference_h > 0 && reference_h < GRHR + 1) {
      row_refs_dy[0] = GRREFERENCE->GetLine(reference_h - 1);
      refs[0] = row_refs_dy[0].front();
    }
    if (reference_h > -1 && reference_h < GRHR) {
      row_refs_dy[1] = GRREFERENCE->GetLine(reference_h);
      refs[1] = row_refs_dy[1].front();
    }
    if (reference_h > -2 && reference_h < GRHR - 1) {
      row_refs_dy[2] = GRREFERENCE->GetLine(reference_h + 1);
      refs[2] = row_refs_dy[2].front();
    }

    if (!LTP) {
      uint32_t CONTEXT = (line1 & 0x1c00) | (refs[0] & 0x01c0) |
                         ((refs[1] >> 3) & 0x0038) | ((refs[2] >> 6) & 0x0007);
      for (int32_t w = 0; w < iGRW; w += 8) {
        int32_t nBits = iGRW - w > 8 ? 8 : iGRW - w;
        if (!is_first_line) {
          line1 = line1 << 8;
          if (w + 8 < iGRW) {
            line1 |= row_prev[w / 8 + 1] << 4;
          }
        }
        if (h > GRHR + GRREFERENCEDY + 1) {
          refs = {};
        } else {
          const bool next_w_in_bounds = w + 8 < GRWR;
          for (auto [row_ref_dy, ref] : fxcrt::Zip(row_refs_dy, refs)) {
            if (!row_ref_dy.empty()) {
              ref <<= 8;
              if (next_w_in_bounds) {
                ref |= row_ref_dy[(w / 8) + 1];
              }
            }
          }
        }
        uint8_t cVal = 0;
        for (int32_t k = 0; k < nBits; k++) {
          int bVal = pArithDecoder->Decode(&grContexts[CONTEXT]);
          cVal |= bVal << (7 - k);
          CONTEXT = ((CONTEXT & 0x0cdb) << 1) | (bVal << 9) |
                    ((line1 >> (7 - k)) & 0x0400) |
                    ((refs[0] >> (7 - k)) & 0x0040) |
                    ((refs[1] >> (10 - k)) & 0x0008) |
                    ((refs[2] >> (13 - k)) & 0x0001);
        }
        row_write[w / 8] = cVal;
      }
    } else {
      std::array<pdfium::span<const uint8_t>, 3> row_refs = GetRowRefs(h);
      uint32_t CONTEXT = (line1 & 0x1c00) | (refs[0] & 0x01c0) |
                         ((refs[1] >> 3) & 0x0038) | ((refs[2] >> 6) & 0x0007);
      for (int32_t w = 0; w < iGRW; w += 8) {
        int32_t nBits = iGRW - w > 8 ? 8 : iGRW - w;
        if (!is_first_line) {
          line1 = line1 << 8;
          if (w + 8 < iGRW) {
            line1 |= row_prev[w / 8 + 1] << 4;
          }
        }
        const bool next_w_in_bounds = w + 8 < GRWR;
        for (auto [row_ref_dy, ref] : fxcrt::Zip(row_refs_dy, refs)) {
          if (!row_ref_dy.empty()) {
            ref <<= 8;
            if (next_w_in_bounds) {
              ref |= row_ref_dy[(w / 8) + 1];
            }
          }
        }
        uint8_t cVal = 0;
        for (int32_t k = 0; k < nBits; k++) {
          int bVal = GRREFERENCE->GetPixel(w + k, row_refs[1]);
          if (!TPGRON || !TypicalPrediction(w + k, bVal, row_refs)) {
            if (pArithDecoder->IsComplete()) {
              return nullptr;
            }

            bVal = pArithDecoder->Decode(&grContexts[CONTEXT]);
          }
          cVal |= bVal << (7 - k);
          CONTEXT = ((CONTEXT & 0x0cdb) << 1) | (bVal << 9) |
                    ((line1 >> (7 - k)) & 0x0400) |
                    ((refs[0] >> (7 - k)) & 0x0040) |
                    ((refs[1] >> (10 - k)) & 0x0008) |
                    ((refs[2] >> (13 - k)) & 0x0001);
        }
        row_write[w / 8] = cVal;
      }
    }
  }
  return GRREG;
}

std::unique_ptr<CJBig2_Image> CJBig2_GRRDProc::DecodeTemplate1Unopt(
    CJBig2_ArithDecoder* pArithDecoder,
    pdfium::span<JBig2ArithCtx> grContexts) {
  auto GRREG = std::make_unique<CJBig2_Image>(GRW, GRH);
  if (!GRREG->has_data()) {
    return nullptr;
  }

  GRREG->Fill(false);
  int LTP = 0;
  for (uint32_t h = 0; h < GRH; h++) {
    if (TPGRON) {
      if (pArithDecoder->IsComplete()) {
        return nullptr;
      }
      LTP = LTP ^ pArithDecoder->Decode(&grContexts[0x0008]);
    }

    pdfium::span<uint8_t> row_write = GRREG->GetLine(h);
    pdfium::span<const uint8_t> row_prev = GRREG->GetLine(h - 1);
    std::array<pdfium::span<const uint8_t>, 3> row_refs_dy = GetRowRefsDy(h);
    std::array<uint32_t, 5> lines;
    lines[0] = GRREG->GetPixel(1, row_prev);
    lines[0] |= GRREG->GetPixel(0, row_prev) << 1;
    lines[0] |= GRREG->GetPixel(-1, row_prev) << 2;
    lines[1] = 0;
    lines[2] = GRREFERENCE->GetPixel(-GRREFERENCEDX, row_refs_dy[0]);
    lines[2] = GRREFERENCE->GetPixel(-GRREFERENCEDX, row_refs_dy[0]);
    lines[3] = GRREFERENCE->GetPixel(-GRREFERENCEDX + 1, row_refs_dy[1]);
    lines[3] |= GRREFERENCE->GetPixel(-GRREFERENCEDX, row_refs_dy[1]) << 1;
    lines[3] |= GRREFERENCE->GetPixel(-GRREFERENCEDX - 1, row_refs_dy[1]) << 2;
    lines[4] = GRREFERENCE->GetPixel(-GRREFERENCEDX + 1, row_refs_dy[2]);
    lines[4] |= GRREFERENCE->GetPixel(-GRREFERENCEDX, row_refs_dy[2]) << 1;

    if (!LTP) {
      for (uint32_t w = 0; w < GRW; w++) {
        uint32_t CONTEXT = lines[4];
        CONTEXT |= lines[3] << 2;
        CONTEXT |= lines[2] << 5;
        CONTEXT |= lines[1] << 6;
        CONTEXT |= lines[0] << 7;
        if (pArithDecoder->IsComplete()) {
          return nullptr;
        }
        int bVal = pArithDecoder->Decode(&grContexts[CONTEXT]);
        GRREG->SetPixel(w, row_write, bVal);
        const int w_dx = w - GRREFERENCEDX + 1;
        lines[0] = ((lines[0] << 1) | GRREG->GetPixel(w + 2, row_prev)) & 0x07;
        lines[1] = ((lines[1] << 1) | bVal) & 0x01;
        lines[2] =
            ((lines[2] << 1) | GRREFERENCE->GetPixel(w_dx, row_refs_dy[0])) &
            0x01;
        lines[3] = ((lines[3] << 1) |
                    GRREFERENCE->GetPixel(w_dx + 1, row_refs_dy[1])) &
                   0x07;
        lines[4] = ((lines[4] << 1) |
                    GRREFERENCE->GetPixel(w_dx + 1, row_refs_dy[2])) &
                   0x03;
      }
    } else {
      std::array<pdfium::span<const uint8_t>, 3> row_refs = GetRowRefs(h);
      for (uint32_t w = 0; w < GRW; w++) {
        int bVal = GRREFERENCE->GetPixel(w, row_refs[1]);
        if (!TPGRON || !TypicalPrediction(w, bVal, row_refs)) {
          uint32_t CONTEXT = lines[4];
          CONTEXT |= lines[3] << 2;
          CONTEXT |= lines[2] << 5;
          CONTEXT |= lines[1] << 6;
          CONTEXT |= lines[0] << 7;
          if (pArithDecoder->IsComplete()) {
            return nullptr;
          }
          bVal = pArithDecoder->Decode(&grContexts[CONTEXT]);
        }
        GRREG->SetPixel(w, row_write, bVal);
        int w_dx = w - GRREFERENCEDX + 1;
        lines[0] = ((lines[0] << 1) | GRREG->GetPixel(w + 2, row_prev)) & 0x07;
        lines[1] = ((lines[1] << 1) | bVal) & 0x01;
        lines[2] =
            ((lines[2] << 1) | GRREFERENCE->GetPixel(w_dx, row_refs_dy[0])) &
            0x01;
        lines[3] = ((lines[3] << 1) |
                    GRREFERENCE->GetPixel(w_dx + 1, row_refs_dy[1])) &
                   0x07;
        lines[4] = ((lines[4] << 1) |
                    GRREFERENCE->GetPixel(w_dx + 1, row_refs_dy[2])) &
                   0x03;
      }
    }
  }
  return GRREG;
}

std::unique_ptr<CJBig2_Image> CJBig2_GRRDProc::DecodeTemplate1Opt(
    CJBig2_ArithDecoder* pArithDecoder,
    pdfium::span<JBig2ArithCtx> grContexts) {
  if (!GRREFERENCE->has_data()) {
    return nullptr;
  }

  int32_t iGRW = static_cast<int32_t>(GRW);
  int32_t iGRH = static_cast<int32_t>(GRH);
  auto GRREG = std::make_unique<CJBig2_Image>(iGRW, iGRH);
  if (!GRREG->has_data()) {
    return nullptr;
  }

  int LTP = 0;
  const int32_t GRWR = GRREFERENCE->width();
  const int32_t GRHR = GRREFERENCE->height();
  if (GRREFERENCEDY < -GRHR + 1 || GRREFERENCEDY > GRHR - 1) {
    GRREFERENCEDY = 0;
  }
  for (int32_t h = 0; h < iGRH; h++) {
    if (TPGRON) {
      if (pArithDecoder->IsComplete()) {
        return nullptr;
      }

      LTP = LTP ^ pArithDecoder->Decode(&grContexts[0x0008]);
    }
    const bool is_first_line = h == 0;
    pdfium::span<uint8_t> row_write = GRREG->GetLine(h);
    pdfium::span<const uint8_t> row_prev;
    uint32_t line1 = 0;
    if (!is_first_line) {
      row_prev = GRREG->GetLine(h - 1);
      line1 = row_prev.front() << 1;
    }
    const int32_t reference_h = h - GRREFERENCEDY;
    std::array<pdfium::span<const uint8_t>, 3> row_refs_dy;
    std::array<uint32_t, 3> refs = {};
    if (reference_h > 0 && reference_h < GRHR + 1) {
      row_refs_dy[0] = GRREFERENCE->GetLine(reference_h - 1);
      refs[0] = row_refs_dy[0].front();
    }
    if (reference_h > -1 && reference_h < GRHR) {
      row_refs_dy[1] = GRREFERENCE->GetLine(reference_h);
      refs[1] = row_refs_dy[1].front();
    }
    if (reference_h > -2 && reference_h < GRHR - 1) {
      row_refs_dy[2] = GRREFERENCE->GetLine(reference_h + 1);
      refs[2] = row_refs_dy[2].front();
    }
    if (!LTP) {
      uint32_t CONTEXT = (line1 & 0x0380) | ((refs[0] >> 2) & 0x0020) |
                         ((refs[1] >> 4) & 0x001c) | ((refs[2] >> 6) & 0x0003);
      for (int32_t w = 0; w < iGRW; w += 8) {
        int32_t nBits = iGRW - w > 8 ? 8 : iGRW - w;
        if (!is_first_line) {
          line1 = line1 << 8;
          if (w + 8 < iGRW) {
            line1 |= row_prev[w / 8 + 1] << 1;
          }
        }
        const bool next_w_in_bounds = w + 8 < GRWR;
        for (auto [row_ref_dy, ref] : fxcrt::Zip(row_refs_dy, refs)) {
          if (!row_ref_dy.empty()) {
            ref <<= 8;
            if (next_w_in_bounds) {
              ref |= row_ref_dy[(w / 8) + 1];
            }
          }
        }
        uint8_t cVal = 0;
        for (int32_t k = 0; k < nBits; k++) {
          int bVal = pArithDecoder->Decode(&grContexts[CONTEXT]);
          cVal |= bVal << (7 - k);
          CONTEXT = ((CONTEXT & 0x018d) << 1) | (bVal << 6) |
                    ((line1 >> (7 - k)) & 0x0080) |
                    ((refs[0] >> (9 - k)) & 0x0020) |
                    ((refs[1] >> (11 - k)) & 0x0004) |
                    ((refs[2] >> (13 - k)) & 0x0001);
        }
        row_write[w / 8] = cVal;
      }
    } else {
      std::array<pdfium::span<const uint8_t>, 3> row_refs = GetRowRefs(h);
      uint32_t CONTEXT = (line1 & 0x0380) | ((refs[0] >> 2) & 0x0020) |
                         ((refs[1] >> 4) & 0x001c) | ((refs[2] >> 6) & 0x0003);
      for (int32_t w = 0; w < iGRW; w += 8) {
        int32_t nBits = iGRW - w > 8 ? 8 : iGRW - w;
        if (!is_first_line) {
          line1 = line1 << 8;
          if (w + 8 < iGRW) {
            line1 |= row_prev[(w / 8) + 1] << 1;
          }
        }
        const bool next_w_in_bounds = w + 8 < GRWR;
        for (auto [row_ref_dy, ref] : fxcrt::Zip(row_refs_dy, refs)) {
          if (!row_ref_dy.empty()) {
            ref <<= 8;
            if (next_w_in_bounds) {
              ref |= row_ref_dy[(w / 8) + 1];
            }
          }
        }
        uint8_t cVal = 0;
        for (int32_t k = 0; k < nBits; k++) {
          int bVal = GRREFERENCE->GetPixel(w + k, row_refs[1]);
          if (!TPGRON || !TypicalPrediction(w + k, bVal, row_refs)) {
            if (pArithDecoder->IsComplete()) {
              return nullptr;
            }

            bVal = pArithDecoder->Decode(&grContexts[CONTEXT]);
          }
          cVal |= bVal << (7 - k);
          CONTEXT = ((CONTEXT & 0x018d) << 1) | (bVal << 6) |
                    ((line1 >> (7 - k)) & 0x0080) |
                    ((refs[0] >> (9 - k)) & 0x0020) |
                    ((refs[1] >> (11 - k)) & 0x0004) |
                    ((refs[2] >> (13 - k)) & 0x0001);
        }
        row_write[w / 8] = cVal;
      }
    }
  }
  return GRREG;
}

std::array<pdfium::span<const uint8_t>, 3> CJBig2_GRRDProc::GetRowRefs(
    uint32_t h) const {
  return {
      GRREFERENCE->GetLine(h - 1),
      GRREFERENCE->GetLine(h),
      GRREFERENCE->GetLine(h + 1),
  };
}

std::array<pdfium::span<const uint8_t>, 3> CJBig2_GRRDProc::GetRowRefsDy(
    uint32_t h) const {
  return {
      GRREFERENCE->GetLine(h - GRREFERENCEDY - 1),
      GRREFERENCE->GetLine(h - GRREFERENCEDY),
      GRREFERENCE->GetLine(h - GRREFERENCEDY + 1),
  };
}

bool CJBig2_GRRDProc::TypicalPrediction(
    int x,
    int val,
    pdfium::span<pdfium::span<const uint8_t>, 3> row_refs) const {
  return (val == GRREFERENCE->GetPixel(x - 1, row_refs[0])) &&
         (val == GRREFERENCE->GetPixel(x, row_refs[0])) &&
         (val == GRREFERENCE->GetPixel(x + 1, row_refs[0])) &&
         (val == GRREFERENCE->GetPixel(x - 1, row_refs[1])) &&
         (val == GRREFERENCE->GetPixel(x + 1, row_refs[1])) &&
         (val == GRREFERENCE->GetPixel(x - 1, row_refs[2])) &&
         (val == GRREFERENCE->GetPixel(x, row_refs[2])) &&
         (val == GRREFERENCE->GetPixel(x + 1, row_refs[2]));
}
