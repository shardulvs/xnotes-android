// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxge/cfx_substfont.h"

#include <algorithm>
#include <array>
#include <limits>

#include "core/fxcrt/numerics/safe_math.h"
#include "core/fxge/fx_font.h"

namespace {

constexpr auto kWeightPow = std::to_array<const uint8_t>({
    0,   6,   12,  14,  16,  18,  22,  24,  28,  30,  32,  34,  36,  38,  40,
    42,  44,  46,  48,  50,  52,  54,  56,  58,  60,  62,  64,  66,  68,  70,
    70,  72,  72,  74,  74,  74,  76,  76,  76,  78,  78,  78,  80,  80,  80,
    82,  82,  82,  84,  84,  84,  84,  86,  86,  86,  88,  88,  88,  88,  90,
    90,  90,  90,  92,  92,  92,  92,  94,  94,  94,  94,  96,  96,  96,  96,
    96,  98,  98,  98,  98,  100, 100, 100, 100, 100, 102, 102, 102, 102, 102,
    104, 104, 104, 104, 104, 106, 106, 106, 106, 106,
});

constexpr auto kWeightPow11 = std::to_array<const uint8_t>({
    0,  4,  7,  8,  9,  10, 12, 13, 15, 17, 18, 19, 20, 21, 22, 23, 24,
    25, 26, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 39, 39, 40, 40, 41,
    41, 41, 42, 42, 42, 43, 43, 43, 44, 44, 44, 45, 45, 45, 46, 46, 46,
    46, 43, 47, 47, 48, 48, 48, 48, 45, 50, 50, 50, 46, 51, 51, 51, 52,
    52, 52, 52, 53, 53, 53, 53, 53, 54, 54, 54, 54, 55, 55, 55, 55, 55,
    56, 56, 56, 56, 56, 57, 57, 57, 57, 57, 58, 58, 58, 58, 58,
});

constexpr auto kWeightPowShiftJis = std::to_array<const uint8_t>({
    0,   0,   2,   4,   6,   8,   10,  14,  16,  20,  22,  26,  28,  32,  34,
    38,  42,  44,  48,  52,  56,  60,  64,  66,  70,  74,  78,  82,  86,  90,
    96,  96,  96,  96,  98,  98,  98,  100, 100, 100, 100, 102, 102, 102, 102,
    104, 104, 104, 104, 104, 106, 106, 106, 106, 106, 108, 108, 108, 108, 108,
    110, 110, 110, 110, 110, 112, 112, 112, 112, 112, 112, 114, 114, 114, 114,
    114, 114, 114, 116, 116, 116, 116, 116, 116, 116, 118, 118, 118, 118, 118,
    118, 118, 120, 120, 120, 120, 120, 120, 120, 120,
});

constexpr size_t kWeightPowArraySize = 100;
static_assert(kWeightPowArraySize == std::size(kWeightPow), "Wrong size");
static_assert(kWeightPowArraySize == std::size(kWeightPow11), "Wrong size");
static_assert(kWeightPowArraySize == std::size(kWeightPowShiftJis),
              "Wrong size");

constexpr auto kAngleSkew = std::to_array<const int8_t>({
    -0,  -2,  -3,  -5,  -7,  -9,  -11, -12, -14, -16, -18, -19, -21, -23, -25,
    -27, -29, -31, -32, -34, -36, -38, -40, -42, -45, -47, -49, -51, -53, -55,
});

int GetSkewFromAngle(int angle) {
  // |angle| is non-positive so |-angle| is used as the index. Need to make sure
  // |angle| != INT_MIN since -INT_MIN is undefined.
  if (angle > 0 || angle == std::numeric_limits<int>::min() ||
      static_cast<size_t>(-angle) >= std::size(kAngleSkew)) {
    return -58;
  }
  return kAngleSkew[-angle];
}

}  // namespace

CFX_SubstFont::CFX_SubstFont() = default;

CFX_SubstFont::~CFX_SubstFont() = default;

#if defined(PDF_USE_SKIA)
int CFX_SubstFont::GetOriginalWeight() const {
  int weight = weight_;

  // Perform the inverse weight adjustment of UseChromeSerif() to get the
  // original font weight.
  if (family_ == "Chrome Serif") {
    weight = weight * 5 / 4;
  }
  return weight;
}
#endif

void CFX_SubstFont::UseChromeSerif() {
  weight_ = weight_ * 4 / 5;
  family_ = "Chrome Serif";
}

// The following is not a perfect solution and can be further improved.
// For example, if `this` is "Book" and the `base_font_name` is "Bookman",
// this function will return "true" even though the actual font "Bookman"
// is not loaded.
// An exact string match is not possible here, because `subst_font_name`
// will be the same value for different postscript names.
// For example: "Times New Roman" as `subst_font_name` for all of these
// `base_font_name` values: "TimesNewRoman,Bold", "TimesNewRomanPS-Bold",
// "TimesNewRomanBold" and "TimesNewRoman-Bold".
bool CFX_SubstFont::IsActualFontLoaded(const ByteString& base_font_name) const {
  // Skip if we loaded the actual font.
  // example: TimesNewRoman,Bold -> Times New Roman
  ByteString subst_font_name = family_;
  subst_font_name.Remove(' ');
  subst_font_name.MakeLower();

  std::optional<size_t> find =
      base_font_name.Find(subst_font_name.AsStringView());
  return find.has_value() && find.value() == 0;
}

int CFX_SubstFont::GetWeightLevel(size_t index) const {
  if (index >= kWeightPowArraySize) {
    return -1;
  }
  if (charset_ == FX_Charset::kShiftJIS) {
    return kWeightPowShiftJis[index];
  }
  return kWeightPow11[index];
}

int CFX_SubstFont::GetWeightLevelForLoad(size_t index) const {
  index = std::min(index, kWeightPowArraySize - 1);
  if (charset_ == FX_Charset::kShiftJIS) {
    return kWeightPowShiftJis[index] * 65536 / 36655;
  }
  return kWeightPow[index];
}

int CFX_SubstFont::GetSkew() const {
  return GetSkewFromAngle(italic_angle_);
}

int CFX_SubstFont::GetSkewCJK() const {
  return GetSkewFromAngle(italic_cjk_ ? -15 : 0);
}

int CFX_SubstFont::GetEmboldenLevelForRender(bool is_cid_font,
                                             int32_t ft_matrix_xx,
                                             int32_t ft_matrix_xy) const {
  if (flag_mm_) {
    return 0;
  }
  const int weight = GetEffectiveWeight(is_cid_font);
  if (weight <= 400) {
    return 0;
  }
  const size_t index = static_cast<size_t>((weight - 400) / 10);
  const int level = GetWeightLevel(index);
  if (level < 0) {
    return -1;
  }
  pdfium::CheckedNumeric<int64_t> checked_level = level;
  checked_level = checked_level *
                  (pdfium::CheckedNumeric<int64_t>(ft_matrix_xx).Abs() +
                   pdfium::CheckedNumeric<int64_t>(ft_matrix_xy).Abs()) /
                  36655;
  return checked_level.ValueOrDefault<int>(0);
}

int CFX_SubstFont::GetEmboldenLevelForLoad() const {
  if (flag_mm_) {
    return 0;
  }
  const int weight = GetWeight();
  if (weight <= 400) {
    return 0;
  }
  const size_t index = static_cast<size_t>((weight - 400) / 10);
  return GetWeightLevelForLoad(index);
}

void CFX_SubstFont::ConfigureExternalSubst(const ByteString& face_name,
                                           FX_Charset charset,
                                           int weight,
                                           bool is_italic,
                                           int italic_angle,
                                           bool face_is_bold,
                                           bool face_is_italic) {
  family_ = face_name;
  charset_ = charset;
  int face_weight =
      face_is_bold ? pdfium::kFontWeightBold : pdfium::kFontWeightNormal;
  if (weight != face_weight) {
    weight_ = weight;
  }
  if (is_italic && !face_is_italic) {
    if (italic_angle == 0) {
      italic_angle = -12;
    } else if (abs(italic_angle) < 5) {
      italic_angle = 0;
    }
    italic_angle_ = italic_angle;
  }
}
