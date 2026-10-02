// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef CORE_FXGE_CFX_GEMODULE_H_
#define CORE_FXGE_CFX_GEMODULE_H_

#include <stdint.h>

#include <memory>
#include <optional>

#include "build/build_config.h"
#include "core/fxcrt/data_vector.h"
#include "core/fxcrt/retain_ptr.h"
#include "core/fxcrt/span.h"
#include "core/fxcrt/unowned_ptr.h"
#include "core/fxge/cfx_fontmgr.h"

class CFX_DIBBase;
class SystemFontInfoIface;

#if BUILDFLAG(IS_WIN)
enum class WindowsPrintMode {
  kEmf = 0,
  kTextOnly = 1,
  kPostScript2 = 2,
  kPostScript3 = 3,
  kPostScript2PassThrough = 4,
  kPostScript3PassThrough = 5,
  kEmfImageMasks = 6,
  kPostScript3Type42 = 7,
  kPostScript3Type42PassThrough = 8,
};
#endif

struct EncoderIface {
  DataVector<uint8_t> (*pA85EncodeFunc)(pdfium::span<const uint8_t> src_span);
  DataVector<uint8_t> (*pFaxEncodeFunc)(RetainPtr<const CFX_DIBBase> src);
  DataVector<uint8_t> (*pFlateEncodeFunc)(pdfium::span<const uint8_t> src_span);
  bool (*pJpegEncodeFunc)(const RetainPtr<const CFX_DIBBase>& pSource,
                          uint8_t** dest_buf,
                          size_t* dest_size);
  DataVector<uint8_t> (*pRunLengthEncodeFunc)(
      pdfium::span<const uint8_t> src_span);
};

class CFX_GEModule {
 public:
  class PlatformIface {
   public:
    static std::unique_ptr<PlatformIface> Create();
    virtual ~PlatformIface() = default;

    virtual void Init() = 0;
    virtual void Terminate() = 0;

    virtual std::unique_ptr<SystemFontInfoIface>
    CreateDefaultSystemFontInfo() = 0;
#if BUILDFLAG(IS_APPLE)
    virtual void* CreatePlatformFont(pdfium::span<const uint8_t> font_span) = 0;
#endif
  };

  // This internal definition of renderer types must stay updated with respect
  // to the public definition of `FPDF_RENDERER_TYPE` in `fpdfview.h`.
  enum class RendererType {
    kAgg = 0,
    kSkia = 1,
#if defined(PDF_USE_SKIA)
    kDefault = kSkia,
#else
    kDefault = kAgg,
#endif
  };

  // Uses other defaults when `user_font_paths` is nullopt, otherwise uses
  // only those in the span, which may lead to using none when empty.
  static void Create(
      std::optional<pdfium::span<const char* const>> user_font_paths,
      RendererType renderer_type,
      CFX_FontMgr::FontBackend font_backend);

  static void Destroy();
  static CFX_GEModule* Get();

  CFX_FontMgr* GetFontMgr() const { return font_mgr_.get(); }
  PlatformIface* GetPlatform() const { return platform_.get(); }
  std::optional<pdfium::span<const char* const>> GetUserFontPaths() const {
    return user_font_paths_;
  }

  void SetEncoderIface(const EncoderIface* encoders) {
    encoder_iface_ = encoders;
  }
  const EncoderIface* GetEncoderIface() const { return encoder_iface_.get(); }

#if BUILDFLAG(IS_WIN)
  static void SetPrintMode(WindowsPrintMode mode);
  static WindowsPrintMode GetPrintMode();
#endif

#if defined(PDF_USE_SKIA)
  // Runtime check to see if Skia is the renderer variant in use.
  static bool IsSkiaRenderer() {
    return Get()->renderer_type_ == RendererType::kSkia;
  }
#endif

#if defined(PDF_ENABLE_FONTATIONS)
  static bool IsFontations() {
    return Get()->font_backend_ == CFX_FontMgr::FontBackend::kFontations;
  }
#endif

 private:
  CFX_GEModule(std::optional<pdfium::span<const char* const>> user_font_paths,
               RendererType renderer_type,
               CFX_FontMgr::FontBackend font_backend);
  ~CFX_GEModule();

  const RendererType renderer_type_;
#if defined(PDF_ENABLE_FONTATIONS)
  const CFX_FontMgr::FontBackend font_backend_;
#endif
  std::unique_ptr<PlatformIface> const platform_;  // Must outlive `font_mgr_`.
  std::unique_ptr<CFX_FontMgr> const font_mgr_;
  std::optional<pdfium::span<const char* const>> user_font_paths_;
  UnownedPtr<const EncoderIface> encoder_iface_;
};

#endif  // CORE_FXGE_CFX_GEMODULE_H_
