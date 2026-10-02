// Copyright 2014 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef CORE_FXCODEC_FX_CODEC_DEF_H_
#define CORE_FXCODEC_FX_CODEC_DEF_H_

enum class FXCODEC_STATUS {
  kError = -1,
  kFrameReady,
  kFrameToBeContinued,
  kDecodeReady,
  kDecodeToBeContinued,
  kDecodeFinished,
};

#ifdef PDF_ENABLE_XFA
enum FXCODEC_IMAGE_TYPE {
  FXCODEC_IMAGE_UNKNOWN = 0,
  FXCODEC_IMAGE_JPG,
  FXCODEC_IMAGE_BMP,
  FXCODEC_IMAGE_PNG,
  FXCODEC_IMAGE_GIF,
  FXCODEC_IMAGE_TIFF,
  FXCODEC_IMAGE_MAX
};
#endif  // PDF_ENABLE_XFA

#endif  // CORE_FXCODEC_FX_CODEC_DEF_H_
