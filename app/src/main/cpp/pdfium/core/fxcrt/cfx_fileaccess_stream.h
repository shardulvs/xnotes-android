// Copyright 2026 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CORE_FXCRT_CFX_FILEACCESS_STREAM_H_
#define CORE_FXCRT_CFX_FILEACCESS_STREAM_H_

#include <memory>

#include "core/fxcrt/fileaccess_iface.h"
#include "core/fxcrt/fx_stream.h"
#include "core/fxcrt/retain_ptr.h"

class CFX_FileAccessStream final : public IFX_SeekableStream {
 public:
  CONSTRUCT_VIA_MAKE_RETAIN;

  static RetainPtr<CFX_FileAccessStream> CreateFromFilename(
      const char* filename);

  // IFX_SeekableStream:
  FX_FILESIZE GetSize() override;
  bool IsEOF() override;
  FX_FILESIZE GetPosition() override;
  bool ReadBlockAtOffset(pdfium::span<uint8_t> buffer,
                         FX_FILESIZE offset) override;
  bool WriteBlock(pdfium::span<const uint8_t> buffer) override;
  bool Flush() override;

 private:
  explicit CFX_FileAccessStream(std::unique_ptr<FileAccessIface> pFA);
  ~CFX_FileAccessStream() override;

  std::unique_ptr<FileAccessIface> file_;
};

#endif  // CORE_FXCRT_CFX_FILEACCESS_STREAM_H_
