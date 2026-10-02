// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxge/cfx_fontmapper.h"

#include <stdint.h>

#include <algorithm>
#include <memory>
#include <utility>

#include "build/build_config.h"
#include "core/fxcrt/byteorder.h"
#include "core/fxcrt/cfx_read_only_span_stream.h"
#include "core/fxcrt/check_op.h"
#include "core/fxcrt/containers/adapters.h"
#include "core/fxcrt/containers/contains.h"
#include "core/fxcrt/data_vector.h"
#include "core/fxcrt/fx_codepage.h"
#include "core/fxcrt/fx_extension.h"
#include "core/fxcrt/fx_memory.h"
#include "core/fxcrt/unowned_ptr.h"
#include "core/fxcrt/unowned_ptr_exclusion.h"
#include "core/fxge/cfx_standardfont.h"
#include "core/fxge/cfx_substfont.h"
#include "core/fxge/fx_font.h"
#include "core/fxge/systemfontinfo_iface.h"

namespace {

struct AltFontFamily {
  const char* font_name_;    // Raw, POD struct.
  const char* font_family_;  // Raw, POD struct.
};

constexpr AltFontFamily kAltFontFamilies[] = {
    {"AGaramondPro", "Adobe Garamond Pro"},
    {"BankGothicBT-Medium", "BankGothic Md BT"},
    {"ForteMT", "Forte"},
};

#if BUILDFLAG(IS_LINUX) || BUILDFLAG(IS_CHROMEOS) || defined(OS_ASMJS)
const char kNarrowFamily[] = "LiberationSansNarrow";
#elif BUILDFLAG(IS_ANDROID)
const char kNarrowFamily[] = "RobotoCondensed";
#else
const char kNarrowFamily[] = "ArialNarrow";
#endif  // BUILDFLAG(IS_LINUX) || BUILDFLAG(IS_CHROMEOS) || defined(OS_ASMJS)

ByteString TT_NormalizeName(ByteString norm) {
  norm.Remove(' ');
  norm.Remove('-');
  norm.Remove(',');
  auto pos = norm.Find('+');
  if (pos.has_value() && pos.value() != 0) {
    norm = norm.First(pos.value());
  }
  norm.MakeLower();
  return norm;
}

const char* GetFontFamily(uint32_t nStyle, const ByteString& fontname) {
  if (fontname.Contains("Script")) {
    if (FontStyleIsForceBold(nStyle)) {
      return "ScriptMTBold";
    }
    if (fontname.Contains("Palace")) {
      return "PalaceScriptMT";
    } else if (fontname.Contains("French")) {
      return "FrenchScriptMT";
    } else if (fontname.Contains("FreeStyle")) {
      return "FreeStyleScript";
    }
    return nullptr;
  }
  for (const auto& alternate : kAltFontFamilies) {
    if (fontname.Contains(alternate.font_name_)) {
      return alternate.font_family_;
    }
  }
  return nullptr;
}

ByteString ParseStyle(const ByteString& bsStyle, size_t iStart) {
  ByteStringView bsRegion = bsStyle.AsStringView().Substr(iStart);
  size_t index = bsRegion.Find(',').value_or(bsRegion.GetLength());
  return ByteString(bsRegion.First(index));
}

struct FX_FontStyle {
  const char* name;
  size_t len;
  uint32_t style;
};

constexpr FX_FontStyle kFontStyles[] = {
    {"Regular", 7, pdfium::kFontStyleNormal},
    {"Reg", 3, pdfium::kFontStyleNormal},
    {"BoldItalic", 10, pdfium::kFontStyleForceBold | pdfium::kFontStyleItalic},
    {"Italic", 6, pdfium::kFontStyleItalic},
    {"Bold", 4, pdfium::kFontStyleForceBold},
};

const FX_FontStyle* GetStyleType(ByteStringView font_name,
                                 bool reverse_search) {
  if (font_name.IsEmpty()) {
    return nullptr;
  }

  for (const FX_FontStyle& style : kFontStyles) {
    if (style.len > font_name.GetLength()) {
      continue;
    }

    ByteStringView style_view =
        reverse_search ? font_name.Last(style.len) : font_name.First(style.len);
    if (style_view == style.name) {
      return &style;
    }
  }
  return nullptr;
}

bool ParseStyles(const ByteString& style_str,
                 bool* is_style_available,
                 int* weight,
                 uint32_t* style) {
  if (style_str.IsEmpty()) {
    return false;
  }

  size_t i = 0;
  bool is_first_item = true;
  while (i < style_str.GetLength()) {
    ByteString buf = ParseStyle(style_str, i);
    const FX_FontStyle* style_result =
        GetStyleType(buf.AsStringView(), /*reverse_search=*/false);
    if ((i && !*is_style_available) || (!i && !style_result)) {
      return true;
    }

    uint32_t parsed_style;
    if (style_result) {
      *is_style_available = true;
      parsed_style = style_result->style;
    } else {
      parsed_style = pdfium::kFontStyleNormal;
    }

    if (FontStyleIsForceBold(parsed_style)) {
      // If we're already bold, then we're double bold, use special weight.
      if (FontStyleIsForceBold(*style)) {
        *weight = pdfium::kFontWeightExtraBold;
      } else {
        *weight = pdfium::kFontWeightBold;
        *style |= pdfium::kFontStyleForceBold;
      }

      is_first_item = false;
    }
    if (FontStyleIsItalic(parsed_style) && FontStyleIsForceBold(parsed_style)) {
      *style |= pdfium::kFontStyleItalic;
    } else if (FontStyleIsItalic(parsed_style)) {
      if (!is_first_item) {
        return true;
      }

      *style |= pdfium::kFontStyleItalic;
      break;
    }
    i += buf.GetLength() + 1;
  }
  return false;
}

bool CheckSupportThirdPartFont(const ByteString& name, int* pitch_family) {
  if (name != "MyriadPro") {
    return false;
  }
  *pitch_family &= ~pdfium::kFontPitchFamilyRoman;
  return true;
}

bool IsStylableBaseFont(CFX_StandardFont::Index base_font) {
  return base_font == CFX_StandardFont::kCourier ||
         base_font == CFX_StandardFont::kHelvetica ||
         base_font == CFX_StandardFont::kTimes;
}

uint32_t GetStyleFromBaseFont(CFX_StandardFont::Index base_font) {
  uint32_t style = pdfium::kFontStyleNormal;
  if (base_font < CFX_StandardFont::kSymbol) {
    int pos = base_font % 4;
    if (pos == 1 || pos == 2) {
      style |= pdfium::kFontStyleForceBold;
    }
    if (pos / 2) {
      style |= pdfium::kFontStyleItalic;
    }
  }
  return style;
}

int GetPitchFamilyFromBaseFont(CFX_StandardFont::Index base_font) {
  if (base_font < 4) {
    return pdfium::kFontPitchFamilyFixed;
  }
  if (base_font >= 8) {
    return pdfium::kFontPitchFamilyRoman;
  }
  return 0;
}

int GetPitchFamilyFromFlags(uint32_t flags) {
  int pitch_family = 0;
  if (FontStyleIsSerif(flags)) {
    pitch_family |= pdfium::kFontPitchFamilyRoman;
  }
  if (FontStyleIsScript(flags)) {
    pitch_family |= pdfium::kFontPitchFamilyScript;
  }
  if (FontStyleIsFixedPitch(flags)) {
    pitch_family |= pdfium::kFontPitchFamilyFixed;
  }
  return pitch_family;
}

CFX_StandardFont::Index AdjustBaseFontForStyle(
    CFX_StandardFont::Index base_font,
    uint32_t style) {
  if (!style || !IsStylableBaseFont(base_font)) {
    return base_font;
  }
  int base_int = base_font;
  if (FontStyleIsForceBold(style) && FontStyleIsItalic(style)) {
    base_int += 2;
  } else if (FontStyleIsForceBold(style)) {
    base_int += 1;
  } else if (FontStyleIsItalic(style)) {
    base_int += 3;
  }
  return static_cast<CFX_StandardFont::Index>(base_int);
}

FX_Charset GetCharset(FX_CodePage code_page,
                      std::optional<CFX_StandardFont::Index> base_font,
                      uint32_t flags) {
  if (code_page != FX_CodePage::kDefANSI) {
    return FX_GetCharsetFromCodePage(code_page);
  }
  if (FontStyleIsSymbolic(flags) && !base_font.has_value()) {
    return FX_Charset::kSymbol;
  }
  return FX_Charset::kANSI;
}

ByteString GetSubstName(const ByteString& name, bool is_truetype) {
  ByteString subst_name = name;
  if (is_truetype && name.Front() == '@') {
    subst_name.Delete(0);
  } else {
    subst_name.Remove(' ');
  }
  MaybeRemoveSubsettedFontPrefix(subst_name);
  std::optional<CFX_StandardFont::Index> std_font =
      CFX_StandardFont::GetStandardFontIndex(subst_name);
  if (std_font) {
    subst_name = CFX_StandardFont::GetCanonicalFontName(*std_font);
  }
  return subst_name;
}

ByteString GetFontNameFromFace(const CFX_Face& face) {
  ByteString family_name = face.GetFamilyName();
  if (family_name.IsEmpty()) {
    return ByteString();
  }

  ByteString style_name = face.GetStyleName();
  if (!style_name.IsEmpty() && style_name != "Regular") {
    family_name += ' ';
    family_name += style_name;
  }
  return family_name;
}

bool IsNarrowFontName(const ByteString& name) {
  static const char kNarrowFonts[][10] = {"Narrow", "Condensed"};
  for (const char* font : kNarrowFonts) {
    std::optional<size_t> pos = name.Find(font);
    if (pos.has_value() && pos.value() != 0) {
      return true;
    }
  }
  return false;
}

uint32_t GetChecksumFromTT(pdfium::span<const uint8_t> font_data) {
  uint32_t checksum = 0;
  auto span = font_data.first(std::min<size_t>(font_data.size(), 1024));
  while (span.size() >= 4) {
    checksum += fxcrt::GetUInt32MSBFirst(span.first<4u>());
    span = span.subspan<4u>();
  }
  return checksum;
}

class ScopedFontDeleter {
 public:
  FX_STACK_ALLOCATED();

  ScopedFontDeleter(SystemFontInfoIface* font_info, void* font)
      : font_info_(font_info), font_(font) {}
  ~ScopedFontDeleter() { font_info_->DeleteFont(font_); }

 private:
  UnownedPtr<SystemFontInfoIface> const font_info_;
  UNOWNED_PTR_EXCLUSION void* const font_;  // void type incompatible.
};

}  // namespace

CFX_FontMapper::CFX_FontMapper() = default;

CFX_FontMapper::~CFX_FontMapper() = default;

void CFX_FontMapper::SetSystemFontInfo(
    std::unique_ptr<SystemFontInfoIface> font_info) {
  if (!font_info) {
    return;
  }

  list_loaded_ = false;
  font_info_ = std::move(font_info);
}

std::unique_ptr<SystemFontInfoIface> CFX_FontMapper::TakeSystemFontInfo() {
  return std::move(font_info_);
}

ByteString CFX_FontMapper::GetPSNameFromTT(void* font_handle) {
  size_t size =
      font_info_->GetFontData(font_handle, SystemFontInfoIface::kTableNAME, {});
  if (!size) {
    return ByteString();
  }

  DataVector<uint8_t> buffer(size);
  size_t bytes_read = font_info_->GetFontData(
      font_handle, SystemFontInfoIface::kTableNAME, buffer);
  return bytes_read == size ? GetNameFromTT(buffer, 6) : ByteString();
}

void CFX_FontMapper::AddInstalledFont(const ByteString& name,
                                      FX_Charset charset) {
  if (!font_info_) {
    return;
  }

  face_array_.push_back({name, static_cast<uint32_t>(charset)});
  if (name == last_family_) {
    return;
  }

  bool is_localized = std::any_of(name.begin(), name.end(), [](const char& c) {
    return static_cast<uint8_t>(c) > 0x80;
  });

  if (is_localized) {
    void* font_handle = font_info_->GetFont(name);
    if (!font_handle) {
      font_handle =
          font_info_->MapFont(this, 0, false, FX_Charset::kDefault, 0, name);
      if (!font_handle) {
        return;
      }
    }

    ScopedFontDeleter scoped_font(font_info_.get(), font_handle);
    ByteString new_name = GetPSNameFromTT(font_handle);
    if (!new_name.IsEmpty()) {
      localized_ttfonts_.emplace_back(new_name, name);
    }
  }
  installed_ttfonts_.push_back(name);
  last_family_ = name;
}

void CFX_FontMapper::LoadInstalledFonts() {
  if (!font_info_ || list_loaded_) {
    return;
  }

  if (!skip_font_enumeration_) {
    font_info_->EnumFontList(this);
  }
  list_loaded_ = true;
}

ByteString CFX_FontMapper::MatchInstalledFonts(const ByteString& norm_name) {
  LoadInstalledFonts();
  for (const ByteString& font : pdfium::Reversed(installed_ttfonts_)) {
    if (TT_NormalizeName(font) == norm_name) {
      return font;
    }
  }
  for (const auto& font_data : pdfium::Reversed(localized_ttfonts_)) {
    if (TT_NormalizeName(font_data.first) == norm_name) {
      return font_data.second;
    }
  }
  return ByteString();
}

RetainPtr<CFX_Face> CFX_FontMapper::UseInternalSubst(
    std::optional<CFX_StandardFont::Index> base_font,
    int weight,
    int italic_angle,
    int pitch_family,
    CFX_SubstFont* subst_font) {
  if (base_font.has_value()) {
    CFX_StandardFont::Index index = base_font.value();
    if (!standard_faces_[index]) {
      standard_faces_[index] =
          CFX_Face::New(nullptr,
                        pdfium::MakeRetain<CFX_ReadOnlySpanStream>(
                            CFX_StandardFont::GetFontData(index)),
                        0);
    }
    return standard_faces_[index];
  }

  subst_font->SetIsBuiltInGenericFont();
  subst_font->SetItalicAngle(italic_angle);
  if (weight) {
    subst_font->SetWeight(weight);
  }
  if (FontFamilyIsRoman(pitch_family)) {
    subst_font->UseChromeSerif();
    if (!generic_serif_face_) {
      generic_serif_face_ =
          CFX_Face::New(nullptr,
                        pdfium::MakeRetain<CFX_ReadOnlySpanStream>(
                            CFX_StandardFont::GetGenericSerifFontData()),
                        0);
    }
    return generic_serif_face_;
  }
  subst_font->SetFamily("Chrome Sans");
  if (!generic_sans_face_) {
    generic_sans_face_ =
        CFX_Face::New(nullptr,
                      pdfium::MakeRetain<CFX_ReadOnlySpanStream>(
                          CFX_StandardFont::GetGenericSansFontData()),
                      0);
  }
  return generic_sans_face_;
}

RetainPtr<CFX_Face> CFX_FontMapper::UseExternalSubst(
    void* font_handle,
    ByteString face_name,
    int weight,
    bool is_italic,
    int italic_angle,
    FX_Charset charset,
    CFX_SubstFont* subst_font) {
  DCHECK(font_handle);

  ScopedFontDeleter scoped_font(font_info_.get(), font_handle);
  const bool got_external_face_name =
      font_info_->GetFaceName(font_handle, &face_name);
  if (charset == FX_Charset::kDefault) {
    font_info_->GetFontCharset(font_handle, &charset);
  }
  size_t ttc_size =
      font_info_->GetFontData(font_handle, SystemFontInfoIface::kTableTTCF, {});
  size_t font_size =
      font_info_->GetFontData(font_handle, SystemFontInfoIface::kTableNone, {});
  if (font_size == 0 && ttc_size == 0) {
    return nullptr;
  }

  RetainPtr<CFX_Face> face =
      ttc_size
          ? GetCachedTTCFace(font_handle, ttc_size, font_size)
          : GetCachedFace(font_handle, face_name, weight, is_italic, font_size);
  if (!face) {
    return nullptr;
  }

  if (!got_external_face_name) {
    ByteString maybe_face_name = GetFontNameFromFace(*face);
    if (!maybe_face_name.IsEmpty()) {
      face_name = maybe_face_name;
    }
  }
  subst_font->ConfigureExternalSubst(face_name, charset, weight, is_italic,
                                     italic_angle, face->IsBold(),
                                     face->IsItalic());
  return face;
}

RetainPtr<CFX_Face> CFX_FontMapper::FindSubstFace(const ByteString& name,
                                                  bool is_truetype,
                                                  uint32_t flags,
                                                  int weight,
                                                  int italic_angle,
                                                  FX_CodePage code_page,
                                                  CFX_SubstFont* subst_font) {
  if (weight == 0) {
    weight = pdfium::kFontWeightNormal;
  }

  if (!(flags & kFontUseExternAttr)) {
    weight = pdfium::kFontWeightNormal;
    italic_angle = 0;
  }
  const ByteString subst_name = GetSubstName(name, is_truetype);
  if (subst_name == "Symbol" && !is_truetype) {
    subst_font->SetFamily("Chrome Symbol");
    subst_font->SetCharset(FX_Charset::kSymbol);
    return UseInternalSubst(CFX_StandardFont::kSymbol, weight, italic_angle, 0,
                            subst_font);
  }
  if (subst_name == "ZapfDingbats") {
    subst_font->SetFamily("Chrome Dingbats");
    subst_font->SetCharset(FX_Charset::kSymbol);
    return UseInternalSubst(CFX_StandardFont::kDingbats, weight, italic_angle,
                            0, subst_font);
  }
  ByteString family;
  ByteString style;
  bool has_comma = false;
  std::optional<CFX_StandardFont::Index> std_font;
  {
    std::optional<size_t> pos = subst_name.Find(",");
    if (pos.has_value()) {
      family = subst_name.First(pos.value());
      std_font = CFX_StandardFont::GetStandardFontIndex(family);
      if (std_font) {
        family = CFX_StandardFont::GetCanonicalFontName(*std_font);
      }
      style = subst_name.Substr(pos.value() + 1);
      has_comma = true;
    } else {
      family = subst_name;
      std_font = CFX_StandardFont::GetStandardFontIndex(family);
    }
  }
  int pitch_family;
  uint32_t nStyle;
  bool has_hyphen = false;
  std::optional<CFX_StandardFont::Index> base_font;
  if (std_font.has_value() && std_font.value() < CFX_StandardFont::kSymbol) {
    base_font = std_font.value();
    nStyle = GetStyleFromBaseFont(*base_font);
    pitch_family = GetPitchFamilyFromBaseFont(*base_font);
  } else {
    nStyle = pdfium::kFontStyleNormal;
    if (!has_comma) {
      std::optional<size_t> pos = family.ReverseFind('-');
      if (pos.has_value()) {
        style = family.Substr(pos.value() + 1);
        family = family.First(pos.value());
        has_hyphen = true;
      }
    }
    if (!has_hyphen) {
      size_t nLen = family.GetLength();
      const FX_FontStyle* style_result =
          GetStyleType(family.AsStringView(), /*reverse_search=*/true);
      if (style_result) {
        family = family.First(nLen - style_result->len);
        nStyle |= style_result->style;
      }
    }
    pitch_family = GetPitchFamilyFromFlags(flags);
  }

  const int old_weight = weight;
  if (FontStyleIsForceBold(nStyle)) {
    weight = pdfium::kFontWeightBold;
  }

  bool is_style_available = false;
  if (ParseStyles(style, &is_style_available, &weight, &nStyle)) {
    family = subst_name;
    base_font = std::nullopt;
  }

  if (!font_info_) {
    return UseInternalSubst(base_font, old_weight, italic_angle, pitch_family,
                            subst_font);
  }

  const FX_Charset Charset = GetCharset(code_page, base_font, flags);
  const bool is_cjk = FX_CharSetIsCJK(Charset);
  bool is_italic = FontStyleIsItalic(nStyle);

  const char* maybe_family = GetFontFamily(nStyle, family);
  if (maybe_family) {
    family = ByteString(maybe_family);
  }

  ByteString match = MatchInstalledFonts(TT_NormalizeName(family));
  if (match.IsEmpty() && family != subst_name &&
      (!has_comma && (!has_hyphen || (has_hyphen && !is_style_available)))) {
    match = MatchInstalledFonts(TT_NormalizeName(subst_name));
  }
  if (match.IsEmpty() && !base_font.has_value()) {
    if (!is_cjk) {
      if (!CheckSupportThirdPartFont(family, &pitch_family)) {
        is_italic = italic_angle != 0;
        // When `skip_font_enumeration_` is true (version 2), CFX_FontMapper
        // relies on `MapFont()` to handle font matching with the correct
        // weight. Don't reset weight here as it would break bold/light font
        // variants.
        if (!skip_font_enumeration_) {
          weight = old_weight;
        }
      }
      if (IsNarrowFontName(subst_name)) {
        family = kNarrowFamily;
      }
    } else {
      subst_font->SetSubstCJK(true);
      if (nStyle) {
        subst_font->SetWeightCJK(nStyle ? weight : pdfium::kFontWeightNormal);
      }
      if (FontStyleIsItalic(nStyle)) {
        subst_font->SetItalicCJK(true);
      }
    }
    if (FontStyleIsItalic(flags)) {
      is_italic = true;
    }
  } else {
    italic_angle = 0;
    if (nStyle == pdfium::kFontStyleNormal) {
      weight = pdfium::kFontWeightNormal;
    }
    if (!match.IsEmpty()) {
      family = match;
    }
    if (base_font.has_value()) {
      base_font = AdjustBaseFontForStyle(*base_font, nStyle);
      family = CFX_StandardFont::GetCanonicalFontName(*base_font);
    }
  }
  void* font_handle = font_info_->MapFont(this, weight, is_italic, Charset,
                                          pitch_family, family);
  if (font_handle) {
    return UseExternalSubst(font_handle, subst_name, weight, is_italic,
                            italic_angle, Charset, subst_font);
  }

  if (is_cjk) {
    is_italic = italic_angle != 0;
    weight = old_weight;
  }
  if (!match.IsEmpty()) {
    font_handle = font_info_->GetFont(match);
    if (!font_handle) {
      return UseInternalSubst(base_font, old_weight, italic_angle, pitch_family,
                              subst_font);
    }
    return UseExternalSubst(font_handle, subst_name, weight, is_italic,
                            italic_angle, Charset, subst_font);
  }

  if (Charset == FX_Charset::kSymbol) {
#if !BUILDFLAG(IS_WIN)
    if (subst_name == "Symbol") {
      subst_font->SetFamily("Chrome Symbol");
      subst_font->SetCharset(FX_Charset::kSymbol);
      return UseInternalSubst(CFX_StandardFont::kSymbol, old_weight,
                              italic_angle, pitch_family, subst_font);
    }
#endif
    return FindSubstFace(family, is_truetype,
                         flags & ~pdfium::kFontStyleSymbolic, weight,
                         italic_angle, FX_CodePage::kDefANSI, subst_font);
  }

  if (Charset == FX_Charset::kANSI) {
    return UseInternalSubst(base_font, old_weight, italic_angle, pitch_family,
                            subst_font);
  }

  auto it = std::ranges::find_if(face_array_, [Charset](const FaceData& face) {
    return face.charset == static_cast<uint32_t>(Charset);
  });
  if (it == face_array_.end()) {
    return UseInternalSubst(base_font, old_weight, italic_angle, pitch_family,
                            subst_font);
  }
  font_handle = font_info_->GetFont(it->name);
  if (!font_handle) {
    return nullptr;
  }
  return UseExternalSubst(font_handle, subst_name, weight, is_italic,
                          italic_angle, Charset, subst_font);
}

size_t CFX_FontMapper::GetFaceSize() const {
  return face_array_.size();
}

ByteString CFX_FontMapper::GetFaceName(size_t index) const {
  CHECK_LT(index, face_array_.size());
  return face_array_[index].name;
}

bool CFX_FontMapper::HasInstalledFont(ByteStringView name) const {
  for (const auto& font : installed_ttfonts_) {
    if (font == name) {
      return true;
    }
  }
  return false;
}

bool CFX_FontMapper::HasLocalizedFont(ByteStringView name) const {
  for (const auto& fontPair : localized_ttfonts_) {
    if (fontPair.first == name) {
      return true;
    }
  }
  return false;
}

#if BUILDFLAG(IS_WIN)
std::optional<ByteString> CFX_FontMapper::InstalledFontNameStartingWith(
    const ByteString& name) const {
  for (const auto& thisname : installed_ttfonts_) {
    if (thisname.First(name.GetLength()) == name) {
      return thisname;
    }
  }
  return std::nullopt;
}

std::optional<ByteString> CFX_FontMapper::LocalizedFontNameStartingWith(
    const ByteString& name) const {
  for (const auto& thispair : localized_ttfonts_) {
    if (thispair.first.First(name.GetLength()) == name) {
      return thispair.second;
    }
  }
  return std::nullopt;
}
#endif  // BUILDFLAG(IS_WIN)

#ifdef PDF_ENABLE_XFA
FixedSizeDataVector<uint8_t> CFX_FontMapper::RawBytesForIndex(size_t index) {
  CHECK_LT(index, face_array_.size());

  void* font_handle = font_info_->MapFont(this, 0, false, FX_Charset::kDefault,
                                          0, GetFaceName(index));
  if (!font_handle) {
    return FixedSizeDataVector<uint8_t>();
  }
  ScopedFontDeleter scoped_font(font_info_.get(), font_handle);
  size_t required_size =
      font_info_->GetFontData(font_handle, SystemFontInfoIface::kTableNone, {});
  if (required_size == 0) {
    return FixedSizeDataVector<uint8_t>();
  }
  auto result = FixedSizeDataVector<uint8_t>::Uninit(required_size);
  size_t actual_size = font_info_->GetFontData(
      font_handle, SystemFontInfoIface::kTableNone, result.span());
  if (actual_size != required_size) {
    return FixedSizeDataVector<uint8_t>();
  }
  return result;
}
#endif  // PDF_ENABLE_XFA

RetainPtr<CFX_Face> CFX_FontMapper::GetCachedTTCFace(void* font_handle,
                                                     size_t ttc_size,
                                                     size_t data_size) {
  CHECK_GE(ttc_size, data_size);
  auto font_data = FixedSizeDataVector<uint8_t>::Uninit(ttc_size);
  size_t size = font_info_->GetFontData(
      font_handle, SystemFontInfoIface::kTableTTCF, font_data.span());
  if (size != ttc_size) {
    return nullptr;
  }

  uint32_t checksum = GetChecksumFromTT(font_data);
  RetainPtr<FontCacheEntry> cache_entry =
      GetTTCFontCacheEntry(ttc_size, checksum);
  if (!cache_entry) {
    cache_entry =
        AddTTCFontCacheEntry(ttc_size, checksum, std::move(font_data));
  }
  CHECK_EQ(ttc_size, cache_entry->FontStream()->span().size());
  size_t font_offset = ttc_size - data_size;
  uint32_t face_index =
      GetTTCIndex(cache_entry->FontStream()->span(), font_offset);
  RetainPtr<CFX_Face> face(cache_entry->GetFace(face_index));
  if (face) {
    return face;
  }

  face = CFX_Face::New(cache_entry, cache_entry->FontStream(), face_index);
  if (!face) {
    return nullptr;
  }

  cache_entry->SetFace(face_index, face.Get());
  return face;
}

RetainPtr<CFX_Face> CFX_FontMapper::GetCachedFace(void* font_handle,
                                                  ByteString subst_name,
                                                  int weight,
                                                  bool is_italic,
                                                  size_t data_size) {
  RetainPtr<FontCacheEntry> cache_entry =
      GetFontCacheEntry(subst_name, weight, is_italic);
  if (!cache_entry) {
    auto font_data = FixedSizeDataVector<uint8_t>::Uninit(data_size);
    size_t size = font_info_->GetFontData(
        font_handle, SystemFontInfoIface::kTableNone, font_data.span());
    if (size != data_size) {
      return nullptr;
    }

    cache_entry =
        AddFontCacheEntry(subst_name, weight, is_italic, std::move(font_data));
  }
  RetainPtr<CFX_Face> face(cache_entry->GetFace(0));
  if (face) {
    return face;
  }

  face = CFX_Face::New(cache_entry, cache_entry->FontStream(), 0);
  if (!face) {
    return nullptr;
  }

  cache_entry->SetFace(0, face.Get());
  return face;
}

CFX_FontMapper::FontCacheEntry::FontCacheEntry(
    FixedSizeDataVector<uint8_t>&& data)
    : font_stream_(pdfium::MakeRetain<CFX_ReadOnlyFixedSizeDataVectorStream>(
          std::move(data))) {}

CFX_FontMapper::FontCacheEntry::~FontCacheEntry() = default;

void CFX_FontMapper::FontCacheEntry::SetFace(uint32_t face_index,
                                             CFX_Face* face) {
  CHECK_LT(face_index, std::size(ttc_faces_));
  ttc_faces_[face_index].Reset(face);
}

CFX_Face* CFX_FontMapper::FontCacheEntry::GetFace(uint32_t face_index) const {
  CHECK_LT(face_index, std::size(ttc_faces_));
  return ttc_faces_[face_index].Get();
}

RetainPtr<CFX_FontMapper::FontCacheEntry> CFX_FontMapper::GetFontCacheEntry(
    const ByteString& face_name,
    int weight,
    bool italic) {
  auto it = face_map_.find({face_name, weight, italic});
  return it != face_map_.end() ? pdfium::WrapRetain(it->second.Get()) : nullptr;
}

RetainPtr<CFX_FontMapper::FontCacheEntry> CFX_FontMapper::AddFontCacheEntry(
    const ByteString& face_name,
    int weight,
    bool italic,
    FixedSizeDataVector<uint8_t> data) {
  auto cache_entry = pdfium::MakeRetain<FontCacheEntry>(std::move(data));
  face_map_[{face_name, weight, italic}].Reset(cache_entry.Get());
  return cache_entry;
}

RetainPtr<CFX_FontMapper::FontCacheEntry> CFX_FontMapper::GetTTCFontCacheEntry(
    size_t ttc_size,
    uint32_t checksum) {
  auto it = ttc_face_map_.find({ttc_size, checksum});
  return it != ttc_face_map_.end() ? pdfium::WrapRetain(it->second.Get())
                                   : nullptr;
}

RetainPtr<CFX_FontMapper::FontCacheEntry> CFX_FontMapper::AddTTCFontCacheEntry(
    size_t ttc_size,
    uint32_t checksum,
    FixedSizeDataVector<uint8_t> data) {
  auto new_entry = pdfium::MakeRetain<FontCacheEntry>(std::move(data));
  ttc_face_map_[{ttc_size, checksum}].Reset(new_entry.Get());
  return new_entry;
}
