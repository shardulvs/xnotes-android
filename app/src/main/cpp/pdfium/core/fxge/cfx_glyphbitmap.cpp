// Copyright 2019 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "core/fxge/cfx_glyphbitmap.h"

#include <utility>

#include "core/fxge/dib/cfx_dibitmap.h"

CFX_GlyphBitmap::CFX_GlyphBitmap(const CFX_Point& top_left_origin,
                                 RetainPtr<CFX_DIBitmap> bitmap)
    : top_left_origin_(top_left_origin), bitmap_(std::move(bitmap)) {}

CFX_GlyphBitmap::~CFX_GlyphBitmap() = default;

RetainPtr<CFX_DIBitmap> CFX_GlyphBitmap::GetWritableBitmap() {
  return bitmap_;
}

RetainPtr<const CFX_DIBitmap> CFX_GlyphBitmap::GetBitmap() const {
  return bitmap_;
}
