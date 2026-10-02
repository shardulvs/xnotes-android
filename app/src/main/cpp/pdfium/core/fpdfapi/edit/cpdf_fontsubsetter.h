// Copyright 2026 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CORE_FPDFAPI_EDIT_CPDF_FONTSUBSETTER_H_
#define CORE_FPDFAPI_EDIT_CPDF_FONTSUBSETTER_H_

#include <stdint.h>

#include <map>
#include <set>

#include "core/fxcrt/bytestring.h"
#include "core/fxcrt/retain_ptr.h"
#include "core/fxcrt/span.h"
#include "core/fxcrt/unowned_ptr.h"

class CPDF_Document;
class CPDF_Dictionary;
class CPDF_Object;
class CPDF_Page;
class CPDF_Stream;
class CPDF_TextObject;

// CPDF_FontSubsetter generate object overrides for any text-related PDF objects
// in order to subset new embedded fonts. CPDF_FontSubsetter only creates new
// PDF objects and does not modify any existing PDF objects. Use during saving
// to create objects that should override existing objects when writing the PDF.
// CPDF_FontSubsetter does not support Type 1 fonts.
class CPDF_FontSubsetter {
 public:
  explicit CPDF_FontSubsetter(CPDF_Document* doc);
  ~CPDF_FontSubsetter();

  // Given `new_obj_nums`, a collection of all new object numbers sorted in
  // ascending order, returns a map of object numbers to new PDF objects that
  // should override the original objects in order to subset any embedded fonts
  // for new text.
  std::map<uint32_t, RetainPtr<const CPDF_Object>> GenerateObjectOverrides(
      pdfium::span<const uint32_t> new_obj_nums);

 private:
  // Potential fonts that can be subsetted.
  struct SubsetCandidate {
    SubsetCandidate();
    ~SubsetCandidate();

    // The new font name after subsetting.
    ByteString subset_font_name;

    // PDF font-related objects that need to be overridden during the save.
    //
    // In theory, an embedded font file could have multiple RootFonts, CIDFonts,
    // and FontDescriptors pointing to it. However, CPDF_FontSubsetter only
    // supports new embedded fonts, and PDFium only creates 1:1 mappings for new
    // fonts.
    RetainPtr<const CPDF_Dictionary> root_font;
    RetainPtr<const CPDF_Dictionary> cid_font;
    RetainPtr<const CPDF_Dictionary> descriptor;
    RetainPtr<const CPDF_Stream> font_stream;

    // The set of GIDs used by text.
    std::set<uint32_t> used_gids;

    // Mapping of char codes to their widths.
    std::map<uint32_t, uint32_t> char_code_to_width;

    // Mapping of char codes to unicode, allowing duplicate entries.
    std::multimap<uint32_t, uint32_t> char_code_to_unicode;
  };

  // Gets the subset candidates from all pages. `new_obj_nums` must be sorted in
  // ascending order.
  void CollectSubsetCandidates(pdfium::span<const uint32_t> new_obj_nums);

  // Gets the subset candidates from `page`. `new_obj_nums` must be sorted in
  // ascending order.
  void CollectSubsetCandidatesFromPage(
      CPDF_Page* page,
      pdfium::span<const uint32_t> new_obj_nums);

  // Adds the characters used in `text` to `candidate`. Also adds widths to
  // `candidate` if `subset_widths` is true.
  void AddUsedText(const CPDF_TextObject* text,
                   SubsetCandidate& candidate,
                   bool subset_widths);

  UnownedPtr<CPDF_Document> const doc_;

  // Map of font file stream object numbers to subset candidates.
  std::map<uint32_t, SubsetCandidate> candidates_;
};

#endif  // CORE_FPDFAPI_EDIT_CPDF_FONTSUBSETTER_H_
