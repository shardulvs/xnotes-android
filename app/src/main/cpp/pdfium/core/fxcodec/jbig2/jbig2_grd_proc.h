// Copyright 2015 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef CORE_FXCODEC_JBIG2_JBIG2_GRD_PROC_H_
#define CORE_FXCODEC_JBIG2_JBIG2_GRD_PROC_H_

#include <stdint.h>

#include <array>
#include <memory>

#include "core/fxcodec/fx_codec_def.h"
#include "core/fxcrt/fx_coordinates.h"
#include "core/fxcrt/raw_span.h"
#include "core/fxcrt/span.h"
#include "core/fxcrt/unowned_ptr.h"

class CJBig2_ArithDecoder;
class CJBig2_BitStream;
class CJBig2_Image;
class JBig2ArithCtx;
class PauseIndicatorIface;

class CJBig2_GRDProc {
 public:
  class ProgressiveArithDecodeState {
   public:
    ProgressiveArithDecodeState();
    ~ProgressiveArithDecodeState();

    UnownedPtr<std::unique_ptr<CJBig2_Image>> pImage;
    UnownedPtr<CJBig2_ArithDecoder> pArithDecoder;
    pdfium::span<JBig2ArithCtx> gbContexts;
    UnownedPtr<PauseIndicatorIface> pPause;
  };

  CJBig2_GRDProc();
  ~CJBig2_GRDProc();

  std::unique_ptr<CJBig2_Image> DecodeArith(
      CJBig2_ArithDecoder* pArithDecoder,
      pdfium::span<JBig2ArithCtx> gbContexts);

  FXCODEC_STATUS StartDecodeArith(ProgressiveArithDecodeState* pState);
  FXCODEC_STATUS StartDecodeMMR(std::unique_ptr<CJBig2_Image>* pImage,
                                CJBig2_BitStream* pStream);
  FXCODEC_STATUS ContinueDecode(ProgressiveArithDecodeState* pState);
  void FinishDecode();

  const FX_RECT& GetReplaceRect() const { return replace_rect_; }

  bool MMR;
  bool TPGDON;
  bool USESKIP;
  uint8_t GBTEMPLATE;
  uint32_t GBW;
  uint32_t GBH;
  UnownedPtr<CJBig2_Image> SKIP;
  std::array<int8_t, 8> GBAT;

 private:
  struct TemplateOpt3Params {
    uint32_t tp_ctx;
    uint32_t context_mask;
    int val1_shift;
    uint32_t val1_context_mask;
    uint32_t val1_bit_mask;
    int val2_shift;
    uint32_t val2_context_mask;
    uint32_t val2_bit_mask;
  };

  bool UseTemplate0Opt3() const;
  bool UseTemplate1Opt3() const;
  bool UseTemplate23Opt3() const;

  FXCODEC_STATUS ProgressiveDecodeArith(ProgressiveArithDecodeState* pState);
  FXCODEC_STATUS ProgressiveDecodeArithTemplate0Opt3(
      ProgressiveArithDecodeState* pState);
  FXCODEC_STATUS ProgressiveDecodeArithTemplate0Unopt(
      ProgressiveArithDecodeState* pState);
  FXCODEC_STATUS ProgressiveDecodeArithTemplate1Opt3(
      ProgressiveArithDecodeState* pState);
  FXCODEC_STATUS ProgressiveDecodeArithTemplate1Unopt(
      ProgressiveArithDecodeState* pState);
  FXCODEC_STATUS ProgressiveDecodeArithTemplate2Opt3(
      ProgressiveArithDecodeState* pState);
  FXCODEC_STATUS ProgressiveDecodeArithTemplate2Unopt(
      ProgressiveArithDecodeState* pState);
  FXCODEC_STATUS ProgressiveDecodeArithTemplate3Opt3(
      ProgressiveArithDecodeState* pState);
  FXCODEC_STATUS ProgressiveDecodeArithTemplate3Unopt(
      ProgressiveArithDecodeState* pState);

  bool ProgressiveDecodeArithTemplateOpt3Helper(
      ProgressiveArithDecodeState* state,
      const TemplateOpt3Params& params,
      uint32_t nLineBytes,
      uint32_t nBitsLeft);

  void AdvanceLine(const CJBig2_Image* image);
  void CopyPrevLine(CJBig2_Image* image);

  template <int OPT>
  std::unique_ptr<CJBig2_Image> DecodeArithOpt3(
      CJBig2_ArithDecoder* pArithDecoder,
      pdfium::span<JBig2ArithCtx> gbContexts);
  template <int UNOPT>
  std::unique_ptr<CJBig2_Image> DecodeArithTemplateUnopt(
      CJBig2_ArithDecoder* pArithDecoder,
      pdfium::span<JBig2ArithCtx> gbContexts);
  std::unique_ptr<CJBig2_Image> DecodeArithTemplate3Opt3(
      CJBig2_ArithDecoder* pArithDecoder,
      pdfium::span<JBig2ArithCtx> gbContexts);
  std::unique_ptr<CJBig2_Image> DecodeArithTemplate3Unopt(
      CJBig2_ArithDecoder* pArithDecoder,
      pdfium::span<JBig2ArithCtx> gbContexts);

  uint32_t loop_index_ = 0;
  pdfium::raw_span<const uint8_t> line_prev2_;
  pdfium::raw_span<const uint8_t> line_prev1_;
  pdfium::raw_span<uint8_t> line_;
  FXCODEC_STATUS progressive_status_;
  uint16_t decode_type_ = 0;
  int ltp_ = 0;
  FX_RECT replace_rect_;
};

#endif  // CORE_FXCODEC_JBIG2_JBIG2_GRD_PROC_H_
