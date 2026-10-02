// Copyright 2026 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CORE_FXCRT_MAYBE_OWNED_DATA_VECTOR_H_
#define CORE_FXCRT_MAYBE_OWNED_DATA_VECTOR_H_

#include <utility>

#include "core/fxcrt/check.h"
#include "core/fxcrt/data_vector.h"
#include "core/fxcrt/span.h"

namespace fxcrt {

template <typename T>
class MaybeOwnedDataVector {
 public:
  using OwnedType = DataVector<T>;
  using UnownedType = pdfium::span<T>;
  using ConstUnownedType = pdfium::span<const T>;

  MaybeOwnedDataVector() = default;
  explicit MaybeOwnedDataVector(OwnedType data)
      : data_(std::move(data)), span_(data_) {}
  explicit MaybeOwnedDataVector(UnownedType span) : span_(span) {}

  MaybeOwnedDataVector(const MaybeOwnedDataVector& that) : data_(that.data_) {
    if (IsOwned()) {
      span_ = data_;
    } else {
      span_ = that.span_;
    }
  }

  MaybeOwnedDataVector& operator=(const MaybeOwnedDataVector& that) {
    if (this != &that) {
      data_ = that.data_;
      if (IsOwned()) {
        span_ = data_;
      } else {
        span_ = that.span_;
      }
    }
    return *this;
  }

  MaybeOwnedDataVector(MaybeOwnedDataVector&& that) noexcept
      : data_(std::move(that.data_)) {
    if (IsOwned()) {
      span_ = data_;
    } else {
      span_ = that.span_;
    }
    that.span_ = {};
  }

  MaybeOwnedDataVector& operator=(MaybeOwnedDataVector&& that) noexcept {
    if (this != &that) {
      data_ = std::move(that.data_);
      if (IsOwned()) {
        span_ = data_;
      } else {
        span_ = that.span_;
      }
      that.span_ = {};
    }
    return *this;
  }

  ~MaybeOwnedDataVector() = default;

  UnownedType span() { return span_; }
  ConstUnownedType span() const { return span_; }

  bool IsOwned() const { return !data_.empty(); }

  void ResizeOwned(size_t new_size) {
    CHECK(IsOwned());
    span_ = {};
    data_.resize(new_size);
    span_ = data_;
  }

 private:
  OwnedType data_;
  UnownedType span_;
};

}  // namespace fxcrt

using fxcrt::MaybeOwnedDataVector;

#endif  // CORE_FXCRT_MAYBE_OWNED_DATA_VECTOR_H_
