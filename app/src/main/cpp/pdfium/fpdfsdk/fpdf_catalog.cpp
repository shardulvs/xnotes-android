// Copyright 2017 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "public/fpdf_catalog.h"

#include "core/fpdfapi/parser/cpdf_dictionary.h"
#include "core/fpdfapi/parser/cpdf_document.h"
#include "core/fpdfapi/parser/cpdf_string.h"
#include "core/fxcrt/compiler_specific.h"
#include "core/fxcrt/retain_ptr.h"
#include "core/fxcrt/widestring.h"
#include "fpdfsdk/cpdfsdk_helpers.h"

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFCatalog_IsTagged(FPDF_DOCUMENT document) {
  CPDF_Document* doc = CPDFDocumentFromFPDFDocument(document);
  if (!doc) {
    return false;
  }

  const CPDF_Dictionary* pCatalog = doc->GetRoot();
  if (!pCatalog) {
    return false;
  }

  RetainPtr<const CPDF_Dictionary> pMarkInfo = pCatalog->GetDictFor("MarkInfo");
  return pMarkInfo && pMarkInfo->GetIntegerFor("Marked") != 0;
}

FPDF_EXPORT unsigned long FPDF_CALLCONV
FPDFCatalog_GetLanguage(FPDF_DOCUMENT document,
                        FPDF_WCHAR* buffer,
                        unsigned long buflen) {
  CPDF_Document* doc = CPDFDocumentFromFPDFDocument(document);
  if (!doc) {
    return 0;
  }

  const CPDF_Dictionary* catalog = doc->GetRoot();
  if (!catalog) {
    return 0;
  }

  // SAFETY: required from caller.
  return Utf16EncodeMaybeCopyAndReturnLength(
      catalog->GetUnicodeTextFor("Lang"),
      UNSAFE_BUFFERS(SpanFromFPDFApiArgs(buffer, buflen)));
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFCatalog_SetLanguage(FPDF_DOCUMENT document, FPDF_WIDESTRING language) {
  if (!language) {
    return false;
  }

  CPDF_Document* doc = CPDFDocumentFromFPDFDocument(document);
  if (!doc) {
    return false;
  }

  RetainPtr<CPDF_Dictionary> catalog = doc->GetMutableRoot();
  if (!catalog) {
    return false;
  }

  // SAFETY: required from caller.
  catalog->SetNewFor<CPDF_String>(
      "Lang",
      UNSAFE_BUFFERS(WideStringFromFPDFWideString(language).AsStringView()));
  return true;
}
