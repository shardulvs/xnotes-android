// Copyright 2026 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "core/fxcrt/cfx_read_only_container_stream.h"

#include "build/build_config.h"

// Instantiate.
template class CFX_ReadOnlyContainerStream<DataVector<uint8_t>>;
template class CFX_ReadOnlyContainerStream<FixedSizeDataVector<uint8_t>>;
template class CFX_ReadOnlyContainerStream<ByteString>;

#if BUILDFLAG(IS_POSIX)
template class CFX_ReadOnlyContainerStream<std::unique_ptr<MappedDataBytes>>;
#endif
