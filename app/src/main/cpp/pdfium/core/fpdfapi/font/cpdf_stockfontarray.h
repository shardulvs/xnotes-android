// Copyright 2017 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef CORE_FPDFAPI_FONT_CPDF_STOCKFONTARRAY_H_
#define CORE_FPDFAPI_FONT_CPDF_STOCKFONTARRAY_H_

#include <array>

#include "core/fxcrt/retain_ptr.h"
#include "core/fxge/cfx_standardfont.h"

class CPDF_Font;

class CPDF_StockFontArray {
 public:
  CPDF_StockFontArray();
  ~CPDF_StockFontArray();

  RetainPtr<CPDF_Font> GetFont(CFX_StandardFont::Index index) const;
  void SetFont(CFX_StandardFont::Index index, RetainPtr<CPDF_Font> font);

 private:
  std::array<RetainPtr<CPDF_Font>, 14> stock_fonts_;
};

#endif  // CORE_FPDFAPI_FONT_CPDF_STOCKFONTARRAY_H_
