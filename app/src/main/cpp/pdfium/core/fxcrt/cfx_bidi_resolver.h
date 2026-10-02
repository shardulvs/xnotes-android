// Copyright 2026 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CORE_FXCRT_CFX_BIDI_RESOLVER_H_
#define CORE_FXCRT_CFX_BIDI_RESOLVER_H_

#include <memory>
#include <string>
#include <vector>

struct UBiDi;

// Wraps ICU's UBiDi engine to extract visual text runs from a logical
// paragraph.
class CFX_BidiResolver final {
 public:
  enum class ParagraphDirection { kAuto, kLeftToRight, kRightToLeft };

  struct ResolvedRun {
    int start;
    int length;
    bool is_rtl;
  };

  // Returns a new instance on success, or nullptr if ICU initialization fails.
  static std::unique_ptr<CFX_BidiResolver> Create(std::u16string utf16_text,
                                                  ParagraphDirection direction);

  ~CFX_BidiResolver();

  // Extracts visual runs for a subset of the paragraph. Returns an empty
  // vector if the line limits are invalid.
  std::vector<ResolvedRun> GetVisualRunsForLine(int line_start,
                                                int line_length) const;

 private:
  struct UBiDiDeleter {
    void operator()(UBiDi* bidi) const;
  };
  using ScopedUBiDi = std::unique_ptr<UBiDi, UBiDiDeleter>;

  CFX_BidiResolver(std::u16string utf16_text, ParagraphDirection direction);

  const std::u16string utf16_text_;
  ScopedUBiDi paragraph_bidi_;
};

#endif  // CORE_FXCRT_CFX_BIDI_RESOLVER_H_
