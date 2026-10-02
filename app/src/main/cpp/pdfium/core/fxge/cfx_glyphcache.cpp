// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxge/cfx_glyphcache.h"

#include <initializer_list>
#include <memory>
#include <utility>

#include "build/build_config.h"
#include "core/fxcrt/fx_codepage.h"
#include "core/fxcrt/span.h"
#include "core/fxcrt/to_underlying.h"
#include "core/fxge/cfx_font.h"
#include "core/fxge/cfx_gemodule.h"
#include "core/fxge/cfx_glyphbitmap.h"
#include "core/fxge/cfx_path.h"
#include "core/fxge/cfx_substfont.h"
#include "core/fxge/fx_font.h"

#if BUILDFLAG(IS_APPLE)
#include "core/fxge/cfx_textrenderoptions.h"
#endif

namespace {

constexpr uint32_t kInvalidGlyphIndex = static_cast<uint32_t>(-1);

class UniqueKeyGen {
 public:
  UniqueKeyGen(const CFX_Font* font,
               const CFX_Matrix& matrix,
               int dest_width,
               FontAntiAliasingMode anti_alias,
               bool bNative);

  pdfium::span<const uint8_t> span() const;

 private:
  void Initialize(std::initializer_list<const int> args);

  size_t key_len_;
  uint32_t key_[32];
};

void UniqueKeyGen::Initialize(std::initializer_list<const int32_t> args) {
  auto key_span = pdfium::span<uint32_t, pdfium::dynamic_extent>(key_);
  for (const auto& arg : args) {
    key_span.front() = arg;
    key_span = key_span.subspan<1u>();
  }
  key_len_ = args.size();
}

pdfium::span<const uint8_t> UniqueKeyGen::span() const {
  return pdfium::as_bytes(pdfium::span(key_).first(key_len_));
}

UniqueKeyGen::UniqueKeyGen(const CFX_Font* font,
                           const CFX_Matrix& matrix,
                           int dest_width,
                           FontAntiAliasingMode anti_alias,
                           bool bNative) {
  int nMatrixA = static_cast<int>(matrix.a * 10000);
  int nMatrixB = static_cast<int>(matrix.b * 10000);
  int nMatrixC = static_cast<int>(matrix.c * 10000);
  int nMatrixD = static_cast<int>(matrix.d * 10000);

#if BUILDFLAG(IS_APPLE)
  if (bNative) {
    if (font->GetSubstFont()) {
      Initialize(
          {nMatrixA, nMatrixB, nMatrixC, nMatrixD, dest_width,
           fxcrt::to_underlying(anti_alias), font->GetSubstFont()->GetWeight(),
           font->GetSubstFont()->GetItalicAngle(), font->IsVertical(), 3});
    } else {
      Initialize({nMatrixA, nMatrixB, nMatrixC, nMatrixD, dest_width,
                  fxcrt::to_underlying(anti_alias), 3});
    }
    return;
  }
#endif

  CHECK(!bNative);
  if (font->GetSubstFont()) {
    Initialize({nMatrixA, nMatrixB, nMatrixC, nMatrixD, dest_width,
                fxcrt::to_underlying(anti_alias),
                font->GetSubstFont()->GetWeight(),
                font->GetSubstFont()->GetItalicAngle(), font->IsVertical()});
  } else {
    Initialize({nMatrixA, nMatrixB, nMatrixC, nMatrixD, dest_width,
                fxcrt::to_underlying(anti_alias)});
  }
}

}  // namespace

CFX_GlyphCache::CFX_GlyphCache(RetainPtr<CFX_Face> face)
    : face_(std::move(face)) {}

CFX_GlyphCache::~CFX_GlyphCache() = default;

std::unique_ptr<CFX_GlyphBitmap> CFX_GlyphCache::RenderGlyph(
    uint32_t glyph_index,
    bool is_cid_font,
    bool is_vertical,
    const CFX_Matrix& matrix,
    int dest_width,
    FontAntiAliasingMode anti_alias,
    const CFX_SubstFont* subst_font) {
  if (!face_) {
    return nullptr;
  }
  return face_->RenderGlyph(glyph_index, is_cid_font, is_vertical, matrix,
                            dest_width, anti_alias, subst_font);
}

const CFX_Path* CFX_GlyphCache::LoadGlyphPath(const CFX_Font* font,
                                              uint32_t glyph_index,
                                              int dest_width) {
  if (!face_ || glyph_index == kInvalidGlyphIndex) {
    return nullptr;
  }

  const auto* pSubstFont = font->GetSubstFont();
  int weight = pSubstFont ? pSubstFont->GetWeight() : 0;
  int angle = pSubstFont ? pSubstFont->GetItalicAngle() : 0;
  bool vertical = pSubstFont && font->IsVertical();
  const PathMapKey key =
      std::make_tuple(glyph_index, dest_width, weight, angle, vertical);
  auto it = path_map_.find(key);
  if (it != path_map_.end()) {
    return it->second.get();
  }

  path_map_[key] = font->LoadGlyphPathImpl(glyph_index, dest_width);
  return path_map_[key].get();
}

const CFX_GlyphBitmap* CFX_GlyphCache::LoadGlyphBitmap(
    const CFX_Font* font,
    uint32_t glyph_index,
    bool is_cid_font,
    const CFX_Matrix& matrix,
    int dest_width,
    FontAntiAliasingMode anti_alias,
    CFX_TextRenderOptions* text_options) {
  if (glyph_index == kInvalidGlyphIndex) {
    return nullptr;
  }

#if BUILDFLAG(IS_APPLE)
  const bool bNative = text_options->native_text;
#else
  const bool bNative = false;
#endif
  UniqueKeyGen keygen(font, matrix, dest_width, anti_alias, bNative);
  auto FaceGlyphsKey = ByteString(ByteStringView(keygen.span()));

#if BUILDFLAG(IS_APPLE)
  bool bDoLookUp = !text_options->native_text;
#if defined(PDF_USE_SKIA)
  bDoLookUp = bDoLookUp || CFX_GEModule::IsSkiaRenderer();
#endif  // defined(PDF_USE_SKIA)
#else   // BUILDFLAG(IS_APPLE)
  const bool bDoLookUp = true;
#endif  // BUILDFLAG(IS_APPLE)
  if (bDoLookUp) {
    return LookUpGlyphBitmap(font, matrix, FaceGlyphsKey, glyph_index,
                             is_cid_font, dest_width, anti_alias);
  }

#if BUILDFLAG(IS_APPLE)
#if defined(PDF_USE_SKIA)
  DCHECK(!CFX_GEModule::IsSkiaRenderer());
#endif  // defined(PDF_USE_SKIA)

  auto it = size_map_.find(FaceGlyphsKey);
  if (it != size_map_.end()) {
    SizeToGlyphMap& size_glyph_cache = it->second;
    auto size_glyph_it = size_glyph_cache.find(glyph_index);
    if (size_glyph_it != size_glyph_cache.end()) {
      return size_glyph_it->second.get();
    }
  }
  UniqueKeyGen keygen2(font, matrix, dest_width, anti_alias,
                       /*bNative=*/false);
  auto FaceGlyphsKey2 = ByteString(ByteStringView(keygen2.span()));
  text_options->native_text = false;
  return LookUpGlyphBitmap(font, matrix, FaceGlyphsKey2, glyph_index,
                           is_cid_font, dest_width, anti_alias);
#endif  // BUILDFLAG(IS_APPLE)
}

int CFX_GlyphCache::GetGlyphWidth(const CFX_Font* font,
                                  uint32_t glyph_index,
                                  int dest_width,
                                  int weight) {
  const WidthMapKey key = std::make_tuple(glyph_index, dest_width, weight);
  auto it = width_map_.find(key);
  if (it != width_map_.end()) {
    return it->second;
  }

  width_map_[key] = font->GetGlyphWidthImpl(glyph_index, dest_width, weight);
  return width_map_[key];
}

CFX_GlyphBitmap* CFX_GlyphCache::LookUpGlyphBitmap(
    const CFX_Font* font,
    const CFX_Matrix& matrix,
    const ByteString& FaceGlyphsKey,
    uint32_t glyph_index,
    bool is_cid_font,
    int dest_width,
    FontAntiAliasingMode anti_alias) {
  SizeToGlyphMap* pSizeCache;
  auto it = size_map_.find(FaceGlyphsKey);
  if (it == size_map_.end()) {
    size_map_[FaceGlyphsKey] = SizeToGlyphMap();
    pSizeCache = &(size_map_[FaceGlyphsKey]);
  } else {
    pSizeCache = &(it->second);
  }

  auto it2 = pSizeCache->find(glyph_index);
  if (it2 != pSizeCache->end()) {
    return it2->second.get();
  }

  std::unique_ptr<CFX_GlyphBitmap> pGlyphBitmap =
      RenderGlyph(glyph_index, is_cid_font, font->IsVertical(), matrix,
                  dest_width, anti_alias, font->GetSubstFont());
  CFX_GlyphBitmap* pResult = pGlyphBitmap.get();
  (*pSizeCache)[glyph_index] = std::move(pGlyphBitmap);
  return pResult;
}
