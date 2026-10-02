// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef CORE_FPDFAPI_PAGE_CPDF_TRANSFERFUNC_H_
#define CORE_FPDFAPI_PAGE_CPDF_TRANSFERFUNC_H_

#include <stdint.h>

#include "core/fxcrt/fixed_size_data_vector.h"
#include "core/fxcrt/observed_ptr.h"
#include "core/fxcrt/retain_ptr.h"
#include "core/fxcrt/span.h"
#include "core/fxge/dib/fx_dib.h"

class CFX_DIBBase;

class CPDF_TransferFunc final : public Retainable, public Observable {
 public:
  CONSTRUCT_VIA_MAKE_RETAIN;

  static constexpr size_t kChannelSampleSize = 256;

  FX_COLORREF TranslateColor(FX_COLORREF colorref) const;
  RetainPtr<CFX_DIBBase> TranslateImage(RetainPtr<CFX_DIBBase> pSrc);

  pdfium::span<const uint8_t, kChannelSampleSize> GetSamplesR() const;
  pdfium::span<const uint8_t, kChannelSampleSize> GetSamplesG() const;
  pdfium::span<const uint8_t, kChannelSampleSize> GetSamplesB() const;

  bool GetIdentity() const { return identity_; }

 private:
  CPDF_TransferFunc(bool bIdentify,
                    FixedSizeDataVector<uint8_t> samples_r,
                    FixedSizeDataVector<uint8_t> samples_g,
                    FixedSizeDataVector<uint8_t> samples_b);
  ~CPDF_TransferFunc() override;

  const bool identity_;
  const FixedSizeDataVector<uint8_t> samples_r_;
  const FixedSizeDataVector<uint8_t> samples_g_;
  const FixedSizeDataVector<uint8_t> samples_b_;
};

#endif  // CORE_FPDFAPI_PAGE_CPDF_TRANSFERFUNC_H_
