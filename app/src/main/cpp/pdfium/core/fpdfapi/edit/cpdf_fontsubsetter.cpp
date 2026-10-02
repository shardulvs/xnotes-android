// Copyright 2026 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "core/fpdfapi/edit/cpdf_fontsubsetter.h"

#include <hb-subset.h>
#include <hb.h>
#include <stdint.h>

#include <algorithm>
#include <array>
#include <map>
#include <memory>
#include <set>
#include <utility>
#include <vector>

#include "core/fpdfapi/edit/cpdf_font_util.h"
#include "core/fpdfapi/font/cpdf_font.h"
#include "core/fpdfapi/page/cpdf_page.h"
#include "core/fpdfapi/page/cpdf_textobject.h"
#include "core/fpdfapi/parser/cpdf_array.h"
#include "core/fpdfapi/parser/cpdf_dictionary.h"
#include "core/fpdfapi/parser/cpdf_document.h"
#include "core/fpdfapi/parser/cpdf_name.h"
#include "core/fpdfapi/parser/cpdf_number.h"
#include "core/fpdfapi/parser/cpdf_reference.h"
#include "core/fpdfapi/parser/cpdf_stream.h"
#include "core/fpdfapi/parser/cpdf_stream_acc.h"
#include "core/fxcrt/byteorder.h"
#include "core/fxcrt/bytestring.h"
#include "core/fxcrt/check.h"
#include "core/fxcrt/compiler_specific.h"
#include "core/fxcrt/data_vector.h"
#include "core/fxcrt/fx_extension.h"
#include "core/fxcrt/fx_random.h"
#include "core/fxcrt/fx_string.h"
#include "core/fxcrt/retain_ptr.h"
#include "core/fxcrt/span.h"
#include "core/fxcrt/widestring.h"
#include "core/fxge/fx_font.h"

namespace {

template <auto DestroyFunction>
struct HBDeleter {
  template <typename T>
  void operator()(T* ptr) const {
    DestroyFunction(ptr);
  }
};

using ScopedHBBlob = std::unique_ptr<hb_blob_t, HBDeleter<hb_blob_destroy>>;
using ScopedHBFace = std::unique_ptr<hb_face_t, HBDeleter<hb_face_destroy>>;
using ScopedHBSubsetInput =
    std::unique_ptr<hb_subset_input_t, HBDeleter<hb_subset_input_destroy>>;

DataVector<uint8_t> GenerateFontSubset(CPDF_Document* doc,
                                       pdfium::span<const uint8_t> font_data,
                                       const std::set<uint32_t>& gids) {
  // Wrap the data.
  ScopedHBBlob blob(
      hb_blob_create_or_fail(reinterpret_cast<const char*>(font_data.data()),
                             static_cast<uint32_t>(font_data.size()),
                             HB_MEMORY_MODE_READONLY, nullptr, nullptr));
  if (!blob) {
    return {};
  }

  ScopedHBSubsetInput input(hb_subset_input_create_or_fail());
  if (!input) {
    return {};
  }

  hb_subset_input_set_flags(input.get(), HB_SUBSET_FLAGS_RETAIN_GIDS |
                                             HB_SUBSET_FLAGS_NOTDEF_OUTLINE);

  hb_set_t* glyphs = hb_subset_input_glyph_set(input.get());
  for (uint32_t gid : gids) {
    hb_set_add(glyphs, gid);
  }

  ScopedHBFace face(hb_face_create(blob.get(), 0));
  ScopedHBFace subset_face(hb_subset_or_fail(face.get(), input.get()));
  if (!subset_face) {
    return {};
  }

  ScopedHBBlob subset_blob(hb_face_reference_blob(subset_face.get()));
  unsigned int out_len;
  const char* out_data = hb_blob_get_data(subset_blob.get(), &out_len);
  if (!out_data || out_len == 0) {
    return {};
  }

  // SAFETY: HarfBuzz guarantees the correct length from hb_blob_get_length.
  return DataVector<uint8_t>(out_data, UNSAFE_BUFFERS(out_data + out_len));
}

// Returns a font subset name with a tag prefix, replacing existing subset
// prefixes if necessary. ISO 32000-1:2008 spec, section 9.6.4 "Font Subsets":
// the font name must begin with a tag followed by a plus sign (+). The tag must
// consist of six uppercase letters.
ByteString GenerateFontSubsetName(ByteString base_font_name) {
  // Replace existing font subset tags if necessary.
  MaybeRemoveSubsettedFontPrefix(base_font_name);

  ByteString subset_font_name;
  subset_font_name.Reserve(kSubsettedFontPrefixLength +
                           base_font_name.GetLength() + 1);
  std::array<uint32_t, kSubsettedFontPrefixLength> random_nums;
  FX_Random::Fill(random_nums);
  for (uint32_t num : random_nums) {
    subset_font_name += 'A' + (num % 26);
  }
  subset_font_name += "+";
  subset_font_name += base_font_name;
  return subset_font_name;
}

}  // namespace

CPDF_FontSubsetter::CPDF_FontSubsetter(CPDF_Document* doc) : doc_(doc) {}

CPDF_FontSubsetter::~CPDF_FontSubsetter() = default;

std::map<uint32_t, RetainPtr<const CPDF_Object>>
CPDF_FontSubsetter::GenerateObjectOverrides(
    pdfium::span<const uint32_t> new_obj_nums) {
  if (new_obj_nums.empty()) {
    return {};
  }

  candidates_.clear();
  CollectSubsetCandidates(new_obj_nums);

  std::map<uint32_t, RetainPtr<const CPDF_Object>> overrides;
  for (auto& [obj_num, candidate] : candidates_) {
    auto original_stream_acc =
        pdfium::MakeRetain<CPDF_StreamAcc>(candidate.font_stream);
    original_stream_acc->LoadAllDataFiltered();
    auto original_stream_span = original_stream_acc->GetSpan();

    DataVector<uint8_t> subsetted_font_data =
        GenerateFontSubset(doc_, original_stream_span, candidate.used_gids);
    if (subsetted_font_data.empty()) {
      continue;
    }

    // OpenType fonts containing CFF data have an "OTTO" tag at the start of the
    // file.
    bool is_opentype_cff = IsOpenTypeCFF(original_stream_span);

    // Override the font file stream.
    // See ISO 32000-1:2008 section 9.9 "Embedded Font Programs" for OpenType
    // CFF font entries.
    auto subsetted_font_dict = pdfium::MakeRetain<CPDF_Dictionary>();
    if (is_opentype_cff) {
      subsetted_font_dict->SetNewFor<CPDF_Name>("Subtype", "OpenType");
    } else {
      // Only Type 1 and TrueType fonts require a Length1 entry.
      subsetted_font_dict->SetNewFor<CPDF_Number>(
          "Length1", static_cast<int>(subsetted_font_data.size()));
    }
    overrides[obj_num] = pdfium::MakeRetain<CPDF_Stream>(
        std::move(subsetted_font_data), std::move(subsetted_font_dict));

    // Override the root font dict.
    RetainPtr<CPDF_Dictionary> new_root_font =
        ToDictionary(candidate.root_font->Clone());
    new_root_font->SetNewFor<CPDF_Name>("BaseFont", candidate.subset_font_name);
    overrides[candidate.root_font->GetObjNum()] = new_root_font;

    // Override the CID font dict if necessary.
    if (candidate.cid_font) {
      RetainPtr<CPDF_Dictionary> new_cid_font =
          ToDictionary(candidate.cid_font->Clone());
      new_cid_font->SetNewFor<CPDF_Name>("BaseFont",
                                         candidate.subset_font_name);
      if (is_opentype_cff) {
        new_cid_font->SetNewFor<CPDF_Name>("Subtype", "CIDFontType0");
      }
      overrides[candidate.cid_font->GetObjNum()] = new_cid_font;

      // Override widths if necessary.
      RetainPtr<const CPDF_Array> original_widths =
          candidate.cid_font->GetArrayFor("W");
      if (original_widths) {
        overrides[original_widths->GetObjNum()] =
            CreateWidthsArray(candidate.char_code_to_width);
      }
    }

    // Override the font descriptor.
    RetainPtr<CPDF_Dictionary> new_descriptor =
        ToDictionary(candidate.descriptor->Clone());
    new_descriptor->SetNewFor<CPDF_Name>("FontName",
                                         candidate.subset_font_name);
    if (is_opentype_cff) {
      // Always set the symbolic flag and remove the nonsymbolic flag. A
      // subsetted font's character set may not be a strict subset of the
      // "standard Latin character set." Furthermore, the mapping (whether GIDs
      // in a simple font or CIDs in a composite font) is unique to the subset.
      // Marking it symbolic prevents PDF readers from applying font
      // substitution strategies that would result in incorrect glyphs. See ISO
      // 32000-1:2008, section 9.8.2 "Font Descriptor Flags".
      int flags = new_descriptor->GetIntegerFor("Flags");
      flags |= 0x04;
      flags &= ~0x20;
      new_descriptor->SetNewFor<CPDF_Number>("Flags", flags);

      new_descriptor->RemoveFor("FontFile2");
      new_descriptor->SetNewFor<CPDF_Reference>("FontFile3", doc_, obj_num);
    }
    overrides[candidate.descriptor->GetObjNum()] = new_descriptor;

    // Override ToUnicode.
    RetainPtr<const CPDF_Stream> to_unicode =
        candidate.root_font->GetStreamFor("ToUnicode");
    if (to_unicode) {
      overrides[to_unicode->GetObjNum()] =
          LoadUnicode(candidate.char_code_to_unicode);
    }
  }
  return overrides;
}

CPDF_FontSubsetter::SubsetCandidate::SubsetCandidate() = default;

CPDF_FontSubsetter::SubsetCandidate::~SubsetCandidate() = default;

void CPDF_FontSubsetter::CollectSubsetCandidates(
    pdfium::span<const uint32_t> new_obj_nums) {
  for (int i = 0; i < doc_->GetPageCount(); ++i) {
    RetainPtr<CPDF_Dictionary> page_dict = doc_->GetMutablePageDictionary(i);
    if (!page_dict) {
      continue;
    }

    auto page = pdfium::MakeRetain<CPDF_Page>(doc_, std::move(page_dict));
    page->ParseContent();
    CollectSubsetCandidatesFromPage(page, new_obj_nums);
  }
}

void CPDF_FontSubsetter::CollectSubsetCandidatesFromPage(
    CPDF_Page* page,
    pdfium::span<const uint32_t> new_obj_nums) {
  for (const auto& page_obj : *page) {
    const CPDF_TextObject* text = page_obj->AsText();
    if (!text) {
      continue;
    }

    RetainPtr<CPDF_Font> font = text->GetFont();
    RetainPtr<const CPDF_Dictionary> root_font = font->GetFontDict();
    if (!std::ranges::binary_search(new_obj_nums, root_font->GetObjNum())) {
      continue;
    }

    RetainPtr<const CPDF_Dictionary> cid_font;
    RetainPtr<const CPDF_Dictionary> descriptor;
    if (font->IsCIDFont()) {
      RetainPtr<const CPDF_Array> descendants =
          root_font->GetArrayFor("DescendantFonts");
      CHECK(descendants);
      cid_font = descendants->GetDictAt(0);
      CHECK(cid_font);
      descriptor = cid_font->GetDictFor("FontDescriptor");
    } else {
      descriptor = root_font->GetDictFor("FontDescriptor");
    }
    if (!descriptor) {
      continue;
    }

    // Internally, all embedded font file streams are set as "FontFile" or
    // "FontFile2". HarfBuzz does not support subsetting Type 1 fonts, so fonts
    // with "FontFile" are ignored.
    RetainPtr<const CPDF_Stream> font_stream =
        descriptor->GetStreamFor("FontFile2");
    if (!font_stream) {
      continue;
    }

    uint32_t obj_num = font_stream->GetObjNum();
    auto& candidate = candidates_[obj_num];
    if (!candidate.font_stream) {
      candidate.subset_font_name =
          GenerateFontSubsetName(font->GetBaseFontName());
      candidate.font_stream = font_stream;
      candidate.root_font = root_font;
      candidate.cid_font = cid_font;
      candidate.descriptor = descriptor;
    }

    bool subset_widths = cid_font && cid_font->GetArrayFor("W");
    AddUsedText(text, candidate, subset_widths);
  }
}

void CPDF_FontSubsetter::AddUsedText(const CPDF_TextObject* text,
                                     SubsetCandidate& candidate,
                                     bool subset_widths) {
  CPDF_Font* font = text->GetFont();
  const std::vector<uint32_t>& char_codes = text->GetCharCodes();
  std::set<uint32_t>& used_gids = candidate.used_gids;
  for (uint32_t char_code : char_codes) {
    int gid = font->GlyphFromCharCode(char_code, /*pVertGlyph=*/nullptr);
    if (gid != -1) {
      used_gids.insert(static_cast<uint32_t>(gid));
    }

    if (subset_widths) {
      int width = font->GetCharWidth(char_code);
      if (width >= 0) {
        candidate.char_code_to_width[char_code] = static_cast<uint32_t>(width);
      }
    }

    WideString unicode = font->UnicodeFromCharCode(char_code);
    if (!unicode.IsEmpty()) {
      candidate.char_code_to_unicode.emplace(
          char_code, static_cast<uint32_t>(unicode.Front()));
    }
  }
}
