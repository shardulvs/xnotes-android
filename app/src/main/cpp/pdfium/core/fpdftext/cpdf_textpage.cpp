// Copyright 2014 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fpdftext/cpdf_textpage.h"

#include <math.h>
#include <stdint.h>

#include <algorithm>
#include <array>
#include <utility>
#include <vector>

#include "core/fpdfapi/font/cpdf_cidfont.h"
#include "core/fpdfapi/font/cpdf_font.h"
#include "core/fpdfapi/page/cpdf_form.h"
#include "core/fpdfapi/page/cpdf_formobject.h"
#include "core/fpdfapi/page/cpdf_page.h"
#include "core/fpdfapi/page/cpdf_pageobject.h"
#include "core/fpdfapi/page/cpdf_textobject.h"
#include "core/fpdfapi/parser/cpdf_dictionary.h"
#include "core/fpdfapi/parser/cpdf_string.h"
#include "core/fpdftext/unicodenormalizationdata.h"
#include "core/fxcrt/check.h"
#include "core/fxcrt/check_op.h"
#include "core/fxcrt/compiler_specific.h"
#include "core/fxcrt/data_vector.h"
#include "core/fxcrt/fx_bidi.h"
#include "core/fxcrt/fx_extension.h"
#include "core/fxcrt/fx_unicode.h"
#include "core/fxcrt/notreached.h"
#include "core/fxcrt/span.h"
#include "core/fxcrt/stl_util.h"
#include "core/fxcrt/zip.h"

namespace {

constexpr float kDefaultFontSize = 1.0f;
constexpr float kSizeEpsilon = 0.01f;
constexpr std::array<pdfium::span<const uint16_t>, 3>
    kUnicodeDataNormalizationMaps = {{kUnicodeDataNormalizationMap2,
                                      kUnicodeDataNormalizationMap3,
                                      kUnicodeDataNormalizationMap4}};

float NormalizeThreshold(float threshold, int t1, int t2, int t3) {
  DCHECK_LT(t1, t2);
  DCHECK_LT(t2, t3);
  if (threshold < t1) {
    return threshold / 2.0f;
  }
  if (threshold < t2) {
    return threshold / 4.0f;
  }
  if (threshold < t3) {
    return threshold / 5.0f;
  }
  return threshold / 6.0f;
}

float CalculateBaseSpace(const CPDF_TextObject* text_obj,
                         const CFX_Matrix& matrix) {
  const size_t nItems = text_obj->CountItems();
  const float char_space = text_obj->text_state().GetCharSpace();
  if (char_space == 0.0f || nItems < 2) {
    return 0.0f;
  }

  bool has_kerning = false;
  const float spacing = matrix.TransformDistance(char_space);
  const float fontsize_h = text_obj->text_state().GetFontSizeH();
  float base_space = spacing;
  for (float kerning_val : text_obj->GetCharKernings()) {
    if (kerning_val != 0) {
      float kerning = -fontsize_h * kerning_val / 1000;
      base_space = std::min(base_space, kerning + spacing);
      has_kerning = true;
    }
  }
  if (base_space < 0.0 || (nItems == 2 && has_kerning)) {
    return 0.0f;
  }

  return base_space;
}

float CalculateBaseSpaceAdjustment(const CPDF_TextObject* text_obj,
                                   const CFX_Matrix& matrix) {
  float char_space = text_obj->text_state().GetCharSpace();
  if (char_space > 0.001f) {
    return -matrix.TransformDistance(char_space);
  }
  if (char_space < -0.001f) {
    return matrix.TransformDistance(fabs(char_space));
  }
  return 0.0f;
}

DataVector<wchar_t> GetUnicodeNormalization(wchar_t wch) {
  wch = wch & 0xFFFF;
  wchar_t wFind = kUnicodeDataNormalization[wch];
  if (!wFind) {
    return DataVector<wchar_t>(1, wch);
  }
  if (wFind >= 0x8000) {
    return DataVector<wchar_t>(1,
                               kUnicodeDataNormalizationMap1[wFind - 0x8000]);
  }
  wch = wFind & 0x0FFF;
  wFind >>= 12;
  auto maps = kUnicodeDataNormalizationMaps[wFind - 2].subspan(
      static_cast<size_t>(wch));
  if (wFind == 4) {
    wFind = maps.front();
    maps = maps.subspan<1u>();
  }
  const auto range = maps.first(static_cast<size_t>(wFind));
  return DataVector<wchar_t>(range.begin(), range.end());
}

float MaskPercentFilled(const std::vector<bool>& mask,
                        int32_t start,
                        int32_t end) {
  if (start >= end) {
    return 0;
  }
  float count = std::count_if(mask.begin() + start, mask.begin() + end,
                              [](bool r) { return r; });
  return count / (end - start);
}

bool IsControlChar(const CPDF_TextPage::CharInfo& char_info) {
  switch (char_info.unicode()) {
    case 0x2:
    case 0x3:
    case 0x93:
    case 0x94:
    case 0x96:
    case 0x97:
    case 0x98:
    case 0xfffe:
      return char_info.char_type() != CPDF_TextPage::CharType::kHyphen;
    default:
      return false;
  }
}

bool IsHyphenCode(wchar_t c) {
  return c == 0x2D || c == 0xAD;
}

bool IsNormalCharacter(const CPDF_TextPage::CharInfo& char_info) {
  return char_info.unicode() != 0 ? !IsControlChar(char_info)
                                  : char_info.char_code() != 0;
}

bool IsRectIntersect(const CFX_FloatRect& rect1, const CFX_FloatRect& rect2) {
  CFX_FloatRect rect = rect1;
  rect.Intersect(rect2);
  return !rect.IsEmpty();
}

bool IsRightToLeft(const CPDF_TextObject& text_obj) {
  RetainPtr<const CPDF_Font> font = text_obj.GetFont();
  const size_t nItems = text_obj.CountItems();
  WideString str;
  str.Reserve(nItems);
  for (size_t i = 0; i < nItems; ++i) {
    CPDF_TextObject::Item item = text_obj.GetItemInfo(i);
    WideString unicode = font->UnicodeFromCharCode(item.char_code_);
    wchar_t wc = unicode.Front();  // Front() safe when empty.
    if (wc == 0) {
      wc = item.char_code_;
    }
    if (wc) {
      str += wc;
    }
  }
  return CFX_BidiString(str, /*auto_order=*/true).OverallDirection() ==
         CFX_BidiChar::Direction::kRight;
}

int GetCharWidth(uint32_t charCode, CPDF_Font* font) {
  if (charCode == CPDF_Font::kInvalidCharCode) {
    return 0;
  }

  int w = font->GetCharWidth(charCode);
  if (w > 0) {
    return w;
  }

  ByteString str;
  font->AppendChar(&str, charCode);
  w = font->GetStringWidth(str.AsStringView());
  if (w > 0) {
    return w;
  }

  FX_RECT rect = font->GetCharBBox(charCode);
  if (!rect.Valid()) {
    return 0;
  }

  return std::max(rect.Width(), 0);
}

float CalculateSpaceThreshold(CPDF_Font* font,
                              float fontsize_h,
                              uint32_t char_code) {
  const uint32_t space_charcode = font->CharCodeFromUnicode(' ');
  float threshold = 0;
  if (space_charcode != CPDF_Font::kInvalidCharCode) {
    threshold = fontsize_h * font->GetCharWidth(space_charcode) / 1000;
  }
  if (threshold > fontsize_h / 3) {
    threshold = 0;
  } else {
    threshold /= 2;
  }
  if (threshold == 0) {
    threshold = GetCharWidth(char_code, font);
    threshold = NormalizeThreshold(threshold, 300, 500, 700);
    threshold = fontsize_h * threshold / 1000;
  }
  return threshold;
}

bool GenerateSpace(const CFX_PointF& pos,
                   float last_pos,
                   float this_width,
                   float last_width,
                   float threshold) {
  if (fabs(last_pos + last_width - pos.x) <= threshold) {
    return false;
  }

  float threshold_pos = threshold + last_width;
  float pos_difference = pos.x - last_pos;
  if (fabs(pos_difference) > threshold_pos) {
    return true;
  }
  if (pos.x < 0 && -threshold_pos > pos_difference) {
    return true;
  }
  return pos_difference > this_width + last_width;
}

bool EndHorizontalLine(const CFX_FloatRect& this_rect,
                       const CFX_FloatRect& prev_rect) {
  if (this_rect.Height() <= 4.5 || prev_rect.Height() <= 4.5) {
    return false;
  }

  float top = std::min(this_rect.top, prev_rect.top);
  float bottom = std::max(this_rect.bottom, prev_rect.bottom);
  return bottom >= top;
}

bool EndVerticalLine(const CFX_FloatRect& this_rect,
                     const CFX_FloatRect& prev_rect,
                     const CFX_FloatRect& curline_rect,
                     float this_fontsize,
                     float prev_fontsize) {
  if (this_rect.Width() <= this_fontsize * 0.1f ||
      prev_rect.Width() <= prev_fontsize * 0.1f) {
    return false;
  }

  float left = std::max(this_rect.left, curline_rect.left);
  float right = std::min(this_rect.right, curline_rect.right);
  return right <= left;
}

float GetFontSize(const CPDF_TextObject* text_object) {
  bool has_font = text_object && text_object->GetFont();
  return has_font ? text_object->GetFontSize() : kDefaultFontSize;
}

CFX_FloatRect GetLooseBounds(const CPDF_TextPage::CharInfo& charinfo) {
  if (charinfo.char_box().IsEmpty()) {
    return charinfo.char_box();
  }

  const CPDF_TextObject* text_object = charinfo.text_object();
  float font_size = GetFontSize(text_object);
  if (text_object && !FXSYS_IsFloatZero(font_size) &&
      charinfo.char_code() != CPDF_Font::kInvalidCharCode) {
    RetainPtr<CPDF_Font> font = text_object->GetFont();
    bool is_vert_writing = font->IsVertWriting();
    if (is_vert_writing && font->IsCIDFont()) {
      CPDF_CIDFont* cid_font = font->AsCIDFont();
      uint16_t cid = cid_font->CIDFromCharCode(charinfo.char_code());

      CFX_Point16 vertical_origin = cid_font->GetVertOrigin(cid);
      double offsetx = (vertical_origin.x - 500) * font_size / 1000.0;
      double offsety = vertical_origin.y * font_size / 1000.0;
      // Note that `vert_width` is generally negative, and then so is `height`.
      int16_t vert_width = cid_font->GetVertWidth(cid);
      double height = vert_width * font_size / 1000.0;

      float left = charinfo.origin().x + offsetx;
      float right = left + font_size;
      float top = charinfo.origin().y + offsety;
      float bottom = top + height;
      CFX_FloatRect char_box(left, bottom, right, top);
      char_box.Union(charinfo.char_box());
      return char_box;
    }

    int ascent = font->GetTypeAscent();
    int descent = font->GetTypeDescent();
    const FX_RECT& font_bbox = font->GetFontBBox();
    if (font_bbox.top > font_bbox.bottom) {
      ascent = std::min(ascent, font_bbox.top);
      descent = std::max(descent, font_bbox.bottom);
    }
    if (ascent != descent) {
      // Compute `left` and `right` based on the individual character's `width`.
      float width = text_object->GetCharWidth(charinfo.char_code());
      CFX_Matrix inverse_matrix = charinfo.matrix().GetInverse();
      CFX_PointF original_origin = inverse_matrix.Transform(charinfo.origin());
      float left = original_origin.x;
      float right = original_origin.x + (is_vert_writing ? -width : width);

      float bottom = original_origin.y + descent * font_size / 1000;
      float top = original_origin.y + ascent * font_size / 1000;
      CFX_FloatRect char_box = charinfo.matrix().TransformRect(
          CFX_FloatRect(left, bottom, right, top));
      char_box.Union(charinfo.char_box());
      return char_box;
    }
  }

  // Fallback to the tight bounds in empty text scenarios, or bad font metrics
  return charinfo.char_box();
}

}  // namespace

CPDF_TextPage::TransformedTextObject::TransformedTextObject() = default;

CPDF_TextPage::TransformedTextObject::TransformedTextObject(
    const TransformedTextObject& that) = default;

CPDF_TextPage::TransformedTextObject::~TransformedTextObject() = default;

CPDF_TextPage::CharInfo::CharInfo(CharType char_type,
                                  uint32_t char_code,
                                  wchar_t unicode,
                                  CFX_PointF origin,
                                  CFX_FloatRect char_box,
                                  CFX_Matrix matrix,
                                  CPDF_TextObject* text_object)
    : char_type_(char_type),
      unicode_(unicode),
      char_code_(char_code),
      origin_(origin),
      char_box_(char_box),
      matrix_(matrix),
      text_object_(text_object) {
  loose_char_box_ = GetLooseBounds(*this);
}

CPDF_TextPage::CharInfo::CharInfo(const CharInfo&) = default;

CPDF_TextPage::CharInfo::~CharInfo() = default;

CPDF_TextPage::CPDF_TextPage(const CPDF_Page* page, bool rtl)
    : page_(page), rtl_(rtl), display_matrix_(page_->GetDisplayMatrix()) {
  Init();
}

CPDF_TextPage::~CPDF_TextPage() = default;

void CPDF_TextPage::Init() {
  text_buf_.SetAllocStep(10240);
  ProcessObject();

  const int count = CountChars();
  if (count) {
    char_indices_.push_back({0, 0});
  }

  bool skipped = false;
  for (int i = 0; i < count; ++i) {
    const CharInfo& charinfo = char_list_[i];
    if (charinfo.char_type() == CharType::kGenerated ||
        IsNormalCharacter(charinfo)) {
      char_indices_.back().count++;
      skipped = true;
    } else {
      if (skipped) {
        char_indices_.push_back({i + 1, 0});
        skipped = false;
      } else {
        char_indices_.back().index = i + 1;
      }
    }
  }
}

int CPDF_TextPage::CountChars() const {
  return fxcrt::CollectionSize<int>(char_list_);
}

int CPDF_TextPage::CharIndexFromTextIndex(int text_index) const {
  int count = 0;
  for (const auto& info : char_indices_) {
    count += info.count;
    if (count > text_index) {
      return text_index - count + info.count + info.index;
    }
  }
  return -1;
}

int CPDF_TextPage::TextIndexFromCharIndex(int char_index) const {
  int count = 0;
  for (const auto& info : char_indices_) {
    int text_index = char_index - info.index;
    if (text_index < info.count) {
      return text_index >= 0 ? text_index + count : -1;
    }

    count += info.count;
  }
  return -1;
}

std::vector<CFX_FloatRect> CPDF_TextPage::GetRectArray(int start,
                                                       int count) const {
  std::vector<CFX_FloatRect> rects;
  if (start < 0 || count == 0) {
    return rects;
  }

  const int number_of_chars = CountChars();
  if (start >= number_of_chars) {
    return rects;
  }

  if (count < 0 || start + count > number_of_chars) {
    count = number_of_chars - start;
  }
  DCHECK_GT(count, 0);

  const CPDF_TextObject* text_object = nullptr;
  CFX_FloatRect rect;
  int pos = start;
  bool is_new_rect = true;
  while (count--) {
    const CharInfo& charinfo = char_list_[pos++];
    if (charinfo.char_type() == CharType::kGenerated) {
      continue;
    }
    if (charinfo.char_box().Width() < kSizeEpsilon ||
        charinfo.char_box().Height() < kSizeEpsilon) {
      continue;
    }
    if (!text_object) {
      text_object = charinfo.text_object();
    }
    if (text_object != charinfo.text_object()) {
      rects.push_back(rect);
      text_object = charinfo.text_object();
      is_new_rect = true;
    }
    if (is_new_rect) {
      is_new_rect = false;
      rect = charinfo.char_box();
      rect.Normalize();
      continue;
    }
    rect.Union(charinfo.char_box());
  }
  rects.push_back(rect);
  return rects;
}

int CPDF_TextPage::GetIndexAtPos(const CFX_PointF& point,
                                 const CFX_SizeF& tolerance) const {
  int pos;
  int near_pos = -1;
  double xdif = 5000;
  double ydif = 5000;
  const int count = CountChars();
  for (pos = 0; pos < count; ++pos) {
    const CFX_FloatRect& orig_charrect = char_list_[pos].char_box();
    if (orig_charrect.Contains(point)) {
      break;
    }

    if (tolerance.width <= 0 && tolerance.height <= 0) {
      continue;
    }

    CFX_FloatRect charrect = orig_charrect;
    charrect.Normalize();
    CFX_FloatRect char_rect_ext(charrect.left - tolerance.width / 2,
                                charrect.bottom - tolerance.height / 2,
                                charrect.right + tolerance.width / 2,
                                charrect.top + tolerance.height / 2);
    if (!char_rect_ext.Contains(point)) {
      continue;
    }

    double current_xdiff =
        std::min(fabs(point.x - charrect.left), fabs(point.x - charrect.right));
    double current_ydiff =
        std::min(fabs(point.y - charrect.bottom), fabs(point.y - charrect.top));
    if (current_ydiff + current_xdiff < xdif + ydif) {
      xdif = current_xdiff;
      ydif = current_ydiff;
      near_pos = pos;
    }
  }
  return pos < count ? pos : near_pos;
}

WideString CPDF_TextPage::GetTextByPredicate(
    const std::function<bool(const CharInfo&)>& predicate) const {
  float posy = 0;
  bool IsContainPreChar = false;
  bool IsAddLineFeed = false;
  WideString strText;
  for (const auto& charinfo : char_list_) {
    if (predicate(charinfo)) {
      if (fabs(posy - charinfo.origin().y) > 0 && !IsContainPreChar &&
          IsAddLineFeed) {
        posy = charinfo.origin().y;
        if (!strText.IsEmpty()) {
          strText += L"\r\n";
        }
      }
      IsContainPreChar = true;
      IsAddLineFeed = false;
      if (charinfo.unicode()) {
        strText += charinfo.unicode();
      }
    } else if (charinfo.unicode() == L' ') {
      if (IsContainPreChar) {
        strText += L' ';
        IsContainPreChar = false;
        IsAddLineFeed = false;
      }
    } else {
      IsContainPreChar = false;
      IsAddLineFeed = true;
    }
  }
  return strText;
}

WideString CPDF_TextPage::GetTextByRect(const CFX_FloatRect& rect) const {
  return GetTextByPredicate([&rect](const CharInfo& charinfo) {
    return IsRectIntersect(rect, charinfo.char_box());
  });
}

WideString CPDF_TextPage::GetTextByObject(
    const CPDF_TextObject* text_obj) const {
  return GetTextByPredicate([text_obj](const CharInfo& charinfo) {
    return charinfo.text_object() == text_obj;
  });
}

const CPDF_TextPage::CharInfo& CPDF_TextPage::GetCharInfo(size_t index) const {
  CHECK_LT(index, char_list_.size());
  return char_list_[index];
}

CPDF_TextPage::CharInfo& CPDF_TextPage::GetCharInfo(size_t index) {
  CHECK_LT(index, char_list_.size());
  return char_list_[index];
}

float CPDF_TextPage::GetCharFontSize(size_t index) const {
  CHECK_LT(index, char_list_.size());
  return GetFontSize(char_list_[index].text_object());
}

CFX_FloatRect CPDF_TextPage::GetCharLooseBounds(size_t index) const {
  CHECK_LT(index, char_list_.size());
  return char_list_[index].loose_char_box();
}

WideString CPDF_TextPage::GetPageText(int start, int count) const {
  if (start < 0 || start >= CountChars() || count <= 0 || char_list_.empty() ||
      text_buf_.IsEmpty()) {
    return WideString();
  }

  const int count_chars = CountChars();
  int text_start = TextIndexFromCharIndex(start);

  // If the character at |start| is a non-printing character, then
  // TextIndexFromCharIndex will return -1, so scan ahead to the first printing
  // character.
  while (text_start < 0) {
    if (start >= count_chars) {
      return WideString();
    }
    start++;
    text_start = TextIndexFromCharIndex(start);
  }

  count = std::min(count, count_chars - start);

  int last = start + count - 1;
  int text_last = TextIndexFromCharIndex(last);

  // If the character at |last| is a non-printing character, then
  // TextIndexFromCharIndex will return -1, so scan back to the last printing
  // character.
  while (text_last < 0) {
    if (last < text_start) {
      return WideString();
    }

    last--;
    text_last = TextIndexFromCharIndex(last);
  }

  if (text_last < text_start) {
    return WideString();
  }

  int text_count = text_last - text_start + 1;

  return WideString(text_buf_.AsStringView().Substr(text_start, text_count));
}

int CPDF_TextPage::CountRects(int start, int count) {
  if (start < 0) {
    return -1;
  }

  sel_rects_ = GetRectArray(start, count);
  return fxcrt::CollectionSize<int>(sel_rects_);
}

bool CPDF_TextPage::GetRect(int rectIndex, CFX_FloatRect* pRect) const {
  if (!fxcrt::IndexInBounds(sel_rects_, rectIndex)) {
    return false;
  }

  *pRect = sel_rects_[rectIndex];
  return true;
}

CPDF_TextPage::TextOrientation CPDF_TextPage::FindTextlineFlowOrientation()
    const {
  const int32_t nPageWidth = static_cast<int32_t>(page_->GetPageWidth());
  const int32_t nPageHeight = static_cast<int32_t>(page_->GetPageHeight());
  if (nPageWidth <= 0 || nPageHeight <= 0) {
    return TextOrientation::kUnknown;
  }

  std::vector<bool> nHorizontalMask(nPageWidth);
  std::vector<bool> nVerticalMask(nPageHeight);
  float fLineHeight = 0.0f;
  int32_t nStartH = nPageWidth;
  int32_t nEndH = 0;
  int32_t nStartV = nPageHeight;
  int32_t nEndV = 0;
  for (const auto& page_obj : *page_) {
    if (!page_obj->IsActive() || !page_obj->IsText()) {
      continue;
    }

    int32_t minH = static_cast<int32_t>(
        std::clamp<float>(page_obj->GetRect().left, 0.0f, nPageWidth));
    int32_t maxH = static_cast<int32_t>(
        std::clamp<float>(page_obj->GetRect().right, 0.0f, nPageWidth));
    int32_t minV = static_cast<int32_t>(
        std::clamp<float>(page_obj->GetRect().bottom, 0.0f, nPageHeight));
    int32_t maxV = static_cast<int32_t>(
        std::clamp<float>(page_obj->GetRect().top, 0.0f, nPageHeight));
    if (minH >= maxH || minV >= maxV) {
      continue;
    }

    for (int32_t i = minH; i < maxH; ++i) {
      nHorizontalMask[i] = true;
    }
    for (int32_t i = minV; i < maxV; ++i) {
      nVerticalMask[i] = true;
    }

    nStartH = std::min(nStartH, minH);
    nEndH = std::max(nEndH, maxH);
    nStartV = std::min(nStartV, minV);
    nEndV = std::max(nEndV, maxV);

    if (fLineHeight <= 0.0f) {
      fLineHeight = page_obj->GetRect().Height();
    }
  }
  const int32_t nDoubleLineHeight = 2 * fLineHeight;
  if ((nEndV - nStartV) < nDoubleLineHeight) {
    return TextOrientation::kHorizontal;
  }
  if ((nEndH - nStartH) < nDoubleLineHeight) {
    return TextOrientation::kVertical;
  }

  const float nSumH = MaskPercentFilled(nHorizontalMask, nStartH, nEndH);
  if (nSumH > 0.8f) {
    return TextOrientation::kHorizontal;
  }

  const float nSumV = MaskPercentFilled(nVerticalMask, nStartV, nEndV);
  if (nSumH > nSumV) {
    return TextOrientation::kHorizontal;
  }
  if (nSumH < nSumV) {
    return TextOrientation::kVertical;
  }
  return TextOrientation::kUnknown;
}

void CPDF_TextPage::AppendGeneratedCharacter(wchar_t unicode,
                                             const CFX_Matrix& form_matrix,
                                             bool use_temp_buffer) {
  std::optional<CharInfo> charinfo = GenerateCharInfo(unicode, form_matrix);
  if (!charinfo.has_value()) {
    return;
  }

  if (use_temp_buffer) {
    temp_text_buf_.AppendChar(unicode);
    temp_char_list_.push_back(charinfo.value());
  } else {
    text_buf_.AppendChar(unicode);
    char_list_.push_back(charinfo.value());
  }
}

void CPDF_TextPage::ProcessObject() {
  if (page_->GetActivePageObjectCount() == 0) {
    return;
  }

  textline_dir_ = FindTextlineFlowOrientation();
  for (auto it = page_->begin(); it != page_->end(); ++it) {
    CPDF_PageObject* page_obj = it->get();
    if (!page_obj->IsActive()) {
      continue;
    }

    if (page_obj->IsText()) {
      ProcessTextObject(page_obj->AsText(), CFX_Matrix(), page_, it);
    } else if (page_obj->IsForm()) {
      ProcessFormObject(page_obj->AsForm(), CFX_Matrix());
    }
  }
  ProcessTransformedTextObjects();
  text_objects_.clear();
  CloseTempLine();
}

void CPDF_TextPage::ProcessFormObject(CPDF_FormObject* form_obj,
                                      const CFX_Matrix& form_matrix) {
  CFX_Matrix actual_form_matrix = form_obj->form_matrix() * form_matrix;
  const CPDF_PageObjectHolder* holder = form_obj->form();
  for (auto it = holder->begin(); it != holder->end(); ++it) {
    CPDF_PageObject* page_obj = it->get();
    if (!page_obj->IsActive()) {
      continue;
    }

    if (page_obj->IsText()) {
      ProcessTextObject(page_obj->AsText(), actual_form_matrix, holder, it);
    } else if (page_obj->IsForm()) {
      ProcessFormObject(page_obj->AsForm(), actual_form_matrix);
    }
  }
}

void CPDF_TextPage::AddCharInfo(wchar_t wc, const CharInfo& info, bool is_rtl) {
  if (!IsNormalCharacter(info)) {
    char_list_.push_back(info);
    return;
  }

  if (is_rtl) {
    wc = pdfium::unicode::GetMirrorChar(wc);
  }

  DataVector<wchar_t> normalized;
  if (is_rtl || (wc >= 0xFB00 && wc <= 0xFB06)) {
    normalized = GetUnicodeNormalization(wc);
  }

  CharInfo modified_info = info;
  if (normalized.empty()) {
    text_buf_.AppendChar(wc);
    if (is_rtl) {
      modified_info.set_unicode(wc);
    }
    char_list_.push_back(modified_info);
    return;
  }

  modified_info.set_char_type(CharType::kPiece);
  for (wchar_t normalized_char : normalized) {
    modified_info.set_unicode(normalized_char);
    text_buf_.AppendChar(normalized_char);
    char_list_.push_back(modified_info);
  }
}

void CPDF_TextPage::CloseTempLine() {
  if (temp_char_list_.empty()) {
    return;
  }

  WideString str = temp_text_buf_.MakeString();
  bool prev_char_is_space = false;
  for (size_t i = 0; i < str.GetLength(); ++i) {
    if (str[i] != ' ') {
      prev_char_is_space = false;
      continue;
    }
    if (prev_char_is_space) {
      temp_text_buf_.Delete(i, 1);
      temp_char_list_.erase(temp_char_list_.begin() + i);
      str.Delete(i);
      --i;
    }
    prev_char_is_space = true;
  }
  CFX_BidiString bidi(str, /*auto_order=*/true);
  if (rtl_) {
    bidi.SetOverallDirectionRight();
  }
  CFX_BidiChar::Direction current_direction = bidi.OverallDirection();
  for (const auto& segment : bidi) {
    auto str_span = str.span().subspan(segment.start, segment.count);
    auto char_span =
        pdfium::span(temp_char_list_).subspan(segment.start, segment.count);
    if (segment.direction == CFX_BidiChar::Direction::kRight ||
        (segment.direction == CFX_BidiChar::Direction::kNeutral &&
         current_direction == CFX_BidiChar::Direction::kRight)) {
      current_direction = CFX_BidiChar::Direction::kRight;
      const bool is_actual_text =
          !char_span.empty() &&
          char_span[0].char_type() == CharType::kActualText;
      if (is_actual_text) {
        // Since /ActualText strings are already in logical order, pass their
        // characters in forward order instead of reversing them.
        for (auto [c, info] : fxcrt::Zip(str_span, char_span)) {
          AddCharInfo(c, info, /*is_rtl=*/true);
        }
      } else {
        for (size_t i = segment.count; i > 0; --i) {
          AddCharInfo(str_span[i - 1], char_span[i - 1], /*is_rtl=*/true);
        }
      }
    } else {
      if (segment.direction != CFX_BidiChar::Direction::kLeftWeak) {
        current_direction = CFX_BidiChar::Direction::kLeft;
      }
      for (auto [c, info] : fxcrt::Zip(str_span, char_span)) {
        AddCharInfo(c, info, /*is_rtl=*/false);
      }
    }
  }
  temp_char_list_.clear();
  temp_text_buf_.Delete(0, temp_text_buf_.GetLength());
}

void CPDF_TextPage::ProcessTextObject(
    CPDF_TextObject* text_obj,
    const CFX_Matrix& form_matrix,
    const CPDF_PageObjectHolder* obj_list,
    CPDF_PageObjectHolder::const_iterator obj_iter) {
  if (fabs(text_obj->GetRect().Width()) < kSizeEpsilon) {
    return;
  }

  TransformedTextObject new_obj;
  new_obj.text_obj_ = text_obj;
  new_obj.form_matrix_ = form_matrix;
  if (text_objects_.empty()) {
    text_objects_.push_back(new_obj);
    return;
  }
  if (IsSameAsPreTextObject(text_obj, obj_list, obj_iter)) {
    return;
  }

  // Explicitly copy in case `text_objects_` gets cleared.
  TransformedTextObject prev_obj = text_objects_.back();
  size_t nItem = prev_obj.text_obj_->CountItems();
  if (nItem == 0) {
    return;
  }

  CPDF_TextObject::Item item = prev_obj.text_obj_->GetItemInfo(nItem - 1);
  float prev_width =
      GetCharWidth(item.char_code_, prev_obj.text_obj_->GetFont().Get()) *
      prev_obj.text_obj_->GetFontSize() / 1000;

  CFX_Matrix prev_matrix =
      prev_obj.text_obj_->GetTextMatrix() * prev_obj.form_matrix_;
  prev_width = prev_matrix.TransformDistance(fabs(prev_width));
  item = text_obj->GetItemInfo(0);
  float this_width = GetCharWidth(item.char_code_, text_obj->GetFont().Get()) *
                     text_obj->GetFontSize() / 1000;
  this_width = fabs(this_width);

  CFX_Matrix this_matrix = text_obj->GetTextMatrix() * form_matrix;
  this_width = this_matrix.TransformDistance(fabs(this_width));

  float threshold = std::max(prev_width, this_width) / 4;
  CFX_PointF prev_pos = display_matrix_.Transform(
      prev_obj.form_matrix_.Transform(prev_obj.text_obj_->GetPos()));
  CFX_PointF this_pos =
      display_matrix_.Transform(form_matrix.Transform(text_obj->GetPos()));
  if (fabs(this_pos.y - prev_pos.y) > threshold * 2) {
    ProcessTransformedTextObjects();
    text_objects_.clear();
    text_objects_.push_back(new_obj);
    return;
  }

  for (size_t i = text_objects_.size(); i > 0; --i) {
    TransformedTextObject prev_text_obj = text_objects_[i - 1];
    CFX_PointF new_prev_pos =
        display_matrix_.Transform(prev_text_obj.form_matrix_.Transform(
            prev_text_obj.text_obj_->GetPos()));
    if (this_pos.x >= new_prev_pos.x) {
      text_objects_.insert(text_objects_.begin() + i, new_obj);
      return;
    }
  }
  text_objects_.insert(text_objects_.begin(), new_obj);
}

CPDF_TextPage::MarkedContentState CPDF_TextPage::PreMarkedContent(
    const CPDF_TextObject* text_obj) {
  const CPDF_ContentMarks* marks = text_obj->GetContentMarks();
  const size_t content_marks_count = marks->CountItems();
  if (content_marks_count == 0) {
    return MarkedContentState::kPass;
  }

  WideString actual_text;
  bool bExist = false;
  RetainPtr<const CPDF_Dictionary> dict;
  for (size_t i = 0; i < content_marks_count; ++i) {
    const CPDF_ContentMarkItem* item = marks->GetItem(i);
    dict = item->GetParam();
    if (!dict) {
      continue;
    }
    RetainPtr<const CPDF_String> temp = dict->GetStringFor("ActualText");
    if (temp) {
      bExist = true;
      actual_text = temp->GetUnicodeText();
    }
  }
  if (!bExist) {
    return MarkedContentState::kPass;
  }

  if (prev_text_obj_) {
    const CPDF_ContentMarks* prev_marks = prev_text_obj_->GetContentMarks();
    if (prev_marks->CountItems() == content_marks_count &&
        prev_marks->GetItem(content_marks_count - 1)->GetParam() == dict) {
      return MarkedContentState::kDone;
    }
  }

  if (actual_text.IsEmpty()) {
    return MarkedContentState::kPass;
  }

  bExist = false;
  for (size_t i = 0; i < actual_text.GetLength(); ++i) {
    wchar_t wc = actual_text[i];
    if ((wc > 0x80 && wc < 0xFFFD) || (wc <= 0x80 && isprint(wc))) {
      bExist = true;
      break;
    }
  }
  if (!bExist) {
    return MarkedContentState::kDone;
  }

  return MarkedContentState::kDelay;
}

bool CPDF_TextPage::ProcessMarkedContent(const TransformedTextObject& obj) {
  CPDF_TextObject* const text_obj = obj.text_obj_;
  const CPDF_ContentMarks* marks = text_obj->GetContentMarks();
  const size_t content_marks_count = marks->CountItems();
  WideString actual_text;
  for (size_t n = 0; n < content_marks_count; ++n) {
    const CPDF_ContentMarkItem* item = marks->GetItem(n);
    RetainPtr<const CPDF_Dictionary> dict = item->GetParam();
    if (dict) {
      actual_text = dict->GetUnicodeTextFor("ActualText");
    }
  }
  if (actual_text.IsEmpty()) {
    return false;
  }

  const bool is_rtl =
      IsRightToLeft(*text_obj) ||
      (prev_text_obj_ && IsRightToLeft(*prev_text_obj_) &&
       CFX_BidiString(actual_text, /*auto_order=*/true).OverallDirection() ==
           CFX_BidiChar::Direction::kRight);
  CFX_Matrix matrix = text_obj->GetTextMatrix() * obj.form_matrix_;
  CFX_FloatRect rect = text_obj->GetRect();
  float step = 0;

  if (is_rtl) {
    rect.left = rect.right - (rect.Width() / actual_text.GetLength());
    step = -rect.Width();
  } else {
    rect.right = rect.left + (rect.Width() / actual_text.GetLength());
    step = rect.Width();
  }

  RetainPtr<CPDF_Font> const font = text_obj->GetFont();
  for (size_t k = 0; k < actual_text.GetLength(); ++k) {
    wchar_t wc = actual_text[k];
    if (wc <= 0x80 && !isprint(wc)) {
      wc = 0x20;
    }
    if (wc >= 0xFFFD) {
      continue;
    }

    CFX_FloatRect char_box(rect);
    char_box.Translate(k * step, 0);
    temp_text_buf_.AppendChar(wc);
    temp_char_list_.push_back(
        CharInfo(CharType::kActualText, CPDF_Font::kInvalidCharCode, wc,
                 text_obj->GetPos(), char_box, matrix, text_obj));
  }
  return is_rtl;
}

void CPDF_TextPage::FindPreviousTextObject() {
  const CharInfo* prev_char_info = GetPrevCharInfo();
  if (!prev_char_info) {
    return;
  }

  if (prev_char_info->text_object()) {
    prev_text_obj_ = prev_char_info->text_object();
  }
}

void CPDF_TextPage::ReverseTempTextBufs(size_t char_list_index,
                                        size_t buf_index) {
  DCHECK(!temp_char_list_.empty());
  if (char_list_index < temp_char_list_.size()) {
    pdfium::span<CharInfo> reverse_span =
        pdfium::span(temp_char_list_).subspan(char_list_index);
    std::ranges::reverse(reverse_span);
  }
  pdfium::span<wchar_t> temp_span = temp_text_buf_.GetWideSpan();
  DCHECK(!temp_span.empty());
  if (buf_index < temp_span.size()) {
    pdfium::span<wchar_t> reverse_span = temp_span.subspan(buf_index);
    std::ranges::reverse(reverse_span);
  }
}

void CPDF_TextPage::ProcessTransformedTextObjects() {
  for (const TransformedTextObject& obj : text_objects_) {
    CPDF_TextObject* const text_obj = obj.text_obj_;
    if (fabs(text_obj->GetRect().Width()) < kSizeEpsilon) {
      continue;
    }

    const CFX_Matrix& form_matrix = obj.form_matrix_;
    const MarkedContentState ePreMKC = PreMarkedContent(text_obj);
    if (ePreMKC == MarkedContentState::kDone) {
      prev_text_obj_ = text_obj;
      prev_matrix_ = form_matrix;
      continue;
    }

    if (prev_text_obj_) {
      GenerateCharacter type = ProcessInsertObject(text_obj, form_matrix);
      if (type == GenerateCharacter::kLineBreak) {
        curline_rect_ = text_obj->GetRect();
      } else {
        curline_rect_.Union(text_obj->GetRect());
      }

      if (!ProcessGenerateCharacter(type, text_obj, form_matrix)) {
        continue;
      }
    } else {
      curline_rect_ = text_obj->GetRect();
    }

    // Save these before ProcessMarkedContent() or ProcessTextObjectItems()
    // modifies the containers.
    const size_t orig_char_list_index = temp_char_list_.size();
    const size_t orig_buf_index = temp_text_buf_.GetLength();
    if (ePreMKC == MarkedContentState::kDelay) {
      if (ProcessMarkedContent(obj)) {
        // TODO(thestig): It would be nicer if there was a way to avoid doing
        // this second reversal.
        ReverseTempTextBufs(orig_char_list_index, orig_buf_index);
      }
      prev_text_obj_ = text_obj;
      prev_matrix_ = form_matrix;
      continue;
    }

    prev_text_obj_ = text_obj;
    prev_matrix_ = form_matrix;

    const CFX_Matrix matrix = text_obj->GetTextMatrix() * form_matrix;
    if (ProcessTextObjectItems(text_obj, form_matrix, matrix)) {
      ReverseTempTextBufs(orig_char_list_index, orig_buf_index);
    }
  }
}

CPDF_TextPage::TextOrientation CPDF_TextPage::GetTextObjectWritingMode(
    const CPDF_TextObject* text_obj) const {
  size_t char_count = text_obj->CharCount();
  if (char_count <= 1) {
    return textline_dir_;
  }

  CPDF_TextObject::Item first = text_obj->GetCharInfo(0);
  CPDF_TextObject::Item last = text_obj->GetCharInfo(char_count - 1);
  CFX_Matrix text_matrix = text_obj->GetTextMatrix();
  first.origin_ = text_matrix.Transform(first.origin_);
  last.origin_ = text_matrix.Transform(last.origin_);

  static constexpr float kEpsilon = 0.0001f;
  float dX = fabs(last.origin_.x - first.origin_.x);
  float dY = fabs(last.origin_.y - first.origin_.y);
  if (dX <= kEpsilon && dY <= kEpsilon) {
    return TextOrientation::kUnknown;
  }

  static constexpr float kThreshold = 0.0872f;
  CFX_VectorF v(dX, dY);
  v.Normalize();
  bool is_under_threshold = v.x <= kThreshold;
  if (v.y <= kThreshold) {
    return is_under_threshold ? textline_dir_ : TextOrientation::kHorizontal;
  }
  return is_under_threshold ? TextOrientation::kVertical : textline_dir_;
}

bool CPDF_TextPage::IsHyphen(wchar_t current_char) const {
  WideStringView current_text = temp_text_buf_.AsStringView();
  if (current_text.IsEmpty()) {
    current_text = text_buf_.AsStringView();
  }

  if (current_text.IsEmpty()) {
    return false;
  }

  auto iter = current_text.rbegin();
  for (; (iter + 1) != current_text.rend() && *iter == 0x20; ++iter) {
    // Do nothing
  }

  if (!IsHyphenCode(*iter)) {
    return false;
  }

  if ((iter + 1) != current_text.rend()) {
    iter++;
    if (FXSYS_iswalpha(*iter) && FXSYS_iswalnum(current_char)) {
      return true;
    }
  }

  const CharInfo* prev_char_info = GetPrevCharInfo();
  return prev_char_info &&
         (prev_char_info->char_type() == CharType::kPiece ||
          prev_char_info->char_type() == CharType::kActualText) &&
         IsHyphenCode(prev_char_info->unicode());
}

const CPDF_TextPage::CharInfo* CPDF_TextPage::GetPrevCharInfo() const {
  if (!temp_char_list_.empty()) {
    return &temp_char_list_.back();
  }
  return !char_list_.empty() ? &char_list_.back() : nullptr;
}

CPDF_TextPage::GenerateCharacter CPDF_TextPage::ProcessInsertObject(
    const CPDF_TextObject* text_obj,
    const CFX_Matrix& form_matrix) {
  FindPreviousTextObject();
  TextOrientation writing_mode = GetTextObjectWritingMode(text_obj);
  if (writing_mode == TextOrientation::kUnknown) {
    writing_mode = GetTextObjectWritingMode(prev_text_obj_);
  }

  size_t nItem = prev_text_obj_->CountItems();
  if (nItem == 0) {
    return GenerateCharacter::kNone;
  }

  CPDF_TextObject::Item prev_item = prev_text_obj_->GetItemInfo(nItem - 1);
  CPDF_TextObject::Item item = text_obj->GetItemInfo(0);
  const CFX_FloatRect& this_rect = text_obj->GetRect();
  const CFX_FloatRect& prev_rect = prev_text_obj_->GetRect();
  WideString unicode =
      text_obj->GetFont()->UnicodeFromCharCode(item.char_code_);
  if (unicode.IsEmpty()) {
    unicode += static_cast<wchar_t>(item.char_code_);
  }

  wchar_t current_char = unicode.Front();
  if (writing_mode == TextOrientation::kHorizontal) {
    if (EndHorizontalLine(this_rect, prev_rect)) {
      return IsHyphen(current_char) ? GenerateCharacter::kHyphen
                                    : GenerateCharacter::kLineBreak;
    }
  } else if (writing_mode == TextOrientation::kVertical) {
    if (EndVerticalLine(this_rect, prev_rect, curline_rect_,
                        text_obj->GetFontSize(),
                        prev_text_obj_->GetFontSize())) {
      return IsHyphen(current_char) ? GenerateCharacter::kHyphen
                                    : GenerateCharacter::kLineBreak;
    }
  }

  float last_pos = prev_item.origin_.x;
  int nLastWidth =
      GetCharWidth(prev_item.char_code_, prev_text_obj_->GetFont().Get());
  float last_width = nLastWidth * prev_text_obj_->GetFontSize() / 1000;
  last_width = fabs(last_width);
  int nThisWidth = GetCharWidth(item.char_code_, text_obj->GetFont().Get());
  float this_width = fabs(nThisWidth * text_obj->GetFontSize() / 1000);
  float threshold = std::max(last_width, this_width) / 4;

  CFX_Matrix prev_matrix = prev_text_obj_->GetTextMatrix() * prev_matrix_;
  CFX_Matrix prev_matrix_inverse = prev_matrix.GetInverse();

  CFX_PointF pos =
      prev_matrix_inverse.Transform(form_matrix.Transform(text_obj->GetPos()));
  if (last_width < this_width) {
    threshold = prev_matrix_inverse.TransformDistance(threshold);
  }

  bool is_newline = false;
  if (writing_mode == TextOrientation::kHorizontal) {
    CFX_FloatRect rect = prev_text_obj_->GetRect();
    float rect_height = rect.Height();
    rect.Normalize();
    if ((rect.IsEmpty() && rect_height > 5) ||
        ((pos.y > threshold * 2 || pos.y < threshold * -3) &&
         (fabs(pos.y) >= 1 || fabs(pos.y) > fabs(pos.x)))) {
      is_newline = true;
      if (nItem > 1) {
        CPDF_TextObject::Item first_item = prev_text_obj_->GetItemInfo(0);
        CFX_Matrix m = prev_text_obj_->GetTextMatrix();
        if (prev_item.origin_.x > first_item.origin_.x &&
            display_matrix_.a > 0.9 && display_matrix_.b < 0.1 &&
            display_matrix_.c < 0.1 && display_matrix_.d < -0.9 && m.b < 0.1 &&
            m.c < 0.1) {
          CFX_FloatRect re(0, prev_text_obj_->GetRect().bottom, 1000,
                           prev_text_obj_->GetRect().top);
          if (re.Contains(text_obj->GetPos())) {
            is_newline = false;
          } else {
            if (CFX_FloatRect(0, text_obj->GetRect().bottom, 1000,
                              text_obj->GetRect().top)
                    .Contains(prev_text_obj_->GetPos())) {
              is_newline = false;
            }
          }
        }
      }
    }
  }
  if (is_newline) {
    return IsHyphen(current_char) ? GenerateCharacter::kHyphen
                                  : GenerateCharacter::kLineBreak;
  }

  if (text_obj->CharCount() == 1 && IsHyphenCode(current_char) &&
      IsHyphen(current_char)) {
    return GenerateCharacter::kHyphen;
  }

  if (current_char == L' ') {
    return GenerateCharacter::kNone;
  }

  WideString prev_str =
      prev_text_obj_->GetFont()->UnicodeFromCharCode(prev_item.char_code_);
  if (prev_str.Back() == L' ') {
    return GenerateCharacter::kNone;
  }

  CFX_Matrix matrix = text_obj->GetTextMatrix() * form_matrix;
  float threshold2 = std::max(nLastWidth, nThisWidth);
  threshold2 = NormalizeThreshold(threshold2, 400, 700, 800);
  if (nLastWidth >= nThisWidth) {
    threshold2 *= fabs(prev_text_obj_->GetFontSize());
  } else {
    threshold2 *= fabs(text_obj->GetFontSize());
    threshold2 = matrix.TransformDistance(threshold2);
    threshold2 = prev_matrix_inverse.TransformDistance(threshold2);
  }
  threshold2 /= 1000;
  if ((threshold2 < 1.4881 && threshold2 > 1.4879) ||
      (threshold2 < 1.39001 && threshold2 > 1.38999)) {
    threshold2 *= 1.5;
  }
  return GenerateSpace(pos, last_pos, this_width, last_width, threshold2)
             ? GenerateCharacter::kSpace
             : GenerateCharacter::kNone;
}

bool CPDF_TextPage::ProcessGenerateCharacter(GenerateCharacter type,
                                             const CPDF_TextObject* text_object,
                                             const CFX_Matrix& form_matrix) {
  switch (type) {
    case GenerateCharacter::kNone:
      return true;
    case GenerateCharacter::kSpace: {
      AppendGeneratedCharacter(L' ', form_matrix, /*use_temp_buffer=*/true);
      return true;
    }
    case GenerateCharacter::kLineBreak:
      CloseTempLine();
      if (text_buf_.GetSize()) {
        AppendGeneratedCharacter(L'\r', form_matrix, /*use_temp_buffer=*/false);
        AppendGeneratedCharacter(L'\n', form_matrix, /*use_temp_buffer=*/false);
      }
      return true;
    case GenerateCharacter::kHyphen:
      if (text_object->CharCount() == 1) {
        CPDF_TextObject::Item item = text_object->GetCharInfo(0);
        WideString unicode =
            text_object->GetFont()->UnicodeFromCharCode(item.char_code_);
        if (unicode.IsEmpty()) {
          unicode += static_cast<wchar_t>(item.char_code_);
        }
        wchar_t current_char = unicode.Front();
        if (IsHyphenCode(current_char)) {
          return false;
        }
      }
      while (temp_text_buf_.GetSize() > 0 &&
             temp_text_buf_.AsStringView().Back() == 0x20) {
        temp_text_buf_.Delete(temp_text_buf_.GetLength() - 1, 1);
        temp_char_list_.pop_back();
      }
      CharInfo& charinfo = temp_char_list_.back();
      temp_text_buf_.Delete(temp_text_buf_.GetLength() - 1, 1);
      charinfo.set_char_type(CharType::kHyphen);
      charinfo.set_unicode(0x2);
      temp_text_buf_.AppendChar(0xfffe);
      return true;
  }
  NOTREACHED();
}

bool CPDF_TextPage::ProcessTextObjectItems(CPDF_TextObject* text_object,
                                           const CFX_Matrix& form_matrix,
                                           const CFX_Matrix& matrix) {
  const float base_space = CalculateBaseSpace(text_object, matrix) +
                           CalculateBaseSpaceAdjustment(text_object, matrix);
  RetainPtr<CPDF_Font> const font = text_object->GetFont();

  float spacing = 0;
  const size_t nItems = text_object->CountItems();
  const std::vector<float>& kernings = text_object->GetCharKernings();
  for (size_t i = 0; i < nItems; ++i) {
    CPDF_TextObject::Item item = text_object->GetItemInfo(i);
    if (i > 0 && kernings[i - 1] != 0) {
      WideStringView str = temp_text_buf_.AsStringView();
      if (str.IsEmpty()) {
        str = text_buf_.AsStringView();
      }
      if (!str.IsEmpty() && str.Back() != L' ') {
        float fontsize_h = text_object->text_state().GetFontSizeH();
        spacing = -fontsize_h * kernings[i - 1] / 1000;
      }
    }

    spacing -= base_space;

    if (spacing && i > 0) {
      const float threshold = CalculateSpaceThreshold(
          font, text_object->text_state().GetFontSizeH(), item.char_code_);
      if (threshold && spacing && spacing >= threshold) {
        temp_text_buf_.AppendChar(L' ');
        CFX_PointF origin = matrix.Transform(item.origin_);
        temp_char_list_.push_back(CharInfo(
            CharType::kGenerated, CPDF_Font::kInvalidCharCode, L' ', origin,
            CFX_FloatRect(origin.x, origin.y, origin.x, origin.y), form_matrix,
            text_object));
      }
    }

    spacing = 0;
    WideString unicode = font->UnicodeFromCharCode(item.char_code_);
    CharType char_type = CharType::kNormal;
    if (unicode.IsEmpty() && item.char_code_) {
      unicode += static_cast<wchar_t>(item.char_code_);
      char_type = CharType::kNotUnicode;
    }

    const FX_RECT rect = font->GetCharBBox(item.char_code_);
    const float font_size = text_object->GetFontSize() / 1000;
    CFX_FloatRect char_box(rect.left * font_size + item.origin_.x,
                           rect.bottom * font_size + item.origin_.y,
                           rect.right * font_size + item.origin_.x,
                           rect.top * font_size + item.origin_.y);
    if (fabsf(char_box.top - char_box.bottom) < kSizeEpsilon) {
      char_box.top = char_box.bottom + font_size;
    }
    if (fabsf(char_box.right - char_box.left) < kSizeEpsilon) {
      char_box.right =
          char_box.left + text_object->GetCharWidth(item.char_code_);
    }
    char_box = matrix.TransformRect(char_box);

    CharInfo charinfo(char_type, item.char_code_, 0,
                      matrix.Transform(item.origin_), char_box, matrix,
                      text_object);
    if (unicode.IsEmpty()) {
      temp_char_list_.push_back(charinfo);
      temp_text_buf_.AppendChar(0xfffe);
      continue;
    }

    bool add_unicode = true;
    const int count = std::min(fxcrt::CollectionSize<int>(temp_char_list_), 7);
    static constexpr float kTextCharRatioGapDelta = 0.07f;
    float threshold = charinfo.matrix().TransformXDistance(
        kTextCharRatioGapDelta * text_object->GetFontSize());
    for (int n = fxcrt::CollectionSize<int>(temp_char_list_);
         n > fxcrt::CollectionSize<int>(temp_char_list_) - count; --n) {
      const CharInfo& candidate_charinfo = temp_char_list_[n - 1];
      if (candidate_charinfo.char_code() != charinfo.char_code()) {
        continue;
      }
      if (!candidate_charinfo.text_object() ||
          candidate_charinfo.text_object()->GetFont() !=
              charinfo.text_object()->GetFont()) {
        continue;
      }
      CFX_PointF diff = candidate_charinfo.origin() - charinfo.origin();
      if (fabs(diff.x) < threshold && fabs(diff.y) < threshold) {
        add_unicode = false;
        break;
      }
    }
    if (add_unicode) {
      for (wchar_t c : unicode) {
        charinfo.set_unicode(c);
        temp_text_buf_.AppendChar(c ? c : 0xfffe);
        temp_char_list_.push_back(charinfo);
      }
    } else if (i == 0) {
      WideStringView str = temp_text_buf_.AsStringView();
      if (!str.IsEmpty() && str.Back() == L' ') {
        temp_text_buf_.Delete(temp_text_buf_.GetLength() - 1, 1);
        temp_char_list_.pop_back();
      }
    }
  }
  // Tell the caller to reverse the temporary text buffers if RTL text has a
  // mirrored matrix (determinant < 0).
  const bool is_rtl = IsRightToLeft(*text_object);
  return is_rtl && (matrix.a * matrix.d - matrix.b * matrix.c) < 0;
}

bool CPDF_TextPage::IsSameTextObject(CPDF_TextObject* text_obj1,
                                     CPDF_TextObject* text_obj2) const {
  if (!text_obj1 || !text_obj2) {
    return false;
  }

  CFX_FloatRect prev_obj_rect = text_obj2->GetRect();
  const CFX_FloatRect& current_obj_rect = text_obj1->GetRect();
  if (prev_obj_rect.IsEmpty() && current_obj_rect.IsEmpty()) {
    float xdiff = fabs(prev_obj_rect.left - current_obj_rect.left);
    size_t count = char_list_.size();
    if (count >= 2) {
      float dbSpace = char_list_[count - 2].char_box().Width();
      if (xdiff > dbSpace) {
        return false;
      }
    }
  }
  if (!prev_obj_rect.IsEmpty() || !current_obj_rect.IsEmpty()) {
    prev_obj_rect.Intersect(current_obj_rect);
    if (prev_obj_rect.IsEmpty()) {
      return false;
    }
    if (fabs(prev_obj_rect.Width() - current_obj_rect.Width()) >
        current_obj_rect.Width() / 2) {
      return false;
    }
    if (text_obj2->GetFontSize() != text_obj1->GetFontSize()) {
      return false;
    }
  }

  size_t nPreCount = text_obj2->CountItems();
  if (nPreCount != text_obj1->CountItems()) {
    return false;
  }

  // If both objects have no items, consider them same.
  if (nPreCount == 0) {
    return true;
  }

  CPDF_TextObject::Item itemPer;
  CPDF_TextObject::Item itemCur;
  for (size_t i = 0; i < nPreCount; ++i) {
    itemPer = text_obj2->GetItemInfo(i);
    itemCur = text_obj1->GetItemInfo(i);
    if (itemCur.char_code_ != itemPer.char_code_) {
      return false;
    }
  }

  CFX_PointF diff = text_obj1->GetPos() - text_obj2->GetPos();
  float font_size = text_obj2->GetFontSize();
  float char_size =
      GetCharWidth(itemPer.char_code_, text_obj2->GetFont().Get());
  float max_pre_size = std::max(
      std::max(prev_obj_rect.Height(), prev_obj_rect.Width()), font_size);
  return fabs(diff.x) <= 0.9 * char_size * font_size / 1000 &&
         fabs(diff.y) <= max_pre_size / 8;
}

bool CPDF_TextPage::IsSameAsPreTextObject(
    CPDF_TextObject* text_obj,
    const CPDF_PageObjectHolder* obj_list,
    CPDF_PageObjectHolder::const_iterator iter) const {
  int i = 0;
  while (i < 5 && iter != obj_list->begin()) {
    --iter;
    CPDF_PageObject* other_obj = iter->get();
    if (other_obj == text_obj || !other_obj->IsText()) {
      continue;
    }
    if (IsSameTextObject(other_obj->AsText(), text_obj)) {
      return true;
    }
    ++i;
  }
  return false;
}

std::optional<CPDF_TextPage::CharInfo> CPDF_TextPage::GenerateCharInfo(
    wchar_t unicode,
    const CFX_Matrix& form_matrix) {
  const CharInfo* prev_char_info = GetPrevCharInfo();
  if (!prev_char_info) {
    return std::nullopt;
  }

  int pre_width = 0;
  if (prev_char_info->text_object() &&
      prev_char_info->char_code() != CPDF_Font::kInvalidCharCode) {
    pre_width = GetCharWidth(prev_char_info->char_code(),
                             prev_char_info->text_object()->GetFont().Get());
  }

  float font_size = prev_char_info->text_object()
                        ? prev_char_info->text_object()->GetFontSize()
                        : prev_char_info->char_box().Height();
  if (!font_size) {
    font_size = kDefaultFontSize;
  }

  CFX_PointF origin(prev_char_info->origin().x + pre_width * font_size / 1000,
                    prev_char_info->origin().y);
  return CharInfo(CharType::kGenerated, CPDF_Font::kInvalidCharCode, unicode,
                  origin, CFX_FloatRect(origin.x, origin.y, origin.x, origin.y),
                  form_matrix, /*text_object=*/nullptr);
}
