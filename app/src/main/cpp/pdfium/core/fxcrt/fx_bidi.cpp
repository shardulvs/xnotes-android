// Copyright 2014 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxcrt/fx_bidi.h"

#include <algorithm>

#include "core/fxcrt/check_op.h"
#include "core/fxcrt/fx_unicode.h"

CFX_BidiChar::CFX_BidiChar()
    : current_segment_({0, 0, Direction::kNeutral}),
      last_segment_({0, 0, Direction::kNeutral}) {}

bool CFX_BidiChar::AppendChar(wchar_t wch) {
  Direction direction;
  switch (pdfium::unicode::GetBidiClass(wch)) {
    case BidiClass::kL:
      direction = Direction::kLeft;
      break;
    case BidiClass::kAN:
    case BidiClass::kEN:
    case BidiClass::kNSM:
    case BidiClass::kCS:
    case BidiClass::kES:
    case BidiClass::kET:
    case BidiClass::kBN:
      direction = Direction::kLeftWeak;
      break;
    case BidiClass::kR:
    case BidiClass::kAL:
      direction = Direction::kRight;
      break;
    default:
      direction = Direction::kNeutral;
      break;
  }

  bool bChangeDirection = (direction != current_segment_.direction);
  if (bChangeDirection) {
    StartNewSegment(direction);
  }

  current_segment_.count++;
  return bChangeDirection;
}

bool CFX_BidiChar::EndChar() {
  StartNewSegment(Direction::kNeutral);
  return last_segment_.count > 0;
}

void CFX_BidiChar::StartNewSegment(CFX_BidiChar::Direction direction) {
  last_segment_ = current_segment_;
  current_segment_.start += current_segment_.count;
  current_segment_.count = 0;
  current_segment_.direction = direction;
}

CFX_BidiString::CFX_BidiString(const WideString& str, bool auto_order)
    : str_(str) {
  CFX_BidiChar bidi;
  for (wchar_t c : str_) {
    if (bidi.AppendChar(c)) {
      order_.push_back(bidi.GetSegmentInfo());
    }
  }
  if (bidi.EndChar()) {
    order_.push_back(bidi.GetSegmentInfo());
  }

  if (!auto_order) {
    return;
  }

  size_t nR2L = std::count_if(
      order_.begin(), order_.end(), [](const CFX_BidiChar::Segment& seg) {
        return seg.direction == CFX_BidiChar::Direction::kRight;
      });

  size_t nL2R = std::count_if(
      order_.begin(), order_.end(), [](const CFX_BidiChar::Segment& seg) {
        return seg.direction == CFX_BidiChar::Direction::kLeft;
      });

  if (nR2L > nL2R) {
    SetOverallDirectionRight();
  }
}

CFX_BidiString::~CFX_BidiString() = default;

CFX_BidiChar::Direction CFX_BidiString::OverallDirection() const {
  DCHECK_NE(overall_direction_, CFX_BidiChar::Direction::kNeutral);
  DCHECK_NE(overall_direction_, CFX_BidiChar::Direction::kLeftWeak);
  return overall_direction_;
}

void CFX_BidiString::SetOverallDirectionRight() {
  if (overall_direction_ != CFX_BidiChar::Direction::kRight) {
    std::reverse(order_.begin(), order_.end());
    overall_direction_ = CFX_BidiChar::Direction::kRight;
  }
}
