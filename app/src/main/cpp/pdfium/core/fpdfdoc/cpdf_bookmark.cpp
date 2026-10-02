// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fpdfdoc/cpdf_bookmark.h"

#include <algorithm>
#include <utility>

#include "core/fpdfapi/parser/cpdf_array.h"
#include "core/fpdfapi/parser/cpdf_dictionary.h"
#include "core/fpdfapi/parser/cpdf_number.h"
#include "core/fpdfapi/parser/cpdf_string.h"
#include "core/fxcrt/retain_ptr.h"
#include "core/fxge/dib/fx_dib.h"

CPDF_Bookmark::CPDF_Bookmark() = default;

CPDF_Bookmark::CPDF_Bookmark(const CPDF_Bookmark& that) = default;

CPDF_Bookmark::CPDF_Bookmark(RetainPtr<const CPDF_Dictionary> dict)
    : dict_(std::move(dict)) {}

CPDF_Bookmark::~CPDF_Bookmark() = default;

WideString CPDF_Bookmark::GetTitle() const {
  if (!dict_) {
    return WideString();
  }
  RetainPtr<const CPDF_String> pString =
      ToString(dict_->GetDirectObjectFor("Title"));
  if (!pString) {
    return WideString();
  }
  WideString title = pString->GetUnicodeText();
  WideString result;
  result.Reserve(title.GetLength());
  for (const wchar_t wc : title) {
    result += std::max(wc, static_cast<wchar_t>(0x20));
  }
  return result;
}

CPDF_Dest CPDF_Bookmark::GetDest(CPDF_Document* document) const {
  if (!dict_) {
    return CPDF_Dest(nullptr);
  }
  return CPDF_Dest::Create(document, dict_->GetDirectObjectFor("Dest"));
}

CPDF_Action CPDF_Bookmark::GetAction() const {
  return CPDF_Action(dict_ ? dict_->GetDictFor("A") : nullptr);
}

std::optional<FX_RGB_STRUCT<float>> CPDF_Bookmark::GetColor() const {
  if (!dict_) {
    return std::nullopt;
  }
  RetainPtr<const CPDF_Array> rgb_array = dict_->GetArrayFor("C");
  if (!rgb_array || rgb_array->size() != 3) {
    return std::nullopt;
  }
  float red = rgb_array->GetNumberAt(0)->GetNumber();
  float green = rgb_array->GetNumberAt(1)->GetNumber();
  float blue = rgb_array->GetNumberAt(2)->GetNumber();
  return {{red, green, blue}};
}

int CPDF_Bookmark::GetStyle() const {
  return dict_ ? dict_->GetIntegerFor("F") : 0;
}

int CPDF_Bookmark::GetCount() const {
  return dict_->GetIntegerFor("Count");
}
