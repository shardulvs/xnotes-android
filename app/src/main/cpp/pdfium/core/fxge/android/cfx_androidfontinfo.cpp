// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxge/android/cfx_androidfontinfo.h"

#include <algorithm>
#include <functional>
#include <utility>

#include "core/fxcrt/compiler_specific.h"
#include "core/fxcrt/fx_codepage.h"
#include "core/fxcrt/fx_extension.h"
#include "core/fxcrt/span.h"

namespace {

constexpr char kRoboto[] = "Roboto";
constexpr char kDroidSans[] = "Droid Sans";
constexpr char kDroidSerif[] = "Droid Serif";
constexpr char kDroidSansMono[] = "Droid Sans Mono";
constexpr char kDroidSansFallback[] = "Droid Sans Fallback";

// Repeat the function from bytestring.cpp here, so it can be used in
// a constexpr context. Then make the compiler prove the mappings are
// correct.
constexpr uint32_t HashNormalizeFontName(const char* family) {
  uint32_t hash_code = 0;
  UNSAFE_BUFFERS({
    for (size_t i = 0; family[i] != '\0'; ++i) {
      char ch = family[i];
      if (ch == ' ' || ch == '-' || ch == ',') {
        continue;
      }
      hash_code = 31 * hash_code + FXSYS_ToLowerASCII(ch);
    }
  });
  return hash_code;
}

static_assert(HashNormalizeFontName("Arial") == 0x058c5083);
static_assert(HashNormalizeFontName("Baskerville") == 0x3d49f40e);
static_assert(HashNormalizeFontName("Courier New") == 0x83746053);
static_assert(HashNormalizeFontName("Courier") == 0x3918fe2d);
static_assert(HashNormalizeFontName("Cursive") == 0x432c41c5);
static_assert(HashNormalizeFontName("Fantasy") == 0xbf85ff26);
static_assert(HashNormalizeFontName("Georgia") == 0xfb4ce0de);
static_assert(HashNormalizeFontName("Goudy") == 0x05dfade2);
static_assert(HashNormalizeFontName("Monaco") == 0xc04fe601);
static_assert(HashNormalizeFontName("Monospace") == 0xaaa60c03);
static_assert(HashNormalizeFontName("Palatino") == 0x3b98b31c);
static_assert(HashNormalizeFontName("STSong") == 0xcad5eaf6);
static_assert(HashNormalizeFontName("Sanserif") == 0x779ce19d);
static_assert(HashNormalizeFontName("Serif") == 0x0684317d);
static_assert(HashNormalizeFontName("SimHei") == 0xca3812d5);
static_assert(HashNormalizeFontName("SimSun") == 0xca383e15);
static_assert(HashNormalizeFontName("Tahoma") == 0xcb7a04c8);
static_assert(HashNormalizeFontName("Verdana") == 0x14ee2d13);

struct AndroidFontMap {
  uint32_t family;
  const char* subst;
};

const AndroidFontMap kAndroidFontmap[] = {
    {0x058c5083, kRoboto},             // Arial
    {0x05dfade2, kDroidSerif},         // Goudy
    {0x0684317d, kDroidSerif},         // Serif
    {0x14ee2d13, kRoboto},             // Verdana
    {0x3918fe2d, kDroidSansMono},      // Courier
    {0x3b98b31c, kDroidSerif},         // Palatino
    {0x3d49f40e, kDroidSerif},         // Baskerville
    {0x432c41c5, kDroidSerif},         // Cursive
    {0x491b6ad0, kDroidSerif},         // Unknown
    {0x5612cab1, kDroidSansFallback},  // Unknown
    {0x779ce19d, kRoboto},             // Sanserif
    {0x7cc9510b, kDroidSansFallback},  // Unknown
    {0x83746053, kDroidSansMono},      // Courier New
    {0xaaa60c03, kDroidSansMono},      // Monospace
    {0xbf85ff26, kDroidSerif},         // Fantasy
    {0xc04fe601, kDroidSansMono},      // Monaco
    {0xca3812d5, kDroidSansFallback},  // SimHei
    {0xca383e15, kDroidSansFallback},  // SimSun
    {0xcad5eaf6, kDroidSansFallback},  // STSong
    {0xcb7a04c8, kRoboto},             // Tahoma
    {0xfb4ce0de, kDroidSerif},         // Georgia
};

const AndroidFontMap kAndroidSansFontMap[] = {
    {0x058c5083, kDroidSans},  // Arial
    {0x14ee2d13, kDroidSans},  // Verdana
    {0x779ce19d, kDroidSans},  // Sanserif
    {0xcb7a04c8, kDroidSans},  // Tahoma
    {0xfb4ce0de, kDroidSans},  // Georgia
};

uint32_t AndroidNormalizeFontName(ByteStringView family) {
  uint32_t hash_code = 0;
  for (char ch : family) {
    if (ch == ' ' || ch == '-' || ch == ',') {
      continue;
    }
    hash_code = 31 * hash_code + FXSYS_ToLowerASCII(ch);
  }
  return hash_code;
}

const char* AndroidGetSubstFont(uint32_t hash,
                                pdfium::span<const AndroidFontMap> font_map) {
  auto it = std::ranges::lower_bound(font_map, hash, std::less<>{},
                                     &AndroidFontMap::family);

  if (it != font_map.end() && it->family == hash) {
    return it->subst;
  }
  return nullptr;
}

bool AndroidMaybeArabic(ByteStringView facename) {
  ByteString name(facename);
  name.MakeLower();
  return name.Contains("arabic");
}

bool AndroidMaybeSymbol(ByteStringView facename) {
  ByteString name(facename);
  name.MakeLower();
  return name.Contains("symbol");
}

}  // namespace

CFX_AndroidFontInfo::CFX_AndroidFontInfo() = default;

CFX_AndroidFontInfo::~CFX_AndroidFontInfo() = default;

void CFX_AndroidFontInfo::Init(
    std::optional<pdfium::span<const char* const>> user_paths) {
  AddPath("/system/fonts");
  if (user_paths.has_value()) {
    for (const char* path : user_paths.value()) {
      AddPath(path);
    }
  }
}

void* CFX_AndroidFontInfo::MapFont(CFX_FontMapper* mapper,
                                   int weight,
                                   bool bItalic,
                                   FX_Charset charset,
                                   int pitch_family,
                                   const ByteString& face) {
  bool is_cjk = FX_CharSetIsCJK(charset);
  if (charset != FX_Charset::kMSWin_Arabic &&
      AndroidMaybeArabic(face.AsStringView())) {
    charset = FX_Charset::kMSWin_Arabic;
  } else if (charset == FX_Charset::kANSI ||
             AndroidMaybeSymbol(face.AsStringView())) {
    charset = FX_Charset::kDefault;
  }

  // First try to match the actual face name.
  void* font = FindFont(weight, bItalic, charset, pitch_family, face,
                        /*must_match_name=*/true);
  if (font) {
    return font;
  }

  const uint32_t face_name_hash = AndroidNormalizeFontName(face.AsStringView());
  const char* subst_name = AndroidGetSubstFont(face_name_hash, kAndroidFontmap);
  if (subst_name) {
    font = FindFont(weight, bItalic, charset, pitch_family, subst_name,
                    /*must_match_name=*/true);
    if (font) {
      return font;
    }
  }

  const char* subst_sans_name =
      AndroidGetSubstFont(face_name_hash, kAndroidSansFontMap);
  if (subst_sans_name) {
    font = FindFont(weight, bItalic, charset, pitch_family, subst_sans_name,
                    /*must_match_name=*/true);
    if (font) {
      return font;
    }
  }

  font = GetSubstFont(face);
  if (font) {
    return font;
  }

  if (is_cjk) {
    return FindFont(weight, bItalic, charset, pitch_family, face,
                    /*must_match_name=*/false);
  }
  return nullptr;
}

bool CFX_AndroidFontInfo::IsBetterMatch(const FontFaceInfo* candidate,
                                        int32_t candidate_score,
                                        const FontFaceInfo* current_best,
                                        int32_t current_best_score,
                                        FX_Charset charset,
                                        const ByteString& family,
                                        bool must_match_name) const {
  if (!FX_CharSetIsCJK(charset)) {
    return CFX_FolderFontInfo::IsBetterMatch(candidate, candidate_score,
                                             current_best, current_best_score,
                                             charset, family, must_match_name);
  }

  if (must_match_name) {
    // When a name match is required, prefer score over glyph count.
    if (current_best && candidate_score < current_best_score) {
      return false;
    }
    ByteStringView family_view = family.AsStringView();
    if (candidate->face_name_ != family &&
        !FindFamilyNameMatch(family_view, candidate->face_name_)) {
      return false;
    }
    if (current_best) {
      if (candidate_score != current_best_score) {
        return candidate_score > current_best_score;
      }
      return candidate->glyph_count_ > current_best->glyph_count_;
    }
    return true;
  }

  // When a name match is not required, prefer glyph count over score to
  // avoid empty boxes.
  uint32_t current_best_glyphs = current_best ? current_best->glyph_count_ : 0;
  if (candidate->glyph_count_ != current_best_glyphs) {
    return candidate->glyph_count_ > current_best_glyphs;
  }

  return candidate_score > current_best_score;
}
