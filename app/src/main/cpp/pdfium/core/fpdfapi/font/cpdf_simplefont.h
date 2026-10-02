// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef CORE_FPDFAPI_FONT_CPDF_SIMPLEFONT_H_
#define CORE_FPDFAPI_FONT_CPDF_SIMPLEFONT_H_

#include <stdint.h>

#include <vector>

#include "core/fpdfapi/font/cpdf_font.h"
#include "core/fpdfapi/font/cpdf_fontencoding.h"
#include "core/fxcrt/fx_string.h"

// 8-bit fonts, supporting at most 256 characters mapped via /Encoding.
// See ISO 32000-1:2008, section 9.6
class CPDF_SimpleFont : public CPDF_Font {
 public:
  ~CPDF_SimpleFont() override;

  // CPDF_Font
  bool IsUnicodeCompatible() const override;
  int GlyphFromCharCode(uint32_t charcode, bool* pVertGlyph) override;
  WideString UnicodeFromCharCode(uint32_t charcode) const override;
  uint32_t CharCodeFromUnicode(wchar_t Unicode) const override;

  const CPDF_FontEncoding* GetEncoding() const { return &encoding_; }

  static constexpr char kNotDef[] = ".notdef";
  static constexpr char kSpace[] = "space";

 protected:
  static constexpr size_t kInternalTableSize = 256;

  CPDF_SimpleFont(CPDF_Document* document,
                  RetainPtr<CPDF_Dictionary> font_dict);

  void LoadDifferences(const CPDF_Dictionary* encoding);
  void LoadPDFEncoding(bool bEmbedded, bool bTrueType);

  CPDF_FontEncoding encoding_{FontEncoding::kBuiltin};
  FontEncoding base_encoding_ = FontEncoding::kBuiltin;
  std::vector<ByteString> char_names_;
};

#endif  // CORE_FPDFAPI_FONT_CPDF_SIMPLEFONT_H_
