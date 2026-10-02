// Copyright 2014 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "public/fpdf_progressive.h"

#include <memory>
#include <utility>

#include "core/fpdfapi/page/cpdf_page.h"
#include "core/fpdfapi/render/cpdf_pagerendercontext.h"
#include "core/fpdfapi/render/cpdf_progressiverenderer.h"
#include "core/fxge/cfx_gemodule.h"
#include "core/fxge/cfx_renderdevice.h"
#include "core/fxge/dib/cfx_dibitmap.h"
#include "fpdfsdk/cpdfsdk_helpers.h"
#include "fpdfsdk/cpdfsdk_pauseadapter.h"
#include "fpdfsdk/cpdfsdk_renderpage.h"
#include "public/fpdfview.h"

// These checks are here because core/ and public/ cannot depend on each other.
static_assert(CPDF_ProgressiveRenderer::kReady == FPDF_RENDER_READY,
              "CPDF_ProgressiveRenderer::kReady value mismatch");
static_assert(CPDF_ProgressiveRenderer::kToBeContinued ==
                  FPDF_RENDER_TOBECONTINUED,
              "CPDF_ProgressiveRenderer::kToBeContinued value mismatch");
static_assert(CPDF_ProgressiveRenderer::kDone == FPDF_RENDER_DONE,
              "CPDF_ProgressiveRenderer::kDone value mismatch");
static_assert(CPDF_ProgressiveRenderer::kFailed == FPDF_RENDER_FAILED,
              "CPDF_ProgressiveRenderer::kFailed value mismatch");

namespace {

int ToFPDFStatus(CPDF_ProgressiveRenderer::Status status) {
  return static_cast<int>(status);
}

}  // namespace

FPDF_EXPORT int FPDF_CALLCONV
FPDF_RenderPageBitmapWithColorScheme_Start(FPDF_BITMAP bitmap,
                                           FPDF_PAGE page,
                                           int start_x,
                                           int start_y,
                                           int size_x,
                                           int size_y,
                                           int rotate,
                                           int flags,
                                           const FPDF_COLORSCHEME* color_scheme,
                                           IFSDK_PAUSE* pause) {
  if (!pause || pause->version != 1) {
    return FPDF_RENDER_FAILED;
  }

  CPDF_Page* pPage = CPDFPageFromFPDFPage(page);
  if (!pPage) {
    return FPDF_RENDER_FAILED;
  }

  RetainPtr<CFX_DIBitmap> pBitmap(CFXDIBitmapFromFPDFBitmap(bitmap));
  if (!pBitmap) {
    return FPDF_RENDER_FAILED;
  }
  ValidateBitmapPremultiplyState(pBitmap);

  auto owned_context = std::make_unique<CPDF_PageRenderContext>();
  CPDF_PageRenderContext* context = owned_context.get();
  pPage->SetRenderContext(std::move(owned_context));
  context->return_premultiplied_ = pBitmap->IsPremultiplied();

#if defined(PDF_USE_SKIA)
  if (CFX_GEModule::IsSkiaRenderer()) {
    pBitmap->PreMultiply();
  }
#endif

  auto device = CFX_RenderDevice::CreateForBitmap(
      pBitmap, !!(flags & FPDF_REVERSE_BYTE_ORDER));
  if (!device) {
#if defined(PDF_USE_SKIA)
    if (CFX_GEModule::IsSkiaRenderer() && !context->return_premultiplied_) {
      pBitmap->UnPreMultiply();
    }
#endif  // defined(PDF_USE_SKIA)
    return FPDF_RENDER_FAILED;
  }
  context->device_ = std::move(device);

  CPDFSDK_PauseAdapter pause_adapter(pause);
  CPDFSDK_RenderPageWithContext(context, pPage, start_x, start_y, size_x,
                                size_y, rotate, flags, color_scheme,
                                /*need_to_restore=*/false, &pause_adapter);

  if (!context->renderer_) {
#if defined(PDF_USE_SKIA)
    if (CFX_GEModule::IsSkiaRenderer() && !context->return_premultiplied_) {
      pBitmap->UnPreMultiply();
    }
#endif  // defined(PDF_USE_SKIA)

    return FPDF_RENDER_FAILED;
  }

  int status = ToFPDFStatus(context->renderer_->GetStatus());
  if (status == FPDF_RENDER_TOBECONTINUED) {
    // Note that `pBitmap` is always pre-multiplied here, even if a straight
    // alpha bitmap was passed in, as the caller is expected to pass it to
    // FPDF_RenderPage_Continue(). Then FPDF_RenderPage_Continue() can continue
    // rendering into it without doing another round of (un)pre-multiplication.
    // FPDF_RenderPage_Continue() will call UnPreMultiply() if needed when done.
    return FPDF_RENDER_TOBECONTINUED;
  }

#if defined(PDF_USE_SKIA)
  if (CFX_GEModule::IsSkiaRenderer() && !context->return_premultiplied_) {
    pBitmap->UnPreMultiply();
  }
#endif  // defined(PDF_USE_SKIA)
  return status;
}

FPDF_EXPORT int FPDF_CALLCONV FPDF_RenderPageBitmap_Start(FPDF_BITMAP bitmap,
                                                          FPDF_PAGE page,
                                                          int start_x,
                                                          int start_y,
                                                          int size_x,
                                                          int size_y,
                                                          int rotate,
                                                          int flags,
                                                          IFSDK_PAUSE* pause) {
  return FPDF_RenderPageBitmapWithColorScheme_Start(
      bitmap, page, start_x, start_y, size_x, size_y, rotate, flags,
      /*color_scheme=*/nullptr, pause);
}

FPDF_EXPORT int FPDF_CALLCONV FPDF_RenderPage_Continue(FPDF_PAGE page,
                                                       IFSDK_PAUSE* pause) {
  if (!pause || pause->version != 1) {
    return FPDF_RENDER_FAILED;
  }

  CPDF_Page* pPage = CPDFPageFromFPDFPage(page);
  if (!pPage) {
    return FPDF_RENDER_FAILED;
  }

  auto* context =
      static_cast<CPDF_PageRenderContext*>(pPage->GetRenderContext());
  if (!context || !context->renderer_) {
    return FPDF_RENDER_FAILED;
  }

  CPDFSDK_PauseAdapter pause_adapter(pause);
  context->renderer_->Continue(&pause_adapter);

  int status = ToFPDFStatus(context->renderer_->GetStatus());
  if (status == FPDF_RENDER_TOBECONTINUED) {
    return FPDF_RENDER_TOBECONTINUED;
  }

#if defined(PDF_USE_SKIA)
  if (CFX_GEModule::IsSkiaRenderer() && !context->return_premultiplied_) {
    context->device_->GetBitmap()->UnPreMultiply();
  }
#endif  // defined(PDF_USE_SKIA)
  return status;
}

FPDF_EXPORT void FPDF_CALLCONV FPDF_RenderPage_Close(FPDF_PAGE page) {
  CPDF_Page* pPage = CPDFPageFromFPDFPage(page);
  if (pPage) {
    pPage->ClearRenderContext();
  }
}
