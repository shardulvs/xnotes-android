// Copyright 2026 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CONSTANTS_CATALOG_H_
#define CONSTANTS_CATALOG_H_

namespace pdfium::catalog {

// ISO 32000-1:2008 spec, table 28.
// Entries in the catalog dictionary.
inline constexpr char kVersion[] = "Version";
inline constexpr char kPages[] = "Pages";
inline constexpr char kPageLabels[] = "PageLabels";
inline constexpr char kNames[] = "Names";
inline constexpr char kDests[] = "Dests";
inline constexpr char kViewerPreferences[] = "ViewerPreferences";
inline constexpr char kOutlines[] = "Outlines";
inline constexpr char kAcroForm[] = "AcroForm";

}  // namespace pdfium::catalog

#endif  // CONSTANTS_CATALOG_H_
