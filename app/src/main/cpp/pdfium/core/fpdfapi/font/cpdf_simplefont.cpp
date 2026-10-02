// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fpdfapi/font/cpdf_simplefont.h"

#include <utility>
#include <vector>

#include "constants/font_encodings.h"
#include "core/fpdfapi/parser/cpdf_array.h"
#include "core/fpdfapi/parser/cpdf_dictionary.h"
#include "core/fpdfapi/parser/cpdf_name.h"
#include "core/fxge/fx_font.h"

namespace {

void GetPredefinedEncoding(const ByteString& value, FontEncoding* basemap) {
  if (value == pdfium::font_encodings::kWinAnsiEncoding) {
    *basemap = FontEncoding::kWinAnsi;
  } else if (value == pdfium::font_encodings::kMacRomanEncoding) {
    *basemap = FontEncoding::kMacRoman;
  } else if (value == pdfium::font_encodings::kMacExpertEncoding) {
    *basemap = FontEncoding::kMacExpert;
  } else if (value == pdfium::font_encodings::kPDFDocEncoding) {
    *basemap = FontEncoding::kPdfDoc;
  }
}

}  // namespace

CPDF_SimpleFont::CPDF_SimpleFont(CPDF_Document* document,
                                 RetainPtr<CPDF_Dictionary> font_dict)
    : CPDF_Font(document, std::move(font_dict)) {}

CPDF_SimpleFont::~CPDF_SimpleFont() = default;

void CPDF_SimpleFont::LoadDifferences(const CPDF_Dictionary* encoding) {
  RetainPtr<const CPDF_Array> diffs = encoding->GetArrayFor("Differences");
  if (!diffs) {
    return;
  }

  char_names_.resize(kInternalTableSize);
  uint32_t cur_code = 0;
  for (uint32_t i = 0; i < diffs->size(); i++) {
    RetainPtr<const CPDF_Object> element = diffs->GetDirectObjectAt(i);
    if (!element) {
      continue;
    }

    const CPDF_Name* name = element->AsName();
    if (name) {
      if (cur_code < char_names_.size()) {
        char_names_[cur_code] = name->GetString();
      }
      cur_code++;
    } else {
      cur_code = element->GetInteger();
    }
  }
}

void CPDF_SimpleFont::LoadPDFEncoding(bool bEmbedded, bool bTrueType) {
  RetainPtr<const CPDF_Object> pEncoding =
      font_dict_->GetDirectObjectFor("Encoding");
  if (!pEncoding) {
    if (base_font_name_ == "Symbol") {
      base_encoding_ =
          bTrueType ? FontEncoding::kMsSymbol : FontEncoding::kAdobeSymbol;
    } else if (!bEmbedded && base_encoding_ == FontEncoding::kBuiltin) {
      base_encoding_ = FontEncoding::kWinAnsi;
    }
    return;
  }
  if (pEncoding->IsName()) {
    if (base_encoding_ == FontEncoding::kAdobeSymbol ||
        base_encoding_ == FontEncoding::kZapfDingbats) {
      return;
    }
    if (FontStyleIsSymbolic(flags_) && base_font_name_ == "Symbol") {
      if (!bTrueType) {
        base_encoding_ = FontEncoding::kAdobeSymbol;
      }
      return;
    }
    ByteString bsEncoding = pEncoding->GetString();
    if (bsEncoding == pdfium::font_encodings::kMacExpertEncoding) {
      bsEncoding = pdfium::font_encodings::kWinAnsiEncoding;
    }
    GetPredefinedEncoding(bsEncoding, &base_encoding_);
    return;
  }

  const CPDF_Dictionary* dict = pEncoding->AsDictionary();
  if (!dict) {
    return;
  }

  if (base_encoding_ != FontEncoding::kAdobeSymbol &&
      base_encoding_ != FontEncoding::kZapfDingbats) {
    ByteString bsEncoding = dict->GetByteStringFor("BaseEncoding");
    if (bTrueType && bsEncoding == pdfium::font_encodings::kMacExpertEncoding) {
      bsEncoding = pdfium::font_encodings::kWinAnsiEncoding;
    }
    GetPredefinedEncoding(bsEncoding, &base_encoding_);
  }
  if ((!bEmbedded || bTrueType) && base_encoding_ == FontEncoding::kBuiltin) {
    base_encoding_ = FontEncoding::kStandard;
  }

  LoadDifferences(dict);
}

bool CPDF_SimpleFont::IsUnicodeCompatible() const {
  return base_encoding_ != FontEncoding::kBuiltin &&
         base_encoding_ != FontEncoding::kAdobeSymbol &&
         base_encoding_ != FontEncoding::kZapfDingbats;
}

WideString CPDF_SimpleFont::UnicodeFromCharCode(uint32_t charcode) const {
  WideString unicode = CPDF_Font::UnicodeFromCharCode(charcode);
  if (!unicode.IsEmpty()) {
    return unicode;
  }
  wchar_t ret = encoding_.UnicodeFromCharCode((uint8_t)charcode);
  if (ret == 0) {
    return WideString();
  }
  return WideString(ret);
}

uint32_t CPDF_SimpleFont::CharCodeFromUnicode(wchar_t unicode) const {
  uint32_t ret = CPDF_Font::CharCodeFromUnicode(unicode);
  if (ret) {
    return ret;
  }
  return encoding_.CharCodeFromUnicode(unicode);
}

int CPDF_SimpleFont::GlyphFromCharCode(uint32_t charcode, bool* pVertGlyph) {
  if (pVertGlyph) {
    *pVertGlyph = false;
  }
  return -1;
}
