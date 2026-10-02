// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxcrt/bytestring_pool.h"

namespace fxcrt {

ByteStringPool::ByteStringPool() = default;

ByteStringPool::~ByteStringPool() = default;

ByteString ByteStringPool::Intern(const ByteString& str) {
  if (str.IsEmpty()) {
    return str;
  }
  return *pool_.insert(str).first;
}

}  // namespace fxcrt
