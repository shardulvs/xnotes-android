// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef CORE_FPDFDOC_CPVT_SECTION_H_
#define CORE_FPDFDOC_CPVT_SECTION_H_

#include <stdint.h>

#include <memory>
#include <vector>

#include "core/fpdfdoc/cpvt_floatrect.h"
#include "core/fpdfdoc/cpvt_lineinfo.h"
#include "core/fpdfdoc/cpvt_wordinfo.h"
#include "core/fpdfdoc/cpvt_wordrange.h"
#include "core/fxcrt/fx_coordinates.h"
#include "core/fxcrt/span.h"
#include "core/fxcrt/unowned_ptr.h"

class CPVT_VariableText;
struct CPVT_LineInfo;
struct CPVT_WordPlace;

class CPVT_Section final {
 public:
  class Line {
   public:
    explicit Line(const CPVT_LineInfo& lineinfo);
    ~Line();

    CPVT_WordPlace GetBeginWordPlace() const;
    CPVT_WordPlace GetEndWordPlace() const;
    CPVT_WordPlace GetPrevWordPlace(const CPVT_WordPlace& place) const;
    CPVT_WordPlace GetNextWordPlace(const CPVT_WordPlace& place) const;
    CPVT_WordPlace line_place_;
    CPVT_LineInfo line_info_;
  };

  explicit CPVT_Section(CPVT_VariableText* pVT);
  ~CPVT_Section();

  void ResetLinePlace();
  CPVT_WordPlace AddWord(const CPVT_WordPlace& place,
                         const CPVT_WordInfo& wordinfo);
  CPVT_WordPlace AddLine(const CPVT_LineInfo& lineinfo);
  void ClearWords(const CPVT_WordRange& PlaceRange);
  void ClearWord(const CPVT_WordPlace& place);
  CPVT_FloatRect Rearrange();
  CFX_SizeF GetSectionSize(float fFontSize);
  CPVT_WordPlace GetBeginWordPlace() const;
  CPVT_WordPlace GetEndWordPlace() const;
  CPVT_WordPlace GetPrevWordPlace(const CPVT_WordPlace& place) const;
  CPVT_WordPlace GetNextWordPlace(const CPVT_WordPlace& place) const;
  void UpdateWordPlace(CPVT_WordPlace& place) const;
  CPVT_WordPlace SearchWordPlace(const CFX_PointF& point) const;
  CPVT_WordPlace SearchWordPlace(float fx,
                                 const CPVT_WordPlace& lineplace) const;

  void SetPlace(const CPVT_WordPlace& place) { sec_place_ = place; }
  void SetPlaceIndex(int32_t index) { sec_place_.nSecIndex = index; }
  const CPVT_FloatRect& GetRect() const { return rect_; }
  void SetRect(const CPVT_FloatRect& rect) { rect_ = rect; }

  int32_t GetLineArraySize() const;
  const Line* GetLineFromArray(int32_t index) const;
  int32_t GetWordArraySize() const;
  const CPVT_WordInfo* GetWordFromArray(int32_t index) const;
  void EraseWordsFrom(int32_t index);

 private:
  using WordIterator =
      std::vector<std::unique_ptr<CPVT_WordInfo>>::const_iterator;

  struct WordRangeIteratorPair {
    WordIterator begin;
    WordIterator end;
  };

  CPVT_FloatRect RearrangeCharArray() const;
  CPVT_FloatRect RearrangeTypeset();
  CPVT_FloatRect SplitLines(bool bTypeset, float fFontSize);
  // Iterates through all lines in the section, laying out words according to
  // the text alignment and bidi rules (reordering words if necessary), and
  // assigns final (X, Y) coordinates to each word and line. Returns the
  // bounding box encompassing all the lines.
  CPVT_FloatRect OutputLines(const CPVT_FloatRect& rect) const;

  // Clears [0, word_index] from `word_array_`.
  void ClearLeftWords(int32_t word_index);
  // Clears (word_index, word_array_.size()) from `word_array_`.
  void ClearRightWords(int32_t word_index);
  // Clears (begin_index, end_index] from `word_array_`.
  void ClearMidWords(int32_t begin_index, int32_t end_index);

  // Returns iterators for the range [begin_index, end_index) in `word_array_`.
  // Both indices are clamped to [0, word_array_.size()].
  // If `begin_index` > `end_index`, returns an empty range.
  WordRangeIteratorPair GetWordRangeIteratorPair(int32_t begin_index,
                                                 int32_t end_index) const;

  // Like GetWordRangeIteratorPair(), but returns a span into `word_array_`.
  pdfium::span<const std::unique_ptr<CPVT_WordInfo>> GetWordRangeSpan(
      int32_t start,
      int32_t length) const;

  CPVT_WordPlace SearchWordPlaceImpl(float fx,
                                     const CPVT_WordRange& range) const;

  CPVT_WordPlace sec_place_;
  CPVT_FloatRect rect_;
  std::vector<std::unique_ptr<Line>> line_array_;
  std::vector<std::unique_ptr<CPVT_WordInfo>> word_array_;
  UnownedPtr<CPVT_VariableText> const vt_;
};

#endif  // CORE_FPDFDOC_CPVT_SECTION_H_
