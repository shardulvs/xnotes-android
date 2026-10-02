// Copyright 2026 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CORE_FPDFAPI_EDIT_CPDF_FONT_UTIL_H_
#define CORE_FPDFAPI_EDIT_CPDF_FONT_UTIL_H_

#include <stdint.h>

#include <map>

#include "core/fxcrt/retain_ptr.h"

class CPDF_Array;
class CPDF_Stream;

// Creates a widths array for composite fonts from a mapping of CIDs to widths.
// It is the caller's responsibility to add it to a `CPDF_Document` if
// necessary.
RetainPtr<CPDF_Array> CreateWidthsArray(
    const std::map<uint32_t, uint32_t>& widths);

// Loads the charcode to unicode mapping into a stream. It is the caller's
// responsibility to add it to a `CPDF_Document` if necessary.
RetainPtr<CPDF_Stream> LoadUnicode(
    const std::multimap<uint32_t, uint32_t>& to_unicode);

#endif  // CORE_FPDFAPI_EDIT_CPDF_FONT_UTIL_H_
