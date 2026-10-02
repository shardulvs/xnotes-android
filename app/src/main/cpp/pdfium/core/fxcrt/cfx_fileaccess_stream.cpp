// Copyright 2026 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxcrt/cfx_fileaccess_stream.h"

#include <utility>

// static
RetainPtr<CFX_FileAccessStream> CFX_FileAccessStream::CreateFromFilename(
    const char* filename) {
  std::unique_ptr<FileAccessIface> file_access = FileAccessIface::Create();
  if (!file_access->Open(filename)) {
    return nullptr;
  }
  return pdfium::MakeRetain<CFX_FileAccessStream>(std::move(file_access));
}

CFX_FileAccessStream::CFX_FileAccessStream(std::unique_ptr<FileAccessIface> pFA)
    : file_(std::move(pFA)) {}

CFX_FileAccessStream::~CFX_FileAccessStream() = default;

FX_FILESIZE CFX_FileAccessStream::GetSize() {
  return file_->GetSize();
}

bool CFX_FileAccessStream::IsEOF() {
  return GetPosition() >= GetSize();
}

FX_FILESIZE CFX_FileAccessStream::GetPosition() {
  return file_->GetPosition();
}

bool CFX_FileAccessStream::ReadBlockAtOffset(pdfium::span<uint8_t> buffer,
                                             FX_FILESIZE offset) {
  return file_->ReadPos(buffer, offset) > 0;
}

bool CFX_FileAccessStream::WriteBlock(pdfium::span<const uint8_t> buffer) {
  if (file_->SetPosition(GetSize()) == static_cast<FX_FILESIZE>(-1)) {
    return false;
  }
  return file_->Write(buffer);
}

bool CFX_FileAccessStream::Flush() {
  return file_->Flush();
}
