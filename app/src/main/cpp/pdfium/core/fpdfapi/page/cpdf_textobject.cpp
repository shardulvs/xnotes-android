// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fpdfapi/page/cpdf_textobject.h"

#include <algorithm>

#include "core/fpdfapi/font/cpdf_cidfont.h"
#include "core/fpdfapi/font/cpdf_font.h"
#include "core/fxcrt/check.h"
#include "core/fxcrt/check_op.h"
#include "core/fxcrt/fx_coordinates.h"
#include "core/fxcrt/span.h"
#include "core/fxcrt/span_util.h"
#include "core/fxcrt/zip.h"

namespace {

constexpr bool IsLatinWord(uint32_t unicode) {
  return unicode != 0x20 && unicode <= 0x28FF;
}

bool IsVertWritingCIDFont(const CPDF_CIDFont* font) {
  return font && font->IsVertWriting();
}

float GetWordSpaceIfNeeded(const CPDF_CIDFont* cid_font,
                           uint32_t char_code,
                           float word_space) {
  if (char_code == ' ' && (!cid_font || cid_font->GetCharSize(' ') == 1)) {
    return word_space;
  }
  return 0;
}

}  // namespace

CPDF_TextObject::Item::Item() = default;

CPDF_TextObject::Item::Item(const Item& that) = default;

CPDF_TextObject::Item::~Item() = default;

CPDF_TextObject::CPDF_TextObject(int32_t content_stream)
    : CPDF_PageObject(content_stream) {}

CPDF_TextObject::CPDF_TextObject() : CPDF_TextObject(kNoContentStream) {}

CPDF_TextObject::~CPDF_TextObject() = default;

size_t CPDF_TextObject::CountItems() const {
  return char_codes_.size();
}

CPDF_TextObject::Item CPDF_TextObject::GetItemInfo(size_t index) const {
  DCHECK_LT(index, char_codes_.size());

  Item info;
  info.char_code_ = char_codes_[index];
  info.origin_ = CFX_PointF(char_positions_[index], 0);

  RetainPtr<CPDF_Font> font = GetFont();
  const CPDF_CIDFont* cid_font = font->AsCIDFont();
  if (!IsVertWritingCIDFont(cid_font)) {
    return info;
  }

  uint16_t cid = cid_font->CIDFromCharCode(info.char_code_);
  info.origin_ = CFX_PointF(0, info.origin_.x);

  CFX_Point16 vertical_origin = cid_font->GetVertOrigin(cid);
  float font_size = GetFontSize();
  info.origin_.x -= font_size * vertical_origin.x / 1000;
  info.origin_.y -= font_size * vertical_origin.y / 1000;
  return info;
}

uint32_t CPDF_TextObject::GetCharCode(size_t index) const {
  return index < char_codes_.size() ? char_codes_[index]
                                    : CPDF_Font::kInvalidCharCode;
}

CPDF_TextObject::Item CPDF_TextObject::GetCharInfo(size_t index) const {
  return index < char_codes_.size() ? GetItemInfo(index) : Item();
}

int CPDF_TextObject::CountWords() const {
  RetainPtr<CPDF_Font> font = GetFont();
  bool is_in_latin_word = false;
  int word_count = 0;
  for (uint32_t char_code : char_codes_) {
    WideString unicode_str = font->UnicodeFromCharCode(char_code);
    uint16_t unicode = unicode_str.Front();  // Front() safe when empty.
    const bool is_latin = IsLatinWord(unicode);
    if (is_latin && is_in_latin_word) {
      continue;
    }

    is_in_latin_word = is_latin;
    if (unicode != 0x20) {
      word_count++;
    }
  }

  return word_count;
}

WideString CPDF_TextObject::GetWordString(int word_index) const {
  RetainPtr<CPDF_Font> font = GetFont();
  WideString result_str;
  int word_count = 0;
  bool is_in_latin_word = false;
  for (uint32_t char_code : char_codes_) {
    WideString unicode_str = font->UnicodeFromCharCode(char_code);
    uint16_t unicode = unicode_str.Front();  // Front() safe when empty.
    const bool is_latin = IsLatinWord(unicode);
    if (!is_latin || !is_in_latin_word) {
      is_in_latin_word = is_latin;
      if (unicode != 0x20) {
        word_count++;
      }
    }
    if (word_count - 1 == word_index) {
      result_str += unicode;
    }
  }
  return result_str;
}

std::unique_ptr<CPDF_TextObject> CPDF_TextObject::Clone() const {
  auto obj = std::make_unique<CPDF_TextObject>();
  obj->CopyData(this);
  obj->char_codes_ = char_codes_;
  obj->char_kernings_ = char_kernings_;
  obj->char_positions_ = char_positions_;
  obj->pos_ = pos_;
  return obj;
}

CPDF_PageObject::Type CPDF_TextObject::GetType() const {
  return Type::kText;
}

void CPDF_TextObject::Transform(const CFX_Matrix& matrix) {
  SetTextMatrix(GetTextMatrix() * matrix);
  SetDirty(true);
}

CPDF_TextObject* CPDF_TextObject::AsText() {
  return this;
}

bool CPDF_TextObject::SetAbsolutePositions(
    pdfium::span<const float> absolute_positions) {
  if (char_codes_.size() <= 1) {
    return false;
  }

  if (absolute_positions.size() != char_codes_.size() - 1) {
    return false;
  }

  float font_size = GetFontSize();
  if (font_size == 0.0f) {
    return false;
  }
  font_size /= 1000;

  RetainPtr<CPDF_Font> font = GetFont();
  const CPDF_CIDFont* cid_font = font->AsCIDFont();
  const float char_space = text_state().GetCharSpace();
  const float word_space = text_state().GetWordSpace();

  // Re-calculate `char_kernings_` based on `absolute_positions`. Then let
  // CalcPositionDataInternal() re-calculate `char_positions_`.
  auto char_codes = pdfium::span(char_codes_).first(absolute_positions.size());
  auto char_kernings =
      pdfium::span(char_kernings_).first(absolute_positions.size());
  float current_position = 0;
  for (auto [char_code, next_position, kerning] :
       fxcrt::Zip(char_codes, absolute_positions, char_kernings)) {
    current_position += GetCharWidth(char_code);
    current_position += GetWordSpaceIfNeeded(cid_font, char_code, word_space);
    current_position += char_space;

    kerning = (current_position - next_position) / font_size;
    current_position = next_position;
  }
  char_kernings_.back() = 0;

  CalcPositionDataInternal(font);
  return true;
}

CFX_Matrix CPDF_TextObject::GetTextMatrix() const {
  pdfium::span<const float, 4> text_matrix = text_state().GetMatrix();
  return CFX_Matrix(text_matrix[0], text_matrix[2], text_matrix[1],
                    text_matrix[3], pos_.x, pos_.y);
}

void CPDF_TextObject::SetTextMatrix(const CFX_Matrix& matrix) {
  pdfium::span<float, 4> text_matrix = mutable_text_state().GetMutableMatrix();
  text_matrix[0] = matrix.a;
  text_matrix[1] = matrix.c;
  text_matrix[2] = matrix.b;
  text_matrix[3] = matrix.d;
  pos_ = CFX_PointF(matrix.e, matrix.f);
  CalcPositionDataInternal(GetFont());
}

void CPDF_TextObject::SetSegments(pdfium::span<const ByteString> strings,
                                  pdfium::span<const float> kernings) {
  size_t segments_count = strings.size();
  CHECK(segments_count);
  char_codes_.clear();
  char_kernings_.clear();
  char_positions_.clear();
  RetainPtr<CPDF_Font> font = GetFont();
  size_t char_count = 0;
  for (const auto& str : strings) {
    char_count += font->CountChar(str.AsStringView());
  }
  CHECK(char_count);
  char_codes_.resize(char_count);
  char_kernings_.resize(char_count);
  char_positions_.resize(char_count);
  size_t index = 0;
  for (size_t i = 0; i < segments_count; ++i) {
    ByteStringView segment = strings[i].AsStringView();
    size_t offset = 0;
    while (offset < segment.GetLength()) {
      DCHECK_LT(index, char_codes_.size());
      char_codes_[index++] = font->GetNextChar(segment, &offset);
    }
    if (i != segments_count - 1) {
      char_kernings_[index - 1] = kernings[i];
    }
  }
}

void CPDF_TextObject::SetText(const ByteString& str) {
  SetSegments(pdfium::span_from_ref(str), pdfium::span<float>());
  CalcPositionDataInternal(GetFont());
  SetDirty(true);
}

float CPDF_TextObject::GetCharWidth(uint32_t char_code) const {
  const float font_size = GetFontSize() / 1000;
  RetainPtr<CPDF_Font> font = GetFont();
  const CPDF_CIDFont* cid_font = font->AsCIDFont();
  if (!IsVertWritingCIDFont(cid_font)) {
    return font->GetCharWidth(char_code) * font_size;
  }

  uint16_t cid = cid_font->CIDFromCharCode(char_code);
  return cid_font->GetVertWidth(cid) * font_size;
}

RetainPtr<CPDF_Font> CPDF_TextObject::GetFont() const {
  return text_state().GetFont();
}

float CPDF_TextObject::GetFontSize() const {
  return text_state().GetFontSize();
}

TextRenderingMode CPDF_TextObject::GetTextRenderMode() const {
  return text_state().GetTextMode();
}

void CPDF_TextObject::SetTextRenderMode(TextRenderingMode mode) {
  mutable_text_state().SetTextMode(mode);
  SetDirty(true);
}

CFX_PointF CPDF_TextObject::CalcPositionData(float horz_scale) {
  RetainPtr<CPDF_Font> font = GetFont();
  const float current_position = CalcPositionDataInternal(font);
  if (IsVertWritingCIDFont(font->AsCIDFont())) {
    return {0, current_position};
  }
  return {current_position * horz_scale, 0};
}

float CPDF_TextObject::CalcPositionDataInternal(
    const RetainPtr<CPDF_Font>& font) {
  float current_position = 0;
  float min_x = 10000.0f;
  float max_x = -10000.0f;
  float min_y = 10000.0f;
  float max_y = -10000.0f;
  const CPDF_CIDFont* cid_font = font->AsCIDFont();
  const bool is_vertical_writing = IsVertWritingCIDFont(cid_font);
  const float font_size = GetFontSize();
  const float char_space = text_state().GetCharSpace();
  const float word_space = text_state().GetWordSpace();

  for (size_t i = 0; i < char_codes_.size(); ++i) {
    const uint32_t char_code = char_codes_[i];
    char_positions_[i] = current_position;

    FX_RECT char_rect = font->GetCharBBox(char_code);
    float char_width;
    if (is_vertical_writing) {
      uint16_t cid = cid_font->CIDFromCharCode(char_code);
      CFX_Point16 vertical_origin = cid_font->GetVertOrigin(cid);
      char_rect.Offset(-vertical_origin.x, -vertical_origin.y);
      min_x = std::min({min_x, static_cast<float>(char_rect.left),
                        static_cast<float>(char_rect.right)});
      max_x = std::max({max_x, static_cast<float>(char_rect.left),
                        static_cast<float>(char_rect.right)});
      const float char_top =
          current_position + char_rect.top * font_size / 1000;
      const float char_bottom =
          current_position + char_rect.bottom * font_size / 1000;
      min_y = std::min({min_y, char_top, char_bottom});
      max_y = std::max({max_y, char_top, char_bottom});
      char_width = cid_font->GetVertWidth(cid) * font_size / 1000;
    } else {
      min_y = std::min({min_y, static_cast<float>(char_rect.top),
                        static_cast<float>(char_rect.bottom)});
      max_y = std::max({max_y, static_cast<float>(char_rect.top),
                        static_cast<float>(char_rect.bottom)});
      const float char_left =
          current_position + char_rect.left * font_size / 1000;
      const float char_right =
          current_position + char_rect.right * font_size / 1000;
      min_x = std::min({min_x, char_left, char_right});
      max_x = std::max({max_x, char_left, char_right});
      char_width = font->GetCharWidth(char_code) * font_size / 1000;
    }
    current_position += char_width;
    current_position += GetWordSpaceIfNeeded(cid_font, char_code, word_space);
    current_position += char_space;
    current_position -= (char_kernings_[i] * font_size) / 1000;
  }

  if (is_vertical_writing) {
    min_x = min_x * font_size / 1000;
    max_x = max_x * font_size / 1000;
  } else {
    min_y = min_y * font_size / 1000;
    max_y = max_y * font_size / 1000;
  }

  SetOriginalRect(CFX_FloatRect(min_x, min_y, max_x, max_y));
  CFX_FloatRect rect = GetTextMatrix().TransformRect(GetOriginalRect());
  if (TextRenderingModeIsStrokeMode(text_state().GetTextMode())) {
    // TODO(crbug.com/42270854): Does the original rect need a similar
    // adjustment?
    const float half_width = graph_state().GetLineWidth() / 2;
    rect.Inflate(half_width, half_width);
  }
  SetRect(rect);

  return current_position;
}
