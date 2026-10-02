// Copyright 2014 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef CORE_FDRM_FX_CRYPT_H_
#define CORE_FDRM_FX_CRYPT_H_

#include <stdint.h>
#include <array>

#include "core/fdrm/fx_crypt_aes.h"
#include "core/fdrm/fx_crypt_sha.h"
#include "core/fxcrt/span.h"

struct CryptRc4Context {
  static constexpr int32_t kPermutationLength = 256;

  int32_t x;
  int32_t y;
  std::array<int32_t, kPermutationLength> m;
};

struct CryptMd5Context {
  std::array<uint32_t, 2> total;
  std::array<uint32_t, 4> state;
  uint8_t buffer[64];
};

void CryptArcFourCryptBlock(pdfium::span<uint8_t> data,
                            pdfium::span<const uint8_t> key);
void CryptArcFourSetup(CryptRc4Context* context,
                       pdfium::span<const uint8_t> key);
void CryptArcFourCrypt(CryptRc4Context* context, pdfium::span<uint8_t> data);

CryptMd5Context CryptMd5Start();
void CryptMd5Update(CryptMd5Context* context, pdfium::span<const uint8_t> data);
void CryptMd5Finish(CryptMd5Context* context, pdfium::span<uint8_t, 16> digest);
void CryptMd5Generate(pdfium::span<const uint8_t> data,
                      pdfium::span<uint8_t, 16> digest);

#endif  // CORE_FDRM_FX_CRYPT_H_
