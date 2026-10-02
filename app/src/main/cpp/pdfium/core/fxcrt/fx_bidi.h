// Copyright 2014 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef CORE_FXCRT_FX_BIDI_H_
#define CORE_FXCRT_FX_BIDI_H_

#include <stddef.h>

#include <vector>

#include "core/fxcrt/widestring.h"

// Processes characters and group them into segments based on text direction.
class CFX_BidiChar {
 public:
  enum class Direction { kNeutral, kLeft, kRight, kLeftWeak };
  struct Segment {
    size_t start;         // Start position.
    size_t count;         // Character count.
    Direction direction;  // Segment direction.
  };

  CFX_BidiChar();

  // Append a character and classify it as left, left-weak, right, or neutral.
  // Returns true if the character has a different direction than the
  // existing direction to indicate there is a segment to process.
  bool AppendChar(wchar_t wch);

  // Call this after the last character has been appended. AppendChar()
  // must not be called after this.
  // Returns true if there is still a segment to process.
  bool EndChar();

  // Call after a change in direction is indicated by the above to get
  // information about the segment to process.
  const Segment& GetSegmentInfo() const { return last_segment_; }

 private:
  void StartNewSegment(CFX_BidiChar::Direction direction);

  Segment current_segment_;
  Segment last_segment_;
};

class CFX_BidiString {
 public:
  using const_iterator = std::vector<CFX_BidiChar::Segment>::const_iterator;

  // Constructs a BidiString with configurable automatic Bidi ordering.
  // When `auto_order` is true, the overall reading direction of the line
  // and its internal segment order are automatically flipped to Right-to-Left
  // if the number of R2L segments strictly exceeds the number of L2R segments.
  // When `auto_order` is false, this heuristic is skipped, and segments remain
  // in their logical input order unless SetOverallDirectionRight() is called.
  CFX_BidiString(const WideString& str, bool auto_order);
  ~CFX_BidiString();

  // Overall direction is always LEFT or RIGHT, never NEUTRAL.
  CFX_BidiChar::Direction OverallDirection() const;

  // Force the overall direction to be R2L regardless of what was detected.
  void SetOverallDirectionRight();

  const_iterator begin() const { return order_.begin(); }
  const_iterator end() const { return order_.end(); }

 private:
  const WideString& str_;
  std::vector<CFX_BidiChar::Segment> order_;
  CFX_BidiChar::Direction overall_direction_ = CFX_BidiChar::Direction::kLeft;
};

#endif  // CORE_FXCRT_FX_BIDI_H_
