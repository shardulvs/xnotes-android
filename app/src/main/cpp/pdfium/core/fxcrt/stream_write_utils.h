// Copyright 2019 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CORE_FXCRT_STREAM_WRITE_UTILS_H_
#define CORE_FXCRT_STREAM_WRITE_UTILS_H_

#include <iosfwd>

#include "core/fxcrt/fx_coordinates.h"

namespace fxcrt {

// Writes `value` to `stream` as a decimal string in a form suitable for use in
// a PDF content stream. Does not use scientific notation. Omits leading zeroes.
std::ostream& WriteFloat(std::ostream& stream, float value);

std::ostream& WriteMatrix(std::ostream& stream, const CFX_Matrix& matrix);
std::ostream& WritePoint(std::ostream& stream, const CFX_PointF& point);
std::ostream& WriteRect(std::ostream& stream, const CFX_FloatRect& rect);

}  // namespace fxcrt

using fxcrt::WriteFloat;
using fxcrt::WriteMatrix;
using fxcrt::WritePoint;
using fxcrt::WriteRect;

#endif  // CORE_FXCRT_STREAM_WRITE_UTILS_H_
