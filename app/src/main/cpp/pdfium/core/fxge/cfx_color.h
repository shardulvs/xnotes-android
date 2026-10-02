// Copyright 2017 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef CORE_FXGE_CFX_COLOR_H_
#define CORE_FXGE_CFX_COLOR_H_

#include "core/fxge/dib/fx_dib.h"

struct CFX_Color {
  // Ordered by increasing number of components.
  enum class Type { kTransparent = 0, kGray, kRGB, kCMYK };

  struct TypeAndARGB {
    TypeAndARGB(CFX_Color::Type type_in, FX_ARGB argb_in)
        : color_type(type_in), argb(argb_in) {}

    CFX_Color::Type color_type;
    FX_ARGB argb;
  };

  static constexpr CFX_Color MakeTransparent() { return CFX_Color(); }
  static constexpr CFX_Color MakeGray(float g) {
    return CFX_Color(Type::kGray, g);
  }
  static constexpr CFX_Color MakeRGB(float r, float g, float b) {
    return CFX_Color(Type::kRGB, r, g, b);
  }
  static constexpr CFX_Color MakeCMYK(float c, float m, float y, float k) {
    return CFX_Color(Type::kCMYK, c, m, y, k);
  }
  static constexpr CFX_Color MakeRGBBytes(int32_t r, int32_t g, int32_t b) {
    return CFX_Color(Type::kRGB, r / 255.0f, g / 255.0f, b / 255.0f);
  }
  static constexpr CFX_Color FromColorRef(FX_COLORREF ref) {
    return CFX_Color(Type::kRGB, FXARGB_R(ref) / 255.0f, FXARGB_G(ref) / 255.0f,
                     FXARGB_B(ref) / 255.0f);
  }

  constexpr CFX_Color() = default;
  CFX_Color(const CFX_Color& that) = default;
  CFX_Color& operator=(const CFX_Color& that) = default;

  CFX_Color operator/(float fColorDivide) const;
  CFX_Color operator-(float fColorSub) const;

  CFX_Color ConvertColorType(Type nConvertColorType) const;
  FX_COLORREF ToFXColor(int32_t nTransparency) const;

  Type nColorType = Type::kTransparent;
  float fColor1 = 0.0f;
  float fColor2 = 0.0f;
  float fColor3 = 0.0f;
  float fColor4 = 0.0f;

 private:
  constexpr CFX_Color(Type type, float g) : nColorType(type), fColor1(g) {}
  constexpr CFX_Color(Type type, float r, float g, float b)
      : nColorType(type), fColor1(r), fColor2(g), fColor3(b) {}
  constexpr CFX_Color(Type type, float c, float m, float y, float k)
      : nColorType(type), fColor1(c), fColor2(m), fColor3(y), fColor4(k) {}
};

inline bool operator==(const CFX_Color& c1, const CFX_Color& c2) {
  if (c1.nColorType != c2.nColorType) {
    return false;
  }
  auto is_nearly = [](float f1, float f2) {
    return f1 - f2 < 0.0001f && f1 - f2 > -0.0001f;
  };
  switch (c1.nColorType) {
    case CFX_Color::Type::kTransparent:
      return true;
    case CFX_Color::Type::kGray:
      return is_nearly(c1.fColor1, c2.fColor1);
    case CFX_Color::Type::kRGB:
      return is_nearly(c1.fColor1, c2.fColor1) &&
             is_nearly(c1.fColor2, c2.fColor2) &&
             is_nearly(c1.fColor3, c2.fColor3);
    case CFX_Color::Type::kCMYK:
      return is_nearly(c1.fColor1, c2.fColor1) &&
             is_nearly(c1.fColor2, c2.fColor2) &&
             is_nearly(c1.fColor3, c2.fColor3) &&
             is_nearly(c1.fColor4, c2.fColor4);
  }
  return false;
}

#endif  // CORE_FXGE_CFX_COLOR_H_
