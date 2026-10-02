// Copyright 2014 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxge/cfx_font.h"

#include <stdint.h>

#include <algorithm>
#include <limits>
#include <memory>
#include <utility>

#include "build/build_config.h"
#include "core/fxcrt/data_vector.h"
#include "core/fxcrt/fx_codepage.h"
#include "core/fxcrt/fx_stream.h"
#include "core/fxcrt/span.h"
#include "core/fxcrt/unowned_ptr.h"
#include "core/fxge/cfx_cttgsubtable.h"
#include "core/fxge/cfx_face.h"
#include "core/fxge/cfx_fontmapper.h"
#include "core/fxge/cfx_fontmgr.h"
#include "core/fxge/cfx_gemodule.h"
#include "core/fxge/cfx_glyphcache.h"
#include "core/fxge/cfx_path.h"
#include "core/fxge/cfx_substfont.h"
#include "core/fxge/fx_font.h"

namespace {

const CFX_Font::CharsetFontMap kDefaultTTFMap[] = {
    {static_cast<int>(FX_Charset::kANSI), CFX_Font::kDefaultAnsiFontName},
    {static_cast<int>(FX_Charset::kChineseSimplified), "SimSun"},
    {static_cast<int>(FX_Charset::kChineseTraditional), "MingLiU"},
    {static_cast<int>(FX_Charset::kShiftJIS), "MS Gothic"},
    {static_cast<int>(FX_Charset::kHangul), "Batang"},
    {static_cast<int>(FX_Charset::kMSWin_Cyrillic), "Arial"},
#if BUILDFLAG(IS_WIN)
    {static_cast<int>(FX_Charset::kMSWin_EasternEuropean), "Tahoma"},
#else
    {static_cast<int>(FX_Charset::kMSWin_EasternEuropean), "Arial"},
#endif
    {static_cast<int>(FX_Charset::kMSWin_Arabic), "Arial"},
    // TODO(crbug.com/348468114): Remove sentinel value when
    // FPDF_GetDefaultTTFMap() gets removed.
    {-1, nullptr}};

bool ShouldAppendStyle(const ByteString& style) {
  return !style.IsEmpty() && style != "Regular";
}

}  // namespace

// static
const char CFX_Font::kUntitledFontName[] = "Untitled";

// static
const char CFX_Font::kDefaultAnsiFontName[] = "Helvetica";

// static
const char CFX_Font::kUniversalDefaultFontName[] = "Arial Unicode MS";

// static
pdfium::span<const CFX_Font::CharsetFontMap> CFX_Font::GetDefaultTTFMapSpan() {
  auto map_with_sentinel_value = pdfium::span(kDefaultTTFMap);
  return map_with_sentinel_value.first(map_with_sentinel_value.size() - 1);
}

// static
ByteString CFX_Font::GetDefaultFontNameByCharset(FX_Charset nCharset) {
  for (const auto& entry : GetDefaultTTFMapSpan()) {
    if (static_cast<int>(nCharset) == entry.charset) {
      return entry.fontname;
    }
  }
  return kUniversalDefaultFontName;
}

// static
FX_Charset CFX_Font::GetCharSetFromUnicode(uint16_t word) {
  // to avoid CJK Font to show ASCII
  if (word < 0x7F) {
    return FX_Charset::kANSI;
  }

  // find new charset
  if ((word >= 0x4E00 && word <= 0x9FA5) ||
      (word >= 0xE7C7 && word <= 0xE7F3) ||
      (word >= 0x3000 && word <= 0x303F) ||
      (word >= 0x2000 && word <= 0x206F)) {
    return FX_Charset::kChineseSimplified;
  }

  if (((word >= 0x3040) && (word <= 0x309F)) ||
      ((word >= 0x30A0) && (word <= 0x30FF)) ||
      ((word >= 0x31F0) && (word <= 0x31FF)) ||
      ((word >= 0xFF00) && (word <= 0xFFEF))) {
    return FX_Charset::kShiftJIS;
  }

  if (((word >= 0xAC00) && (word <= 0xD7AF)) ||
      ((word >= 0x1100) && (word <= 0x11FF)) ||
      ((word >= 0x3130) && (word <= 0x318F))) {
    return FX_Charset::kHangul;
  }

  if (word >= 0x0E00 && word <= 0x0E7F) {
    return FX_Charset::kThai;
  }

  if ((word >= 0x0370 && word <= 0x03FF) ||
      (word >= 0x1F00 && word <= 0x1FFF)) {
    return FX_Charset::kMSWin_Greek;
  }

  if ((word >= 0x0600 && word <= 0x06FF) ||
      (word >= 0xFB50 && word <= 0xFEFC)) {
    return FX_Charset::kMSWin_Arabic;
  }

  if (word >= 0x0590 && word <= 0x05FF) {
    return FX_Charset::kMSWin_Hebrew;
  }

  if (word >= 0x0400 && word <= 0x04FF) {
    return FX_Charset::kMSWin_Cyrillic;
  }

  if (word >= 0x0100 && word <= 0x024F) {
    return FX_Charset::kMSWin_EasternEuropean;
  }

  if (word >= 0x1E00 && word <= 0x1EFF) {
    return FX_Charset::kMSWin_Vietnamese;
  }

  return FX_Charset::kANSI;
}

CFX_Font::CFX_Font() = default;

int CFX_Font::GetSubstFontItalicAngle() const {
  const CFX_SubstFont* subst_font = GetSubstFont();
  return subst_font ? subst_font->GetItalicAngle() : 0;
}

std::vector<CharCodeAndIndex> CFX_Font::GetCharCodesAndIndices(
    char32_t max_char) {
  if (!face_) {
    return {};
  }
  return face_->GetCharCodesAndIndices(max_char);
}

#if defined(PDF_ENABLE_XFA) && !BUILDFLAG(IS_WIN)
void CFX_Font::SetFaceFromFont(const CFX_Font& that) {
  ClearGlyphCache();
  object_tag_ = 0;
  face_ = that.face_;
}

void CFX_Font::SetSubstFont(std::unique_ptr<CFX_SubstFont> subst) {
  subst_font_ = std::move(subst);
}
#endif  // defined(PDF_ENABLE_XFA) & !BUILDFLAG(IS_WIN)

CFX_Font::~CFX_Font() {
  font_data_ = {};  // font_data_ can't outive face_.
  face_.Reset();

#if BUILDFLAG(IS_APPLE)
  ReleasePlatformResource();
#endif
}

std::unique_ptr<CFX_CTTGSUBTable> CFX_Font::ParseGSUBTable() const {
  if (!face_) {
    return nullptr;
  }
  return face_->ParseGSUBTable();
}

bool CFX_Font::LoadFaceZeroFromSpan(pdfium::span<const uint8_t> src_span,
                                    bool force_vertical,
                                    uint64_t object_tag) {
  vertical_ = force_vertical;
  font_data_allocation_ = DataVector<uint8_t>(src_span.begin(), src_span.end());
  font_data_ = font_data_allocation_;
  return LoadFaceFromSpanStream(
      pdfium::MakeRetain<CFX_ReadOnlySpanStream>(font_data_allocation_), 0,
      object_tag);
}

bool CFX_Font::LoadFaceFromSpanStream(
    const RetainPtr<CFX_ReadOnlySpanStream>& stream,
    int face_index,
    uint64_t object_tag) {
  object_tag_ = object_tag;
  face_ = CFX_Face::New(nullptr, stream, face_index);
  return !!face_;
}

void CFX_Font::LoadSubstFace(const ByteString& face_name,
                             bool bTrueType,
                             uint32_t flags,
                             int weight,
                             int italic_angle,
                             FX_CodePage code_page,
                             bool bVertical) {
  vertical_ = bVertical;
  object_tag_ = 0;
  subst_font_ = std::make_unique<CFX_SubstFont>();
  face_ = CFX_GEModule::Get()->GetFontMgr()->GetBuiltinMapper()->FindSubstFace(
      face_name, bTrueType, flags, weight, italic_angle, code_page,
      subst_font_.get());
  if (face_) {
    font_data_ = face_->GetData();
  }
}

bool CFX_Font::HasAnyGlyphs() const {
  return face_ && face_->GetGlyphCount() > 0;
}

int CFX_Font::GetGlyphWidth(uint32_t glyph_index) const {
  return GetGlyphWidth(glyph_index, 0, 0);
}

int CFX_Font::GetGlyphWidth(uint32_t glyph_index,
                            int dest_width,
                            int weight) const {
  return GetOrCreateGlyphCache()->GetGlyphWidth(this, glyph_index, dest_width,
                                                weight);
}

int CFX_Font::GetGlyphWidthImpl(uint32_t glyph_index,
                                int dest_width,
                                int weight) const {
  if (!face_) {
    return 0;
  }

  return face_->GetGlyphWidth(glyph_index, dest_width, weight,
                              subst_font_.get());
}

bool CFX_Font::IsTTFont() const {
  return face_ && face_->IsTtOt();
}

int CFX_Font::GetCharIndex(uint32_t code) const {
  return face_ ? face_->GetCharIndex(code) : 0;
}

int CFX_Font::GetNameIndex(const char* name) const {
  return face_ ? face_->GetNameIndex(name) : 0;
}

int CFX_Font::GetAscent() const {
  if (!face_) {
    return 0;
  }
  return NormalizeFontMetric(face_->GetAscender(), face_->GetUnitsPerEm());
}

int CFX_Font::GetDescent() const {
  if (!face_) {
    return 0;
  }
  return NormalizeFontMetric(face_->GetDescender(), face_->GetUnitsPerEm());
}

std::optional<FX_RECT> CFX_Font::GetGlyphBBox(uint32_t glyph_index) {
  if (!face_) {
    return std::nullopt;
  }
  return face_->GetFontGlyphBBox(glyph_index);
}

std::optional<FX_RECT> CFX_Font::GetCharBBox(uint32_t code,
                                             uint32_t glyph_index) {
  if (!face_) {
    return std::nullopt;
  }
  return face_->GetCharBBox(code, glyph_index);
}

bool CFX_Font::IsItalic() const {
  if (!face_) {
    return false;
  }
  if (face_->IsItalic()) {
    return true;
  }

  ByteString str = face_->GetStyleName();
  str.MakeLower();
  return str.Contains("italic");
}

bool CFX_Font::IsBold() const {
  return face_ && face_->IsBold();
}

bool CFX_Font::IsFixedWidth() const {
  return face_ && face_->IsFixedWidth();
}

#if defined(PDF_USE_SKIA)
bool CFX_Font::IsSubstFontBold() const {
  CFX_SubstFont* subst_font = GetSubstFont();
  return subst_font &&
         subst_font->GetOriginalWeight() >= pdfium::kFontWeightBold;
}
#endif

ByteString CFX_Font::GetPsName() const {
  if (!face_) {
    return ByteString();
  }

  ByteString psName = face_->GetPostscriptName();
  if (psName.IsEmpty()) {
    psName = kUntitledFontName;
  }
  return psName;
}

ByteString CFX_Font::GetFamilyName() const {
  if (!face_ && !subst_font_) {
    return ByteString();
  }
  if (face_) {
    return face_->GetFamilyName();
  }
  return subst_font_->GetFamily();
}

ByteString CFX_Font::GetFamilyNameOrUntitled() const {
  ByteString facename = GetFamilyName();
  return facename.IsEmpty() ? kUntitledFontName : facename;
}

ByteString CFX_Font::GetBaseFontName() const {
  ByteString psname = GetPsName();
  if (!psname.IsEmpty() && psname != kUntitledFontName) {
    return psname;
  }
  if (face_) {
    ByteString style = face_->GetStyleName();
    ByteString facename = GetFamilyNameOrUntitled();
    if (IsTTFont()) {
      facename.Remove(' ');
    }
    if (ShouldAppendStyle(style)) {
      facename += (IsTTFont() ? "," : " ") + style;
    }
    return facename;
  }
  if (subst_font_) {
    return subst_font_->GetFamily();
  }
  return ByteString();
}

std::optional<FX_RECT> CFX_Font::GetRawBBox() const {
  if (!face_) {
    return std::nullopt;
  }
  return face_->GetBBox();
}

std::optional<FX_RECT> CFX_Font::GetBBox() const {
  std::optional<FX_RECT> result = GetRawBBox();
  if (!result.has_value()) {
    return result;
  }

  int em = face_->GetUnitsPerEm();
  if (em != 0) {
    FX_RECT& bbox = result.value();
    bbox.left = pdfium::saturated_cast<int32_t>((bbox.left * 1000.0f) / em);
    bbox.top = pdfium::saturated_cast<int32_t>((bbox.top * 1000.0f) / em);
    bbox.right = pdfium::saturated_cast<int32_t>((bbox.right * 1000.0f) / em);
    bbox.bottom = pdfium::saturated_cast<int32_t>((bbox.bottom * 1000.0f) / em);
  }
  return result;
}

RetainPtr<CFX_GlyphCache> CFX_Font::GetOrCreateGlyphCache() const {
  if (!glyph_cache_) {
    glyph_cache_ = CFX_GEModule::Get()->GetFontMgr()->GetGlyphCache(this);
  }
  return glyph_cache_;
}

void CFX_Font::ClearGlyphCache() {
  glyph_cache_ = nullptr;
}

std::unique_ptr<CFX_Path> CFX_Font::LoadGlyphPathImpl(uint32_t glyph_index,
                                                      int dest_width) const {
  if (!face_) {
    return nullptr;
  }

  return face_->LoadGlyphPath(glyph_index, dest_width, vertical_,
                              subst_font_.get());
}

const CFX_GlyphBitmap* CFX_Font::LoadGlyphBitmap(
    uint32_t glyph_index,
    bool is_cid_font,
    const CFX_Matrix& matrix,
    int dest_width,
    FontAntiAliasingMode anti_alias,
    CFX_TextRenderOptions* text_options) const {
  return GetOrCreateGlyphCache()->LoadGlyphBitmap(
      this, glyph_index, is_cid_font, matrix, dest_width, anti_alias,
      text_options);
}

const CFX_Path* CFX_Font::LoadGlyphPath(uint32_t glyph_index,
                                        int dest_width) const {
  return GetOrCreateGlyphCache()->LoadGlyphPath(this, glyph_index, dest_width);
}
