// Copyright 2017 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxcrt/fx_stream.h"

bool IFX_WriteStream::WriteString(ByteStringView str) {
  return WriteBlock(str.unsigned_span());
}

bool IFX_WriteStream::WriteByte(uint8_t byte) {
  return WriteBlock(pdfium::byte_span_from_ref(byte));
}

bool IFX_WriteStream::WriteDWord(uint32_t i) {
  char buf[20] = {};
  FXSYS_itoa(i, buf, 10);
  auto buf_span = pdfium::as_byte_span(buf);
  // SAFETY: itoa() terminates buf.
  return WriteBlock(buf_span.first(UNSAFE_BUFFERS(strlen(buf))));
}

bool IFX_WriteStream::WriteFilesize(FX_FILESIZE size) {
  char buf[20] = {};
  FXSYS_i64toa(size, buf, 10);
  auto buf_span = pdfium::as_byte_span(buf);
  // SAFETY: itoa() terminates buf.
  return WriteBlock(buf_span.first(UNSAFE_BUFFERS(strlen(buf))));
}

bool IFX_SeekableReadStream::IsEOF() {
  return false;
}

FX_FILESIZE IFX_SeekableReadStream::GetPosition() {
  return 0;
}
