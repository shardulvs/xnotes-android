// Copyright 2026 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "core/fpdfapi/edit/cpdf_font_util.h"

#include <stdint.h>

#include <algorithm>
#include <map>
#include <sstream>
#include <utility>
#include <vector>

#include "core/fpdfapi/parser/cpdf_array.h"
#include "core/fpdfapi/parser/cpdf_document.h"
#include "core/fpdfapi/parser/cpdf_number.h"
#include "core/fpdfapi/parser/cpdf_stream.h"
#include "core/fxcrt/check_op.h"
#include "core/fxcrt/fx_extension.h"
#include "core/fxcrt/fx_string_wrappers.h"
#include "core/fxcrt/numerics/safe_conversions.h"
#include "core/fxcrt/retain_ptr.h"
#include "core/fxcrt/span.h"
#include "core/fxcrt/utf16.h"

namespace {

constexpr uint32_t kMaxBfCharBfRangeEntries = 100;

const char kToUnicodeStart[] =
    "/CIDInit /ProcSet findresource begin\n"
    "12 dict begin\n"
    "begincmap\n"
    "/CIDSystemInfo\n"
    "<</Registry (Adobe)\n"
    "/Ordering (Identity)\n"
    "/Supplement 0\n"
    ">> def\n"
    "/CMapName /Adobe-Identity-H def\n"
    "/CMapType 2 def\n"
    "1 begincodespacerange\n"
    "<0000> <FFFF>\n"
    "endcodespacerange\n";

const char kToUnicodeEnd[] =
    "endcmap\n"
    "CMapName currentdict /CMap defineresource pop\n"
    "end\n"
    "end\n";

void AddCharcode(fxcrt::ostringstream& buffer, uint32_t number) {
  CHECK_LE(number, 0xFFFF);
  buffer << "<";
  char ans[4];
  FXSYS_IntToFourHexChars(number, ans);
  for (char c : ans) {
    buffer << c;
  }
  buffer << ">";
}

// PDF spec 1.7 Section 5.9.2: "Unicode character sequences as expressed in
// UTF-16BE encoding." See https://en.wikipedia.org/wiki/UTF-16#Description
void AddUnicode(fxcrt::ostringstream& buffer, uint32_t unicode) {
  if (pdfium::IsHighSurrogate(unicode) || pdfium::IsLowSurrogate(unicode)) {
    unicode = 0;
  }

  char unicode_buf[8];
  pdfium::span<const char> unicode_span = FXSYS_ToUTF16BE(unicode, unicode_buf);
  CHECK(!unicode_span.empty());
  buffer << "<";
  for (char c : unicode_span) {
    buffer << c;
  }
  buffer << ">";
}

}  // namespace

RetainPtr<CPDF_Array> CreateWidthsArray(
    const std::map<uint32_t, uint32_t>& widths) {
  auto widths_array = pdfium::MakeRetain<CPDF_Array>();
  for (auto it = widths.begin(); it != widths.end(); ++it) {
    auto next_it = std::next(it);

    if (next_it != widths.end() && next_it->first == it->first + 1 &&
        next_it->second == it->second) {
      // The array can have a group c_first c_last w: all CIDs in the range from
      // c_first to c_last will have width w
      widths_array->AppendNew<CPDF_Number>(static_cast<int>(it->first));

      while (next_it != widths.end() && next_it->first == it->first + 1 &&
             next_it->second == it->second) {
        it = next_it;
        next_it = std::next(it);
      }
      widths_array->AppendNew<CPDF_Number>(static_cast<int>(it->first));
      widths_array->AppendNew<CPDF_Number>(static_cast<int>(it->second));
      continue;
    }
    // Otherwise we can have a group of the form c [w1 w2 ...]: c has width
    // w1, c+1 has width w2, etc.
    // A group may contain only a single item, e.g. c[w]
    widths_array->AppendNew<CPDF_Number>(static_cast<int>(it->first));
    auto current_width_array = pdfium::MakeRetain<CPDF_Array>();
    current_width_array->AppendNew<CPDF_Number>(static_cast<int>(it->second));

    while (next_it != widths.end() && next_it->first == it->first + 1) {
      it = next_it;
      next_it = std::next(it);
      current_width_array->AppendNew<CPDF_Number>(static_cast<int>(it->second));
    }
    widths_array->Append(std::move(current_width_array));
  }
  return widths_array;
}

RetainPtr<CPDF_Stream> LoadUnicode(
    const std::multimap<uint32_t, uint32_t>& to_unicode) {
  // A map charcode->unicode
  std::map<uint32_t, uint32_t> char_to_unicode_map;
  // A map <char_start, char_end> to vector v of unicode characters of size (end
  // - start + 1). This abbreviates: start->v[0], start+1->v[1], etc. PDF spec
  // 1.7 Section 5.9.2 says that only the last byte of the unicode may change.
  std::map<std::pair<uint32_t, uint32_t>, std::vector<uint32_t>>
      char_range_to_unicodes_map;
  // A map <start, end> -> unicode
  // This abbreviates: start->unicode, start+1->unicode+1, etc.
  // PDF spec 1.7 Section 5.9.2 says that only the last byte of the unicode may
  // change.
  std::map<std::pair<uint32_t, uint32_t>, uint32_t>
      char_range_to_consecutive_unicodes_map;

  // Calculate the maps
  for (auto it = to_unicode.begin(); it != to_unicode.end(); ++it) {
    uint32_t first_charcode = it->first;
    uint32_t first_unicode = it->second;
    {
      auto next_it = std::next(it);
      if (next_it == to_unicode.end() || first_charcode + 1 != next_it->first) {
        char_to_unicode_map[first_charcode] = first_unicode;
        continue;
      }
    }
    ++it;
    uint32_t current_charcode = it->first;
    uint32_t current_unicode = it->second;
    if (current_charcode % 256 == 0) {
      char_to_unicode_map[first_charcode] = first_unicode;
      char_to_unicode_map[current_charcode] = current_unicode;
      continue;
    }
    const size_t max_extra = 255 - (current_charcode % 256);
    auto next_it = std::next(it);
    if (first_unicode + 1 != current_unicode) {
      // Consecutive charcodes mapping to non-consecutive unicodes
      std::vector<uint32_t> unicodes = {first_unicode, current_unicode};
      for (size_t i = 0; i < max_extra; ++i) {
        if (next_it == to_unicode.end() ||
            current_charcode + 1 != next_it->first) {
          break;
        }
        ++it;
        ++current_charcode;
        unicodes.push_back(it->second);
        next_it = std::next(it);
      }
      CHECK_EQ(it->first - first_charcode + 1, unicodes.size());
      char_range_to_unicodes_map[std::make_pair(first_charcode, it->first)] =
          std::move(unicodes);
      continue;
    }
    // Consecutive charcodes mapping to consecutive unicodes
    for (size_t i = 0; i < max_extra; ++i) {
      if (next_it == to_unicode.end() ||
          current_charcode + 1 != next_it->first ||
          current_unicode + 1 != next_it->second) {
        break;
      }
      ++it;
      ++current_charcode;
      ++current_unicode;
      next_it = std::next(it);
    }
    char_range_to_consecutive_unicodes_map[std::make_pair(
        first_charcode, current_charcode)] = first_unicode;
  }

  fxcrt::ostringstream buffer;
  buffer << kToUnicodeStart;

  {
    // Add `char_to_unicode_map` to `buffer`.
    uint32_t to_process =
        pdfium::checked_cast<uint32_t>(char_to_unicode_map.size());
    auto it = char_to_unicode_map.begin();
    while (to_process) {
      const uint32_t to_process_this_iteration =
          std::min(to_process, kMaxBfCharBfRangeEntries);
      buffer << to_process_this_iteration << " beginbfchar\n";
      for (uint32_t i = 0; i < to_process_this_iteration; ++i) {
        CHECK(it != char_to_unicode_map.end());
        AddCharcode(buffer, it->first);
        buffer << " ";
        AddUnicode(buffer, it->second);
        buffer << "\n";
        ++it;
      }
      buffer << "endbfchar\n";
      to_process -= to_process_this_iteration;
    }
  }

  {
    // Add `char_range_to_unicodes_map` to `buffer`.
    uint32_t to_process =
        pdfium::checked_cast<uint32_t>(char_range_to_unicodes_map.size());
    auto it = char_range_to_unicodes_map.begin();
    while (to_process) {
      const uint32_t to_process_this_iteration =
          std::min(to_process, kMaxBfCharBfRangeEntries);
      buffer << to_process_this_iteration << " beginbfrange\n";
      for (uint32_t i = 0; i < to_process_this_iteration; ++i) {
        CHECK(it != char_range_to_unicodes_map.end());
        const std::pair<uint32_t, uint32_t>& charcode_range = it->first;
        AddCharcode(buffer, charcode_range.first);
        buffer << " ";
        AddCharcode(buffer, charcode_range.second);
        buffer << " [";
        auto unicodes = pdfium::span(it->second);
        AddUnicode(buffer, unicodes[0]);
        for (uint32_t code : unicodes.subspan(1u)) {
          buffer << " ";
          AddUnicode(buffer, code);
        }
        buffer << "]\n";
        ++it;
      }
      buffer << "endbfrange\n";
      to_process -= to_process_this_iteration;
    }
  }

  {
    // Add `char_range_to_consecutive_unicodes_map` to `buffer`.
    uint32_t to_process = pdfium::checked_cast<uint32_t>(
        char_range_to_consecutive_unicodes_map.size());
    auto it = char_range_to_consecutive_unicodes_map.begin();
    while (to_process) {
      const uint32_t to_process_this_iteration =
          std::min(to_process, kMaxBfCharBfRangeEntries);
      buffer << to_process_this_iteration << " beginbfrange\n";
      for (uint32_t i = 0; i < to_process_this_iteration; ++i) {
        CHECK(it != char_range_to_consecutive_unicodes_map.end());
        const std::pair<uint32_t, uint32_t>& charcode_range = it->first;
        AddCharcode(buffer, charcode_range.first);
        buffer << " ";
        AddCharcode(buffer, charcode_range.second);
        buffer << " ";
        AddUnicode(buffer, it->second);
        buffer << "\n";
        ++it;
      }
      buffer << "endbfrange\n";
      to_process -= to_process_this_iteration;
    }
  }

  buffer << kToUnicodeEnd;
  return pdfium::MakeRetain<CPDF_Stream>(&buffer);
}
