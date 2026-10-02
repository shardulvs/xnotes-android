// Copyright 2026 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CORE_FXCRT_SPAN_IO_H_
#define CORE_FXCRT_SPAN_IO_H_

#include <stdio.h>

#include <utility>

#include "build/build_config.h"
#include "core/fxcrt/compiler_specific.h"
#include "core/fxcrt/numerics/safe_conversions.h"
#include "core/fxcrt/span.h"

#if BUILDFLAG(IS_POSIX)
#include <unistd.h>
#endif

#if BUILDFLAG(IS_WIN)
#include <windows.h>
#endif

namespace fxcrt {

// Bounds-checked reads from a file into a container. Returns a span describing
// the portion of the container that was actually read.
template <typename Container,
          typename T = typename decltype(pdfium::span(
              std::declval<Container>()))::element_type>
inline pdfium::span<T> spanread(Container&& dst, FILE* file) {
  auto s = pdfium::span(std::forward<Container>(dst));
  if (s.empty()) {
    return {};
  }
  // SAFETY: fread writes at most `s.size()` elements of size `sizeof(T)` into
  // `s.data()`. `s` guarantees `s.data()` has at least `s.size()` elements.
  size_t count = UNSAFE_BUFFERS(fread(s.data(), sizeof(T), s.size(), file));
  return s.first(count);
}

// Bounds-checked writes from a container into a file. Returns the number of
// elements successfully written.
template <typename Container>
inline size_t spanwrite(const Container& src, FILE* file) {
  auto s = pdfium::span(src);
  if (s.empty()) {
    return 0;
  }
  using T = typename decltype(s)::element_type;
  // SAFETY: fwrite reads at most `s.size()` elements of size `sizeof(T)` from
  // `src.data()`. `s` guarantees `s.data()` has at least `s.size()` elements.
  return UNSAFE_BUFFERS(fwrite(s.data(), sizeof(T), s.size(), file));
}

#if BUILDFLAG(IS_POSIX)
// Safe wrapper for POSIX read() using spans.
// Returns a subspan of `dst` containing the bytes actually read, or an empty
// span on error or EOF.
template <typename Container,
          typename T = typename decltype(pdfium::span(
              std::declval<Container>()))::element_type>
inline pdfium::span<T> spanread(Container&& dst, int fd) {
  auto s = pdfium::span(std::forward<Container>(dst));
  if (s.empty()) {
    return {};
  }
  // SAFETY: read writes at most `s.size_bytes()` bytes into `s.data()`.
  // `s` guarantees `s.data()` has at least `s.size_bytes()` bytes.
  ssize_t bytes_read = UNSAFE_BUFFERS(read(fd, s.data(), s.size_bytes()));
  if (bytes_read <= 0) {
    return {};
  }
  size_t elements_read = static_cast<size_t>(bytes_read) / sizeof(T);
  return s.first(elements_read);
}

// Safe wrapper for POSIX write() using spans.
// Returns the number of elements successfully written.
template <typename Container>
inline size_t spanwrite(const Container& src, int fd) {
  auto s = pdfium::span(src);
  if (s.empty()) {
    return 0;
  }
  using T = typename decltype(s)::element_type;
  // SAFETY: write reads at most `s.size_bytes()` bytes from `s.data()`.
  ssize_t bytes_written = UNSAFE_BUFFERS(write(fd, s.data(), s.size_bytes()));
  if (bytes_written <= 0) {
    return 0;
  }
  return static_cast<size_t>(bytes_written) / sizeof(T);
}
#endif  // BUILDFLAG(IS_POSIX)

#if BUILDFLAG(IS_WIN)
// Safe wrapper for Windows ReadFile() using spans.
// Returns a subspan of `dst` containing the bytes actually read, or an empty
// span on error or EOF.
template <typename Container,
          typename T = typename decltype(pdfium::span(
              std::declval<Container>()))::element_type>
inline pdfium::span<T> spanread(Container&& dst, HANDLE handle) {
  auto s = pdfium::span(std::forward<Container>(dst));
  if (s.empty()) {
    return {};
  }
  DWORD bytes_read = 0;
  // SAFETY: ReadFile writes at most `s.size_bytes()` bytes into `s.data()`.
  // `s` guarantees `s.data()` has at least `s.size_bytes()` bytes.
  if (!::ReadFile(handle, s.data(), pdfium::checked_cast<DWORD>(s.size_bytes()),
                  &bytes_read, nullptr)) {
    return {};
  }
  size_t elements_read = static_cast<size_t>(bytes_read) / sizeof(T);
  return s.first(elements_read);
}

// Safe wrapper for Windows WriteFile() using spans.
// Returns the number of elements successfully written.
template <typename Container>
inline size_t spanwrite(const Container& src, HANDLE handle) {
  auto s = pdfium::span(src);
  if (s.empty()) {
    return 0;
  }
  using T = typename decltype(s)::element_type;
  DWORD bytes_written = 0;
  // SAFETY: WriteFile reads at most `s.size_bytes()` bytes from `s.data()`.
  // `s` guarantees `s.data()` has at least `s.size_bytes()` bytes.
  if (!::WriteFile(handle, s.data(),
                   pdfium::checked_cast<DWORD>(s.size_bytes()), &bytes_written,
                   nullptr)) {
    return 0;
  }
  return static_cast<size_t>(bytes_written) / sizeof(T);
}
#endif  // BUILDFLAG(IS_WIN)

}  // namespace fxcrt

#endif  // CORE_FXCRT_SPAN_IO_H_
