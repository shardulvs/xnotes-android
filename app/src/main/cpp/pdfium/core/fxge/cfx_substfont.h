// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef CORE_FXGE_CFX_SUBSTFONT_H_
#define CORE_FXGE_CFX_SUBSTFONT_H_

#include "core/fxcrt/bytestring.h"
#include "core/fxcrt/fx_codepage.h"
#include "core/fxge/fx_font.h"

// Represents variations to apply on top of an existing font/face to
// convert it to render as if it were an alternative font.
class CFX_SubstFont {
 public:
  CFX_SubstFont();
  ~CFX_SubstFont();

#if defined(PDF_USE_SKIA)
  int GetOriginalWeight() const;
#endif
  void UseChromeSerif();
  bool IsActualFontLoaded(const ByteString& base_font_name) const;

  // Returns the font skew angle based on whether font is a CID font.
  int GetEffectiveSkew(bool is_cid_font) const {
    return subst_cjk_ && is_cid_font ? GetSkewCJK() : GetSkew();
  }

  // Returns the font weight based on whether font is a CID font.
  int GetEffectiveWeight(bool is_cid_font) const {
    return subst_cjk_ && is_cid_font ? weight_cjk_ : weight_;
  }

  // Returns emboldening level for rendering, or negative on failure.
  int GetEmboldenLevelForRender(bool is_cid_font,
                                int32_t ft_matrix_xx,
                                int32_t ft_matrix_xy) const;

  // Returns emboldening level for path loading.
  int GetEmboldenLevelForLoad() const;

  // Returns estimated stem width.
  int GetEstimatedStemV() const { return weight_ / 5; }

  const ByteString& GetFamily() const { return family_; }
  int GetSkew() const;
  int GetWeight() const { return weight_; }
  int GetItalicAngle() const { return italic_angle_; }
  FX_Charset GetCharset() const { return charset_; }
  bool IsSymbolic() const { return charset_ == FX_Charset::kSymbol; }
  bool IsBold() const { return weight_ == pdfium::kFontWeightBold; }

#if BUILDFLAG(IS_APPLE)
  bool IsMediumWeight() const { return weight_ >= 500 && weight_ <= 600; }
#endif

  void SetFamily(const ByteString& family) { family_ = family; }
  void SetCharset(FX_Charset charset) { charset_ = charset; }
  void SetWeight(int weight) { weight_ = weight; }
  void SetItalicAngle(int angle) { italic_angle_ = angle; }
  void SetWeightCJK(int weight) { weight_cjk_ = weight; }
  void SetSubstCJK(bool subst) { subst_cjk_ = subst; }
  void SetItalicCJK(bool italic) { italic_cjk_ = italic; }

  void ConfigureExternalSubst(const ByteString& face_name,
                              FX_Charset charset,
                              int weight,
                              bool is_italic,
                              int italic_angle,
                              bool face_is_bold,
                              bool face_is_italic);

  void SetIsBuiltInGenericFont() { flag_mm_ = true; }
  bool IsBuiltInGenericFont() const { return flag_mm_; }

 private:
  // Returns negative values on failure.
  int GetWeightLevel(size_t index) const;

  // Clamps index to size of table.
  int GetWeightLevelForLoad(size_t index) const;

  int GetSkewCJK() const;

  ByteString family_;
  FX_Charset charset_ = FX_Charset::kANSI;
  int weight_ = 0;
  int italic_angle_ = 0;
  int weight_cjk_ = 0;
  bool subst_cjk_ = false;
  bool italic_cjk_ = false;
  bool flag_mm_ = false;
};

#endif  // CORE_FXGE_CFX_SUBSTFONT_H_
