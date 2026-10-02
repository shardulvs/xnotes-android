// Copyright 2014 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxcrt/fx_extension.h"

#include <wchar.h>

#include <array>

#include "core/fxcrt/check.h"
#include "core/fxcrt/check_op.h"
#include "core/fxcrt/fx_system.h"
#include "core/fxcrt/utf16.h"
#include "core/fxcrt/widestring.h"
#include "third_party/fast_float/src/include/fast_float/fast_float.h"

namespace {

constexpr int kMinutesPerHour = 60;
constexpr int kHoursPerDay = 24;
constexpr int kMinutesPerDay = kMinutesPerHour * kHoursPerDay;

time_t DefaultTimeFunction() {
  return time(nullptr);
}

struct tm* DefaultLocaltimeFunction(const time_t* tp) {
  return localtime(tp);
}

TimeFunction g_time_func = DefaultTimeFunction;
LocaltimeFunction g_localtime_func = DefaultLocaltimeFunction;

}  // namespace

float FXSYS_wcstof(WideStringView pwsStr, size_t* pUsedLen) {
  // TODO(thestig): Consolidate code duplication with StringToFloatImpl().
  // Skip leading whitespaces.
  size_t start = 0;
  size_t len = pwsStr.GetLength();
  while (start < len && pwsStr[start] == ' ') {
    ++start;
  }

  WideStringView sub_strc = pwsStr.Substr(start, len - start);

  float value;
  auto result =
      fast_float::from_chars(sub_strc.begin(), sub_strc.end(), value,
                             static_cast<fast_float::chars_format>(
                                 fast_float::chars_format::general |
                                 fast_float::chars_format::allow_leading_plus));

  if (pUsedLen) {
    *pUsedLen = result.ptr - pwsStr.unterminated_c_str();
  }

  // Return 0 for parsing errors. Some examples of errors are an empty string
  // and a string that cannot be converted to `ReturnType`.
  return result.ec == std::errc() || result.ec == std::errc::result_out_of_range
             ? value
             : 0;
}

void FXSYS_IntToTwoHexChars(uint8_t n, pdfium::span<char, 2u> buf) {
  static constexpr std::array<const char, 16> kHex = {
      '0', '1', '2', '3', '4', '5', '6', '7',
      '8', '9', 'A', 'B', 'C', 'D', 'E', 'F',
  };
  buf[0] = kHex[n / 16];
  buf[1] = kHex[n % 16];
}

void FXSYS_IntToFourHexChars(uint16_t n, pdfium::span<char, 4u> buf) {
  FXSYS_IntToTwoHexChars(n / 256, buf.first<2u>());
  FXSYS_IntToTwoHexChars(n % 256, buf.subspan<2u>());
}

pdfium::span<const char> FXSYS_ToUTF16BE(uint32_t unicode,
                                         pdfium::span<char, 8u> buf) {
  DCHECK_LE(unicode, pdfium::kMaximumSupplementaryCodePoint);
  DCHECK(!pdfium::IsHighSurrogate(unicode));
  DCHECK(!pdfium::IsLowSurrogate(unicode));

  if (unicode <= 0xFFFF) {
    FXSYS_IntToFourHexChars(unicode, buf.first<4u>());
    return buf.first<4u>();
  }
  pdfium::SurrogatePair surrogate_pair(unicode);
  FXSYS_IntToFourHexChars(surrogate_pair.high(), buf.first<4u>());
  FXSYS_IntToFourHexChars(surrogate_pair.low(), buf.subspan<4u>());
  return buf;
}

TimeFunction FXSYS_SetTimeFunction(TimeFunction func) {
  TimeFunction old_func = g_time_func;
  g_time_func = func ? func : DefaultTimeFunction;
  return old_func;
}

LocaltimeFunction FXSYS_SetLocaltimeFunction(LocaltimeFunction func) {
  LocaltimeFunction old_func = g_localtime_func;
  g_localtime_func = func ? func : DefaultLocaltimeFunction;
  return old_func;
}

time_t FXSYS_time(time_t* tloc) {
  time_t ret_val = g_time_func();
  if (tloc) {
    *tloc = ret_val;
  }
  return ret_val;
}

struct tm* FXSYS_localtime(const time_t* tp) {
  return g_localtime_func(tp);
}

int FXSYS_TimeZoneOffsetInMinutes(const tm& local_time, const tm& utc_time) {
  int day_offset = local_time.tm_mday - utc_time.tm_mday;
  if (day_offset > 1) {
    day_offset = -1;
  } else if (day_offset < -1) {
    day_offset = 1;
  }

  return day_offset * kMinutesPerDay +
         (local_time.tm_hour - utc_time.tm_hour) * kMinutesPerHour +
         (local_time.tm_min - utc_time.tm_min);
}
