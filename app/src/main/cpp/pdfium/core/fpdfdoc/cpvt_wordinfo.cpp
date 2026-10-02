// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
//
// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fpdfdoc/cpvt_wordinfo.h"

#include "core/fxcrt/fx_codepage.h"

CPVT_WordInfo::CPVT_WordInfo(uint16_t word,
                             FX_Charset charset,
                             int32_t fontIndex)
    : Word(word),
      nCharset(charset),
      fWordX(0.0f),
      fWordY(0.0f),
      fWordTail(0.0f),
      nFontIndex(fontIndex),
      is_rtl(false) {}

CPVT_WordInfo::CPVT_WordInfo(const CPVT_WordInfo& word) = default;

CPVT_WordInfo::~CPVT_WordInfo() = default;

CPVT_WordInfo& CPVT_WordInfo::operator=(const CPVT_WordInfo& word) = default;
