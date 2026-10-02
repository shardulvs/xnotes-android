// Copyright 2014 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef CORE_FXCODEC_JBIG2_JBIG2_IMAGE_H_
#define CORE_FXCODEC_JBIG2_JBIG2_IMAGE_H_

#include <stddef.h>
#include <stdint.h>

#include <memory>
#include <optional>

#include "core/fxcodec/jbig2/jbig2_define.h"
#include "core/fxcrt/maybe_owned_data_vector.h"
#include "core/fxcrt/span.h"

struct FX_RECT;

enum JBig2ComposeOp {
  JBIG2_COMPOSE_OR = 0,
  JBIG2_COMPOSE_AND = 1,
  JBIG2_COMPOSE_XOR = 2,
  JBIG2_COMPOSE_XNOR = 3,
  JBIG2_COMPOSE_REPLACE = 4
};

class CJBig2_Image {
 public:
  CJBig2_Image(int32_t w, int32_t h);
  CJBig2_Image(int32_t w,
               int32_t h,
               int32_t stride,
               pdfium::span<uint8_t> pBuf);
  CJBig2_Image(const CJBig2_Image& other);
  ~CJBig2_Image();

  static bool IsValidImageSize(int32_t w, int32_t h);

  int32_t width() const { return width_; }
  int32_t height() const { return height_; }
  int32_t stride() const { return stride_; }

  bool has_data() const { return !data_.span().empty(); }

  pdfium::span<const uint8_t> span() const;
  pdfium::span<uint8_t> span();

  // Gets / Sets pixels at position `x` in `line`. Assumes `line` came from
  // GetLine() from the same instance.
  // - GetPixel() returns 0 if `x` is out of bounds.
  // - SetPixel() is a no-op if `x` is out of bounds`.
  int GetPixel(int32_t x, pdfium::span<const uint8_t> line) const;
  void SetPixel(int32_t x, pdfium::span<uint8_t> line, int v);

  // Returns an empty span if `y` is out of bounds, or if there is no data.
  pdfium::span<const uint8_t> GetLine(int32_t y) const;
  pdfium::span<uint8_t> GetLine(int32_t y);

  void CopyLine(pdfium::span<uint8_t> dest, pdfium::span<const uint8_t> src);
  void Fill(bool v);

  bool ComposeFrom(int64_t x, int64_t y, CJBig2_Image* pSrc, JBig2ComposeOp op);
  bool ComposeFromWithRect(int64_t x,
                           int64_t y,
                           CJBig2_Image* pSrc,
                           const FX_RECT& rtSrc,
                           JBig2ComposeOp op);

  std::unique_ptr<CJBig2_Image> SubImage(int32_t x,
                                         int32_t y,
                                         int32_t w,
                                         int32_t h) const;
  void Expand(int32_t h, bool v);

  bool ComposeTo(CJBig2_Image* pDst, int64_t x, int64_t y, JBig2ComposeOp op);

 private:
  std::optional<size_t> GetLineOffset(int32_t y) const;

  // Returns an empty span if `y`, `count`, or `y + count` is out of bounds, or
  // if there is no data.
  pdfium::span<const uint32_t> GetLines32(int32_t y, int32_t count) const;
  pdfium::span<uint32_t> GetLines32(int32_t y, int32_t count);

  // Returns stride in terms of number of uint32_t.
  // No rounding issues since `stride_` is a multiple of 4.
  uint32_t stride32() const { return stride_ / 4; }

  bool ComposeToWithRect(CJBig2_Image* pDst,
                         int64_t x,
                         int64_t y,
                         const FX_RECT& rtSrc,
                         JBig2ComposeOp op);

  // SubImage() already checked `x` and `y` are valid.
  void SubImageFast(uint32_t x,
                    uint32_t y,
                    int32_t w,
                    int32_t h,
                    CJBig2_Image* image) const;
  void SubImageSlow(uint32_t x,
                    uint32_t y,
                    int32_t w,
                    int32_t h,
                    CJBig2_Image* image) const;
  bool ComposeToInternal(CJBig2_Image* pDst,
                         int64_t x_in,
                         int64_t y_in,
                         JBig2ComposeOp op,
                         const FX_RECT& rtSrc);

  MaybeOwnedDataVector<uint8_t> data_;
  int32_t width_ = 0;   // 1-bit pixels
  int32_t height_ = 0;  // lines
  int32_t stride_ = 0;  // bytes, must be multiple of 4.
};

#endif  // CORE_FXCODEC_JBIG2_JBIG2_IMAGE_H_
