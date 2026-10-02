// Copyright 2014 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxcodec/jbig2/jbig2_huffman_decoder.h"

#include <optional>

#include "core/fxcodec/jbig2/jbig2_define.h"
#include "core/fxcrt/fx_safe_types.h"

CJBig2_HuffmanDecoder::CJBig2_HuffmanDecoder(CJBig2_BitStream* pStream)
    : stream_(pStream) {}

CJBig2_HuffmanDecoder::~CJBig2_HuffmanDecoder() = default;

int CJBig2_HuffmanDecoder::DecodeAValue(const CJBig2_HuffmanTable* pTable,
                                        int* nResult) {
  FX_SAFE_UINT32 nSafeVal = 0;
  const unsigned max_codelen = pTable->MaxCodeLen();
  for (unsigned nBits = 1; nBits <= max_codelen; ++nBits) {
    uint32_t nTmp;
    if (stream_->read1Bit(&nTmp) == -1) {
      return -1;
    }

    nSafeVal <<= 1;
    if (!nSafeVal.IsValid()) {
      return -1;
    }

    nSafeVal |= nTmp;
    std::optional<uint32_t> line =
        pTable->FindLine(nBits, nSafeVal.ValueOrDie());
    if (!line.has_value()) {
      continue;
    }

    const uint32_t i = line.value();
    if (pTable->IsHTOOB() && i == pTable->Size() - 1) {
      return kJBig2OOB;
    }

    if (stream_->readNBits(pTable->GetRANGELEN()[i], &nTmp) == -1) {
      return -1;
    }

    uint32_t offset = pTable->IsHTOOB() ? 3 : 2;
    if (i == pTable->Size() - offset) {
      *nResult = pTable->GetRANGELOW()[i] - nTmp;
    } else {
      *nResult = pTable->GetRANGELOW()[i] + nTmp;
    }
    return 0;
  }
  return -1;
}
