// Copyright 2017 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fpdfapi/font/cpdf_stockfontarray.h"

#include <iterator>
#include <utility>

#include "core/fpdfapi/font/cpdf_font.h"
#include "core/fpdfapi/parser/cpdf_dictionary.h"
#include "core/fxcrt/check_op.h"

CPDF_StockFontArray::CPDF_StockFontArray() = default;

CPDF_StockFontArray::~CPDF_StockFontArray() {
  for (size_t i = 0; i < std::size(stock_fonts_); ++i) {
    if (stock_fonts_[i]) {
      // Ensure stock_fonts_[i]'s dict is cleared before releasing what
      // may be the last reference to it.
      RetainPtr<CPDF_Dictionary> destroy =
          stock_fonts_[i]->GetMutableFontDict();
      stock_fonts_[i]->ClearFontDict();
    }
  }
}

RetainPtr<CPDF_Font> CPDF_StockFontArray::GetFont(
    CFX_StandardFont::Index index) const {
  CHECK_LT(static_cast<size_t>(index), std::size(stock_fonts_));
  return stock_fonts_[index];
}

void CPDF_StockFontArray::SetFont(CFX_StandardFont::Index index,
                                  RetainPtr<CPDF_Font> font) {
  if (static_cast<size_t>(index) < std::size(stock_fonts_)) {
    stock_fonts_[index] = std::move(font);
  }
}
