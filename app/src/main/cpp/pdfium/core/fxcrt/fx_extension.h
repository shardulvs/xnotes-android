// Copyright 2014 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef CORE_FXCRT_FX_EXTENSION_H_
#define CORE_FXCRT_FX_EXTENSION_H_

#include <ctype.h>
#include <math.h>
#include <time.h>
#include <wctype.h>

#include "core/fxcrt/span.h"
#include "core/fxcrt/widestring.h"

#if defined(USE_SYSTEM_ICUUC)
#include <unicode/uchar.h>
#else
#include "third_party/icu/source/common/unicode/uchar.h"
#endif

#define FX_INVALID_OFFSET static_cast<uint32_t>(-1)

#define FX_IsOdd(a) ((a) & 1)

float FXSYS_wcstof(WideStringView pwsStr, size_t* pUsedLen);

inline bool FXSYS_iswlower(int32_t c) {
  return u_islower(c);
}

inline bool FXSYS_iswupper(int32_t c) {
  return u_isupper(c);
}

inline int32_t FXSYS_towlower(wchar_t c) {
  return u_tolower(c);
}

inline int32_t FXSYS_towupper(wchar_t c) {
  return u_toupper(c);
}

inline constexpr bool FXSYS_IsLowerASCII(int32_t c) {
  return c >= 'a' && c <= 'z';
}

inline constexpr bool FXSYS_IsUpperASCII(int32_t c) {
  return c >= 'A' && c <= 'Z';
}

inline constexpr char FXSYS_ToLowerASCII(char c) {
  return FXSYS_IsUpperASCII(c) ? (c + ('a' - 'A')) : c;
}

inline constexpr char FXSYS_ToUpperASCII(char c) {
  return FXSYS_IsLowerASCII(c) ? (c + ('A' - 'a')) : c;
}

inline bool FXSYS_iswalpha(wchar_t c) {
  return u_isalpha(c);
}

inline bool FXSYS_iswalnum(wchar_t c) {
  return u_isalnum(c);
}

inline bool FXSYS_iswspace(wchar_t c) {
  return u_isspace(c);
}

inline constexpr bool FXSYS_IsOctalDigit(char c) {
  return c >= '0' && c <= '7';
}

inline constexpr bool FXSYS_IsHexDigit(char c) {
  return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') ||
         (c >= 'A' && c <= 'F');
}

inline constexpr bool FXSYS_IsWideHexDigit(wchar_t c) {
  return (c >= L'0' && c <= L'9') || (c >= L'a' && c <= L'f') ||
         (c >= L'A' && c <= L'F');
}

inline constexpr int FXSYS_HexCharToInt(char c) {
  if (!FXSYS_IsHexDigit(c)) {
    return 0;
  }
  char upchar = FXSYS_ToUpperASCII(c);
  return upchar > '9' ? upchar - 'A' + 10 : upchar - '0';
}

inline constexpr int FXSYS_WideHexCharToInt(wchar_t c) {
  if (!FXSYS_IsWideHexDigit(c)) {
    return 0;
  }
  char upchar = FXSYS_ToUpperASCII(static_cast<char>(c));
  return upchar > '9' ? upchar - 'A' + 10 : upchar - '0';
}

inline bool FXSYS_IsDecimalDigit(char c) {
  return !((c & 0x80) || !isdigit(c));
}

inline bool FXSYS_IsDecimalDigit(wchar_t c) {
  return !((c & 0xFFFFFF80) || !iswdigit(c));
}

inline int FXSYS_DecimalCharToInt(char c) {
  return FXSYS_IsDecimalDigit(c) ? c - '0' : 0;
}

inline int FXSYS_DecimalCharToInt(wchar_t c) {
  return FXSYS_IsDecimalDigit(c) ? c - L'0' : 0;
}

void FXSYS_IntToTwoHexChars(uint8_t n, pdfium::span<char, 2u> buf);
void FXSYS_IntToFourHexChars(uint16_t n, pdfium::span<char, 4u> buf);

// Converts `unicode` to a UTF16-BE hex string. Writes the string into `buf` and
// returns the portion of `buf` used to store the string. The returned span is
// never empty.
pdfium::span<const char> FXSYS_ToUTF16BE(uint32_t unicode,
                                         pdfium::span<char, 8u> buf);

// Strict order over floating types where NaNs may be present.
// All NaNs are treated as equal to each other and greater than infinity.
template <typename T>
bool FXSYS_SafeEQ(const T& lhs, const T& rhs) {
  return (isnan(lhs) && isnan(rhs)) ||
         (!isnan(lhs) && !isnan(rhs) && lhs == rhs);
}

template <typename T>
bool FXSYS_SafeLT(const T& lhs, const T& rhs) {
  if (isnan(lhs) && isnan(rhs)) {
    return false;
  }
  if (isnan(lhs) || isnan(rhs)) {
    return isnan(lhs) < isnan(rhs);
  }
  return lhs < rhs;
}

using TimeFunction = time_t (*)();
using LocaltimeFunction = struct tm* (*)(const time_t*);

// Override time/localtime functions for test consistency. Returns the previous
// override function.
TimeFunction FXSYS_SetTimeFunction(TimeFunction func);
LocaltimeFunction FXSYS_SetLocaltimeFunction(LocaltimeFunction func);

// Replacements for time/localtime that respect overrides.
time_t FXSYS_time(time_t* tloc);
struct tm* FXSYS_localtime(const time_t* tp);

int FXSYS_TimeZoneOffsetInMinutes(const tm& local_time, const tm& utc_time);

#endif  // CORE_FXCRT_FX_EXTENSION_H_
