// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef CORE_FXGE_CFX_CHARMAP_RESOLVER_H_
#define CORE_FXGE_CFX_CHARMAP_RESOLVER_H_

#include <stdint.h>

#include <memory>

#include "core/fxcrt/unowned_ptr.h"

class CFX_Font;

class CFX_CharmapResolver {
 public:
  static std::unique_ptr<CFX_CharmapResolver> CreateUnicode(
      const CFX_Font* font);

#if defined(PDF_ENABLE_XFA)
  static std::unique_ptr<CFX_CharmapResolver> CreateAlternate(
      const CFX_Font* font);
#endif

  virtual ~CFX_CharmapResolver();

  virtual uint32_t GlyphFromCharCode(uint32_t charcode) = 0;

 protected:
  explicit CFX_CharmapResolver(const CFX_Font* font);

  UnownedPtr<const CFX_Font> const font_;
};

#endif  // CORE_FXGE_CFX_CHARMAP_RESOLVER_H_
