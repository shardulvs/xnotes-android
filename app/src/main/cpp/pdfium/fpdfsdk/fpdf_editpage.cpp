// Copyright 2014 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "public/fpdf_edit.h"

#include <algorithm>
#include <cstdlib>
#include <memory>
#include <utility>
#include <vector>

#include "constants/page_object.h"
#include "core/fpdfapi/edit/cpdf_pagecontentgenerator.h"
#include "core/fpdfapi/page/cpdf_colorspace.h"
#include "core/fpdfapi/page/cpdf_docpagedata.h"
#include "core/fpdfapi/page/cpdf_form.h"
#include "core/fpdfapi/page/cpdf_formobject.h"
#include "core/fpdfapi/page/cpdf_imageobject.h"
#include "core/fpdfapi/page/cpdf_page.h"
#include "core/fpdfapi/page/cpdf_pageimagecache.h"
#include "core/fpdfapi/page/cpdf_pageobject.h"
#include "core/fpdfapi/page/cpdf_pathobject.h"
#include "core/fpdfapi/page/cpdf_shadingobject.h"
#include "core/fpdfapi/page/cpdf_textobject.h"
#include "core/fpdfapi/page/cpdf_tilingpattern.h"
#include "core/fpdfapi/parser/cpdf_array.h"
#include "core/fpdfapi/parser/cpdf_dictionary.h"
#include "core/fpdfapi/parser/cpdf_document.h"
#include "core/fpdfapi/parser/cpdf_number.h"
#include "core/fpdfapi/parser/cpdf_string.h"
#include "core/fpdfapi/render/cpdf_docrenderdata.h"
#include "core/fpdfapi/render/cpdf_renderoptions.h"
#include "core/fpdfapi/render/cpdf_rendertiling.h"
#include "core/fpdfdoc/cpdf_annot.h"
#include "core/fpdfdoc/cpdf_annotlist.h"
#include "core/fxcrt/compiler_specific.h"
#include "core/fxcrt/fx_extension.h"
#include "core/fxcrt/notreached.h"
#include "core/fxcrt/numerics/safe_conversions.h"
#include "core/fxcrt/retain_ptr.h"
#include "core/fxcrt/span.h"
#include "core/fxcrt/span_util.h"
#include "core/fxcrt/stl_util.h"
#include "core/fxcrt/unowned_ptr.h"
#include "core/fxge/dib/cfx_dibitmap.h"
#include "fpdfsdk/cpdfsdk_helpers.h"
#include "public/fpdf_formfill.h"

#ifdef PDF_ENABLE_XFA
#include "fpdfsdk/fpdfxfa/cpdfxfa_context.h"
#include "fpdfsdk/fpdfxfa/cpdfxfa_page.h"
#endif  // PDF_ENABLE_XFA

namespace {

static_assert(FPDF_PAGEOBJ_TEXT ==
                  static_cast<int>(CPDF_PageObject::Type::kText),
              "FPDF_PAGEOBJ_TEXT/CPDF_PageObject::TEXT mismatch");
static_assert(FPDF_PAGEOBJ_PATH ==
                  static_cast<int>(CPDF_PageObject::Type::kPath),
              "FPDF_PAGEOBJ_PATH/CPDF_PageObject::PATH mismatch");
static_assert(FPDF_PAGEOBJ_IMAGE ==
                  static_cast<int>(CPDF_PageObject::Type::kImage),
              "FPDF_PAGEOBJ_IMAGE/CPDF_PageObject::IMAGE mismatch");
static_assert(FPDF_PAGEOBJ_SHADING ==
                  static_cast<int>(CPDF_PageObject::Type::kShading),
              "FPDF_PAGEOBJ_SHADING/CPDF_PageObject::SHADING mismatch");
static_assert(FPDF_PAGEOBJ_FORM ==
                  static_cast<int>(CPDF_PageObject::Type::kForm),
              "FPDF_PAGEOBJ_FORM/CPDF_PageObject::FORM mismatch");

bool IsPageObject(CPDF_Page* page) {
  return page && CPDF_Page::IsValidPageDict(page->GetDict());
}

void CalcBoundingBox(CPDF_PageObject* pPageObj) {
  switch (pPageObj->GetType()) {
    case CPDF_PageObject::Type::kText: {
      break;
    }
    case CPDF_PageObject::Type::kPath: {
      CPDF_PathObject* pPathObj = pPageObj->AsPath();
      pPathObj->CalcBoundingBox();
      break;
    }
    case CPDF_PageObject::Type::kImage: {
      CPDF_ImageObject* pImageObj = pPageObj->AsImage();
      pImageObj->CalcBoundingBox();
      break;
    }
    case CPDF_PageObject::Type::kShading: {
      CPDF_ShadingObject* pShadingObj = pPageObj->AsShading();
      pShadingObj->CalcBoundingBox();
      break;
    }
    case CPDF_PageObject::Type::kForm: {
      CPDF_FormObject* pFormObj = pPageObj->AsForm();
      pFormObj->CalcBoundingBox();
      break;
    }
  }
}

RetainPtr<CPDF_Dictionary> GetMarkParamDict(FPDF_PAGEOBJECTMARK mark) {
  CPDF_ContentMarkItem* pMarkItem =
      CPDFContentMarkItemFromFPDFPageObjectMark(mark);
  return pMarkItem ? pMarkItem->GetParam() : nullptr;
}

RetainPtr<CPDF_Dictionary> GetOrCreateMarkParamsDict(FPDF_DOCUMENT document,
                                                     FPDF_PAGEOBJECTMARK mark) {
  CPDF_Document* doc = CPDFDocumentFromFPDFDocument(document);
  if (!doc) {
    return nullptr;
  }

  CPDF_ContentMarkItem* pMarkItem =
      CPDFContentMarkItemFromFPDFPageObjectMark(mark);
  if (!pMarkItem) {
    return nullptr;
  }

  RetainPtr<CPDF_Dictionary> pParams = pMarkItem->GetParam();
  if (!pParams) {
    pParams = doc->New<CPDF_Dictionary>();
    pMarkItem->SetDirectDict(pParams);
  }
  return pParams;
}

bool PageObjectContainsMark(CPDF_PageObject* pPageObj,
                            FPDF_PAGEOBJECTMARK mark) {
  const CPDF_ContentMarkItem* pMarkItem =
      CPDFContentMarkItemFromFPDFPageObjectMark(mark);
  return pMarkItem && pPageObj->GetContentMarks()->ContainsItem(pMarkItem);
}

CPDF_FormObject* CPDFFormObjectFromFPDFPageObject(FPDF_PAGEOBJECT page_object) {
  auto* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  return pPageObj ? pPageObj->AsForm() : nullptr;
}

const CPDF_PageObjectHolder* CPDFPageObjHolderFromFPDFFormObject(
    FPDF_PAGEOBJECT page_object) {
  CPDF_FormObject* pFormObject = CPDFFormObjectFromFPDFPageObject(page_object);
  return pFormObject ? pFormObject->form() : nullptr;
}

RetainPtr<const CPDF_Object> GetParamObject(void* out_value,
                                            FPDF_PAGEOBJECTMARK mark,
                                            FPDF_BYTESTRING key) {
  if (!out_value) {
    return nullptr;
  }

  RetainPtr<const CPDF_Dictionary> params = GetMarkParamDict(mark);
  return params ? params->GetObjectFor(key) : nullptr;
}

struct ParamsAndObject {
  RetainPtr<CPDF_Dictionary> params;
  UnownedPtr<CPDF_PageObject> page_obj;
};

ParamsAndObject SetParamValueHelper(FPDF_DOCUMENT document,
                                    FPDF_PAGEOBJECT page_object,
                                    FPDF_PAGEOBJECTMARK mark) {
  CPDF_PageObject* page_obj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!page_obj || !PageObjectContainsMark(page_obj, mark)) {
    return {nullptr, nullptr};
  }
  RetainPtr<CPDF_Dictionary> params = GetOrCreateMarkParamsDict(document, mark);
  return {params, page_obj};
}

ByteString FormatPDFDate(time_t current_time, const tm& local_time) {
  ByteString date = ByteString::Format(
      "D:%04d%02d%02d%02d%02d%02d", local_time.tm_year + 1900,
      local_time.tm_mon + 1, local_time.tm_mday, local_time.tm_hour,
      local_time.tm_min, local_time.tm_sec);

  tm* utc_time = gmtime(&current_time);
  if (!utc_time) {
    return date;
  }

  constexpr int kMinutesPerHour = 60;
  const int offset_minutes =
      FXSYS_TimeZoneOffsetInMinutes(local_time, *utc_time);
  const int abs_offset_minutes = std::abs(offset_minutes);
  date += ByteString::Format("%c%02d'%02d'", offset_minutes < 0 ? '-' : '+',
                             abs_offset_minutes / kMinutesPerHour,
                             abs_offset_minutes % kMinutesPerHour);
  return date;
}

FPDF_BITMAP RenderTilingPatternToBitmap(CPDF_Pattern* pattern,
                                        CPDF_Document* doc,
                                        CPDF_PageObject* page_obj,
                                        bool use_stroke_color) {
  CPDF_TilingPattern* tiling_pattern = pattern->AsTilingPattern();
  if (!tiling_pattern) {
    return nullptr;
  }
  const std::unique_ptr<CPDF_Form> pattern_form =
      tiling_pattern->Load(page_obj);
  if (!pattern_form) {
    return nullptr;
  }
  const FX_RECT rect = tiling_pattern->bbox().GetOuterRect();
  if (rect.IsEmpty() || !rect.Valid()) {
    return nullptr;
  }

  RetainPtr<CFX_DIBitmap> cell_bitmap = CPDF_RenderTiling::DrawPatternBitmap(
      doc, /*pCache=*/nullptr, tiling_pattern, pattern_form.get(), CFX_Matrix(),
      rect.Width(), rect.Height(), CPDF_RenderOptions::Options());
  if (!cell_bitmap) {
    return nullptr;
  }

  if (tiling_pattern->colored()) {
    ValidateBitmapPremultiplyState(cell_bitmap);

    // Caller takes ownership.
    return FPDFBitmapFromCFXDIBitmap(cell_bitmap.Leak());
  }

  // An uncolored pattern gets its color from the stroke/fill color operands.
  const CPDF_ColorState& color_state = page_obj->color_state();
  const FX_COLORREF colorref =
      use_stroke_color ? color_state.GetStrokeColorRef()
                       : color_state.GetFillColorRef();
  // 0xFFFFFFFF is the unusable color, see FX_COLORREF.
  if (colorref == 0xFFFFFFFF) {
    return nullptr;
  }

  auto result_bitmap = pdfium::MakeRetain<CFX_DIBitmap>();
  if (!result_bitmap->Create(rect.Width(), rect.Height(),
                             FXDIB_Format::kBgra)) {
    return nullptr;
  }
  const CPDF_GeneralState& general_state = page_obj->general_state();
  const float float_alpha = use_stroke_color
                                ? general_state.GetStrokeAlpha()
                                : general_state.GetFillAlpha();
  const int alpha = static_cast<int>(float_alpha * 255);
  if (!result_bitmap->CompositeMask(
          /*dest_left=*/0, /*dest_top=*/0, rect.Width(), rect.Height(),
          cell_bitmap, AlphaAndColorRefToArgb(alpha, colorref),
          /*src_left=*/0, /*src_top=*/0, BlendMode::kNormal)) {
    return nullptr;
  }

  ValidateBitmapPremultiplyState(result_bitmap);

  // Caller takes ownership.
  return FPDFBitmapFromCFXDIBitmap(result_bitmap.Leak());
}

FPDF_BITMAP GetRenderedPattern(FPDF_DOCUMENT document,
                               FPDF_PAGEOBJECT page_object,
                               bool use_stroke_color) {
  CPDF_Document* doc = CPDFDocumentFromFPDFDocument(document);
  if (!doc) {
    return nullptr;
  }

  CPDF_PageObject* object = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!object) {
    return nullptr;
  }

  const CPDF_ColorState& color_state = object->color_state();
  const CPDF_Color* color = use_stroke_color
                                ? color_state.GetStrokeColor()
                                : color_state.GetFillColor();
  if (!color || !color->IsPattern()) {
    return nullptr;
  }

  RetainPtr<CPDF_Pattern> pattern = color->GetPattern();
  if (!pattern) {
    return nullptr;
  }

  return RenderTilingPatternToBitmap(pattern.Get(), doc, object,
                                     use_stroke_color);
}

}  // namespace

FPDF_EXPORT FPDF_DOCUMENT FPDF_CALLCONV FPDF_CreateNewDocument() {
  auto doc =
      std::make_unique<CPDF_Document>(std::make_unique<CPDF_DocRenderData>(),
                                      std::make_unique<CPDF_DocPageData>());
  doc->CreateNewDoc();

  time_t currentTime;
  ByteString DateStr;
  if (IsPDFSandboxPolicyEnabled(FPDF_POLICY_MACHINETIME_ACCESS)) {
    if (FXSYS_time(&currentTime) != -1) {
      tm* pTM = FXSYS_localtime(&currentTime);
      if (pTM) {
        DateStr = FormatPDFDate(currentTime, *pTM);
      }
    }
  }

  RetainPtr<CPDF_Dictionary> pInfoDict = doc->GetInfo();
  if (pInfoDict) {
    if (IsPDFSandboxPolicyEnabled(FPDF_POLICY_MACHINETIME_ACCESS)) {
      pInfoDict->SetNewFor<CPDF_String>("CreationDate", DateStr);
    }
    pInfoDict->SetNewFor<CPDF_String>("Creator", L"PDFium");
  }

  // Caller takes ownership of doc.
  return FPDFDocumentFromCPDFDocument(doc.release());
}

FPDF_EXPORT void FPDF_CALLCONV FPDFPage_Delete(FPDF_DOCUMENT document,
                                               int page_index) {
  auto* doc = CPDFDocumentFromFPDFDocument(document);
  if (!doc) {
    return;
  }

  CPDF_Document::Extension* pExtension = doc->GetExtension();
  const uint32_t page_obj_num = pExtension ? pExtension->DeletePage(page_index)
                                           : doc->DeletePage(page_index);
  doc->SetPageToNullObject(page_obj_num);
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDF_MovePages(FPDF_DOCUMENT document,
               const int* page_indices,
               unsigned long page_indices_len,
               int dest_page_index) {
  auto* doc = CPDFDocumentFromFPDFDocument(document);
  if (!doc) {
    return false;
  }

  // SAFETY: caller ensures `page_indices` points to at least
  // `page_indices_len` ints.
  return doc->MovePages(
      UNSAFE_BUFFERS(pdfium::span(page_indices, page_indices_len)),
      dest_page_index);
}

FPDF_EXPORT FPDF_PAGE FPDF_CALLCONV FPDFPage_New(FPDF_DOCUMENT document,
                                                 int page_index,
                                                 double width,
                                                 double height) {
  CPDF_Document* doc = CPDFDocumentFromFPDFDocument(document);
  if (!doc) {
    return nullptr;
  }

  page_index = std::clamp(page_index, 0, doc->GetPageCount());
  RetainPtr<CPDF_Dictionary> page_dict(doc->CreateNewPage(page_index));
  if (!page_dict) {
    return nullptr;
  }

  page_dict->SetRectFor(pdfium::page_object::kMediaBox,
                        CFX_FloatRect(0, 0, width, height));
  page_dict->SetNewFor<CPDF_Number>(pdfium::page_object::kRotate, 0);
  page_dict->SetNewFor<CPDF_Dictionary>(pdfium::page_object::kResources);

#ifdef PDF_ENABLE_XFA
  if (doc->GetExtension()) {
    auto xfa_page = pdfium::MakeRetain<CPDFXFA_Page>(doc, page_index);
    CHECK(xfa_page->LoadPDFPageFromDict(std::move(page_dict)));
    return FPDFPageFromIPDFPage(xfa_page.Leak());  // Caller takes ownership.
  }
#endif  // PDF_ENABLE_XFA

  auto pPage = pdfium::MakeRetain<CPDF_Page>(doc, std::move(page_dict));
  pPage->AddPageImageCache();
  pPage->ParseContent();

  return FPDFPageFromIPDFPage(pPage.Leak());  // Caller takes ownership.
}

FPDF_EXPORT int FPDF_CALLCONV FPDFPage_GetRotation(FPDF_PAGE page) {
  CPDF_Page* pPage = CPDFPageFromFPDFPage(page);
  return IsPageObject(pPage) ? pPage->GetPageRotation() : -1;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPage_InsertObject(FPDF_PAGE page, FPDF_PAGEOBJECT page_object) {
  CPDF_PageObject* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!pPageObj) {
    return false;
  }

  std::unique_ptr<CPDF_PageObject> pPageObjHolder(pPageObj);
  CPDF_Page* pPage = CPDFPageFromFPDFPage(page);
  if (!IsPageObject(pPage)) {
    return false;
  }

  pPageObj->SetDirty(true);
  pPage->AppendPageObject(std::move(pPageObjHolder));
  CalcBoundingBox(pPageObj);
  return true;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPage_InsertObjectAtIndex(FPDF_PAGE page,
                             FPDF_PAGEOBJECT page_object,
                             size_t index) {
  CPDF_PageObject* cpage_object = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!cpage_object) {
    return false;
  }

  // Take ownership back from the embedder across the C API.
  std::unique_ptr<CPDF_PageObject> page_obj_holder(cpage_object);

  CPDF_Page* cpage = CPDFPageFromFPDFPage(page);
  if (!IsPageObject(cpage)) {
    return false;
  }

  cpage_object->SetDirty(true);
  CalcBoundingBox(cpage_object);

  return cpage->InsertPageObjectAtIndex(index, std::move(page_obj_holder));
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPage_RemoveObject(FPDF_PAGE page, FPDF_PAGEOBJECT page_object) {
  CPDF_PageObject* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!pPageObj) {
    return false;
  }

  CPDF_Page* pPage = CPDFPageFromFPDFPage(page);
  if (!IsPageObject(pPage)) {
    return false;
  }

  // Release ownership to the caller.
  return !!pPage->RemovePageObject(pPageObj).release();
}

FPDF_EXPORT int FPDF_CALLCONV FPDFPage_CountObjects(FPDF_PAGE page) {
  CPDF_Page* pPage = CPDFPageFromFPDFPage(page);
  if (!IsPageObject(pPage)) {
    return -1;
  }

  return pdfium::checked_cast<int>(pPage->GetPageObjectCount());
}

FPDF_EXPORT FPDF_PAGEOBJECT FPDF_CALLCONV FPDFPage_GetObject(FPDF_PAGE page,
                                                             int index) {
  CPDF_Page* pPage = CPDFPageFromFPDFPage(page);
  if (!IsPageObject(pPage)) {
    return nullptr;
  }

  return FPDFPageObjectFromCPDFPageObject(pPage->GetPageObjectByIndex(index));
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV FPDFPage_HasTransparency(FPDF_PAGE page) {
  CPDF_Page* pPage = CPDFPageFromFPDFPage(page);
  return pPage && pPage->BackgroundAlphaNeeded();
}

FPDF_EXPORT void FPDF_CALLCONV
FPDFPageObj_Destroy(FPDF_PAGEOBJECT page_object) {
  delete CPDFPageObjectFromFPDFPageObject(page_object);
}

FPDF_EXPORT int FPDF_CALLCONV
FPDFPageObj_GetMarkedContentID(FPDF_PAGEOBJECT page_object) {
  CPDF_PageObject* cpage_object = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!cpage_object) {
    return -1;
  }

  return cpage_object->GetContentMarks()->GetMarkedContentID();
}

FPDF_EXPORT int FPDF_CALLCONV
FPDFPageObj_CountMarks(FPDF_PAGEOBJECT page_object) {
  CPDF_PageObject* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!pPageObj) {
    return -1;
  }

  return pdfium::checked_cast<int>(pPageObj->GetContentMarks()->CountItems());
}

FPDF_EXPORT FPDF_PAGEOBJECTMARK FPDF_CALLCONV
FPDFPageObj_GetMark(FPDF_PAGEOBJECT page_object, unsigned long index) {
  CPDF_PageObject* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!pPageObj) {
    return nullptr;
  }

  CPDF_ContentMarks* pMarks = pPageObj->GetContentMarks();
  if (index >= pMarks->CountItems()) {
    return nullptr;
  }

  return FPDFPageObjectMarkFromCPDFContentMarkItem(pMarks->GetItem(index));
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObj_AddExistingMark(FPDF_PAGEOBJECT page_object,
                            FPDF_PAGEOBJECTMARK mark) {
  CPDF_PageObject* page = CPDFPageObjectFromFPDFPageObject(page_object);
  CPDF_ContentMarkItem* mark_item =
      CPDFContentMarkItemFromFPDFPageObjectMark(mark);
  if (!page || !mark_item) {
    return false;
  }

  CPDF_ContentMarks* marks = page->GetContentMarks();
  marks->AddExistingMark(pdfium::WrapRetain(mark_item));
  page->SetDirty(true);
  return true;
}

FPDF_EXPORT FPDF_PAGEOBJECTMARK FPDF_CALLCONV
FPDFPageObj_AddMark(FPDF_PAGEOBJECT page_object, FPDF_BYTESTRING name) {
  CPDF_PageObject* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!pPageObj) {
    return nullptr;
  }

  CPDF_ContentMarks* pMarks = pPageObj->GetContentMarks();
  pMarks->AddMark(name);
  pPageObj->SetDirty(true);

  const size_t index = pMarks->CountItems() - 1;
  return FPDFPageObjectMarkFromCPDFContentMarkItem(pMarks->GetItem(index));
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObj_RemoveMark(FPDF_PAGEOBJECT page_object, FPDF_PAGEOBJECTMARK mark) {
  CPDF_PageObject* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  CPDF_ContentMarkItem* pMarkItem =
      CPDFContentMarkItemFromFPDFPageObjectMark(mark);
  if (!pPageObj || !pMarkItem) {
    return false;
  }

  if (!pPageObj->GetContentMarks()->RemoveMark(pMarkItem)) {
    return false;
  }

  pPageObj->SetDirty(true);
  return true;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObjMark_GetName(FPDF_PAGEOBJECTMARK mark,
                        FPDF_WCHAR* buffer,
                        unsigned long buflen,
                        unsigned long* out_buflen) {
  const CPDF_ContentMarkItem* pMarkItem =
      CPDFContentMarkItemFromFPDFPageObjectMark(mark);
  if (!pMarkItem || !out_buflen) {
    return false;
  }
  // SAFETY: required from caller.
  *out_buflen = Utf16EncodeMaybeCopyAndReturnLength(
      WideString::FromUTF8(pMarkItem->GetName().AsStringView()),
      UNSAFE_BUFFERS(SpanFromFPDFApiArgs(buffer, buflen)));
  return true;
}

FPDF_EXPORT int FPDF_CALLCONV
FPDFPageObjMark_CountParams(FPDF_PAGEOBJECTMARK mark) {
  const CPDF_ContentMarkItem* pMarkItem =
      CPDFContentMarkItemFromFPDFPageObjectMark(mark);
  if (!pMarkItem) {
    return -1;
  }

  RetainPtr<const CPDF_Dictionary> pParams = pMarkItem->GetParam();
  return pParams ? fxcrt::CollectionSize<int>(*pParams) : 0;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObjMark_GetParamKey(FPDF_PAGEOBJECTMARK mark,
                            unsigned long index,
                            FPDF_WCHAR* buffer,
                            unsigned long buflen,
                            unsigned long* out_buflen) {
  if (!out_buflen) {
    return false;
  }

  RetainPtr<const CPDF_Dictionary> pParams = GetMarkParamDict(mark);
  if (!pParams) {
    return false;
  }

  CPDF_DictionaryLocker locker(pParams);
  for (auto& it : locker) {
    if (index == 0) {
      // SAFETY: required from caller.
      *out_buflen = Utf16EncodeMaybeCopyAndReturnLength(
          WideString::FromUTF8(it.first.AsStringView()),
          UNSAFE_BUFFERS(SpanFromFPDFApiArgs(buffer, buflen)));
      return true;
    }
    --index;
  }

  return false;
}

FPDF_EXPORT FPDF_OBJECT_TYPE FPDF_CALLCONV
FPDFPageObjMark_GetParamValueType(FPDF_PAGEOBJECTMARK mark,
                                  FPDF_BYTESTRING key) {
  RetainPtr<const CPDF_Dictionary> pParams = GetMarkParamDict(mark);
  if (!pParams) {
    return FPDF_OBJECT_UNKNOWN;
  }

  RetainPtr<const CPDF_Object> pObject = pParams->GetObjectFor(key);
  return pObject ? pObject->GetType() : FPDF_OBJECT_UNKNOWN;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObjMark_GetParamIntValue(FPDF_PAGEOBJECTMARK mark,
                                 FPDF_BYTESTRING key,
                                 int* out_value) {
  RetainPtr<const CPDF_Object> pObj = GetParamObject(out_value, mark, key);
  if (!pObj || !pObj->IsNumber()) {
    return false;
  }

  *out_value = pObj->GetInteger();
  return true;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObjMark_GetParamFloatValue(FPDF_PAGEOBJECTMARK mark,
                                   FPDF_BYTESTRING key,
                                   float* out_value) {
  RetainPtr<const CPDF_Object> pObj = GetParamObject(out_value, mark, key);
  if (!pObj || !pObj->IsNumber()) {
    return false;
  }

  *out_value = pObj->GetNumber();
  return true;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObjMark_GetParamStringValue(FPDF_PAGEOBJECTMARK mark,
                                    FPDF_BYTESTRING key,
                                    FPDF_WCHAR* buffer,
                                    unsigned long buflen,
                                    unsigned long* out_buflen) {
  RetainPtr<const CPDF_Object> pObj = GetParamObject(out_buflen, mark, key);
  if (!pObj || !pObj->IsString()) {
    return false;
  }

  // SAFETY: required from caller.
  *out_buflen = Utf16EncodeMaybeCopyAndReturnLength(
      WideString::FromUTF8(pObj->GetString().AsStringView()),
      UNSAFE_BUFFERS(SpanFromFPDFApiArgs(buffer, buflen)));
  return true;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObjMark_GetParamBlobValue(FPDF_PAGEOBJECTMARK mark,
                                  FPDF_BYTESTRING key,
                                  unsigned char* buffer,
                                  unsigned long buflen,
                                  unsigned long* out_buflen) {
  RetainPtr<const CPDF_Object> pObj = GetParamObject(out_buflen, mark, key);
  if (!pObj || !pObj->IsString()) {
    return false;
  }

  // SAFETY: required from caller.
  auto result_span = UNSAFE_BUFFERS(SpanFromFPDFApiArgs(buffer, buflen));
  ByteString value = pObj->GetString();
  fxcrt::try_spancpy(result_span, value.span());
  *out_buflen = pdfium::checked_cast<unsigned long>(value.span().size());
  return true;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObj_HasTransparency(FPDF_PAGEOBJECT page_object) {
  CPDF_PageObject* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!pPageObj) {
    return false;
  }
  if (pPageObj->general_state().GetBlendType() != BlendMode::kNormal) {
    return true;
  }
  if (pPageObj->general_state().GetSoftMask()) {
    return true;
  }
  if (pPageObj->general_state().GetFillAlpha() != 1.0f) {
    return true;
  }
  if (pPageObj->IsPath() &&
      pPageObj->general_state().GetStrokeAlpha() != 1.0f) {
    return true;
  }
  if (!pPageObj->IsForm()) {
    return false;
  }

  const CPDF_Form* pForm = pPageObj->AsForm()->form();
  if (!pForm) {
    return false;
  }

  const CPDF_Transparency& trans = pForm->GetTransparency();
  return trans.IsGroup() || trans.IsIsolated();
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObjMark_SetIntParam(FPDF_DOCUMENT document,
                            FPDF_PAGEOBJECT page_object,
                            FPDF_PAGEOBJECTMARK mark,
                            FPDF_BYTESTRING key,
                            int value) {
  ParamsAndObject params_and_obj =
      SetParamValueHelper(document, page_object, mark);
  if (!params_and_obj.params || !params_and_obj.page_obj) {
    return false;
  }
  params_and_obj.params->SetNewFor<CPDF_Number>(key, value);
  params_and_obj.page_obj->SetDirty(true);
  return true;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObjMark_SetFloatParam(FPDF_DOCUMENT document,
                              FPDF_PAGEOBJECT page_object,
                              FPDF_PAGEOBJECTMARK mark,
                              FPDF_BYTESTRING key,
                              float value) {
  ParamsAndObject params_and_obj =
      SetParamValueHelper(document, page_object, mark);
  if (!params_and_obj.params || !params_and_obj.page_obj) {
    return false;
  }
  params_and_obj.params->SetNewFor<CPDF_Number>(key, value);
  params_and_obj.page_obj->SetDirty(true);
  return true;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObjMark_SetStringParam(FPDF_DOCUMENT document,
                               FPDF_PAGEOBJECT page_object,
                               FPDF_PAGEOBJECTMARK mark,
                               FPDF_BYTESTRING key,
                               FPDF_BYTESTRING value) {
  ParamsAndObject params_and_obj =
      SetParamValueHelper(document, page_object, mark);
  if (!params_and_obj.params || !params_and_obj.page_obj) {
    return false;
  }
  params_and_obj.params->SetNewFor<CPDF_String>(key, value);
  params_and_obj.page_obj->SetDirty(true);
  return true;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObjMark_SetBlobParam(FPDF_DOCUMENT document,
                             FPDF_PAGEOBJECT page_object,
                             FPDF_PAGEOBJECTMARK mark,
                             FPDF_BYTESTRING key,
                             const unsigned char* value,
                             unsigned long value_len) {
  ParamsAndObject params_and_obj =
      SetParamValueHelper(document, page_object, mark);
  if (!params_and_obj.params || !params_and_obj.page_obj) {
    return false;
  }

  // SAFETY: required from caller.
  params_and_obj.params->SetNewFor<CPDF_String>(
      key, UNSAFE_BUFFERS(pdfium::span(value, value_len)),
      CPDF_String::DataType::kIsHex);
  params_and_obj.page_obj->SetDirty(true);
  return true;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObjMark_RemoveParam(FPDF_PAGEOBJECT page_object,
                            FPDF_PAGEOBJECTMARK mark,
                            FPDF_BYTESTRING key) {
  CPDF_PageObject* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!pPageObj) {
    return false;
  }

  RetainPtr<CPDF_Dictionary> pParams = GetMarkParamDict(mark);
  if (!pParams) {
    return false;
  }

  auto removed = pParams->RemoveFor(key);
  if (!removed) {
    return false;
  }

  pPageObj->SetDirty(true);
  return true;
}

FPDF_EXPORT int FPDF_CALLCONV FPDFPageObj_GetType(FPDF_PAGEOBJECT page_object) {
  CPDF_PageObject* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  return pPageObj ? static_cast<int>(pPageObj->GetType())
                  : FPDF_PAGEOBJ_UNKNOWN;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObj_GetIsActive(FPDF_PAGEOBJECT page_object, FPDF_BOOL* active) {
  if (!active) {
    return false;
  }

  CPDF_PageObject* cpage_object = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!cpage_object) {
    return false;
  }

  *active = cpage_object->IsActive();
  return true;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObj_SetIsActive(FPDF_PAGEOBJECT page_object, FPDF_BOOL active) {
  CPDF_PageObject* cpage_object = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!cpage_object) {
    return false;
  }

  cpage_object->SetIsActive(active);
  return true;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV FPDFPage_GenerateContent(FPDF_PAGE page) {
  CPDF_Page* pPage = CPDFPageFromFPDFPage(page);
  if (!IsPageObject(pPage)) {
    return false;
  }

  CPDF_PageContentGenerator CG(pPage);
  CG.GenerateContent();
  return true;
}

FPDF_EXPORT void FPDF_CALLCONV
FPDFPageObj_Transform(FPDF_PAGEOBJECT page_object,
                      double a,
                      double b,
                      double c,
                      double d,
                      double e,
                      double f) {
  const FS_MATRIX matrix{static_cast<float>(a), static_cast<float>(b),
                         static_cast<float>(c), static_cast<float>(d),
                         static_cast<float>(e), static_cast<float>(f)};
  FPDFPageObj_TransformF(page_object, &matrix);
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObj_TransformF(FPDF_PAGEOBJECT page_object, const FS_MATRIX* matrix) {
  if (!matrix) {
    return false;
  }

  CPDF_PageObject* cpage_object = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!cpage_object) {
    return false;
  }

  cpage_object->Transform(CFXMatrixFromFSMatrix(*matrix));
  return true;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObj_GetMatrix(FPDF_PAGEOBJECT page_object, FS_MATRIX* matrix) {
  CPDF_PageObject* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!pPageObj || !matrix) {
    return false;
  }

  switch (pPageObj->GetType()) {
    case CPDF_PageObject::Type::kText:
      *matrix = FSMatrixFromCFXMatrix(pPageObj->AsText()->GetTextMatrix());
      return true;
    case CPDF_PageObject::Type::kPath:
      *matrix = FSMatrixFromCFXMatrix(pPageObj->AsPath()->matrix());
      return true;
    case CPDF_PageObject::Type::kImage:
      *matrix = FSMatrixFromCFXMatrix(pPageObj->AsImage()->matrix());
      return true;
    case CPDF_PageObject::Type::kShading:
      return false;
    case CPDF_PageObject::Type::kForm:
      *matrix = FSMatrixFromCFXMatrix(pPageObj->AsForm()->form_matrix());
      return true;
  }
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObj_SetMatrix(FPDF_PAGEOBJECT page_object, const FS_MATRIX* matrix) {
  CPDF_PageObject* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!pPageObj || !matrix) {
    return false;
  }

  CFX_Matrix cmatrix = CFXMatrixFromFSMatrix(*matrix);
  switch (pPageObj->GetType()) {
    case CPDF_PageObject::Type::kText:
      pPageObj->AsText()->SetTextMatrix(cmatrix);
      pPageObj->SetMatrixDirty(true);
      return true;
    case CPDF_PageObject::Type::kPath:
      pPageObj->AsPath()->SetPathMatrix(cmatrix);
      pPageObj->SetMatrixDirty(true);
      return true;
    case CPDF_PageObject::Type::kImage:
      pPageObj->AsImage()->SetImageMatrix(cmatrix);
      pPageObj->SetMatrixDirty(pPageObj->original_matrix() != cmatrix);
      return true;
    case CPDF_PageObject::Type::kShading:
      return false;
    case CPDF_PageObject::Type::kForm:
      pPageObj->AsForm()->SetFormMatrix(cmatrix);
      pPageObj->SetMatrixDirty(true);
      return true;
  }
  NOTREACHED();
}

FPDF_EXPORT void FPDF_CALLCONV
FPDFPageObj_SetBlendMode(FPDF_PAGEOBJECT page_object,
                         FPDF_BYTESTRING blend_mode) {
  CPDF_PageObject* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!pPageObj) {
    return;
  }

  pPageObj->mutable_general_state().SetBlendMode(blend_mode);
  pPageObj->SetDirty(true);
}

FPDF_EXPORT void FPDF_CALLCONV FPDFPage_TransformAnnots(FPDF_PAGE page,
                                                        double a,
                                                        double b,
                                                        double c,
                                                        double d,
                                                        double e,
                                                        double f) {
  CPDF_Page* pPage = CPDFPageFromFPDFPage(page);
  if (!pPage) {
    return;
  }

  CPDF_AnnotList AnnotList(pPage);
  for (size_t i = 0; i < AnnotList.Count(); ++i) {
    CPDF_Annot* pAnnot = AnnotList.GetAt(i);
    CFX_Matrix matrix((float)a, (float)b, (float)c, (float)d, (float)e,
                      (float)f);
    CFX_FloatRect rect = matrix.TransformRect(pAnnot->GetRect());

    RetainPtr<CPDF_Dictionary> pAnnotDict = pAnnot->GetMutableAnnotDict();
    RetainPtr<CPDF_Array> pRectArray = pAnnotDict->GetMutableArrayFor("Rect");
    if (pRectArray) {
      pRectArray->Clear();
    } else {
      pRectArray = pAnnotDict->SetNewFor<CPDF_Array>("Rect");
    }

    pRectArray->AppendNew<CPDF_Number>(rect.left);
    pRectArray->AppendNew<CPDF_Number>(rect.bottom);
    pRectArray->AppendNew<CPDF_Number>(rect.right);
    pRectArray->AppendNew<CPDF_Number>(rect.top);

    // TODO(unknown): Transform AP's rectangle
  }
}

FPDF_EXPORT void FPDF_CALLCONV FPDFPage_SetRotation(FPDF_PAGE page,
                                                    int rotate) {
  CPDF_Page* pPage = CPDFPageFromFPDFPage(page);
  if (!IsPageObject(pPage)) {
    return;
  }

  rotate %= 4;
  pPage->GetMutableDict()->SetNewFor<CPDF_Number>(pdfium::page_object::kRotate,
                                                  rotate * 90);
  pPage->UpdateDimensions();
}

FPDF_BOOL FPDFPageObj_SetFillColor(FPDF_PAGEOBJECT page_object,
                                   unsigned int R,
                                   unsigned int G,
                                   unsigned int B,
                                   unsigned int A) {
  CPDF_PageObject* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!pPageObj || R > 255 || G > 255 || B > 255 || A > 255) {
    return false;
  }

  std::vector<float> rgb = {R / 255.f, G / 255.f, B / 255.f};
  pPageObj->mutable_general_state().SetFillAlpha(A / 255.f);
  pPageObj->mutable_color_state().SetFillColor(
      CPDF_ColorSpace::GetStockCS(CPDF_ColorSpace::Family::kDeviceRGB),
      std::move(rgb));
  pPageObj->SetDirty(true);
  return true;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObj_GetFillColor(FPDF_PAGEOBJECT page_object,
                         unsigned int* R,
                         unsigned int* G,
                         unsigned int* B,
                         unsigned int* A) {
  auto* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!pPageObj || !R || !G || !B || !A) {
    return false;
  }

  if (!pPageObj->color_state().HasRef()) {
    return false;
  }

  FX_COLORREF fill_color = pPageObj->color_state().GetFillColorRef();
  *R = FXSYS_GetRValue(fill_color);
  *G = FXSYS_GetGValue(fill_color);
  *B = FXSYS_GetBValue(fill_color);
  *A = FXSYS_GetUnsignedAlpha(pPageObj->general_state().GetFillAlpha());
  return true;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObj_GetBounds(FPDF_PAGEOBJECT page_object,
                      float* left,
                      float* bottom,
                      float* right,
                      float* top) {
  CPDF_PageObject* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!pPageObj) {
    return false;
  }

  const CFX_FloatRect& bbox = pPageObj->GetRect();
  *left = bbox.left;
  *bottom = bbox.bottom;
  *right = bbox.right;
  *top = bbox.top;
  return true;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObj_GetRotatedBounds(FPDF_PAGEOBJECT page_object,
                             FS_QUADPOINTSF* quad_points) {
  CPDF_PageObject* cpage_object = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!cpage_object || !quad_points) {
    return false;
  }

  CFX_Matrix matrix;
  switch (cpage_object->GetType()) {
    case CPDF_PageObject::Type::kText:
      matrix = cpage_object->AsText()->GetTextMatrix();
      break;
    case CPDF_PageObject::Type::kImage:
      matrix = cpage_object->AsImage()->matrix();
      break;
    default:
      // TODO(crbug.com/42270854): Support more object types.
      return false;
  }

  const CFX_FloatRect& bbox = cpage_object->GetOriginalRect();
  const CFX_PointF bottom_left = matrix.Transform({bbox.left, bbox.bottom});
  const CFX_PointF bottom_right = matrix.Transform({bbox.right, bbox.bottom});
  const CFX_PointF top_right = matrix.Transform({bbox.right, bbox.top});
  const CFX_PointF top_left = matrix.Transform({bbox.left, bbox.top});

  // See PDF 32000-1:2008, figure 64 for the QuadPoints ordering.
  quad_points->x1 = bottom_left.x;
  quad_points->y1 = bottom_left.y;
  quad_points->x2 = bottom_right.x;
  quad_points->y2 = bottom_right.y;
  quad_points->x3 = top_right.x;
  quad_points->y3 = top_right.y;
  quad_points->x4 = top_left.x;
  quad_points->y4 = top_left.y;
  return true;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObj_SetStrokeColor(FPDF_PAGEOBJECT page_object,
                           unsigned int R,
                           unsigned int G,
                           unsigned int B,
                           unsigned int A) {
  auto* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!pPageObj || R > 255 || G > 255 || B > 255 || A > 255) {
    return false;
  }

  std::vector<float> rgb = {R / 255.f, G / 255.f, B / 255.f};
  pPageObj->mutable_general_state().SetStrokeAlpha(A / 255.f);
  pPageObj->mutable_color_state().SetStrokeColor(
      CPDF_ColorSpace::GetStockCS(CPDF_ColorSpace::Family::kDeviceRGB),
      std::move(rgb));
  pPageObj->SetDirty(true);
  return true;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObj_GetStrokeColor(FPDF_PAGEOBJECT page_object,
                           unsigned int* R,
                           unsigned int* G,
                           unsigned int* B,
                           unsigned int* A) {
  auto* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!pPageObj || !R || !G || !B || !A) {
    return false;
  }
  if (!pPageObj->color_state().HasRef()) {
    return false;
  }

  FX_COLORREF stroke_color = pPageObj->color_state().GetStrokeColorRef();
  *R = FXSYS_GetRValue(stroke_color);
  *G = FXSYS_GetGValue(stroke_color);
  *B = FXSYS_GetBValue(stroke_color);
  *A = FXSYS_GetUnsignedAlpha(pPageObj->general_state().GetStrokeAlpha());
  return true;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObj_SetStrokeWidth(FPDF_PAGEOBJECT page_object, float width) {
  auto* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!pPageObj || width < 0.0f) {
    return false;
  }

  pPageObj->mutable_graph_state().SetLineWidth(width);
  pPageObj->SetDirty(true);
  return true;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObj_GetStrokeWidth(FPDF_PAGEOBJECT page_object, float* width) {
  auto* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!pPageObj || !width) {
    return false;
  }

  *width = pPageObj->graph_state().GetLineWidth();
  return true;
}

FPDF_EXPORT int FPDF_CALLCONV
FPDFPageObj_GetLineJoin(FPDF_PAGEOBJECT page_object) {
  auto* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  return pPageObj ? static_cast<int>(pPageObj->graph_state().GetLineJoin())
                  : -1;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObj_SetLineJoin(FPDF_PAGEOBJECT page_object, int line_join) {
  auto* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!pPageObj) {
    return false;
  }

  if (line_join < FPDF_LINEJOIN_MITER || line_join > FPDF_LINEJOIN_BEVEL) {
    return false;
  }

  pPageObj->mutable_graph_state().SetLineJoin(
      static_cast<CFX_GraphStateData::LineJoin>(line_join));
  pPageObj->SetDirty(true);
  return true;
}

FPDF_EXPORT int FPDF_CALLCONV
FPDFPageObj_GetLineCap(FPDF_PAGEOBJECT page_object) {
  auto* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  return pPageObj ? static_cast<int>(pPageObj->graph_state().GetLineCap()) : -1;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObj_SetLineCap(FPDF_PAGEOBJECT page_object, int line_cap) {
  auto* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!pPageObj) {
    return false;
  }

  if (line_cap < FPDF_LINECAP_BUTT ||
      line_cap > FPDF_LINECAP_PROJECTING_SQUARE) {
    return false;
  }
  pPageObj->mutable_graph_state().SetLineCap(
      static_cast<CFX_GraphStateData::LineCap>(line_cap));
  pPageObj->SetDirty(true);
  return true;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObj_GetDashPhase(FPDF_PAGEOBJECT page_object, float* phase) {
  auto* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!pPageObj || !phase) {
    return false;
  }

  *phase = pPageObj->graph_state().GetLineDashPhase();
  return true;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObj_SetDashPhase(FPDF_PAGEOBJECT page_object, float phase) {
  auto* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!pPageObj) {
    return false;
  }

  pPageObj->mutable_graph_state().SetLineDashPhase(phase);
  pPageObj->SetDirty(true);
  return true;
}

FPDF_EXPORT int FPDF_CALLCONV
FPDFPageObj_GetDashCount(FPDF_PAGEOBJECT page_object) {
  auto* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  return pPageObj ? pdfium::checked_cast<int>(
                        pPageObj->graph_state().GetLineDashSize())
                  : -1;
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObj_GetDashArray(FPDF_PAGEOBJECT page_object,
                         float* dash_array,
                         size_t dash_count) {
  if (!dash_array) {
    return false;
  }
  auto* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!pPageObj) {
    return false;
  }

  // SAFETY: required from caller.
  auto result_span = UNSAFE_BUFFERS(pdfium::span(dash_array, dash_count));
  auto dash_vector = pPageObj->graph_state().GetLineDashArray();
  return fxcrt::try_spancpy(result_span, pdfium::span(dash_vector));
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFPageObj_SetDashArray(FPDF_PAGEOBJECT page_object,
                         const float* dash_array,
                         size_t dash_count,
                         float phase) {
  if (dash_count > 0 && !dash_array) {
    return false;
  }

  auto* pPageObj = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!pPageObj) {
    return false;
  }

  std::vector<float> dashes;
  if (dash_count > 0) {
    dashes.reserve(dash_count);
    dashes.assign(dash_array, UNSAFE_TODO(dash_array + dash_count));
  }
  pPageObj->mutable_graph_state().SetLineDash(dashes, phase);
  pPageObj->SetDirty(true);
  return true;
}

FPDF_EXPORT FPDF_BITMAP FPDF_CALLCONV
FPDFPageObj_GetRenderedStrokePattern(FPDF_DOCUMENT document,
                                     FPDF_PAGEOBJECT page_object) {
  return GetRenderedPattern(document, page_object, /*use_stroke_color=*/true);
}

FPDF_EXPORT FPDF_BITMAP FPDF_CALLCONV
FPDFPageObj_GetRenderedFillPattern(FPDF_DOCUMENT document,
                                   FPDF_PAGEOBJECT page_object) {
  return GetRenderedPattern(document, page_object, /*use_stroke_color=*/false);
}

FPDF_EXPORT int FPDF_CALLCONV
FPDFFormObj_CountObjects(FPDF_PAGEOBJECT form_object) {
  const auto* pObjectList = CPDFPageObjHolderFromFPDFFormObject(form_object);
  return pObjectList
             ? pdfium::checked_cast<int>(pObjectList->GetPageObjectCount())
             : -1;
}

FPDF_EXPORT FPDF_PAGEOBJECT FPDF_CALLCONV
FPDFFormObj_GetObject(FPDF_PAGEOBJECT form_object, unsigned long index) {
  const auto* pObjectList = CPDFPageObjHolderFromFPDFFormObject(form_object);
  if (!pObjectList) {
    return nullptr;
  }

  return FPDFPageObjectFromCPDFPageObject(
      pObjectList->GetPageObjectByIndex(index));
}

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDFFormObj_RemoveObject(FPDF_PAGEOBJECT form_object,
                         FPDF_PAGEOBJECT page_object) {
  CPDF_PageObject* cform_page_object =
      CPDFPageObjectFromFPDFPageObject(form_object);
  if (!cform_page_object) {
    return false;
  }
  CPDF_FormObject* form_obj = cform_page_object->AsForm();
  if (!form_obj) {
    return false;
  }
  CPDF_PageObject* cpage_object = CPDFPageObjectFromFPDFPageObject(page_object);
  if (!cpage_object) {
    return false;
  }

  std::unique_ptr<CPDF_PageObject> removed_object =
      form_obj->form()->RemovePageObject(cpage_object);
  if (!removed_object) {
    return false;
  }

  cform_page_object->SetDirty(true);

  // Caller takes ownership of the removed page object
  removed_object.release();
  return true;
}
