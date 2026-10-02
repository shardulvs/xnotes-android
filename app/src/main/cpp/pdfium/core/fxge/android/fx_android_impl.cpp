// Copyright 2014 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include <memory>
#include <utility>

#include "core/fxge/android/cfx_androidfontinfo.h"
#include "core/fxge/cfx_fontmgr.h"
#include "core/fxge/cfx_gemodule.h"

class CAndroidPlatform : public CFX_GEModule::PlatformIface {
 public:
  CAndroidPlatform() = default;
  ~CAndroidPlatform() override = default;

  void Init() override {}
  void Terminate() override {}

  std::unique_ptr<SystemFontInfoIface> CreateDefaultSystemFontInfo() override {
    auto font_info = std::make_unique<CFX_AndroidFontInfo>();
    font_info->Init(CFX_GEModule::Get()->GetUserFontPaths());
    return font_info;
  }
};

// static
std::unique_ptr<CFX_GEModule::PlatformIface>
CFX_GEModule::PlatformIface::Create() {
  return std::make_unique<CAndroidPlatform>();
}
