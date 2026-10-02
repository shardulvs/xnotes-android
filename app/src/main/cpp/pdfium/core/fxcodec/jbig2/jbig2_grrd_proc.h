// Copyright 2015 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef CORE_FXCODEC_JBIG2_JBIG2_GRRD_PROC_H_
#define CORE_FXCODEC_JBIG2_JBIG2_GRRD_PROC_H_

#include <stdint.h>

#include <array>
#include <memory>

#include "core/fxcrt/span.h"
#include "core/fxcrt/unowned_ptr.h"

class CJBig2_ArithDecoder;
class CJBig2_Image;
class JBig2ArithCtx;

class CJBig2_GRRDProc {
 public:
  CJBig2_GRRDProc();
  ~CJBig2_GRRDProc();

  std::unique_ptr<CJBig2_Image> Decode(CJBig2_ArithDecoder* pArithDecoder,
                                       pdfium::span<JBig2ArithCtx> grContexts);

  bool GRTEMPLATE;
  bool TPGRON;
  uint32_t GRW;
  uint32_t GRH;
  int32_t GRREFERENCEDX;
  int32_t GRREFERENCEDY;
  UnownedPtr<CJBig2_Image> GRREFERENCE;
  std::array<int8_t, 4> GRAT;

 private:
  uint32_t DecodeTemplate0UnoptCalculateContext(
      const CJBig2_Image& GRREG,
      pdfium::span<const uint32_t, 5> lines,
      uint32_t w,
      pdfium::span<const uint8_t> row_ref_grat,
      pdfium::span<const uint8_t> row_grat) const;
  void DecodeTemplate0UnoptSetPixel(
      CJBig2_Image* GRREG,
      pdfium::span<uint32_t, 5> lines,
      uint32_t w,
      int bVal,
      pdfium::span<pdfium::span<const uint8_t>, 3> row_refs_dy,
      pdfium::span<const uint8_t> row_prev,
      pdfium::span<uint8_t> row_write);

  std::unique_ptr<CJBig2_Image> DecodeTemplate0Unopt(
      CJBig2_ArithDecoder* pArithDecoder,
      pdfium::span<JBig2ArithCtx> grContexts);

  std::unique_ptr<CJBig2_Image> DecodeTemplate0Opt(
      CJBig2_ArithDecoder* pArithDecoder,
      pdfium::span<JBig2ArithCtx> grContexts);

  std::unique_ptr<CJBig2_Image> DecodeTemplate1Unopt(
      CJBig2_ArithDecoder* pArithDecoder,
      pdfium::span<JBig2ArithCtx> grContexts);

  std::unique_ptr<CJBig2_Image> DecodeTemplate1Opt(
      CJBig2_ArithDecoder* pArithDecoder,
      pdfium::span<JBig2ArithCtx> grContexts);

  std::array<pdfium::span<const uint8_t>, 3> GetRowRefs(uint32_t h) const;
  std::array<pdfium::span<const uint8_t>, 3> GetRowRefsDy(uint32_t h) const;
  bool TypicalPrediction(
      int x,
      int val,
      pdfium::span<pdfium::span<const uint8_t>, 3> row_refs) const;
};

#endif  // CORE_FXCODEC_JBIG2_JBIG2_GRRD_PROC_H_
