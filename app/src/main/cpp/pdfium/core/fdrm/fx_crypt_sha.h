// Copyright 2024 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef CORE_FDRM_FX_CRYPT_SHA_H_
#define CORE_FDRM_FX_CRYPT_SHA_H_

#include <stdint.h>

#include <array>

#include "core/fxcrt/data_vector.h"
#include "core/fxcrt/span.h"

struct CryptSha1Context {
  uint64_t total_bytes;
  uint32_t blkused;  // Constrained to [0, 64).
  std::array<uint32_t, 5> h;
  std::array<uint8_t, 64> block;
};

struct CryptSha2Context {
  uint64_t total_bytes;
  std::array<uint64_t, 8> state;
  uint8_t buffer[128];
};

void CryptSha1Start(CryptSha1Context* context);
void CryptSha1Update(CryptSha1Context* context,
                     pdfium::span<const uint8_t> data);
void CryptSha1Finish(CryptSha1Context* context,
                     pdfium::span<uint8_t, 20> digest);
DataVector<uint8_t> CryptSha1Generate(pdfium::span<const uint8_t> data);

void CryptSha256Start(CryptSha2Context* context);
void CryptSha256Update(CryptSha2Context* context,
                       pdfium::span<const uint8_t> data);
void CryptSha256Finish(CryptSha2Context* context,
                       pdfium::span<uint8_t, 32> digest);
DataVector<uint8_t> CryptSha256Generate(pdfium::span<const uint8_t> data);

void CryptSha384Start(CryptSha2Context* context);
void CryptSha384Update(CryptSha2Context* context,
                       pdfium::span<const uint8_t> data);
void CryptSha384Finish(CryptSha2Context* context,
                       pdfium::span<uint8_t, 48> digest);
DataVector<uint8_t> CryptSha384Generate(pdfium::span<const uint8_t> data);

void CryptSha512Start(CryptSha2Context* context);
void CryptSha512Update(CryptSha2Context* context,
                       pdfium::span<const uint8_t> data);
void CryptSha512Finish(CryptSha2Context* context,
                       pdfium::span<uint8_t, 64> digest);
DataVector<uint8_t> CryptSha512Generate(pdfium::span<const uint8_t> data);

#endif  // CORE_FDRM_FX_CRYPT_SHA_H_
