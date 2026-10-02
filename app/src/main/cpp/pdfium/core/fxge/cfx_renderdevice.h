// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef CORE_FXGE_CFX_RENDERDEVICE_H_
#define CORE_FXGE_CFX_RENDERDEVICE_H_

#include <memory>
#include <vector>

#include "build/build_config.h"
#include "core/fxcrt/fx_coordinates.h"
#include "core/fxcrt/retain_ptr.h"
#include "core/fxcrt/span.h"
#include "core/fxcrt/unowned_ptr.h"
#include "core/fxge/cfx_path.h"
#include "core/fxge/dib/fx_dib.h"
#include "core/fxge/renderdevicedriver_iface.h"

#if BUILDFLAG(IS_WIN)
#include <windows.h>
#endif

class CFX_DIBBase;
class CFX_DIBitmap;
class CFX_Font;
class CFX_GraphStateData;
class PauseIndicatorIface;
struct CFX_Color;
struct CFX_FillRenderOptions;
struct CFX_TextRenderOptions;
struct TextCharPos;

enum class BorderStyle { kSolid, kDash, kBeveled, kInset, kUnderline };

#if BUILDFLAG(IS_WIN)
class CFX_PSFontTracker;
#endif

#if defined(PDF_USE_SKIA)
class SkCanvas;
#endif

class CFX_RenderDevice final {
 public:
  class StateRestorer {
   public:
    explicit StateRestorer(CFX_RenderDevice* pDevice);
    ~StateRestorer();

   private:
    UnownedPtr<CFX_RenderDevice> device_;
  };

  static std::unique_ptr<CFX_RenderDevice> CreateForBitmap(
      RetainPtr<CFX_DIBitmap> bitmap,
      bool rgb_byte_order = false);

  static std::unique_ptr<CFX_RenderDevice>
  CreateForBitmapWithBackdropAndGroupKnockout(
      RetainPtr<CFX_DIBitmap> bitmap,
      RetainPtr<CFX_DIBitmap> backdrop_bitmap,
      bool group_knockout);

  static std::unique_ptr<CFX_RenderDevice>
  CreateForNewBitmap(int width, int height, FXDIB_Format format);

  static std::unique_ptr<CFX_RenderDevice> CreateForNewBitmapWithBackdrop(
      int width,
      int height,
      FXDIB_Format format,
      RetainPtr<CFX_DIBitmap> backdrop);

#if BUILDFLAG(IS_WIN)
  static std::unique_ptr<CFX_RenderDevice> CreateForWindowsDC(
      HDC hdc,
      CFX_PSFontTracker* ps_font_tracker);
#endif

#if defined(PDF_USE_SKIA)
  static std::unique_ptr<CFX_RenderDevice> CreateForSkiaCanvas(
      SkCanvas& canvas);
#endif

  static CFX_Matrix GetFlipMatrix(float width,
                                  float height,
                                  float left,
                                  float top);

  ~CFX_RenderDevice();

  void Clear(uint32_t color);
  bool CanUseARGBPremul() const;

  void SaveState();
  void RestoreState(bool bKeepSaved);

  int GetWidth() const { return width_; }
  int GetHeight() const { return height_; }
  DeviceType GetDeviceType() const { return device_type_; }
  bool RenderCapGetBits() const { return render_cap_get_bits_; }
  bool RenderCapBlendMode() const { return render_cap_blend_mode_; }
  bool RenderCapSoftClip() const { return render_cap_soft_clip_; }
  bool RenderCapAlphaOutput() const { return render_cap_alpha_output_; }
#if defined(PDF_USE_SKIA)
  bool RenderCapShading() const { return render_cap_shading_; }
#endif
#if BUILDFLAG(IS_WIN)
  int GetHorzSize() const;
  int GetVertSize() const;
#endif
  RetainPtr<CFX_DIBitmap> GetBitmap();
  RetainPtr<const CFX_DIBitmap> GetBitmap() const;
  [[nodiscard]] bool CreateCompatibleBitmap(const RetainPtr<CFX_DIBitmap>& pDIB,
                                            int width,
                                            int height) const;
  const FX_RECT& GetClipBox() const { return clip_box_; }
  void SetBaseClip(const FX_RECT& rect);
  bool SetClip_PathFill(const CFX_Path& path,
                        const CFX_Matrix* pObject2Device,
                        const CFX_FillRenderOptions& fill_options);
  bool SetClip_PathStroke(const CFX_Path& path,
                          const CFX_Matrix* pObject2Device,
                          const CFX_GraphStateData* pGraphState);
  bool SetClip_Rect(const FX_RECT& pRect);
  bool DrawPath(const CFX_Path& path,
                const CFX_Matrix* pObject2Device,
                const CFX_GraphStateData* pGraphState,
                uint32_t fill_color,
                uint32_t stroke_color,
                const CFX_FillRenderOptions& fill_options);
  bool FillRect(const FX_RECT& rect, uint32_t color);

  RetainPtr<const CFX_DIBitmap> GetBackDrop() const;
  bool GetDIBits(RetainPtr<CFX_DIBitmap> bitmap, int left, int top) const;
  bool SetDIBits(RetainPtr<const CFX_DIBBase> bitmap, int left, int top);
  bool SetDIBitsWithBlend(RetainPtr<const CFX_DIBBase> bitmap,
                          int left,
                          int top,
                          BlendMode blend_mode);
  bool StretchDIBits(RetainPtr<const CFX_DIBBase> bitmap,
                     int left,
                     int top,
                     int dest_width,
                     int dest_height);
  bool StretchDIBitsWithFlagsAndBlend(RetainPtr<const CFX_DIBBase> bitmap,
                                      int left,
                                      int top,
                                      int dest_width,
                                      int dest_height,
                                      const FXDIB_ResampleOptions& options,
                                      BlendMode blend_mode);
  bool SetBitMask(RetainPtr<const CFX_DIBBase> bitmap,
                  int left,
                  int top,
                  uint32_t argb);
  bool StretchBitMask(RetainPtr<CFX_DIBBase> bitmap,
                      int left,
                      int top,
                      int dest_width,
                      int dest_height,
                      uint32_t color);
  bool StretchBitMaskWithFlags(RetainPtr<CFX_DIBBase> bitmap,
                               int left,
                               int top,
                               int dest_width,
                               int dest_height,
                               uint32_t argb,
                               const FXDIB_ResampleOptions& options);
  RenderDeviceDriverIface::StartResult StartDIBits(
      RetainPtr<const CFX_DIBBase> bitmap,
      float alpha,
      uint32_t argb,
      const CFX_Matrix& matrix,
      const FXDIB_ResampleOptions& options);
  RenderDeviceDriverIface::StartResult StartDIBitsWithBlend(
      RetainPtr<const CFX_DIBBase> bitmap,
      float alpha,
      uint32_t argb,
      const CFX_Matrix& matrix,
      const FXDIB_ResampleOptions& options,
      BlendMode blend_mode);
  bool ContinueDIBits(RenderDeviceDriverIface::Continuation* continuation,
                      PauseIndicatorIface* pPause);

  bool DrawNormalText(pdfium::span<const TextCharPos> pCharPos,
                      CFX_Font* font,
                      float font_size,
                      const CFX_Matrix& mtText2Device,
                      uint32_t fill_color,
                      const CFX_TextRenderOptions& options);
  bool DrawTextPath(pdfium::span<const TextCharPos> pCharPos,
                    CFX_Font* font,
                    float font_size,
                    const CFX_Matrix& mtText2User,
                    const CFX_Matrix* pUser2Device,
                    const CFX_GraphStateData* pGraphState,
                    uint32_t fill_color,
                    uint32_t stroke_color,
                    CFX_Path* pClippingPath,
                    const CFX_FillRenderOptions& fill_options);

  void DrawFillRect(const CFX_Matrix* pUser2Device,
                    const CFX_FloatRect& rect,
                    const CFX_Color& color,
                    int32_t nTransparency);
  void DrawFillRect(const CFX_Matrix* pUser2Device,
                    const CFX_FloatRect& rect,
                    const FX_COLORREF& color);
  void DrawStrokeRect(const CFX_Matrix& mtUser2Device,
                      const CFX_FloatRect& rect,
                      const FX_COLORREF& color,
                      float fWidth);
  void DrawStrokeLine(const CFX_Matrix* pUser2Device,
                      const CFX_PointF& ptMoveTo,
                      const CFX_PointF& ptLineTo,
                      const FX_COLORREF& color,
                      float fWidth);
  void DrawBorder(const CFX_Matrix* pUser2Device,
                  const CFX_FloatRect& rect,
                  float fWidth,
                  const CFX_Color& color,
                  const CFX_Color& crLeftTop,
                  const CFX_Color& crRightBottom,
                  BorderStyle nStyle,
                  int32_t nTransparency);
  void DrawFillArea(const CFX_Matrix& mtUser2Device,
                    const std::vector<CFX_PointF>& points,
                    const FX_COLORREF& color);
  void DrawShadow(const CFX_Matrix& mtUser2Device,
                  const CFX_FloatRect& rect,
                  int32_t nTransparency,
                  int32_t nStartGray,
                  int32_t nEndGray);

  // See RenderDeviceDriverIface methods of the same name.
  bool MultiplyAlpha(float alpha);
  bool MultiplyAlphaMask(RetainPtr<const CFX_DIBitmap> mask);

#if defined(PDF_USE_SKIA)
  bool DrawShading(const CPDF_ShadingPattern& pattern,
                   const CFX_Matrix& matrix,
                   const FX_RECT& clip_rect,
                   int alpha);
  bool SetBitsWithMask(RetainPtr<const CFX_DIBBase> bitmap,
                       RetainPtr<const CFX_DIBBase> mask,
                       int left,
                       int top,
                       float alpha,
                       BlendMode blend_type);
  void SyncInternalBitmaps();
#endif  // defined(PDF_USE_SKIA)

 private:
  CFX_RenderDevice();
#if BUILDFLAG(IS_WIN)
  CFX_RenderDevice(HDC hdc, CFX_PSFontTracker* ps_font_tracker);
#endif

  [[nodiscard]] bool Attach(RetainPtr<CFX_DIBitmap> pBitmap);
  [[nodiscard]] bool AttachWithRgbByteOrder(RetainPtr<CFX_DIBitmap> pBitmap,
                                            bool bRgbByteOrder);
  [[nodiscard]] bool AttachWithBackdropAndGroupKnockout(
      RetainPtr<CFX_DIBitmap> pBitmap,
      RetainPtr<CFX_DIBitmap> pBackdropBitmap,
      bool bGroupKnockout);
#if defined(PDF_USE_SKIA)
  [[nodiscard]] bool AttachCanvas(SkCanvas& canvas);
#endif

  [[nodiscard]] bool Create(int width, int height, FXDIB_Format format);
  [[nodiscard]] bool CreateWithBackdrop(int width,
                                        int height,
                                        FXDIB_Format format,
                                        RetainPtr<CFX_DIBitmap> backdrop);

  void SetBitmap(RetainPtr<CFX_DIBitmap> bitmap);

  void SetDeviceDriver(std::unique_ptr<RenderDeviceDriverIface> pDriver);
  RenderDeviceDriverIface* GetDeviceDriver() const {
    return device_driver_.get();
  }

  void InitDeviceInfo();
  void UpdateClipBox();
  bool DrawFillStrokePath(const CFX_Path& path,
                          const CFX_Matrix* pObject2Device,
                          const CFX_GraphStateData* pGraphState,
                          uint32_t fill_color,
                          uint32_t stroke_color,
                          const CFX_FillRenderOptions& fill_options);
  bool DrawCosmeticLine(const CFX_PointF& ptMoveTo,
                        const CFX_PointF& ptLineTo,
                        uint32_t color,
                        const CFX_FillRenderOptions& fill_options);
  void DrawZeroAreaPath(const std::vector<CFX_Path::Point>& path,
                        const CFX_Matrix* matrix,
                        bool adjust,
                        bool aliased_path,
                        uint32_t fill_color,
                        uint8_t fill_alpha);

  bool AttachImpl(RetainPtr<CFX_DIBitmap> pBitmap,
                  bool bRgbByteOrder,
                  RetainPtr<CFX_DIBitmap> pBackdropBitmap,
                  bool bGroupKnockout);

#if defined(PDF_USE_AGG)
  // Implemented in agg/cfx_agg_devicedriver.cpp
  bool AttachAggImpl(RetainPtr<CFX_DIBitmap> pBitmap,
                     bool bRgbByteOrder,
                     RetainPtr<CFX_DIBitmap> pBackdropBitmap,
                     bool bGroupKnockout);

  // Implemented in agg/cfx_agg_devicedriver.cpp
  bool CreateAgg(int width,
                 int height,
                 FXDIB_Format format,
                 RetainPtr<CFX_DIBitmap> pBackdropBitmap);
#endif

#if defined(PDF_USE_SKIA)
  // Implemented in skia/fx_skia_device.cpp
  bool AttachSkiaImpl(RetainPtr<CFX_DIBitmap> pBitmap,
                      bool bRgbByteOrder,
                      RetainPtr<CFX_DIBitmap> pBackdropBitmap,
                      bool bGroupKnockout);

  // Implemented in skia/fx_skia_device.cpp
  bool CreateSkia(int width,
                  int height,
                  FXDIB_Format format,
                  RetainPtr<CFX_DIBitmap> pBackdropBitmap);
#endif

  RetainPtr<CFX_DIBitmap> bitmap_;
  int width_ = 0;
  int height_ = 0;
  int bpp_ = 0;
  bool render_cap_get_bits_ = false;
  bool render_cap_alpha_image_ = false;
  bool render_cap_blend_mode_ = false;
  bool render_cap_soft_clip_ = false;
  bool render_cap_alpha_output_ = false;
  bool render_cap_bytemask_output_ = false;
#if defined(PDF_USE_SKIA)
  bool render_cap_fillstroke_path_ = false;
  bool render_cap_shading_ = false;
  bool render_cap_premultiplied_alpha_ = false;
#endif
  DeviceType device_type_ = DeviceType::kDisplay;
  FX_RECT clip_box_;
  std::unique_ptr<RenderDeviceDriverIface> device_driver_;
};

#endif  // CORE_FXGE_CFX_RENDERDEVICE_H_
