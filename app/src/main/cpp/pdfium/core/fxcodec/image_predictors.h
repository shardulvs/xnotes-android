// Copyright 2026 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CORE_FXCODEC_IMAGE_PREDICTORS_H_
#define CORE_FXCODEC_IMAGE_PREDICTORS_H_

#include <stddef.h>
#include <stdint.h>

#include "core/fxcodec/data_and_bytes_consumed.h"
#include "core/fxcrt/data_vector.h"
#include "core/fxcrt/span.h"

namespace fxcodec {

enum class PredictorType : uint8_t { kNone, kTiff, kPng };

// Values come from ISO 32000-1:2008, table 10.
PredictorType GetPredictor(int predictor);

const DataAndBytesConsumed ApplyPredictor(DataVector<uint8_t> decoded_buf,
                                          int predictor,
                                          int colors,
                                          int bits_per_component,
                                          int columns,
                                          uint32_t bytes_consumed);

void PngPredictLine(pdfium::span<uint8_t> dest_span,
                    pdfium::span<const uint8_t> src_span,
                    pdfium::span<const uint8_t> last_span,
                    size_t row_size,
                    uint32_t bytes_per_pixel);

void TiffPredictLine(pdfium::span<uint8_t> dest_span,
                     int bits_per_component,
                     int colors,
                     int columns);

}  // namespace fxcodec

#endif  // CORE_FXCODEC_IMAGE_PREDICTORS_H_
