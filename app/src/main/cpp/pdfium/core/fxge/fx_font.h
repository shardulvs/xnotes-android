// Copyright 2014 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef CORE_FXGE_FX_FONT_H_
#define CORE_FXGE_FX_FONT_H_

#include <stddef.h>
#include <stdint.h>

#include <optional>
#include <vector>

#include "core/fxcrt/bytestring.h"
#include "core/fxcrt/fx_coordinates.h"
#include "core/fxcrt/span.h"

namespace pdfium {

// Font pitch and family flags.
enum FontPitchFamily {
  kFontPitchFamilyFixed = 1 << 0,
  kFontPitchFamilyRoman = 1 << 4,
  kFontPitchFamilyScript = 1 << 6,
};

// Defined in ISO 32000-1:2008 spec, table 123.
// Defined in ISO 32000-2:2020 spec, table 121.
enum FontStyle {
  kFontStyleNormal = 0,
  kFontStyleFixedPitch = 1 << 0,
  kFontStyleSerif = 1 << 1,
  kFontStyleSymbolic = 1 << 2,
  kFontStyleScript = 1 << 3,
  kFontStyleNonSymbolic = 1 << 5,
  kFontStyleItalic = 1 << 6,
  kFontStyleAllCap = 1 << 16,
  kFontStyleSmallCap = 1 << 17,
  kFontStyleForceBold = 1 << 18,
};

// Font weight values that are in use.
enum FontWeight {
  kFontWeightExtraLight = 100,
  kFontWeightNormal = 400,
  kFontWeightBold = 700,
  kFontWeightExtraBold = 900,
};

}  // namespace pdfium

// Indicates that font properties (style, weight, ...) come from an external
// source rather than the font file itself.
constexpr uint32_t kFontUseExternAttr = 0x80000;

struct CharCodeAndIndex {
  uint32_t char_code;
  uint32_t glyph_index;

  bool operator==(const CharCodeAndIndex&) const = default;
};

struct FontTableLocation {
  uint32_t offset;
  uint32_t size;
};

enum class FontAntiAliasingMode : int {
  kNormal,
  kMono,
  kLcd,
};

// These numbers come from the OpenType name table specification.
constexpr uint16_t kNamePlatformAppleUnicode = 0;
constexpr uint16_t kNamePlatformMac = 1;
constexpr uint16_t kNamePlatformWindows = 3;

// The length of the font subset prefix, as defined in ISO 32000-1:2008 spec,
// section 9.6.4 "Font Subsets".
constexpr size_t kSubsettedFontPrefixLength = 6;

class TextGlyphPos;

FX_RECT GetGlyphsBBox(const std::vector<TextGlyphPos>& glyphs,
                      bool anti_alias_is_lcd);

ByteString GetNameFromTT(pdfium::span<const uint8_t> name_table, uint32_t name);
uint32_t GetTTCIndex(pdfium::span<const uint8_t> font_data, size_t font_offset);
uint32_t GetCodePageRangeFromOS2(pdfium::span<const uint8_t> os2_table);
uint16_t GetGlyphCountFromMaxp(pdfium::span<const uint8_t> maxp_table);

std::optional<FontTableLocation> FindFontTableLocation(
    pdfium::span<const uint8_t> table_dir,
    uint32_t tag);

inline bool FontStyleIsForceBold(uint32_t style) {
  return !!(style & pdfium::kFontStyleForceBold);
}
inline bool FontStyleIsItalic(uint32_t style) {
  return !!(style & pdfium::kFontStyleItalic);
}
inline bool FontStyleIsFixedPitch(uint32_t style) {
  return !!(style & pdfium::kFontStyleFixedPitch);
}
inline bool FontStyleIsSymbolic(uint32_t style) {
  return !!(style & pdfium::kFontStyleSymbolic);
}
inline bool FontStyleIsNonSymbolic(uint32_t style) {
  return !!(style & pdfium::kFontStyleNonSymbolic);
}
inline bool FontStyleIsAllCaps(uint32_t style) {
  return !!(style & pdfium::kFontStyleAllCap);
}
inline bool FontStyleIsSerif(uint32_t style) {
  return !!(style & pdfium::kFontStyleSerif);
}
inline bool FontStyleIsScript(uint32_t style) {
  return !!(style & pdfium::kFontStyleScript);
}

inline bool FontFamilyIsFixedPitch(uint32_t family) {
  return !!(family & pdfium::kFontPitchFamilyFixed);
}
inline bool FontFamilyIsRoman(uint32_t family) {
  return !!(family & pdfium::kFontPitchFamilyRoman);
}
inline bool FontFamilyIsScript(int32_t family) {
  return !!(family & pdfium::kFontPitchFamilyScript);
}

wchar_t UnicodeFromAdobeName(const char* name);
ByteString AdobeNameFromUnicode(wchar_t unicode);

// Take a font metric `value` and scale it down by the font's `upem`. If the
// font is not scalable, i.e. `upem` is 0, then return `value` as is.
// If the computed result is excessively large and does not fit in an int,
// NormalizeFontMetric() handles that with `saturated_cast()`.
int NormalizeFontMetric(int64_t value, uint16_t upem);

// Removes the "XXXXXX+" prefix from a subsetted font name if present. The
// prefix must be 6 uppercase ASCII letters followed by a '+'.
void MaybeRemoveSubsettedFontPrefix(ByteString& font_name);

// Returns true if the font data starts with the "OTTO" tag, indicating an
// OpenType font with CFF data.
bool IsOpenTypeCFF(pdfium::span<const uint8_t> data);

#endif  // CORE_FXGE_FX_FONT_H_
