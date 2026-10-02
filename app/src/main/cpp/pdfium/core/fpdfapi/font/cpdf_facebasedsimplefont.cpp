// Copyright 2026 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "core/fpdfapi/font/cpdf_facebasedsimplefont.h"

#include <algorithm>
#include <array>
#include <utility>

#include "core/fpdfapi/parser/cpdf_array.h"
#include "core/fpdfapi/parser/cpdf_dictionary.h"
#include "core/fxcrt/fx_codepage.h"
#include "core/fxge/cfx_face.h"
#include "core/fxge/fx_font.h"

CPDF_FaceBasedSimpleFont::CPDF_FaceBasedSimpleFont(
    CPDF_Document* document,
    RetainPtr<CPDF_Dictionary> font_dict)
    : CPDF_SimpleFont(document, std::move(font_dict)) {
  char_width_.fill(0xffff);
  glyph_index_.fill(0xffff);
  char_bbox_.fill(FX_RECT(-1, -1, -1, -1));
}

CPDF_FaceBasedSimpleFont::~CPDF_FaceBasedSimpleFont() = default;

int CPDF_FaceBasedSimpleFont::GlyphFromCharCode(uint32_t charcode,
                                                bool* pVertGlyph) {
  if (pVertGlyph) {
    *pVertGlyph = false;
  }

  if (charcode > 0xff) {
    return -1;
  }

  int index = glyph_index_[charcode];
  if (index == 0xffff) {
    return -1;
  }

  return index;
}

void CPDF_FaceBasedSimpleFont::LoadCharMetrics(int charcode) {
  if (!font_.HasFace()) {
    return;
  }
  if (charcode < 0 || charcode > 0xff) {
    return;
  }
  int glyph_index = glyph_index_[charcode];
  if (glyph_index == 0xffff) {
    if (!font_file_ && charcode != 32) {
      LoadCharMetrics(32);
      char_bbox_[charcode] = char_bbox_[32];
      if (use_font_width_) {
        char_width_[charcode] = char_width_[32];
      }
    }
    return;
  }
  RetainPtr<CFX_Face> face = font_.GetFace();
  if (!face) {
    return;
  }

  int err = face->LoadGlyph(glyph_index, /*scale=*/false);
  if (err) {
    return;
  }

  char_bbox_[charcode] = face->GetGlyphBBox(glyph_index);
  if (use_font_width_) {
    int TT_Width = face->GetGlyphTTWidth(glyph_index);
    if (char_width_[charcode] == 0xffff) {
      char_width_[charcode] = TT_Width;
    } else if (TT_Width && !IsEmbedded()) {
      char_bbox_[charcode].right =
          char_bbox_[charcode].right * char_width_[charcode] / TT_Width;
      char_bbox_[charcode].left =
          char_bbox_[charcode].left * char_width_[charcode] / TT_Width;
    }
  }
}

void CPDF_FaceBasedSimpleFont::LoadCharWidths(
    const CPDF_Dictionary* font_desc) {
  RetainPtr<const CPDF_Array> width_array = font_dict_->GetArrayFor("Widths");
  use_font_width_ = !width_array;
  if (!width_array) {
    return;
  }

  if (font_desc && font_desc->KeyExist("MissingWidth")) {
    int missing_width = font_desc->GetIntegerFor("MissingWidth");
    std::ranges::fill(char_width_, missing_width);
  }

  size_t width_start = font_dict_->GetIntegerFor("FirstChar", 0);
  size_t width_end = font_dict_->GetIntegerFor("LastChar", 0);
  if (width_start > 255) {
    return;
  }

  if (width_end == 0 || width_end >= width_start + width_array->size()) {
    width_end = width_start + width_array->size() - 1;
  }
  if (width_end > 255) {
    width_end = 255;
  }
  for (size_t i = width_start; i <= width_end; i++) {
    char_width_[i] = width_array->GetIntegerAt(i - width_start);
  }
}

int CPDF_FaceBasedSimpleFont::GetCharWidth(uint32_t charcode) {
  if (charcode > 0xff) {
    charcode = 0;
  }

  if (char_width_[charcode] == 0xffff) {
    LoadCharMetrics(charcode);
    if (char_width_[charcode] == 0xffff) {
      char_width_[charcode] = 0;
    }
  }
  return char_width_[charcode];
}

FX_RECT CPDF_FaceBasedSimpleFont::GetCharBBox(uint32_t charcode) {
  if (charcode > 0xff) {
    charcode = 0;
  }

  if (char_bbox_[charcode].left == -1) {
    LoadCharMetrics(charcode);
  }

  return char_bbox_[charcode];
}

bool CPDF_FaceBasedSimpleFont::LoadCommon() {
  RetainPtr<const CPDF_Dictionary> font_desc =
      font_dict_->GetDictFor("FontDescriptor");
  if (font_desc) {
    LoadFontDescriptor(font_desc.Get());
  }
  LoadCharWidths(font_desc.Get());
  if (font_file_) {
    MaybeRemoveSubsettedFontPrefix(base_font_name_);
  } else {
    LoadSubstFont();
  }
  if (!FontStyleIsSymbolic(flags_)) {
    base_encoding_ = FontEncoding::kStandard;
  }
  LoadPDFEncoding(!!font_file_, font_.IsTTFont());
  LoadGlyphMap();
  char_names_.clear();
  if (!HasFace()) {
    return true;
  }

  if (FontStyleIsAllCaps(flags_)) {
    static const auto kLowercases =
        std::to_array<std::pair<const uint8_t, const uint8_t>>(
            {{'a', 'z'}, {0xe0, 0xf6}, {0xf8, 0xfd}});
    for (const auto& lower : kLowercases) {
      for (int i = lower.first; i <= lower.second; ++i) {
        if (glyph_index_[i] != 0xffff && font_file_) {
          continue;
        }
        int j = i - 32;
        glyph_index_[i] = glyph_index_[j];
        if (char_width_[j]) {
          char_width_[i] = char_width_[j];
          char_bbox_[i] = char_bbox_[j];
        }
      }
    }
  }
  CheckFontMetrics();
  return true;
}

void CPDF_FaceBasedSimpleFont::LoadSubstFont() {
  if (!use_font_width_ && !FontStyleIsFixedPitch(flags_)) {
    int width = 0;
    size_t i;
    for (i = 0; i < kInternalTableSize; i++) {
      if (char_width_[i] == 0 || char_width_[i] == 0xffff) {
        continue;
      }

      if (width == 0) {
        width = char_width_[i];
      } else if (width != char_width_[i]) {
        break;
      }
    }
    if (i == kInternalTableSize && width) {
      flags_ |= pdfium::kFontStyleFixedPitch;
    }
  }

  int weight = GetFontWeight().value_or(pdfium::kFontWeightNormal);
  if (weight < pdfium::kFontWeightExtraLight ||
      weight > pdfium::kFontWeightExtraBold) {
    weight = pdfium::kFontWeightNormal;
  }
  font_.LoadSubstFace(base_font_name_, IsTrueTypeFont(), flags_, weight,
                      italic_angle_, FX_CodePage::kDefANSI,
                      /*bVertical=*/false);
}

bool CPDF_FaceBasedSimpleFont::HasFontWidths() const {
  return !use_font_width_;
}
