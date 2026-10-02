// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef CORE_FPDFDOC_CPDF_APSETTINGS_H_
#define CORE_FPDFDOC_CPDF_APSETTINGS_H_

#include "core/fpdfdoc/cpdf_iconfit.h"
#include "core/fxcrt/fx_string.h"
#include "core/fxcrt/retain_ptr.h"
#include "core/fxge/cfx_color.h"
#include "core/fxge/dib/fx_dib.h"

class CPDF_Dictionary;
class CPDF_Stream;

class CPDF_ApSettings {
 public:
  // Corresponds to PDF spec section 12.5.6.19 (Widget annotation TP
  // dictionary).
  enum class TextPosition {
    kCaption = 0,
    kIcon = 1,
    kBelow = 2,
    kAbove = 3,
    kRight = 4,
    kLeft = 5,
    kOverlaid = 6,
    kMinValue = kCaption,
    kMaxValue = kOverlaid,
    kDefaultValue = kCaption,
  };
  explicit CPDF_ApSettings(RetainPtr<CPDF_Dictionary> dict);
  CPDF_ApSettings(const CPDF_ApSettings& that);
  ~CPDF_ApSettings();

  bool HasMKEntry(ByteStringView entry) const;
  int GetRotation() const;

  CPDF_IconFit GetIconFit() const;

  TextPosition GetTextPosition() const;

  CFX_Color::TypeAndARGB GetColorARGB(ByteStringView entry) const;

  float GetOriginalColorComponent(int index, ByteStringView entry) const;
  CFX_Color GetOriginalColor(ByteStringView entry) const;

  WideString GetCaption(ByteStringView entry) const;
  RetainPtr<CPDF_Stream> GetIcon(ByteStringView entry) const;

 private:
  RetainPtr<CPDF_Dictionary> const dict_;
};

#endif  // CORE_FPDFDOC_CPDF_APSETTINGS_H_
