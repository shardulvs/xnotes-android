// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxge/cfx_gemodule.h"

#include "core/fxcrt/check.h"
#include "core/fxge/cfx_folderfontinfo.h"

namespace {

CFX_GEModule* g_GEModule = nullptr;

#if BUILDFLAG(IS_WIN)
WindowsPrintMode g_print_mode = WindowsPrintMode::kEmf;
#endif

}  // namespace

#if BUILDFLAG(IS_WIN)
// static
void CFX_GEModule::SetPrintMode(WindowsPrintMode mode) {
  g_print_mode = mode;
}

// static
WindowsPrintMode CFX_GEModule::GetPrintMode() {
  return g_print_mode;
}
#endif

// static
void CFX_GEModule::Create(
    std::optional<pdfium::span<const char* const>> user_font_paths,
    RendererType renderer_type,
    CFX_FontMgr::FontBackend font_backend) {
  DCHECK(!g_GEModule);
  g_GEModule = new CFX_GEModule(user_font_paths, renderer_type, font_backend);
  g_GEModule->platform_->Init();
  g_GEModule->font_mgr_->GetBuiltinMapper()->SetSystemFontInfo(
      g_GEModule->platform_->CreateDefaultSystemFontInfo());
}

// static
void CFX_GEModule::Destroy() {
  DCHECK(g_GEModule);
  g_GEModule->platform_->Terminate();
  delete g_GEModule;
  g_GEModule = nullptr;
}

// static
CFX_GEModule* CFX_GEModule::Get() {
  DCHECK(g_GEModule);
  return g_GEModule;
}

CFX_GEModule::CFX_GEModule(
    std::optional<pdfium::span<const char* const>> user_font_paths,
    RendererType renderer_type,
    CFX_FontMgr::FontBackend font_backend)
    : renderer_type_(renderer_type),
#if defined(PDF_ENABLE_FONTATIONS)
      font_backend_(font_backend),
#endif
      platform_(PlatformIface::Create()),
      font_mgr_(std::make_unique<CFX_FontMgr>(font_backend)),
      user_font_paths_(user_font_paths) {
}

CFX_GEModule::~CFX_GEModule() = default;
