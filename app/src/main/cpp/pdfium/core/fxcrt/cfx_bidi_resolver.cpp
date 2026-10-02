// Copyright 2026 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "core/fxcrt/cfx_bidi_resolver.h"

#include <limits>
#include <utility>

#include "core/fxcrt/check_op.h"
#include "core/fxcrt/fx_safe_types.h"
#include "core/fxcrt/ptr_util.h"

#if defined(USE_SYSTEM_ICUUC)
#include <unicode/ubidi.h>
#else
#include "third_party/icu/source/common/unicode/ubidi.h"
#endif

void CFX_BidiResolver::UBiDiDeleter::operator()(UBiDi* bidi) const {
  ubidi_close(bidi);
}

// static
std::unique_ptr<CFX_BidiResolver> CFX_BidiResolver::Create(
    std::u16string utf16_text,
    ParagraphDirection direction) {
  // Private ctor
  auto resolver = pdfium::WrapUnique(
      new CFX_BidiResolver(std::move(utf16_text), direction));
  if (!resolver->paragraph_bidi_) {
    return nullptr;
  }
  return resolver;
}

CFX_BidiResolver::CFX_BidiResolver(std::u16string utf16_text,
                                   ParagraphDirection direction)
    : utf16_text_(std::move(utf16_text)) {
  if (utf16_text_.empty()) {
    return;
  }

  // A workaround for integer overflow in ICU. See crbug.com/504629701.
  // Can remove this after fixing the ICU issue, and rolling out the ICU
  // update.
  constexpr size_t kIcuRunSize = sizeof(int32_t) * 3;
  CHECK_LE(utf16_text_.size(),
           std::numeric_limits<int32_t>::max() / kIcuRunSize);

  UErrorCode status = U_ZERO_ERROR;
  ScopedUBiDi bidi(ubidi_openSized(static_cast<int32_t>(utf16_text_.size()),
                                   /*maxRunCount=*/0, &status));
  if (U_FAILURE(status)) {
    return;
  }

  UBiDiLevel para_level = UBIDI_DEFAULT_LTR;
  if (direction == ParagraphDirection::kLeftToRight) {
    para_level = UBIDI_LTR;
  } else if (direction == ParagraphDirection::kRightToLeft) {
    para_level = UBIDI_RTL;
  }

  ubidi_setPara(bidi.get(), utf16_text_.data(),
                static_cast<int32_t>(utf16_text_.size()), para_level,
                /*embeddingLevels=*/nullptr, &status);
  if (U_FAILURE(status)) {
    return;
  }

  paragraph_bidi_ = std::move(bidi);
}

CFX_BidiResolver::~CFX_BidiResolver() = default;

std::vector<CFX_BidiResolver::ResolvedRun>
CFX_BidiResolver::GetVisualRunsForLine(int line_start, int line_length) const {
  std::vector<ResolvedRun> runs;
  if (line_start < 0 || line_length <= 0) {
    return runs;
  }

  UErrorCode status = U_ZERO_ERROR;
  ScopedUBiDi line_bidi(
      ubidi_openSized(line_length, /*maxRunCount=*/0, &status));
  if (U_FAILURE(status)) {
    return runs;
  }

  FX_SAFE_INT32 safe_line_end = pdfium::CheckAdd(line_start, line_length);
  if (!safe_line_end.IsValid() ||
      safe_line_end.ValueOrDie() > utf16_text_.size()) {
    return runs;
  }

  ubidi_setLine(paragraph_bidi_.get(), line_start, safe_line_end.ValueOrDie(),
                line_bidi.get(), &status);
  if (U_FAILURE(status)) {
    return runs;
  }

  int32_t run_count = ubidi_countRuns(line_bidi.get(), &status);
  if (U_FAILURE(status) || run_count <= 0) {
    return runs;
  }

  runs.reserve(run_count);
  for (int32_t i = 0; i < run_count; ++i) {
    int32_t run_start = 0;
    int32_t run_length = 0;
    UBiDiDirection dir =
        ubidi_getVisualRun(line_bidi.get(), i, &run_start, &run_length);

    // `ubidi_getVisualRun()` guarantees `run_start` is strictly bounded by
    // `line_length`. Proved `line_start + line_length` doesn't overflow
    // above with `safe_line_end`. Therefore, `run_start + line_start`
    // cannot overflow.
    runs.push_back({run_start + line_start, run_length, dir == UBIDI_RTL});
  }

  return runs;
}
