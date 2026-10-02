// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxge/cfx_charmap_resolver.h"

#include "core/fxcrt/fx_codepage.h"
#include "core/fxge/cfx_face.h"
#include "core/fxge/cfx_font.h"
#include "core/fxge/cfx_substfont.h"
#include "core/fxge/fx_font.h"
#include "core/fxge/fx_fontencoding.h"

namespace {

class UnicodeCharmapResolver : public CFX_CharmapResolver {
 public:
  explicit UnicodeCharmapResolver(const CFX_Font* font)
      : CFX_CharmapResolver(font) {}

  ~UnicodeCharmapResolver() override = default;

  uint32_t GlyphFromCharCode(uint32_t charcode) override {
    RetainPtr<CFX_Face> face = font_->GetFace();
    if (!face) {
      return charcode;
    }
    if (face->SelectCharMap(fxge::FontEncoding::kUnicode)) {
      return face->GetCharIndex(charcode);
    }
    if (font_->GetSubstFont() && font_->GetSubstFont()->IsSymbolic()) {
      uint32_t index = 0;
      if (face->SelectCharMap(fxge::FontEncoding::kSymbol)) {
        index = face->GetCharIndex(charcode);
      }
      if (!index && face->SelectCharMap(fxge::FontEncoding::kAppleRoman)) {
        return face->GetCharIndex(charcode);
      }
    }
    return charcode;
  }
};

#if defined(PDF_ENABLE_XFA)
constexpr fxge::FontEncoding kEncodingIDs[] = {
    fxge::FontEncoding::kSymbol,      fxge::FontEncoding::kUnicode,
    fxge::FontEncoding::kSjis,        fxge::FontEncoding::kGB2312,
    fxge::FontEncoding::kBig5,        fxge::FontEncoding::kWansung,
    fxge::FontEncoding::kJohab,       fxge::FontEncoding::kAdobeStandard,
    fxge::FontEncoding::kAdobeExpert, fxge::FontEncoding::kAdobeCustom,
    fxge::FontEncoding::kLatin1,      fxge::FontEncoding::kOldLatin2,
    fxge::FontEncoding::kAppleRoman,
};

class AlternateCharmapResolver : public CFX_CharmapResolver {
 public:
  AlternateCharmapResolver(const CFX_Font* font, fxge::FontEncoding encoding_id)
      : CFX_CharmapResolver(font), encoding_id_(encoding_id) {}

  ~AlternateCharmapResolver() override = default;

  uint32_t GlyphFromCharCode(uint32_t charcode) override {
    RetainPtr<CFX_Face> face = font_->GetFace();
    uint32_t char_index = face->GetCharIndex(charcode);
    if (char_index > 0) {
      return char_index;
    }
    size_t map_index = 0;
    while (map_index < face->GetCharMapCount()) {
      fxge::FontEncoding encoding_id =
          face->GetCharMapEncodingByIndex(map_index++);
      if (encoding_id_ == encoding_id) {
        continue;
      }
      if (!face->SelectCharMap(encoding_id)) {
        continue;
      }
      char_index = face->GetCharIndex(charcode);
      if (char_index > 0) {
        encoding_id_ = encoding_id;
        return char_index;
      }
    }
    face->SelectCharMap(encoding_id_);
    return 0;
  }

 private:
  fxge::FontEncoding encoding_id_;
};
#endif  // defined(PDF_ENABLE_XFA)

}  // namespace

CFX_CharmapResolver::CFX_CharmapResolver(const CFX_Font* font) : font_(font) {}

CFX_CharmapResolver::~CFX_CharmapResolver() = default;

// static
std::unique_ptr<CFX_CharmapResolver> CFX_CharmapResolver::CreateUnicode(
    const CFX_Font* font) {
  return std::make_unique<UnicodeCharmapResolver>(font);
}

#if defined(PDF_ENABLE_XFA)
std::unique_ptr<CFX_CharmapResolver> CFX_CharmapResolver::CreateAlternate(
    const CFX_Font* font) {
  if (!font || !font->GetFace()) {
    return nullptr;
  }
  for (fxge::FontEncoding id : kEncodingIDs) {
    if (font->GetFace()->SelectCharMap(id)) {
      return std::make_unique<AlternateCharmapResolver>(font, id);
    }
  }
  return nullptr;
}
#endif  // defined(PDF_ENABLE_XFA)
