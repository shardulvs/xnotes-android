// Copyright 2014 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef FPDFSDK_PWL_CPWL_WND_H_
#define FPDFSDK_PWL_CPWL_WND_H_

#include <memory>
#include <vector>

#include "core/fxcrt/cfx_bidi_resolver.h"
#include "core/fxcrt/cfx_timer.h"
#include "core/fxcrt/mask.h"
#include "core/fxcrt/observed_ptr.h"
#include "core/fxcrt/unowned_ptr.h"
#include "core/fxcrt/unowned_ptr_exclusion.h"
#include "core/fxcrt/widestring.h"
#include "core/fxge/cfx_color.h"
#include "core/fxge/cfx_renderdevice.h"
#include "fpdfsdk/pwl/ipwl_fillernotify.h"
#include "public/fpdf_fwlevent.h"

class CPWL_Edit;
class CPWL_ScrollBar;
class IPVT_FontMap;
struct PWL_SCROLL_INFO;

struct CPWL_Dash {
  int32_t nDash;
  int32_t nGap;
  int32_t nPhase;
};

class CPWL_Wnd : public Observable {
 public:
  enum class Styles : uint32_t {
    // window styles
    kWindowBorder = 0x40000000L,
    kWindowBackground = 0x20000000L,
    kWindowVScroll = 0x08000000L,
    kWindowVisible = 0x04000000L,
    kWindowReadOnly = 0x01000000L,
    kWindowAutoFontSize = 0x00800000L,
    kWindowAutoTransparent = 0x00400000L,
    kWindowNoRefreshClip = 0x00200000L,
    // edit and label styles
    kEditMultiline = 0x0001L,
    kEditPassword = 0x0002L,
    kEditLeft = 0x0004L,
    kEditRight = 0x0008L,
    kEditMiddle = 0x0010L,
    kEditTop = 0x0020L,
    kEditCenter = 0x0080L,
    kEditCharArray = 0x0100L,
    kEditAutoScroll = 0x0200L,
    kEditAutoReturn = 0x0400L,
    kEditUndo = 0x0800L,
    kEditRich = 0x1000L,
    kEditTextOverflow = 0x4000L,
    // listbox styles
    kListboxMultipleSel = 0x0001L,
    kListboxHoverSel = 0x0008L,
    // combobox styles
    kComboboxAllowCustomText = 0x0001L,
  };

  static const CFX_Color kDefaultBlackColor;
  static const CFX_Color kDefaultWhiteColor;

  class SharedCaptureFocusState;

  class ProviderIface : public Observable {
   public:
    virtual ~ProviderIface() = default;

    // get a matrix which map user space to CWnd client space
    virtual CFX_Matrix GetWindowMatrix(
        const IPWL_FillerNotify::PerWindowData* pAttached) = 0;

    virtual void OnSetFocusForEdit(CPWL_Edit* pEdit) = 0;
  };

  // Caller-provided options for window creation.
  class CreateParams {
   public:
    CreateParams(CFX_Timer::HandlerIface* timer_handler,
                 IPWL_FillerNotify* filler_notify,
                 ProviderIface* provider);
    CreateParams(const CreateParams& other);
    ~CreateParams();

    // Required:
    CFX_FloatRect rcRectWnd;
    ObservedPtr<CFX_Timer::HandlerIface> const pTimerHandler;
    UnownedPtr<IPWL_FillerNotify> const pFillerNotify;
    UnownedPtr<IPVT_FontMap> font_map;
    ObservedPtr<ProviderIface> pProvider;

    // Optional:
    Mask<Styles> dwFlags;
    CFX_Color sBackgroundColor;
    BorderStyle nBorderStyle = BorderStyle::kSolid;
    int32_t dwBorderWidth = 1;
    CFX_Color sBorderColor;
    CFX_Color sTextColor;
    int32_t nTransparency = 255;
    float fFontSize;
    CPWL_Dash sDash;

    // Ignore, used internally only:
    // TODO(tsepez): fix murky ownership, bare delete.
    UNOWNED_PTR_EXCLUSION SharedCaptureFocusState* pSharedCaptureFocusState =
        nullptr;
    IPWL_FillerNotify::CursorStyle eCursorType =
        IPWL_FillerNotify::CursorStyle::kArrow;
  };

  static bool IsSHIFTKeyDown(Mask<FWL_EVENTFLAG> nFlag);
  static bool IsCTRLKeyDown(Mask<FWL_EVENTFLAG> nFlag);
  static bool IsALTKeyDown(Mask<FWL_EVENTFLAG> nFlag);
  static bool IsMETAKeyDown(Mask<FWL_EVENTFLAG> nFlag);

  // Selects between IsCTRLKeyDown() and IsMETAKeyDown() depending on platform.
  static bool IsPlatformShortcutKey(Mask<FWL_EVENTFLAG> nFlag);

  CPWL_Wnd(const CreateParams& cp,
           std::unique_ptr<IPWL_FillerNotify::PerWindowData> pAttachedData);
  virtual ~CPWL_Wnd();

  // Returns |true| iff this instance is still allocated.
  [[nodiscard]] virtual bool InvalidateRect(const CFX_FloatRect* pRect);

  virtual bool OnKeyDown(FWL_VKEYCODE nKeyCode, Mask<FWL_EVENTFLAG> nFlag);
  virtual bool OnChar(uint16_t nChar, Mask<FWL_EVENTFLAG> nFlag);
  virtual bool OnLButtonDblClk(Mask<FWL_EVENTFLAG> nFlag,
                               const CFX_PointF& point);
  virtual bool OnLButtonDown(Mask<FWL_EVENTFLAG> nFlag,
                             const CFX_PointF& point);
  virtual bool OnLButtonUp(Mask<FWL_EVENTFLAG> nFlag, const CFX_PointF& point);
  virtual bool OnRButtonDown(Mask<FWL_EVENTFLAG> nFlag,
                             const CFX_PointF& point);
  virtual bool OnRButtonUp(Mask<FWL_EVENTFLAG> nFlag, const CFX_PointF& point);
  virtual bool OnMouseMove(Mask<FWL_EVENTFLAG> nFlag, const CFX_PointF& point);
  virtual bool OnMouseWheel(Mask<FWL_EVENTFLAG> nFlag,
                            const CFX_PointF& point,
                            const CFX_Vector& delta);
  virtual void SetScrollInfo(const PWL_SCROLL_INFO& info);
  virtual void SetScrollPosition(float pos);
  virtual void ScrollWindowVertically(float pos);
  virtual void NotifyLButtonDown(CPWL_Wnd* child, const CFX_PointF& pos);
  virtual void NotifyLButtonUp(CPWL_Wnd* child, const CFX_PointF& pos);
  virtual void NotifyMouseMove(CPWL_Wnd* child, const CFX_PointF& pos);
  virtual void SetFocus();
  virtual void KillFocus();
  virtual void SetCursor();

  // Returns |true| iff this instance is still allocated.
  [[nodiscard]] virtual bool SetVisible(bool bVisible);
  virtual void SetFontSize(float fFontSize);
  virtual float GetFontSize() const;
  virtual void SetTextDirection(
      CFX_BidiResolver::ParagraphDirection direction) {}

  virtual WideString GetText();
  virtual WideString GetSelectedText();
  virtual void ReplaceAndKeepSelection(const WideString& text);
  virtual void ReplaceSelection(const WideString& text);
  virtual bool SelectAllText();

  virtual bool CanUndo();
  virtual bool CanRedo();
  virtual bool Undo();
  virtual bool Redo();

  virtual CFX_FloatRect GetFocusRect() const;
  virtual CFX_FloatRect GetClientRect() const;

  virtual void OnSetFocus();
  virtual void OnKillFocus();

  void AddChild(std::unique_ptr<CPWL_Wnd> pWnd);
  void RemoveChild(CPWL_Wnd* pWnd);
  void Realize();
  void Destroy();
  bool Move(const CFX_FloatRect& rcNew, bool bReset, bool bRefresh);

  void InvalidateProvider(ProviderIface* provider);
  void DrawAppearance(CFX_RenderDevice* pDevice,
                      const CFX_Matrix& mtUser2Device);

  int32_t GetBorderWidth() const;
  CFX_FloatRect GetWindowRect() const;

  bool IsVisible() const { return visible_; }
  bool HasFlag(Styles flag) const;
  void RemoveFlag(Styles flag);
  void SetClipRect(const CFX_FloatRect& rect);

  IPWL_FillerNotify::PerWindowData* GetAttachedData() const {
    return attached_data_.get();
  }
  std::unique_ptr<IPWL_FillerNotify::PerWindowData> CloneAttachedData() const;
  std::vector<UnownedPtr<CPWL_Wnd>> GetAncestors();

  bool WndHitTest(const CFX_PointF& point) const;
  bool ClientHitTest(const CFX_PointF& point) const;
  bool IsCaptureMouse() const;

  bool IsFocused() const;
  bool IsReadOnly() const;

  void SetTransparency(int32_t nTransparency);
  CFX_Matrix GetWindowMatrix() const;

 protected:
  virtual void CreateChildWnd(const CreateParams& cp);

  // Returns |true| iff this instance is still allocated.
  [[nodiscard]] virtual bool RepositionChildWnd();

  virtual void DrawThisAppearance(CFX_RenderDevice* pDevice,
                                  const CFX_Matrix& mtUser2Device);

  virtual void OnCreated();
  virtual void OnDestroy();

  bool IsValid() const { return created_; }
  CreateParams* GetCreationParams() { return &creation_params_; }
  ProviderIface* GetProvider() const {
    return creation_params_.pProvider.Get();
  }
  CFX_Timer::HandlerIface* GetTimerHandler() const {
    return creation_params_.pTimerHandler.Get();
  }
  IPWL_FillerNotify* GetFillerNotify() const {
    return creation_params_.pFillerNotify;
  }

  CPWL_Wnd* GetParentWindow() const { return parent_; }
  CPWL_ScrollBar* GetVScrollBar() const;

  // Returns |true| iff this instance is still allocated.
  [[nodiscard]] bool InvalidateRectMove(const CFX_FloatRect& rcOld,
                                        const CFX_FloatRect& rcNew);

  void SetCapture();
  void ReleaseCapture();
  bool IsWndCaptureMouse(const CPWL_Wnd* pWnd) const;
  bool IsWndCaptureKeyboard(const CPWL_Wnd* pWnd) const;

  CFX_Color GetBackgroundColor() const;
  CFX_Color GetBorderColor() const;
  CFX_Color GetTextColor() const;
  CFX_Color GetBorderLeftTopColor(BorderStyle nBorderStyle) const;
  CFX_Color GetBorderRightBottomColor(BorderStyle nBorderStyle) const;
  BorderStyle GetBorderStyle() const;
  const CPWL_Dash& GetBorderDash() const;

  int32_t GetTransparency();
  int32_t GetInnerBorderWidth() const;
  CFX_PointF GetCenterPoint() const;
  const CFX_FloatRect& GetClipRect() const;

  IPVT_FontMap* GetFontMap() const { return creation_params_.font_map; }

 private:
  void DrawChildAppearance(CFX_RenderDevice* pDevice,
                           const CFX_Matrix& mtUser2Device);

  CFX_FloatRect PWLtoWnd(const CFX_FloatRect& rect) const;

  void CreateVScrollBar(const CreateParams& cp);

  void AdjustStyle();
  void CreateSharedCaptureFocusState();
  void DestroySharedCaptureFocusState();
  SharedCaptureFocusState* GetSharedCaptureFocusState() const;

  CreateParams creation_params_;
  std::unique_ptr<IPWL_FillerNotify::PerWindowData> attached_data_;
  UnownedPtr<CPWL_Wnd> parent_;
  std::vector<std::unique_ptr<CPWL_Wnd>> children_;
  UnownedPtr<CPWL_ScrollBar> vscroll_bar_;
  CFX_FloatRect window_rect_;
  CFX_FloatRect clip_rect_;
  bool created_ = false;
  bool visible_ = false;
};

#endif  // FPDFSDK_PWL_CPWL_WND_H_
