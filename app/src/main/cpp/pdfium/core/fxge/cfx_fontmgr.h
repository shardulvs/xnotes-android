// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef CORE_FXGE_CFX_FONTMGR_H_
#define CORE_FXGE_CFX_FONTMGR_H_

#include <stddef.h>
#include <stdint.h>

#include <map>
#include <memory>

#include "core/fxcrt/observed_ptr.h"
#include "core/fxcrt/retain_ptr.h"
#include "core/fxcrt/span.h"
#include "core/fxge/freetype/fx_freetype.h"

#if defined(PDF_USE_SKIA)
#include "third_party/skia/include/core/SkRefCnt.h"  // nogncheck
#endif

class CFX_Face;
class CFX_Font;
class CFX_FontMapper;
class CFX_GlyphCache;

#if defined(PDF_USE_SKIA)
class SkFontMgr;
class SkTypeface;
#endif

class CFX_FontMgr {
 public:
  enum class FontBackend { kFreeType, kFontations };

  explicit CFX_FontMgr(FontBackend backend);
  ~CFX_FontMgr();

  RetainPtr<CFX_GlyphCache> GetGlyphCache(const CFX_Font* font);

  // Always present.
  CFX_FontMapper* GetBuiltinMapper() const { return builtin_mapper_.get(); }

  FXFT_LibraryRec* GetFTLibrary() const { return ft_library_.get(); }

  bool FTLibrarySupportsHinting() const { return ft_library_supports_hinting_; }

#if defined(PDF_USE_SKIA)
  sk_sp<SkTypeface> MakeSkTypeface(pdfium::span<const uint8_t> font_span);
#endif

 private:
  // Must come before `builtin_mapper_`.
  ScopedFXFTLibraryRec const ft_library_;
#if defined(PDF_USE_SKIA)
  const FontBackend font_backend_;
  sk_sp<SkFontMgr> skia_fontmgr_;
  sk_sp<SkFontMgr> skia_fontmgr_fallback_;
#endif
  std::unique_ptr<CFX_FontMapper> builtin_mapper_;
  std::map<CFX_Face*, ObservedPtr<CFX_GlyphCache>> glyph_cache_map_;
  const bool ft_library_supports_hinting_;
};

#if defined(PDF_USE_SKIA) && defined(PDF_USE_SKIA_CUSTOM_FONT_MANAGER)
extern sk_sp<SkFontMgr> pdfium_skia_custom_font_manager();
#endif  // defined(PDF_USE_SKIA) && defined(PDF_USE_SKIA_CUSTOM_FONT_MANAGER)

#endif  // CORE_FXGE_CFX_FONTMGR_H_
