// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxge/cfx_fontmgr.h"

#include <memory>

#include "core/fxge/cfx_face.h"
#include "core/fxge/cfx_font.h"
#include "core/fxge/cfx_fontmapper.h"
#include "core/fxge/cfx_glyphcache.h"
#include "core/fxge/fx_font.h"
#include "core/fxge/systemfontinfo_iface.h"

#if defined(PDF_USE_SKIA)
#include "third_party/skia/include/core/SkFontMgr.h"         // nogncheck
#include "third_party/skia/include/core/SkStream.h"          // nogncheck
#include "third_party/skia/include/core/SkTypeface.h"        // nogncheck
#include "third_party/skia/include/ports/SkFontMgr_empty.h"  // nogncheck

#if defined(PDF_ENABLE_FONTATIONS)
#include "third_party/skia/include/ports/SkFontMgr_Fontations.h"  // nogncheck
#endif  // defined(PDF_ENABLE_FONTATIONS)

#if BUILDFLAG(IS_WIN)
#include "third_party/skia/include/ports/SkTypeface_win.h"  // nogncheck
#elif BUILDFLAG(IS_APPLE)
#include "third_party/skia/include/ports/SkFontMgr_mac_ct.h"  // nogncheck
#endif  // BUILDFLAG(IS_WIN)
#endif  // PDF_USE_SKIA

namespace {

#if defined(PDF_USE_SKIA)
// A singleton SkFontMgr which can be used to decode raw font data or
// otherwise get access to system fonts.
sk_sp<SkFontMgr> CreateSkiaFontManagerFallback(
    CFX_FontMgr::FontBackend backend) {
#if defined(PDF_USE_SKIA_CUSTOM_FONT_MANAGER)
  return pdfium_skia_custom_font_manager();
#else  // defined(PDF_USE_SKIA_CUSTOM_FONT_MANAGER)
#if defined(PDF_ENABLE_FONTATIONS)
  if (backend == CFX_FontMgr::FontBackend::kFontations) {
    // This is a SkFontMgr which will use Fontations to decode font data.
    return SkFontMgr_New_Fontations_Empty();
  }
#endif  // defined(PDF_ENABLE_FONTATIONS)
  // This is a SkFontMgr which will use FreeType to decode font data.
  return SkFontMgr_New_Custom_Empty();
#endif  // defined(PDF_USE_SKIA_CUSTOM_FONT_MANAGER)
}

// static
sk_sp<SkFontMgr> CreateSkiaFontManager(CFX_FontMgr::FontBackend backend) {
#if BUILDFLAG(IS_WIN)
  return SkFontMgr_New_DirectWrite();
#elif BUILDFLAG(IS_APPLE)
  return SkFontMgr_New_CoreText(nullptr);
#else
  // This is a SkFontMgr which will use FreeType to decode font data.
  return CreateSkiaFontManagerFallback(backend);
#endif
}
#endif  // defined(PDF_USE_SKIA)
}  // namespace

CFX_FontMgr::CFX_FontMgr([[maybe_unused]] FontBackend backend)
    : ft_library_(InitializeFreeType()),
#if defined(PDF_USE_SKIA)
      font_backend_(backend),
      skia_fontmgr_(CreateSkiaFontManager(font_backend_)),
#endif
      builtin_mapper_(std::make_unique<CFX_FontMapper>()),
      ft_library_supports_hinting_(
          FreeTypeSetLcdFilterMode(ft_library_.get()) ||
          FreeTypeVersionSupportsHinting(ft_library_.get())) {
}

CFX_FontMgr::~CFX_FontMgr() = default;

RetainPtr<CFX_GlyphCache> CFX_FontMgr::GetGlyphCache(const CFX_Font* font) {
  RetainPtr<CFX_Face> face = font->GetFace();
  auto it = glyph_cache_map_.find(face.Get());
  if (it != glyph_cache_map_.end() && it->second) {
    return pdfium::WrapRetain(it->second.Get());
  }
  auto new_cache = pdfium::MakeRetain<CFX_GlyphCache>(face);
  glyph_cache_map_[face.Get()].Reset(new_cache.Get());
  return new_cache;
}

#if defined(PDF_USE_SKIA)
sk_sp<SkTypeface> CFX_FontMgr::MakeSkTypeface(
    pdfium::span<const uint8_t> font_span) {
  sk_sp<SkTypeface> result;
  if (skia_fontmgr_) {
    result = skia_fontmgr_->makeFromStream(
        std::make_unique<SkMemoryStream>(font_span.data(), font_span.size()));
  }
#if BUILDFLAG(IS_WIN) || BUILDFLAG(IS_APPLE)
  // If DirectWrite or CoreText didn't work, try a fallback font manager.
  if (!result) {
    if (!skia_fontmgr_fallback_) {
      skia_fontmgr_fallback_ = CreateSkiaFontManagerFallback(font_backend_);
    }
    result = skia_fontmgr_fallback_->makeFromStream(
        std::make_unique<SkMemoryStream>(font_span.data(), font_span.size()));
  }
#endif  // BUILDFLAG(IS_WIN) || BUILDFLAG(IS_APPLE)
  return result;
}
#endif  // defined(PDF_USE_SKIA)
