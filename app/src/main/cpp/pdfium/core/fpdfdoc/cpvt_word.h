// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef CORE_FPDFDOC_CPVT_WORD_H_
#define CORE_FPDFDOC_CPVT_WORD_H_

#include <stdint.h>

#include "core/fxcrt/fx_codepage.h"
#include "core/fxcrt/fx_coordinates.h"

class CPVT_Word {
 public:
  CPVT_Word();
  CPVT_Word(uint16_t word,
            FX_Charset charset,
            const CFX_PointF& location,
            float ascent,
            float descent,
            float width,
            int32_t font_index,
            float font_size,
            bool is_rtl);
  CPVT_Word(const CPVT_Word&);
  CPVT_Word& operator=(const CPVT_Word&);
  ~CPVT_Word();

  uint16_t word() const { return word_; }
  FX_Charset charset() const { return charset_; }
  int32_t font_index() const { return font_index_; }
  const CFX_PointF& location() const { return location_; }
  void set_location(const CFX_PointF& location) { location_ = location; }
  float width() const { return width_; }
  float font_size() const { return font_size_; }
  bool is_rtl() const { return is_rtl_; }
  float ascent() const { return ascent_; }
  float descent() const { return descent_; }

  float CaretX() const { return is_rtl_ ? location_.x : location_.x + width_; }
  float AscentY() const { return location_.y + ascent_; }
  float DescentY() const { return location_.y + descent_; }

 private:
  uint16_t word_ = 0;
  FX_Charset charset_ = FX_Charset::kANSI;
  CFX_PointF location_;
  float ascent_ = 0.0f;
  float descent_ = 0.0f;
  float width_ = 0.0f;
  int32_t font_index_ = -1;
  float font_size_ = 0.0f;
  bool is_rtl_ = false;
};

#endif  // CORE_FPDFDOC_CPVT_WORD_H_
