// Copyright 2019 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef CORE_FXGE_CFX_GLYPHBITMAP_H_
#define CORE_FXGE_CFX_GLYPHBITMAP_H_

#include "core/fxcrt/fx_coordinates.h"
#include "core/fxcrt/retain_ptr.h"

class CFX_DIBitmap;

class CFX_GlyphBitmap {
 public:
  CFX_GlyphBitmap(const CFX_Point& top_left_origin,
                  RetainPtr<CFX_DIBitmap> bitmap);
  CFX_GlyphBitmap(const CFX_GlyphBitmap&) = delete;
  CFX_GlyphBitmap& operator=(const CFX_GlyphBitmap&) = delete;
  ~CFX_GlyphBitmap();

  RetainPtr<CFX_DIBitmap> GetWritableBitmap();
  RetainPtr<const CFX_DIBitmap> GetBitmap() const;
  const CFX_Point& top_left_origin() const { return top_left_origin_; }

 private:
  const CFX_Point top_left_origin_;
  const RetainPtr<CFX_DIBitmap> bitmap_;
};

#endif  // CORE_FXGE_CFX_GLYPHBITMAP_H_
